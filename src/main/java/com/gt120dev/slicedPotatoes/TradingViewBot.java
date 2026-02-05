package com.gt120dev.slicedPotatoes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.ichimoku.*;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;

public class TradingViewBot extends Application {
    private final String PAIR = "BTC/USD";
    private final int INTERVAL = 15; // 1 Minute candles

    private WebSocketClient krakenWS;
    private BarSeries series;
    private final ObjectMapper mapper = new ObjectMapper();
    private WebEngine webEngine;

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        this.webEngine = webView.getEngine();

        String url = getClass().getResource("/chart.html").toExternalForm();
        webEngine.load(url);

        com.sun.javafx.webkit.WebConsoleListener.setDefaultListener((wv, msg, line, source) ->
                System.out.println("JS => [" + source + ":" + line + "] " + msg)
        );

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                System.out.println("Engine Ready.");
                new Thread(() -> {
                    try {
                        String historicalData = fetchKrakenDataAsJson();
                        Platform.runLater(() -> {
                            runScript("loadData('" + historicalData + "')");
                            initWebSocket();
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        });

        stage.setTitle(String.format("[K] %s_%dm", PAIR, INTERVAL));
        stage.setScene(new Scene(webView, 1200, 800));
        stage.show();
    }

    private void runScript(String script) {
        if (webEngine != null && webEngine.getLoadWorker().getState() == Worker.State.SUCCEEDED) {
            webEngine.executeScript(script);
        }
    }

    private void initWebSocket() {
        try {
            krakenWS = new WebSocketClient(new URI("wss://ws.kraken.com/v2")) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Platform.runLater(() -> {
                        runScript("setConnectionStatus(true);");
                        send("{\"method\":\"subscribe\",\"params\":{\"channel\":\"ohlc\",\"symbol\":[\"" + PAIR + "\"],\"interval\":" + INTERVAL + "}}");
                    });
                }

                @Override
                public void onMessage(String message) {
                    String processedUpdate = processWsMessage(message);
                    if (processedUpdate != null) {
                        Platform.runLater(() -> runScript("updateRealtime('" + processedUpdate + "')"));
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Platform.runLater(() -> runScript("setConnectionStatus(false);"));
                }

                @Override
                public void onError(Exception ex) {
                    ex.printStackTrace();
                }
            };
            krakenWS.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String processWsMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            // Safety check for channel
            if (!root.has("channel") || !root.get("channel").asText().equals("ohlc")) return null;

            JsonNode dataArray = root.get("data");
            if (dataArray == null || dataArray.isEmpty()) return null;
            JsonNode data = dataArray.get(0);

            // 1. Time Handling & Alignment
            String ts = data.has("interval_begin") ? data.get("interval_begin").asText() : data.get("timestamp").asText();
            long epochSeconds = Instant.parse(ts).getEpochSecond();
            long windowStart = (epochSeconds / (INTERVAL * 60)) * (INTERVAL * 60);
            ZonedDateTime barTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(windowStart), ZoneId.systemDefault());

            // 2. THE GATEKEEPER: Prevent ta4j "backwards in time" Exception
            if (!series.isEmpty()) {
                ZonedDateTime lastBarTime = series.getLastBar().getEndTime();
                if (barTime.isBefore(lastBarTime)) {
                    return null; // Ignore old data
                }
            }

            // 3. Extract OHLC
            double o = data.get("open").asDouble();
            double h = data.get("high").asDouble();
            double l = data.get("low").asDouble();
            double c = data.get("close").asDouble();
            double v = data.get("volume").asDouble();

            // 4. Update Bar Series (Replace current bar or add new one)
            boolean shouldReplace = !series.isEmpty() && series.getLastBar().getEndTime().isEqual(barTime);
            Bar newBar = new BaseBar(Duration.ofMinutes(INTERVAL), barTime, o, h, l, c, v);
            series.addBar(newBar, shouldReplace);

            // 5. Initialize Indicators (Now in correct scope)
            int i = series.getEndIndex();
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

            EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
            RSIIndicator rsi14 = new RSIIndicator(closePrice, 14);
            ATRIndicator atr14 = new ATRIndicator(series, 14);
            SMAIndicator atrSMA = new SMAIndicator(atr14, 14);

            IchimokuTenkanSenIndicator tenkan = new IchimokuTenkanSenIndicator(series, 9);
            IchimokuKijunSenIndicator kijun = new IchimokuKijunSenIndicator(series, 26);

            StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(series, 14);
            SMAIndicator stochK = new SMAIndicator(stochRsi, 3);
            SMAIndicator stochD = new SMAIndicator(stochK, 3);

            // 6. Build the JSON Update Object
            ObjectNode update = mapper.createObjectNode();

            // Volatility Spike Logic
            double currentAtr = atr14.getValue(i).doubleValue();
            double avgAtr = atrSMA.getValue(i).doubleValue();
            update.put("volatilitySpike", currentAtr > (avgAtr * 1.25));

            // Signal Logic (Check enough data exists for Ichimoku/Crosses)
            String signal = "NONE";
            if (i > 1) {
                double currentTenkan = tenkan.getValue(i).doubleValue();
                double currentKijun = kijun.getValue(i).doubleValue();
                double prevTenkan = tenkan.getValue(i - 1).doubleValue();
                double prevKijun = kijun.getValue(i - 1).doubleValue();

                // TK Cross Signal
                if (currentTenkan > currentKijun && prevTenkan <= prevKijun) {
                    signal = "BUY_TK";
                } else if (currentTenkan < currentKijun && prevTenkan >= prevKijun) {
                    signal = "SELL_TK";
                }
                // Pullback / Squeeze Logic
                else if (rsi14.getValue(i).doubleValue() < 30 && stochK.getValue(i).doubleValue() < 0.2) {
                    signal = "PULLBACK_BUY";
                }
            }
            update.put("signal", signal);

            // 7. Map Values to JSON for JavaScript
            update.put("time", windowStart);
            update.put("open", o);
            update.put("high", h);
            update.put("low", l);
            update.put("close", c);
            update.put("ema", ema20.getValue(i).doubleValue());
            update.put("rsi", rsi14.getValue(i).doubleValue());
            update.put("atr", currentAtr);
            update.put("atr", atr14.getValue(i).doubleValue());
            update.put("tenkan", tenkan.getValue(i).doubleValue());
            update.put("kijun", kijun.getValue(i).doubleValue());
            update.put("stochK", stochK.getValue(i).doubleValue() * 100);
            update.put("stochD", stochD.getValue(i).doubleValue() * 100);

            return mapper.writeValueAsString(update);

        } catch (Exception e) {
            System.err.println("Error processing WS message: " + e.getMessage());
            return null;
        }
    }

    private String fetchKrakenDataAsJson() {
        try {
            String apiURL = "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=" + INTERVAL;
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiURL)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(response.body());
            JsonNode result = root.get("result");
            String internalName = result.fieldNames().next();
            JsonNode dataArray = result.get(internalName);

            this.series = new BaseBarSeriesBuilder().withName("XBT_Data").build();

            for (JsonNode node : dataArray) {
                ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(node.get(0).asLong()), ZoneId.systemDefault());
                this.series.addBar(time, node.get(1).asDouble(), node.get(2).asDouble(), node.get(3).asDouble(), node.get(4).asDouble(), node.get(6).asDouble());
            }

            // Indicators for historical data
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
            RSIIndicator rsi14 = new RSIIndicator(closePrice, 14);
            ATRIndicator atr14 = new ATRIndicator(series, 14);
            IchimokuTenkanSenIndicator tenkan = new IchimokuTenkanSenIndicator(series, 9);
            IchimokuKijunSenIndicator kijun = new IchimokuKijunSenIndicator(series, 26);
            StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(series, 14);
            SMAIndicator stochK = new SMAIndicator(stochRsi, 3);
            SMAIndicator stochD = new SMAIndicator(stochK, 3);

            ArrayNode rootArray = mapper.createArrayNode();
            for (int i = 0; i < series.getBarCount(); i++) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("time", series.getBar(i).getEndTime().toEpochSecond());
                obj.put("open", series.getBar(i).getOpenPrice().doubleValue());
                obj.put("high", series.getBar(i).getHighPrice().doubleValue());
                obj.put("low", series.getBar(i).getLowPrice().doubleValue());
                obj.put("close", series.getBar(i).getClosePrice().doubleValue());
                obj.put("ema", ema20.getValue(i).doubleValue());
                obj.put("rsi", rsi14.getValue(i).doubleValue());
                obj.put("atr", atr14.getValue(i).doubleValue());
                obj.put("tenkan", tenkan.getValue(i).doubleValue());
                obj.put("kijun", kijun.getValue(i).doubleValue());
                obj.put("stochK", stochK.getValue(i).doubleValue() * 100);
                obj.put("stochD", stochD.getValue(i).doubleValue() * 100);
                rootArray.add(obj);
            }
            return mapper.writeValueAsString(rootArray);
        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
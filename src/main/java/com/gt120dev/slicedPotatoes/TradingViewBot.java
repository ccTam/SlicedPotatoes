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
    private final String PAIR2 = "XBTUSD";
    private final int INTERVAL = 60; // x Minute candles

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
        stage.setScene(new Scene(webView, 1200, 900));
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

    private void calculateAndPopulateJson(int i, ObjectNode obj) {
        // 1. Initialize Indicators
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
        RSIIndicator rsi14 = new RSIIndicator(closePrice, 14);
        ATRIndicator atr14 = new ATRIndicator(series, 14);
        SMAIndicator atrSMA = new SMAIndicator(atr14, 14);

        IchimokuTenkanSenIndicator tenkan = new IchimokuTenkanSenIndicator(series, 9);
        IchimokuKijunSenIndicator kijun = new IchimokuKijunSenIndicator(series, 26);
        IchimokuSenkouSpanAIndicator spanA = new IchimokuSenkouSpanAIndicator(series, 9, 26);
        IchimokuSenkouSpanBIndicator spanB = new IchimokuSenkouSpanBIndicator(series, 52);

        StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(series, 14);
        SMAIndicator stochK = new SMAIndicator(stochRsi, 3);
        SMAIndicator stochD = new SMAIndicator(stochK, 3);

        // 2. Map Indicator Values to JSON (Standard indicator lines)
        obj.put("ema", ema20.getValue(i).doubleValue());
        obj.put("rsi", rsi14.getValue(i).doubleValue());
        obj.put("atr", atr14.getValue(i).doubleValue());
        obj.put("tenkan", tenkan.getValue(i).doubleValue());
        obj.put("kijun", kijun.getValue(i).doubleValue());
        obj.put("spanA", spanA.getValue(i).doubleValue());
        obj.put("spanB", spanB.getValue(i).doubleValue());
        obj.put("stochK", stochK.getValue(i).doubleValue() * 100);
        obj.put("stochD", stochD.getValue(i).doubleValue() * 100);


        // 3. Volatility Spike Logic
        double currentAtr = atr14.getValue(i).doubleValue();
        double avgAtr = atrSMA.getValue(i).doubleValue();
        obj.put("volatilitySpike", currentAtr > (avgAtr * 1.25));

        // 4. Trading Signal Logic (Crosses and Squeezes)
        String signal = "NONE";
        if (i > 1) {
            double curT = tenkan.getValue(i).doubleValue();
            double curK = kijun.getValue(i).doubleValue();
            double preT = tenkan.getValue(i - 1).doubleValue();
            double preK = kijun.getValue(i - 1).doubleValue();

            // TK Cross Signals
            if (curT > curK && preT <= preK) {
                signal = "BUY_TK";
            } else if (curT < curK && preT >= preK) {
                signal = "SELL_TK";
            }
            // Pullback / Squeeze Logic
            else if (rsi14.getValue(i).doubleValue() < 30 && stochK.getValue(i).doubleValue() < 0.2) {
                signal = "PULLBACK_BUY";
            }
        }
        obj.put("signal", signal);
    }

    private String processWsMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            if (!root.has("channel") || !root.get("channel").asText().equals("ohlc")) return null;

            JsonNode data = root.get("data").get(0);

            // 1. Time Alignment & Gatekeeper
            String ts = data.has("interval_begin") ? data.get("interval_begin").asText() : data.get("timestamp").asText();
            long windowStart = (Instant.parse(ts).getEpochSecond() / (INTERVAL * 60)) * (INTERVAL * 60);
            ZonedDateTime barTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(windowStart), ZoneId.systemDefault());

            if (!series.isEmpty() && barTime.isBefore(series.getLastBar().getEndTime())) return null;

            // 2. Bar Management
            boolean shouldReplace = !series.isEmpty() && series.getLastBar().getEndTime().isEqual(barTime);
            series.addBar(new BaseBar(Duration.ofMinutes(INTERVAL), barTime,
                    data.get("open").asDouble(), data.get("high").asDouble(),
                    data.get("low").asDouble(), data.get("close").asDouble(),
                    data.get("volume").asDouble()), shouldReplace);

            // 3. JSON Construction
            ObjectNode update = mapper.createObjectNode();
            update.put("time", windowStart);
            update.put("open", data.get("open").asDouble());
            update.put("high", data.get("high").asDouble());
            update.put("low", data.get("low").asDouble());
            update.put("close", data.get("close").asDouble());

            // Call the grouped logic
            calculateAndPopulateJson(series.getEndIndex(), update);

            return mapper.writeValueAsString(update);
        } catch (Exception e) {
            System.err.println("WS Process Error: " + e.getMessage());
            return null;
        }
    }

    private String fetchKrakenDataAsJson() {
        try {
            String apiURL = "https://api.kraken.com/0/public/OHLC?pair=" + PAIR2 + "&interval=" + INTERVAL;
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiURL)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(response.body());
            JsonNode dataArray = root.get("result").get(root.get("result").fieldNames().next());

            this.series = new BaseBarSeriesBuilder().withName("Candle_Data").build();

            // 1. Build the series first
            for (JsonNode node : dataArray) {
                series.addBar(ZonedDateTime.ofInstant(Instant.ofEpochSecond(node.get(0).asLong()), ZoneId.systemDefault()),
                        node.get(1).asDouble(), node.get(2).asDouble(), node.get(3).asDouble(), node.get(4).asDouble(), node.get(6).asDouble());
            }

            // 2. Generate JSON Array with full technical data
            ArrayNode rootArray = mapper.createArrayNode();
            for (int i = 0; i < series.getBarCount(); i++) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("time", series.getBar(i).getEndTime().toEpochSecond());
                obj.put("open", series.getBar(i).getOpenPrice().doubleValue());
                obj.put("high", series.getBar(i).getHighPrice().doubleValue());
                obj.put("low", series.getBar(i).getLowPrice().doubleValue());
                obj.put("close", series.getBar(i).getClosePrice().doubleValue());

                // Call the grouped logic for every historical bar
                calculateAndPopulateJson(i, obj);

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
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.ichimoku.*;

public class TradingViewBot extends Application {
    private WebSocketClient krakenWS;
    private final BarSeries series = new BaseBarSeriesBuilder().withName("XBT_Data").build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();

        // Load the HTML file
        String url = getClass().getResource("/chart.html").toExternalForm();
        webView.getEngine().load(url);

        // Listen for page load success
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                // Fetch data in a background thread to keep UI smooth
                new Thread(() -> {
                    try {
                        String jsonData = fetchKrakenDataAsJson();
                        // Push data to JavaScript on the JavaFX Application Thread
                        Platform.runLater(() -> {
                            webView.getEngine().executeScript("loadData('" + jsonData + "')");
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        });

        // Debug JS errors in Java console
        webView.getEngine().setOnError(event -> System.out.println("JS Error: " + event.getMessage()));
        com.sun.javafx.webkit.WebConsoleListener.setDefaultListener((wv, msg, line, source) ->
                System.out.println("JS Console: [" + source + ":" + line + "] " + msg)
        );

        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                // 1. Fetch historical data first to fill the chart
                String historicalData = fetchKrakenDataAsJson();
                webView.getEngine().executeScript("loadData('" + historicalData + "')");

                // 2. Start WebSocket for real-time updates
                initWebSocket(webView);
            }
        });

        stage.setTitle("Java T Bot - (Kraken)");
        stage.setScene(new Scene(webView, 1200, 800));
        stage.show();
    }

    private void initWebSocket(WebView webView) {
        try {
            krakenWS = new WebSocketClient(new URI("wss://ws.kraken.com/v2")) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    // Subscribe to 1-minute candles
                    send("{\"method\":\"subscribe\",\"params\":{\"channel\":\"ohlc\",\"symbol\":[\"BTC/USD\"],\"interval\":1}}");
                }

                @Override
                public void onMessage(String message) {
                    Platform.runLater(() -> {
                        String processedUpdate = processWsMessage(message);
                        if (processedUpdate != null) {
                            // Use .update() instead of loadData to only refresh the last candle
                            webView.getEngine().executeScript("updateRealtime('" + processedUpdate + "')");
                        }
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
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
            if (!root.has("data")) return null;

            JsonNode data = root.get("data").get(0);
            // Convert WebSocket JSON to our Indicator format
            // (Repeat your indicator calculation logic here for the latest bar)
            ObjectNode update = mapper.createObjectNode();
            update.put("time", Instant.parse(data.get("timestamp").asText()).getEpochSecond());
            update.put("open", data.get("open").asDouble());
            update.put("high", data.get("high").asDouble());
            update.put("low", data.get("low").asDouble());
            update.put("close", data.get("close").asDouble());

            // Re-calculate indicators with the new bar
            // ... (Add your Indicator calculation calls here) ...

            return mapper.writeValueAsString(update);
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchKrakenDataAsJson() {
        try {
            // 1. Request data from Kraken (Public API - no key needed)
            String pair = "XBTUSD";
            String apiURL = "https://api.kraken.com/0/public/OHLC?pair=" + pair + "&interval=60";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiURL)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 2. Parse Kraken's nested structure
            JsonNode root = mapper.readTree(response.body());
            // Note: Kraken uses internal names like XXBTZUSD for BTC/USD
            JsonNode result = root.get("result");
            String internalName = result.fieldNames().next(); // Dynamically gets "XXBTZUSD"
            JsonNode dataArray = result.get(internalName);

            // 3. Transform to TradingView Format
            List<ObjectNode> sortedNodes = new ArrayList<>();
            for (JsonNode node : dataArray) {
                ObjectNode tvCandle = mapper.createObjectNode();
                tvCandle.put("time", node.get(0).asLong());    // Time in seconds
                tvCandle.put("open", node.get(1).asDouble());
                tvCandle.put("high", node.get(2).asDouble());
                tvCandle.put("low", node.get(3).asDouble());
                tvCandle.put("close", node.get(4).asDouble());
                sortedNodes.add(tvCandle);
            }

            // 4. Sort: Lightweight-charts REQUIRES ascending order
            sortedNodes.sort(Comparator.comparingLong(n -> n.get("time").asLong()));

            // 1. Create a ta4j BarSeries
            BarSeries series = new BaseBarSeriesBuilder().withName("XBT_Data").build();

            // 2. Load data into series and a list for JSON
            for (JsonNode node : dataArray) {
                ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(node.get(0).asLong()), ZoneId.systemDefault());
                series.addBar(time, node.get(1).asDouble(), node.get(2).asDouble(), node.get(3).asDouble(), node.get(4).asDouble(), node.get(6).asDouble());
            }

            // Calculate Indicators
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            // Standard RSI (14 period)
            RSIIndicator rsi = new RSIIndicator(closePrice, 14);
            // Stochastic RSI (14 period)
            StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(series, 14);
            // Trend: 20-period EMA
            EMAIndicator emaTrend = new EMAIndicator(closePrice, 20);
            // Volatility: ATR
            ATRIndicator atr = new ATRIndicator(series, 14);
            // Calculate a "Signal Line" for ATR to detect spikes (SMA of ATR)
            SMAIndicator atrSMA = new SMAIndicator(atr, 14);

            // Ichimoku
            IchimokuTenkanSenIndicator tenkan = new IchimokuTenkanSenIndicator(series, 9);
            IchimokuKijunSenIndicator kijun = new IchimokuKijunSenIndicator(series, 26);
            // Smooth the Stoch RSI to get K and D lines (standard 3-period smoothing)
            SMAIndicator stochK = new SMAIndicator(stochRsi, 3);
            SMAIndicator stochD = new SMAIndicator(stochK, 3);

            // 4. Combine into JSON
            ArrayNode rootArray = mapper.createArrayNode();
            for (int i = 0; i < series.getBarCount(); i++) {

                double currentAtr = atr.getValue(i).doubleValue();
                double averageAtr = atrSMA.getValue(i).doubleValue();


                ObjectNode obj = mapper.createObjectNode();
                obj.put("time", series.getBar(i).getEndTime().toEpochSecond());
                obj.put("open", series.getBar(i).getOpenPrice().doubleValue());
                obj.put("high", series.getBar(i).getHighPrice().doubleValue());
                obj.put("low", series.getBar(i).getLowPrice().doubleValue());
                obj.put("close", series.getBar(i).getClosePrice().doubleValue());

                // Add indicator values
                obj.put("ema", emaTrend.getValue(i).doubleValue());
                obj.put("atr", atr.getValue(i).doubleValue());
                obj.put("atr", currentAtr);
                obj.put("tenkan", tenkan.getValue(i).doubleValue());
                obj.put("kijun", kijun.getValue(i).doubleValue());

                // Convert 0.0-1.0 to 0-100
                obj.put("stochK", stochK.getValue(i).doubleValue() * 100);
                obj.put("stochD", stochD.getValue(i).doubleValue() * 100);

                // RSI is already 0-100 in ta4j
                obj.put("rsi", rsi.getValue(i).doubleValue());

                boolean isSpike = currentAtr > (averageAtr * 1.25);
                obj.put("volatilitySpike", isSpike);

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
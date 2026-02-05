package com.gt120dev.slicedPotatoes;

import org.jfree.chart.*;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.data.xy.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;

public class KGrapher extends JFrame {

    public KGrapher(String title) throws Exception {
        super(title);

        // 1. Fetch and Parse Data
        OHLCDataset dataset = fetchKrakenData("XBTUSD");

        // 2. Create Chart
        JFreeChart chart = ChartFactory.createCandlestickChart(
                "Bitcoin (BTC/USD) - Kraken", "Time", "Price", dataset, false);

        // 3. Customize Visuals (Optional but recommended)
        XYPlot plot = chart.getXYPlot();
        CandlestickRenderer renderer = (CandlestickRenderer) plot.getRenderer();
        renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_SMALLEST);

        // 4. Wrap in Panel and Display
        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new java.awt.Dimension(1200, 900));
        setContentPane(chartPanel);
    }

    private OHLCDataset fetchKrakenData(String pair) throws Exception {
        String url = "https://api.kraken.com/0/public/OHLC?pair=" + pair + "&interval=240";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();

        // Parse JSON using Jackson
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);
        JsonNode data = root.path("result").path("XXBTZUSD"); // Kraken's internal name for BTC/USD

        List<OHLCDataItem> dataItems = new ArrayList<>();
        for (JsonNode node : data) {
            long date = node.get(0).asLong() * 1000; // Convert sec to ms
            double open = node.get(1).asDouble();
            double high = node.get(2).asDouble();
            double low = node.get(3).asDouble();
            double close = node.get(4).asDouble();
            double vol = node.get(6).asDouble();

            dataItems.add(new OHLCDataItem(new Date(date), open, high, low, close, vol));
        }

        // Convert list to array for JFreeChart
        OHLCDataItem[] itemArray = dataItems.toArray(new OHLCDataItem[0]);
        return new DefaultOHLCDataset("BTC", itemArray);
    }

    public static void main(String[] args) throws Exception {
        KGrapher example = new KGrapher("Java Trading Bot Chart");
        example.pack();
        example.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        example.setVisible(true);
    }

}

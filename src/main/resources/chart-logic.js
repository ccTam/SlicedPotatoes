let chart,
  candleSeries,
  emaSeries,
  atrSeries,
  rsiSeries,
  stochKSeries,
  stochDSeries,
  tenkanSeries,
  kijunSeries,
  spanASeries,
  spanBSeries,
  latestData = null; // To store the most recent values

function initChart() {
  chart = LightweightCharts.createChart(document.getElementById("chart"), {
    layout: {
      background: { type: "solid", color: "#0c0d10" },
      textColor: "#d1d4dc",
    },
    grid: { vertLines: { color: "#1e222d" }, horzLines: { color: "#1e222d" } },
    timeScale: { borderColor: "#485c7b", timeVisible: true },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
  });

  // 1. Main Candle Series
  candleSeries = chart.addCandlestickSeries({
    upColor: "transparent",
    borderUpColor: "#26a69a",
    wickUpColor: "#26a69a",
    downColor: "#ef5350",
    borderDownColor: "#ef5350",
    wickDownColor: "#ef5350",
    borderVisible: true,
    lastValueVisible: true,
    priceLineVisible: true,
    priceLineWidth: 1,
    priceLineColor: "#d1d4dc",
    priceLineStyle: 2,
  });

  // 2. Indicator Series (Overlays & Panes)
  emaSeries = chart.addLineSeries({ color: "#E0E0E0FF", lineWidth: 2 });
  tenkanSeries = chart.addLineSeries({ color: "#dbff81", lineWidth: 1 });
  kijunSeries = chart.addLineSeries({ color: "#4674ff", lineWidth: 2 });
  // Cloud
  // Edges of the cloud
  spanASeries = chart.addLineSeries({
    color: "rgba(38, 166, 154, 0.4)", // Greenish
    lineWidth: 1,
    lineStyle: 2, // Dashed look
  });

  spanBSeries = chart.addLineSeries({
    color: "rgba(239, 83, 80, 0.4)", // Reddish
    lineWidth: 1,
    lineStyle: 2,
  });

  atrSeries = chart.addLineSeries({
    color: "#9c27b0",
    priceScaleId: "atrScale",
  });
  rsiSeries = chart.addLineSeries({
    color: "#7e57c2",
    lineWidth: 2,
    priceScaleId: "rsiScale",
  });
  stochKSeries = chart.addLineSeries({
    color: "#2196F3",
    lineWidth: 1.5,
    priceScaleId: "stochScale",
  });
  stochDSeries = chart.addLineSeries({
    color: "#FF9800",
    lineWidth: 1.5,
    priceScaleId: "stochScale",
  });

  // 3. Pane Layout Configuration
  chart
    .priceScale("right")
    .applyOptions({ scaleMargins: { top: 0.05, bottom: 0.45 } });
  chart
    .priceScale("atrScale")
    .applyOptions({ scaleMargins: { top: 0.6, bottom: 0.3 } });
  chart
    .priceScale("rsiScale")
    .applyOptions({ scaleMargins: { top: 0.72, bottom: 0.18 } });
  chart
    .priceScale("stochScale")
    .applyOptions({ scaleMargins: { top: 0.84, bottom: 0.02 } });

  // 4. Threshold Lines (Static Reference)
  const thresholdStyle = {
    color: "#444",
    lineWidth: 1,
    lineStyle: 2,
    axisLabelVisible: false,
  };
  rsiSeries.createPriceLine({ ...thresholdStyle, lineStyle: 1, price: 70 });
  rsiSeries.createPriceLine({ ...thresholdStyle, lineStyle: 1, price: 30 });
  stochKSeries.createPriceLine({ ...thresholdStyle, lineStyle: 1, price: 80 });
  stochKSeries.createPriceLine({ ...thresholdStyle, lineStyle: 1, price: 20 });

  chart.subscribeCrosshairMove(handleCrosshair);
  chart.timeScale().applyOptions({
    rightOffset: 35, // Leave room for the 26-bar shift + a little extra
    barSpacing: 10,
  });
}

function updateRealtime(jsonString) {
  try {
    setConnectionStatus(true);
    if (!jsonString) return;

    const d = JSON.parse(jsonString);

    // 1. Robust Time Extraction
    const t =
      typeof d.time === "object" && d.time !== null
        ? d.time.seconds || d.time.epochSecond
        : d.time;

    if (!t) {
      console.warn("Received update without timestamp:", d);
      return;
    }

    // 2. Update OHLC (Only if data exists)
    if (d.open && d.close) {
      candleSeries.update({
        time: t,
        open: d.open,
        high: d.high,
        low: d.low,
        close: d.close,
      });
    }

    // 3. Update Indicators with Safety Checks
    const updateSeries = (series, val, timeOverride) => {
      const targetTime = timeOverride || t;
      if (series && val !== undefined && val !== null) {
        series.update({ time: targetTime, value: val });
      }
    };

    updateSeries(emaSeries, d.ema);
    updateSeries(tenkanSeries, d.tenkan);
    updateSeries(kijunSeries, d.kijun);
    const futureT = t + 26 * INTERVAL * 60;
    updateSeries(spanASeries, d.spanA, futureT);
    updateSeries(spanBSeries, d.spanB, futureT);
    updateSeries(atrSeries, d.atr);
    updateSeries(rsiSeries, d.rsi);
    updateSeries(stochKSeries, d.stochK);
    updateSeries(stochDSeries, d.stochD);

    // 4. Handle Signals
    if (d.signal && d.signal !== "NONE") {
      const currentMarkers = candleSeries.getMarkers() || [];
      if (!currentMarkers.find((m) => m.time === t)) {
        const newMarker = {
          time: t,
          position: d.signal.includes("BUY") ? "belowBar" : "aboveBar",
          color:
            d.signal === "PULLBACK_BUY"
              ? "#2196F3"
              : d.signal.includes("BUY")
                ? "#26a69a"
                : "#ef5350",
          shape: d.signal.includes("BUY") ? "arrowUp" : "arrowDown",
          text: d.signal === "PULLBACK_BUY" ? "SQUEEZE" : d.signal,
        };
        candleSeries.setMarkers([...currentMarkers, newMarker]);
      }
    }

    // 5. Handle Spikes
    if (d.volatilitySpike === true) {
      const currentSpikes = atrSeries.getMarkers() || [];
      if (!currentSpikes.find((m) => m.time === t)) {
        atrSeries.setMarkers([
          ...currentSpikes,
          {
            time: t,
            position: "aboveBar",
            shape: "circle",
            color: "#ff5252",
            text: "S",
            size: 1,
          },
        ]);
      }
    }

    // 6. Visual Polish
    if (d.close && d.open) {
      candleSeries.applyOptions({
        priceLineColor: d.close >= d.open ? "#26a69a" : "#ef5350",
      });
    }
    latestData = d;
  } catch (err) {
    // This will now print the EXACT error and the data that caused it
    if (jsonString && jsonString.length > 10) {
      console.warn(
        "Realtime skip (likely empty tick or warm-up):",
        err.message,
      );
    }
  }
}

function loadData(jsonString) {
  try {
    const data = JSON.parse(jsonString);

    // 1. Standard Price and Indicator Data
    candleSeries.setData(data);
    emaSeries.setData(data.map((d) => ({ time: d.time, value: d.ema })));
    atrSeries.setData(data.map((d) => ({ time: d.time, value: d.atr })));
    rsiSeries.setData(data.map((d) => ({ time: d.time, value: d.rsi })));
    stochKSeries.setData(data.map((d) => ({ time: d.time, value: d.stochK })));
    stochDSeries.setData(data.map((d) => ({ time: d.time, value: d.stochD })));
    tenkanSeries.setData(data.map((d) => ({ time: d.time, value: d.tenkan })));
    kijunSeries.setData(data.map((d) => ({ time: d.time, value: d.kijun })));

    // 2. The Cloud Logic (The 26-bar Shift)
    const INTERVAL = 60; // Make sure this matches your Java INTERVAL (minutes)
    const displacement = 26 * INTERVAL * 60; // 26 bars in seconds

    // Filter out NaN/Zero values and apply shift
    const spanAData = data
      .filter((d) => d.spanA && d.spanA > 0)
      .map((d) => ({
        time: d.time + displacement,
        value: d.spanA,
      }));

    const spanBData = data
      .filter((d) => d.spanB && d.spanB > 0)
      .map((d) => ({
        time: d.time + displacement,
        value: d.spanB,
      }));

    spanASeries.setData(spanAData);
    spanBSeries.setData(spanBData);

    // 3. Historical Signal Markers (Rest of your code stays same)
    const candleMarkers = data
      .filter((d) => d.signal && d.signal !== "NONE")
      .map((d) => ({
        time: d.time,
        position: d.signal.includes("BUY") ? "belowBar" : "aboveBar",
        color:
          d.signal === "PULLBACK_BUY"
            ? "#2196F3"
            : d.signal.includes("BUY")
              ? "#26a69a"
              : "#ef5350",
        shape: d.signal.includes("BUY") ? "arrowUp" : "arrowDown",
        text: d.signal === "PULLBACK_BUY" ? "SQZ" : d.signal,
      }));
    candleSeries.setMarkers(candleMarkers);

    // 4. ATR SPIKE MARKERS
    const spikeMarkers = data
      .filter((d) => d.volatilitySpike === true)
      .map((d) => ({
        time: d.time,
        position: "aboveBar",
        shape: "circle",
        color: "#ff5252",
        text: "S",
        size: 1,
      }));
    atrSeries.setMarkers(spikeMarkers);

    chart.timeScale().fitContent();
    latestData = data[data.length - 1];
  } catch (e) {
    console.error("Error loading historical data:", e);
  }
}

function handleCrosshair(param) {
  const legendPrice = document.getElementById("price-legend");

  // Determine if we use the hover data or the "Latest" data
  let isHovering = param && param.time && param.seriesData.size > 0;
  let dataContext = null;
  let ohlcContext = null;

  if (isHovering) {
    // We are hovering over a specific bar
    ohlcContext = param.seriesData.get(candleSeries);
    dataContext = {
      ema: param.seriesData.get(emaSeries),
      atr: param.seriesData.get(atrSeries),
      rsi: param.seriesData.get(rsiSeries),
      stochK: param.seriesData.get(stochKSeries),
      stochD: param.seriesData.get(stochDSeries),
    };
  } else if (latestData) {
    // Cursor is gone, use the most recent live data
    ohlcContext = latestData;
    dataContext = {
      ema: latestData.ema,
      atr: latestData.atr,
      rsi: latestData.rsi,
      stochK: latestData.stochK,
      stochD: latestData.stochD,
    };
  }

  // Update OHLC Legend
  if (ohlcContext && legendPrice) {
    const color = ohlcContext.close >= ohlcContext.open ? "#26a69a" : "#ef5350";
    legendPrice.innerHTML = `O <span style="color:${color}">${ohlcContext.open.toFixed(2)}</span> H <span style="color:${color}">${ohlcContext.high.toFixed(2)}</span> L <span style="color:${color}">${ohlcContext.low.toFixed(2)}</span> C <span style="color:${color}">${ohlcContext.close.toFixed(2)}</span>`;
  }

  // Update Indicator Values
  const updateVal = (id, data) => {
    const el = document.getElementById(id);
    if (!el) return;

    let val = null;
    if (data !== undefined && data !== null) {
      // Handle both Hover objects (with .value) and latestData raw numbers
      val =
        typeof data === "object" && data.hasOwnProperty("value")
          ? data.value
          : data;
    }

    if (val !== null && val !== undefined && !isNaN(val)) {
      el.innerText = val.toFixed(2);
    } else {
      el.innerText = "n/a";
    }
  };

  if (dataContext) {
    updateVal("ema-val", dataContext.ema);
    updateVal("atr-val", dataContext.atr);
    updateVal("rsi-val", dataContext.rsi);
    updateVal("stoch-k-val", dataContext.stochK);
    updateVal("stoch-d-val", dataContext.stochD);
  }
}

function setConnectionStatus(isConnected) {
  const dot = document.getElementById("status-dot");
  const text = document.getElementById("status-text");
  if (!dot || !text) return;

  if (isConnected) {
    dot.classList.add("pulse-green");
    text.innerText = "Live (WS)";
    text.style.color = "#26a69a";
  } else {
    dot.classList.remove("pulse-green");
    text.innerText = "Disconnected";
    text.style.color = "#ef5350";
  }
}

window.addEventListener("resize", () => {
  chart.applyOptions({ width: window.innerWidth, height: window.innerHeight });
});

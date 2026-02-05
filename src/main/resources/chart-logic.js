let chart,
  candleSeries,
  emaSeries,
  atrSeries,
  rsiSeries,
  stochKSeries,
  stochDSeries,
  tenkanSeries,
  kijunSeries;

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
  emaSeries = chart.addLineSeries({ color: "#E0E0E0FF", lineWidth: 1 });
  tenkanSeries = chart.addLineSeries({ color: "#F44336", lineWidth: 1 });
  kijunSeries = chart.addLineSeries({ color: "#880E4F", lineWidth: 1 });

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
  rsiSeries.createPriceLine({ ...thresholdStyle, price: 70 });
  rsiSeries.createPriceLine({ ...thresholdStyle, price: 30 });
  stochKSeries.createPriceLine({ ...thresholdStyle, price: 80 });
  stochKSeries.createPriceLine({ ...thresholdStyle, price: 20 });

  chart.subscribeCrosshairMove(handleCrosshair);
}

function updateRealtime(jsonString) {
  try {
    setConnectionStatus(true);
    const d = JSON.parse(jsonString);
    const t =
      typeof d.time === "object" && d.time !== null
        ? d.time.seconds || d.time.epochSecond
        : d.time;

    // Update OHLC
    candleSeries.update({
      time: t,
      open: d.open,
      high: d.high,
      low: d.low,
      close: d.close,
    });

    // Update Overlays
    if (d.ema !== undefined) emaSeries.update({ time: t, value: d.ema });
    if (d.tenkan !== undefined)
      tenkanSeries.update({ time: t, value: d.tenkan });
    if (d.kijun !== undefined) kijunSeries.update({ time: t, value: d.kijun });

    // Update Indicator Panes
    if (d.atr !== undefined) atrSeries.update({ time: t, value: d.atr });
    if (d.rsi !== undefined) rsiSeries.update({ time: t, value: d.rsi });
    if (d.stochK !== undefined)
      stochKSeries.update({ time: t, value: d.stochK });
    if (d.stochD !== undefined)
      stochDSeries.update({ time: t, value: d.stochD });

    // Handle Live Signals (Avoid duplicates on same timestamp)
    if (d.signal && d.signal !== "NONE") {
      const currentMarkers = candleSeries.getMarkers() || [];
      // Only add if we don't already have a marker for this specific second
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

    // HANDLE LIVE ATR SPIKES
    if (d.volatilitySpike) {
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

    // Live Price Line Color
    candleSeries.applyOptions({
      priceLineColor: d.close >= d.open ? "#26a69a" : "#ef5350",
    });
  } catch (err) {
    console.error("Realtime update failed:", err);
  }
}

function loadData(jsonString) {
  try {
    const data = JSON.parse(jsonString);
    candleSeries.setData(data);
    emaSeries.setData(data.map((d) => ({ time: d.time, value: d.ema })));
    atrSeries.setData(data.map((d) => ({ time: d.time, value: d.atr })));
    rsiSeries.setData(data.map((d) => ({ time: d.time, value: d.rsi })));
    stochKSeries.setData(data.map((d) => ({ time: d.time, value: d.stochK })));
    stochDSeries.setData(data.map((d) => ({ time: d.time, value: d.stochD })));
    tenkanSeries.setData(data.map((d) => ({ time: d.time, value: d.tenkan })));
    kijunSeries.setData(data.map((d) => ({ time: d.time, value: d.kijun })));

    // Historical Signal Markers
    // 1. COMBINED CANDLE MARKERS (Signals + Squeeze)
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
        text: d.signal === "PULLBACK_BUY" ? "SQUEEZE" : d.signal,
      }));
    candleSeries.setMarkers(candleMarkers);

    // 2. ATR SPIKE MARKERS
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
  } catch (e) {
    console.error(e);
  }
}

function handleCrosshair(param) {
  const legendPrice = document.getElementById("price-legend");
  if (!param.time || !param.seriesData.size) return;

  const candle = param.seriesData.get(candleSeries);
  const ema = param.seriesData.get(emaSeries);
  const atr = param.seriesData.get(atrSeries);
  const rsi = param.seriesData.get(rsiSeries);
  const k = param.seriesData.get(stochKSeries);
  const d = param.seriesData.get(stochDSeries);

  if (candle) {
    const color = candle.close >= candle.open ? "#26a69a" : "#ef5350";
    legendPrice.innerHTML = `O <span style="color:${color}">${candle.open}</span> H <span style="color:${color}">${candle.high}</span> L <span style="color:${color}">${candle.low}</span> C <span style="color:${color}">${candle.close}</span>`;
  }

  // Safety check for legend elements before updating
  const updateVal = (id, val) => {
    const el = document.getElementById(id);
    if (el) el.innerText = val.toFixed(2);
  };

  if (ema) updateVal("ema-val", ema.value);
  if (atr) updateVal("atr-val", atr.value);
  if (rsi) updateVal("rsi-val", rsi.value);
  if (k) updateVal("stoch-k-val", k.value);
  if (d) updateVal("stoch-d-val", d.value);
}

function setConnectionStatus(isConnected) {
  const dot = document.getElementById("status-dot");
  const text = document.getElementById("status-text");
  if (!dot || !text) return;

  if (isConnected) {
    dot.classList.add("pulse-green");
    text.innerText = "Live / WS";
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

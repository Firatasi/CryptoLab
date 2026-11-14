import { useEffect, useMemo, useRef, useState } from 'react';
import {
  classifyChartSeriesUpdate,
  mergeLiveSnapshotIntoCandles,
  useLiveMarketQuoteStream,
  type ChartRenderMeta,
  type LiveQuoteSnapshot,
} from './liveMarketStream';
import { formatChartCrosshairTime, formatChartTickMark } from './CandleChart';
import { ConnectionStatusBadge } from '@/shared/ui/Feedback';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  createChart,
  ColorType,
  CrosshairMode,
  type IChartApi,
  type ISeriesApi,
  type TickMarkType,
  type Time,
} from 'lightweight-charts';
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  BarChart2,
  RefreshCw,
  Search,
  TrendingUp,
} from 'lucide-react';
import type { CandleInterval } from '@/shared/contracts/types';
import { toChartNumber } from '@/shared/formatting/chartNumber';
import {
  formatPrice,
  formatRatioPercent,
  formatTimestamp,
} from '@/shared/formatting/formatters';
import { useAuth } from '@/features/auth/AuthContext';
import { Badge, Skeleton } from '@/shared/ui/Primitives';
import { queryKeys } from '@/shared/api/queryKeys';
import { cn } from '@/shared/ui/cn';
import { marketApi, type BinanceTickerItem } from './api';

const INTERVALS: { value: CandleInterval; label: string }[] = [
  { value: '1m', label: '1dk' },
  { value: '5m', label: '5dk' },
  { value: '15m', label: '15dk' },
  { value: '1h', label: '1s' },
  { value: '4h', label: '4s' },
  { value: '1d', label: '1G' },
];

type WatchlistFilter = 'ALL' | 'GAINERS' | 'LOSERS' | 'VOLUME';
type ChartStyle = 'CANDLE' | 'AREA';

interface FormattedBar {
  time: Time;
  openTimeIso: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

interface CrosshairLegend {
  timeText: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  changePct: number;
  ma7: number | null;
  ma25: number | null;
}

function formatCompactUsdt(val: string | number | undefined): string {
  const num = typeof val === 'number' ? val : Number(val ?? 0);
  if (!Number.isFinite(num) || num <= 0) return '0,00';
  if (num >= 1_000_000_000) {
    return `${(num / 1_000_000_000).toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })} Mrd`;
  }
  if (num >= 1_000_000) {
    return `${(num / 1_000_000).toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })} Mn`;
  }
  if (num >= 1_000) {
    return `${(num / 1_000).toLocaleString('tr-TR', {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    })} B`;
  }
  return num.toLocaleString('tr-TR', { maximumFractionDigits: 2 });
}

function computeSma(
  data: { time: Time; close: number }[],
  period: number
): { time: Time; value: number }[] {
  const result: { time: Time; value: number }[] = [];
  if (data.length < period) return result;
  let sum = 0;
  for (let i = 0; i < data.length; i++) {
    sum += data[i].close;
    if (i >= period) {
      sum -= data[i - period].close;
    }
    if (i >= period - 1) {
      result.push({
        time: data[i].time,
        value: sum / period,
      });
    }
  }
  return result;
}

export function BinanceMarketTerminal() {
  const { timezone } = useAuth();
  const [selectedSymbol, setSelectedSymbol] = useState<string>('BTCUSDT');
  const [interval, setIntervalState] = useState<CandleInterval>('15m');
  const [chartStyle, setChartStyle] = useState<ChartStyle>('CANDLE');
  const [showMa7, setShowMa7] = useState<boolean>(true);
  const [showMa25, setShowMa25] = useState<boolean>(true);
  const [showVolume, setShowVolume] = useState<boolean>(true);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [watchlistFilter, setWatchlistFilter] = useState<WatchlistFilter>('ALL');
  const [hoveredBar, setHoveredBar] = useState<CrosshairLegend | null>(null);
  const [canvasSupported, setCanvasSupported] = useState<boolean>(true);

  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const areaSeriesRef = useRef<ISeriesApi<'Area'> | null>(null);
  const ma7SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const ma25SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const lastFitKeyRef = useRef<string | null>(null);
  const lastRenderedMetaRef = useRef<ChartRenderMeta | null>(null);
  const barByTimeRef = useRef<Map<number, FormattedBar>>(new Map());
  const ma7ByTimeRef = useRef<Map<number, number>>(new Map());
  const ma25ByTimeRef = useRef<Map<number, number>>(new Map());
  const timezoneRef = useRef(timezone);
  timezoneRef.current = timezone;

  const tickersQuery = useQuery({
    queryKey: ['market', 'binance-tickers'],
    queryFn: ({ signal }) => marketApi.listTickers({ limit: 100, signal }),
    refetchInterval: 2_000,
  });

  const liveStream = useLiveMarketQuoteStream({
    enabled: Boolean(selectedSymbol),
    instrumentId: selectedSymbol,
  });

  const quoteQuery = useQuery({
    queryKey: queryKeys.market.quote(selectedSymbol),
    queryFn: ({ signal }) => marketApi.getSnapshot(selectedSymbol, signal),
    refetchInterval: liveStream.status === 'LIVE' ? false : 1_500,
  });

  const candlesQuery = useQuery({
    queryKey: queryKeys.market.candles(selectedSymbol, interval),
    queryFn: ({ signal }) =>
      marketApi.getCandles(
        {
          instrumentId: selectedSymbol,
          interval,
          limit: 150,
        },
        signal
      ),
    refetchInterval: 2_500,
  });

  const liveCandles = useMemo(
    () => mergeLiveSnapshotIntoCandles(candlesQuery.data, quoteQuery.data),
    [candlesQuery.data, quoteQuery.data]
  );

  const allTickers = useMemo(() => tickersQuery.data?.items ?? [], [tickersQuery.data]);

  const activeTicker: BinanceTickerItem | undefined = useMemo(
    () => allTickers.find((t) => t.symbol === selectedSymbol) ?? allTickers[0],
    [allTickers, selectedSymbol]
  );

  const filteredWatchlist = useMemo(() => {
    const q = searchQuery.trim().toUpperCase();
    const list = allTickers.filter(
      (item) =>
        !q ||
        item.symbol.includes(q) ||
        item.baseAsset.includes(q)
    );

    if (watchlistFilter === 'GAINERS') {
      return [...list]
        .filter((i) => Number(i.priceChangePercent) > 0)
        .sort((a, b) => Number(b.priceChangePercent) - Number(a.priceChangePercent));
    }
    if (watchlistFilter === 'LOSERS') {
      return [...list]
        .filter((i) => Number(i.priceChangePercent) < 0)
        .sort((a, b) => Number(a.priceChangePercent) - Number(b.priceChangePercent));
    }
    if (watchlistFilter === 'VOLUME') {
      return [...list].sort((a, b) => Number(b.quoteVolume) - Number(a.quoteVolume));
    }
    return list;
  }, [allTickers, searchQuery, watchlistFilter]);

  const formattedBars = useMemo(() => {
    const rawBars = liveCandles?.bars ?? [];
    if (rawBars.length === 0) return [];
    const sorted = [...rawBars].sort(
      (a, b) => new Date(a.openTime).getTime() - new Date(b.openTime).getTime()
    );
    return sorted.map((bar) => ({
      time: Math.floor(new Date(bar.openTime).getTime() / 1000) as Time,
      openTimeIso: bar.openTime,
      open: toChartNumber(bar.open),
      high: toChartNumber(bar.high),
      low: toChartNumber(bar.low),
      close: toChartNumber(bar.close),
      volume: toChartNumber(bar.volume),
    }));
  }, [liveCandles]);

  const ma7SeriesData = useMemo(() => computeSma(formattedBars, 7), [formattedBars]);
  const ma25SeriesData = useMemo(() => computeSma(formattedBars, 25), [formattedBars]);

  const defaultLegend: CrosshairLegend | null = useMemo(() => {
    if (formattedBars.length === 0) return null;
    const last = formattedBars[formattedBars.length - 1];
    const changePct =
      last.open > 0 ? ((last.close - last.open) / last.open) * 100 : 0;
    const m7 = ma7SeriesData.length > 0 ? ma7SeriesData[ma7SeriesData.length - 1].value : null;
    const m25 = ma25SeriesData.length > 0 ? ma25SeriesData[ma25SeriesData.length - 1].value : null;
    return {
      timeText: formatTimestamp(last.openTimeIso, timezone),
      open: last.open,
      high: last.high,
      low: last.low,
      close: last.close,
      volume: last.volume,
      changePct,
      ma7: m7,
      ma25: m25,
    };
  }, [formattedBars, ma7SeriesData, ma25SeriesData, timezone]);

  const hasChartData = !candlesQuery.isLoading && canvasSupported && formattedBars.length > 0;

  useEffect(() => {
    const el = chartContainerRef.current;
    if (!el || !hasChartData) return;

    let has2dCanvas = false;
    try {
      const probe = document.createElement('canvas');
      has2dCanvas = Boolean(probe.getContext && probe.getContext('2d'));
    } catch {
      has2dCanvas = false;
    }
    if (!has2dCanvas) {
      setCanvasSupported(false);
      return;
    }

    let chart: IChartApi | null = null;
    try {
      chart = createChart(el, {
        width: el.clientWidth || 760,
        height: 460,
        layout: {
          background: { type: ColorType.Solid, color: '#ffffff' },
          textColor: '#525252',
          fontFamily: 'JetBrains Mono, monospace',
          fontSize: 11,
        },
        grid: {
          vertLines: { color: '#f5f5f5' },
          horzLines: { color: '#f5f5f5' },
        },
        crosshair: {
          mode: CrosshairMode.Normal,
        },
        localization: {
          timeFormatter: (time: Time) => formatChartCrosshairTime(time, timezone),
        },
        rightPriceScale: {
          borderColor: '#e5e5e5',
          scaleMargins: {
            top: 0.08,
            bottom: showVolume ? 0.24 : 0.08,
          },
        },
        timeScale: {
          borderColor: '#e5e5e5',
          timeVisible: true,
          secondsVisible: false,
          tickMarkFormatter: (time: Time, tickMarkType: TickMarkType) =>
            formatChartTickMark(time, tickMarkType, timezone),
        },
      });

      chartRef.current = chart;
      candleSeriesRef.current = null;
      areaSeriesRef.current = null;
      ma7SeriesRef.current = null;
      ma25SeriesRef.current = null;
      volumeSeriesRef.current = null;
      lastRenderedMetaRef.current = null;

      if (chartStyle === 'CANDLE') {
        candleSeriesRef.current = chart.addCandlestickSeries({
          upColor: '#0a0a0a',
          downColor: '#dc2626',
          borderVisible: false,
          wickUpColor: '#0a0a0a',
          wickDownColor: '#dc2626',
        });
      } else {
        areaSeriesRef.current = chart.addAreaSeries({
          lineColor: '#0a0a0a',
          topColor: 'rgba(10, 10, 10, 0.14)',
          bottomColor: 'rgba(10, 10, 10, 0.0)',
          lineWidth: 2,
        });
      }

      if (showMa7) {
        ma7SeriesRef.current = chart.addLineSeries({
          color: '#525252',
          lineWidth: 1,
          priceLineVisible: false,
          lastValueVisible: false,
        });
      }

      if (showMa25) {
        ma25SeriesRef.current = chart.addLineSeries({
          color: '#dc2626',
          lineWidth: 1,
          priceLineVisible: false,
          lastValueVisible: false,
        });
      }

      if (showVolume) {
        const volumeSeries = chart.addHistogramSeries({
          priceFormat: { type: 'volume' },
          priceScaleId: '',
        });
        volumeSeries.priceScale().applyOptions({
          scaleMargins: {
            top: 0.8,
            bottom: 0,
          },
        });
        volumeSeriesRef.current = volumeSeries;
      }

      chart.subscribeCrosshairMove((param) => {
        if (!param.time) {
          setHoveredBar(null);
          return;
        }
        const t = Number(param.time);
        const bar = barByTimeRef.current.get(t);
        if (!bar) {
          setHoveredBar(null);
          return;
        }
        const changePct = bar.open > 0 ? ((bar.close - bar.open) / bar.open) * 100 : 0;
        setHoveredBar({
          timeText: formatTimestamp(bar.openTimeIso, timezoneRef.current),
          open: bar.open,
          high: bar.high,
          low: bar.low,
          close: bar.close,
          volume: bar.volume,
          changePct,
          ma7: ma7ByTimeRef.current.get(t) ?? null,
          ma25: ma25ByTimeRef.current.get(t) ?? null,
        });
      });

      setCanvasSupported(true);
    } catch {
      setCanvasSupported(false);
    }

    const resizeObserver =
      typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver((entries) => {
            const entry = entries[0];
            if (entry && chart) {
              chart.applyOptions({
                width: Math.max(280, Math.floor(entry.contentRect.width)),
              });
            }
          })
        : null;

    if (resizeObserver && chart) {
      resizeObserver.observe(el);
    }

    return () => {
      resizeObserver?.disconnect();
      chart?.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      areaSeriesRef.current = null;
      ma7SeriesRef.current = null;
      ma25SeriesRef.current = null;
      volumeSeriesRef.current = null;
      lastRenderedMetaRef.current = null;
    };
  }, [hasChartData, chartStyle, showMa7, showMa25, showVolume, timezone]);

  useEffect(() => {
    barByTimeRef.current = new Map(formattedBars.map((b) => [Number(b.time), b]));
    ma7ByTimeRef.current = new Map(ma7SeriesData.map((m) => [Number(m.time), m.value]));
    ma25ByTimeRef.current = new Map(ma25SeriesData.map((m) => [Number(m.time), m.value]));

    if (!hasChartData || formattedBars.length === 0) {
      lastRenderedMetaRef.current = null;
      return;
    }

    const activeSeries =
      chartStyle === 'CANDLE' ? candleSeriesRef.current : areaSeriesRef.current;
    if (!activeSeries) {
      return;
    }

    const fitKey = `${selectedSymbol}:${interval}:${chartStyle}:${showMa7}:${showMa25}:${showVolume}:${timezone}`;
    const firstTime = Number(formattedBars[0].time);
    const lastBar = formattedBars[formattedBars.length - 1];
    const lastTime = Number(lastBar.time);
    const prevLastTime =
      formattedBars.length >= 2 ? Number(formattedBars[formattedBars.length - 2].time) : null;

    const { mode, nextMeta } = classifyChartSeriesUpdate(
      lastRenderedMetaRef.current,
      fitKey,
      activeSeries,
      firstTime,
      prevLastTime,
      lastTime,
      formattedBars.length
    );

    const pushBarUpdate = (bar: FormattedBar) => {
      if (chartStyle === 'CANDLE' && candleSeriesRef.current) {
        candleSeriesRef.current.update({
          time: bar.time,
          open: bar.open,
          high: bar.high,
          low: bar.low,
          close: bar.close,
        });
      } else if (chartStyle === 'AREA' && areaSeriesRef.current) {
        areaSeriesRef.current.update({
          time: bar.time,
          value: bar.close,
        });
      }
      if (showVolume && volumeSeriesRef.current) {
        volumeSeriesRef.current.update({
          time: bar.time,
          value: bar.volume,
          color: bar.close >= bar.open ? 'rgba(10, 10, 10, 0.22)' : 'rgba(220, 38, 38, 0.25)',
        });
      }
      const barT = Number(bar.time);
      const ma7Val = ma7ByTimeRef.current.get(barT);
      if (showMa7 && ma7SeriesRef.current && ma7Val !== undefined) {
        ma7SeriesRef.current.update({ time: bar.time, value: ma7Val });
      }
      const ma25Val = ma25ByTimeRef.current.get(barT);
      if (showMa25 && ma25SeriesRef.current && ma25Val !== undefined) {
        ma25SeriesRef.current.update({ time: bar.time, value: ma25Val });
      }
    };

    if (mode === 'update-last') {
      pushBarUpdate(lastBar);
      return;
    }

    if (mode === 'append-one') {
      pushBarUpdate(formattedBars[formattedBars.length - 2]);
      pushBarUpdate(lastBar);
      lastRenderedMetaRef.current = nextMeta;
      return;
    }

    if (chartStyle === 'CANDLE' && candleSeriesRef.current) {
      candleSeriesRef.current.setData(
        formattedBars.map(({ time, open, high, low, close }) => ({
          time,
          open,
          high,
          low,
          close,
        }))
      );
    } else if (chartStyle === 'AREA' && areaSeriesRef.current) {
      areaSeriesRef.current.setData(
        formattedBars.map(({ time, close }) => ({
          time,
          value: close,
        }))
      );
    }

    if (showMa7 && ma7SeriesRef.current) {
      ma7SeriesRef.current.setData(ma7SeriesData);
    }
    if (showMa25 && ma25SeriesRef.current) {
      ma25SeriesRef.current.setData(ma25SeriesData);
    }
    if (showVolume && volumeSeriesRef.current) {
      volumeSeriesRef.current.setData(
        formattedBars.map((b) => ({
          time: b.time,
          value: b.volume,
          color: b.close >= b.open ? 'rgba(10, 10, 10, 0.22)' : 'rgba(220, 38, 38, 0.25)',
        }))
      );
    }

    lastRenderedMetaRef.current = nextMeta;

    if (lastFitKeyRef.current !== fitKey) {
      lastFitKeyRef.current = fitKey;
      chartRef.current?.timeScale().fitContent();
    }
  }, [
    hasChartData,
    formattedBars,
    ma7SeriesData,
    ma25SeriesData,
    chartStyle,
    showMa7,
    showMa25,
    showVolume,
    timezone,
    selectedSymbol,
    interval,
  ]);

  const activeLegend = hoveredBar ?? defaultLegend;
  const quote = quoteQuery.data as LiveQuoteSnapshot | undefined;

  const displayLastPrice = quote?.lastPrice ?? activeTicker?.lastPrice ?? '0';
  const displayMarkPrice = quote?.markPrice ?? activeTicker?.markPrice ?? '0';
  const displayIndexPrice = quote?.indexPrice ?? activeTicker?.indexPrice ?? displayMarkPrice;
  const displayBid = quote?.bid ?? displayLastPrice;
  const displayAsk = quote?.ask ?? displayLastPrice;
  const displayFunding = quote?.fundingRate ?? activeTicker?.fundingRate ?? '0.00010000';
  const displayChangePct = Number(quote?.priceChangePercent ?? activeTicker?.priceChangePercent ?? 0);
  const displayChangeAbs = quote?.priceChange ?? activeTicker?.priceChange ?? '0';
  const displayHigh = quote?.high24h ?? activeTicker?.highPrice ?? displayLastPrice;
  const displayLow = quote?.low24h ?? activeTicker?.lowPrice ?? displayLastPrice;
  const displayVolQuote = quote?.quoteVolume24h ?? activeTicker?.quoteVolume ?? '0';
  const isPositive24h = displayChangePct >= 0;

  return (
    <div className="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      {/* TradingView Tarzı Canlı Binance Üst Kotasyon Şeridi */}
      <section className="border border-[#e5e5e5] bg-white">
        <div className="flex flex-wrap items-center justify-between gap-4 border-b border-[#e5e5e5] bg-[#0a0a0a] px-4 py-2.5 text-white">
          <div className="flex flex-wrap items-center gap-3">
            <span className="inline-flex items-center gap-1.5 font-mono text-xs font-semibold uppercase tracking-widest text-white">
              <Activity className="h-3.5 w-3.5 text-white" aria-hidden="true" />
              BINANCE USD-M PERPETUAL CANLI TERMİNAL
            </span>
            <ConnectionStatusBadge state={liveStream.status} />
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                void tickersQuery.refetch();
                void quoteQuery.refetch();
                void candlesQuery.refetch();
              }}
              className="inline-flex items-center gap-1.5 border border-[#262626] bg-[#262626] px-2.5 py-1 font-mono text-[11px] text-white transition-colors hover:bg-white hover:text-[#0a0a0a]"
            >
              <RefreshCw className="h-3 w-3" aria-hidden="true" />
              <span>Veriyi Yenile</span>
            </button>
            <Link to={`/market?instrumentId=${encodeURIComponent(selectedSymbol)}`}>
              <button
                type="button"
                className="rounded-full bg-white px-3.5 py-1 text-[11px] font-semibold text-[#0a0a0a] transition-colors hover:bg-[#e5e5e5]"
              >
                Tam Ekran Piyasa Görünümü
              </button>
            </Link>
          </div>
        </div>

        {/* Sembol + 24s TradingView Metrikleri */}
        <div className="grid grid-cols-2 gap-px bg-[#e5e5e5] sm:grid-cols-4 lg:grid-cols-9">
          {/* Aktif Sembol Seçimi */}
          <div className="col-span-2 flex items-center justify-between bg-white p-3.5 sm:col-span-2">
            <div>
              <div className="flex items-center gap-2">
                <span className="font-mono text-xl font-bold tracking-tight text-[#0a0a0a]">
                  {selectedSymbol}
                </span>
                <Badge tone="accent">PERP</Badge>
              </div>
              <div className="mt-0.5 font-mono text-[11px] text-[#737373]">
                Binance Vadeli · USDT Uzlaşmalı
              </div>
            </div>
            <div className="text-right">
              <div
                data-testid="mark-price-value"
                className="font-mono text-lg font-bold tabular-nums text-[#0a0a0a]"
              >
                {formatPrice(displayLastPrice)}
              </div>
              <div
                className={cn(
                  'inline-flex items-center gap-0.5 font-mono text-xs font-semibold tabular-nums',
                  isPositive24h ? 'text-[#0a0a0a]' : 'text-[#dc2626]'
                )}
              >
                {isPositive24h ? (
                  <ArrowUpRight className="h-3.5 w-3.5" aria-hidden="true" />
                ) : (
                  <ArrowDownRight className="h-3.5 w-3.5" aria-hidden="true" />
                )}
                <span>
                  {isPositive24h ? '+' : ''}
                  {displayChangePct.toFixed(2)}% ({formatPrice(displayChangeAbs)})
                </span>
              </div>
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              Mark Fiyatı
            </div>
            <div className="mt-1 font-mono text-xs font-semibold tabular-nums text-[#0a0a0a]">
              {formatPrice(displayMarkPrice)}
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              Index Fiyatı
            </div>
            <div className="mt-1 font-mono text-xs font-semibold tabular-nums text-[#0a0a0a]">
              {formatPrice(displayIndexPrice)}
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              24s Yüksek (High)
            </div>
            <div className="mt-1 font-mono text-xs font-semibold tabular-nums text-[#0a0a0a]">
              {formatPrice(displayHigh)}
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              24s Düşük (Low)
            </div>
            <div className="mt-1 font-mono text-xs font-semibold tabular-nums text-[#dc2626]">
              {formatPrice(displayLow)}
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              Alış (Bid) / Satış (Ask)
            </div>
            <div className="mt-1 font-mono text-[11px] font-medium tabular-nums text-[#0a0a0a]">
              <span>{formatPrice(displayBid)}</span>
              <span className="mx-1 text-[#a3a3a3]">/</span>
              <span className="text-[#dc2626]">{formatPrice(displayAsk)}</span>
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              24s Hacim (USDT)
            </div>
            <div className="mt-1 font-mono text-xs font-semibold tabular-nums text-[#0a0a0a]">
              {formatCompactUsdt(displayVolQuote)} USDT
            </div>
          </div>

          <div className="bg-white p-3">
            <div className="text-[10px] font-medium uppercase tracking-wider text-[#737373]">
              Fonlama (Funding)
            </div>
            <div className="mt-1 font-mono text-xs font-semibold tabular-nums text-[#0a0a0a]">
              {formatRatioPercent(displayFunding, 4)}
            </div>
          </div>
        </div>

        {/* Ana TradingView Çalışma Alanı: Sol/Orta Grafik + Sağ Binance İzleme Listesi */}
        <div className="grid grid-cols-1 divide-y divide-[#e5e5e5] lg:grid-cols-[1fr_340px] lg:divide-x lg:divide-y-0">
          {/* Sol: TradingView Grafik ve Kontrol Çubuğu */}
          <div className="flex flex-col bg-white">
            {/* TradingView Üst Grafik Araç Çubuğu */}
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#e5e5e5] bg-[#f5f5f5] px-4 py-2">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="mr-1 font-mono text-[11px] font-semibold uppercase tracking-wider text-[#737373]">
                  Periyot:
                </span>
                {INTERVALS.map((item) => (
                  <button
                    key={item.value}
                    type="button"
                    onClick={() => setIntervalState(item.value)}
                    className={cn(
                      'border px-2.5 py-1 font-mono text-xs transition-colors',
                      interval === item.value
                        ? 'border-[#0a0a0a] bg-[#0a0a0a] font-semibold text-white'
                        : 'border-[#e5e5e5] bg-white text-[#525252] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
                    )}
                  >
                    {item.label}
                  </button>
                ))}

                <span className="mx-1.5 h-4 w-px bg-[#d4d4d4]" aria-hidden="true" />

                <button
                  type="button"
                  onClick={() =>
                    setChartStyle((prev) => (prev === 'CANDLE' ? 'AREA' : 'CANDLE'))
                  }
                  className="inline-flex items-center gap-1 border border-[#e5e5e5] bg-white px-2.5 py-1 font-mono text-xs text-[#0a0a0a] hover:border-[#0a0a0a]"
                >
                  <BarChart2 className="h-3.5 w-3.5" aria-hidden="true" />
                  <span>{chartStyle === 'CANDLE' ? 'Mum Grafiği' : 'Alan Grafiği'}</span>
                </button>
              </div>

              {/* Teknik Göstergeler (MA7, MA25, Volume) */}
              <div className="flex flex-wrap items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setShowMa7((v) => !v)}
                  className={cn(
                    'border px-2 py-1 font-mono text-[11px] transition-colors',
                    showMa7
                      ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                      : 'border-[#e5e5e5] bg-white text-[#737373]'
                  )}
                >
                  MA(7)
                </button>
                <button
                  type="button"
                  onClick={() => setShowMa25((v) => !v)}
                  className={cn(
                    'border px-2 py-1 font-mono text-[11px] transition-colors',
                    showMa25
                      ? 'border-[#dc2626] bg-[#dc2626] text-white'
                      : 'border-[#e5e5e5] bg-white text-[#737373]'
                  )}
                >
                  MA(25)
                </button>
                <button
                  type="button"
                  onClick={() => setShowVolume((v) => !v)}
                  className={cn(
                    'border px-2 py-1 font-mono text-[11px] transition-colors',
                    showVolume
                      ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                      : 'border-[#e5e5e5] bg-white text-[#737373]'
                  )}
                >
                  Hacim (Vol)
                </button>
              </div>
            </div>

            {/* TradingView OHLCV + MA Crosshair Bilgi Şeridi */}
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#e5e5e5] bg-white px-4 py-2 font-mono text-[11px] tabular-nums">
              {activeLegend ? (
                <div className="flex flex-wrap items-center gap-3">
                  <span className="font-semibold text-[#0a0a0a]">
                    {selectedSymbol} · {interval}
                  </span>
                  <span className="text-[#737373]">
                    A: <strong className="text-[#0a0a0a]">{formatPrice(String(activeLegend.open))}</strong>
                  </span>
                  <span className="text-[#737373]">
                    Y: <strong className="text-[#0a0a0a]">{formatPrice(String(activeLegend.high))}</strong>
                  </span>
                  <span className="text-[#737373]">
                    D: <strong className="text-[#dc2626]">{formatPrice(String(activeLegend.low))}</strong>
                  </span>
                  <span className="text-[#737373]">
                    K: <strong className="text-[#0a0a0a]">{formatPrice(String(activeLegend.close))}</strong>
                  </span>
                  <span
                    className={cn(
                      'font-semibold',
                      activeLegend.changePct >= 0 ? 'text-[#0a0a0a]' : 'text-[#dc2626]'
                    )}
                  >
                    ({activeLegend.changePct >= 0 ? '+' : ''}
                    {activeLegend.changePct.toFixed(2)}%)
                  </span>
                  <span className="text-[#737373]">
                    Hacim: <strong className="text-[#0a0a0a]">{formatPrice(String(activeLegend.volume), 2)}</strong>
                  </span>
                  {showMa7 && activeLegend.ma7 !== null && (
                    <span className="text-[#525252]">
                      MA(7): <strong>{formatPrice(String(activeLegend.ma7))}</strong>
                    </span>
                  )}
                  {showMa25 && activeLegend.ma25 !== null && (
                    <span className="text-[#dc2626]">
                      MA(25): <strong>{formatPrice(String(activeLegend.ma25))}</strong>
                    </span>
                  )}
                </div>
              ) : (
                <span className="text-[#737373]">Canlı Binance mum verileri yükleniyor...</span>
              )}
              {activeLegend && (
                <span className="text-[#737373]">{activeLegend.timeText}</span>
              )}
            </div>

            {/* Grafik Kanvas Alanı */}
            <div className="relative flex-1 p-2">
              {candlesQuery.isLoading ? (
                <Skeleton className="h-[460px] w-full" />
              ) : canvasSupported && formattedBars.length > 0 ? (
                <div
                  ref={chartContainerRef}
                  data-testid="landing-binance-chart"
                  className="h-[460px] w-full overflow-hidden bg-white"
                />
              ) : (
                <div className="flex h-[460px] flex-col items-center justify-center border border-[#e5e5e5] bg-[#f5f5f5] p-6 text-center font-mono text-xs text-[#0a0a0a]">
                  <TrendingUp className="mb-2 h-6 w-6 text-[#0a0a0a]" aria-hidden="true" />
                  <div>
                    {selectedSymbol} ({interval}) — {formattedBars.length} doğrulanmış Binance mumu
                    hazır.
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Sağ: TradingView Tarzı Canlı Binance Varlık İzleme Listesi (Watchlist) */}
          <div className="flex h-[565px] flex-col bg-white">
            <div className="space-y-2.5 border-b border-[#e5e5e5] p-3">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-semibold uppercase tracking-wider text-[#0a0a0a]">
                  Binance Varlıklar ({filteredWatchlist.length})
                </span>
                <span className="font-mono text-[10px] text-[#737373]">USDT-M PERP</span>
              </div>

              <div className="relative">
                <Search
                  className="pointer-events-none absolute left-2.5 top-2.5 h-3.5 w-3.5 text-[#737373]"
                  aria-hidden="true"
                />
                <input
                  type="search"
                  aria-label="Binance varlık ara"
                  placeholder="Coin ara (BTC, ETH, SOL, PEPE, AVAX)..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="h-8 w-full border border-[#e5e5e5] bg-[#f5f5f5] pl-8 pr-3 text-xs text-[#0a0a0a] placeholder:text-[#737373] focus:border-[#0a0a0a] focus:bg-white focus:outline-none"
                />
              </div>

              <div className="grid grid-cols-4 gap-1">
                {(
                  [
                    { id: 'ALL', label: 'Tümü' },
                    { id: 'GAINERS', label: 'Yükselen' },
                    { id: 'LOSERS', label: 'Düşen' },
                    { id: 'VOLUME', label: 'Hacim' },
                  ] as const
                ).map((tab) => (
                  <button
                    key={tab.id}
                    type="button"
                    onClick={() => setWatchlistFilter(tab.id)}
                    className={cn(
                      'border py-1 font-mono text-[10px] font-medium uppercase transition-colors',
                      watchlistFilter === tab.id
                        ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                        : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:text-[#0a0a0a]'
                    )}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Watchlist Tablo Başlığı */}
            <div className="grid grid-cols-[1.2fr_1fr_0.9fr] border-b border-[#e5e5e5] bg-[#f5f5f5] px-3 py-1.5 font-mono text-[10px] font-semibold uppercase tracking-wider text-[#737373]">
              <span>Sembol</span>
              <span className="text-right">Son Fiyat</span>
              <span className="text-right">24s %</span>
            </div>

            {/* Watchlist Satırları */}
            <div className="flex-1 divide-y divide-[#e5e5e5] overflow-y-auto">
              {tickersQuery.isLoading ? (
                <div className="space-y-2 p-3">
                  <Skeleton className="h-10 w-full" />
                  <Skeleton className="h-10 w-full" />
                  <Skeleton className="h-10 w-full" />
                  <Skeleton className="h-10 w-full" />
                  <Skeleton className="h-10 w-full" />
                </div>
              ) : (
                filteredWatchlist.map((item) => {
                  const isSelected = item.symbol === selectedSymbol;
                  const pct = Number(item.priceChangePercent);
                  const isUp = pct >= 0;

                  return (
                    <button
                      key={item.symbol}
                      type="button"
                      onClick={() => setSelectedSymbol(item.symbol)}
                      className={cn(
                        'grid w-full grid-cols-[1.2fr_1fr_0.9fr] items-center px-3 py-2.5 text-left transition-colors',
                        isSelected
                          ? 'bg-[#0a0a0a] text-white'
                          : 'bg-white text-[#0a0a0a] hover:bg-[#f5f5f5]'
                      )}
                    >
                      <div>
                        <div className="font-mono text-xs font-bold">{item.symbol}</div>
                        <div
                          className={cn(
                            'font-mono text-[10px]',
                            isSelected ? 'text-[#a3a3a3]' : 'text-[#737373]'
                          )}
                        >
                          Hacim: {formatCompactUsdt(item.quoteVolume)}
                        </div>
                      </div>

                      <div className="text-right font-mono text-xs font-semibold tabular-nums">
                        {formatPrice(item.lastPrice)}
                      </div>

                      <div className="text-right">
                        <span
                          className={cn(
                            'inline-block border px-1.5 py-0.5 font-mono text-[11px] font-semibold tabular-nums',
                            isSelected
                              ? 'border-white/30 bg-white/10 text-white'
                              : isUp
                                ? 'border-[#0a0a0a] bg-[#f5f5f5] text-[#0a0a0a]'
                                : 'border-[#dc2626] bg-red-50 text-[#dc2626]'
                          )}
                        >
                          {isUp ? '+' : ''}
                          {pct.toFixed(2)}%
                        </span>
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}

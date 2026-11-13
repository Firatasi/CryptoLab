import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createChart,
  ColorType,
  type IChartApi,
  type ISeriesApi,
  type TickMarkType,
  type Time,
} from 'lightweight-charts';
import { AlertTriangle, BarChart2, TrendingUp } from 'lucide-react';
import type { CandleInterval, CandleSeriesResponse } from '@/shared/contracts/types';
import { toChartNumber } from '@/shared/formatting/chartNumber';
import {
  formatPrice,
  formatTimestamp,
  type TimezonePreference,
} from '@/shared/formatting/formatters';
import { Badge, Card, QualityBadge, Skeleton } from '@/shared/ui/Primitives';
import { ErrorBanner, EmptyState } from '@/shared/ui/Feedback';
import { cn } from '@/shared/ui/cn';

const INTERVAL_OPTIONS: readonly { value: CandleInterval; label: string }[] = [
  { value: '1m', label: '1dk' },
  { value: '5m', label: '5dk' },
  { value: '15m', label: '15dk' },
  { value: '1h', label: '1s' },
  { value: '4h', label: '4s' },
  { value: '1d', label: '1G' },
];

type ChartStyle = 'CANDLE' | 'AREA';

function chartTimeToDate(time: Time): Date | null {
  if (typeof time === 'number') {
    return new Date(time * 1000);
  }
  if (typeof time === 'string') {
    const parsed = new Date(time);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }
  if (time && typeof time === 'object' && 'year' in time) {
    return new Date(Date.UTC(time.year, time.month - 1, time.day));
  }
  return null;
}

export function formatChartCrosshairTime(time: Time, timezone: TimezonePreference): string {
  const date = chartTimeToDate(time);
  if (!date) return '';
  if (timezone === 'UTC') {
    return (
      new Intl.DateTimeFormat('en-GB', {
        timeZone: 'UTC',
        day: '2-digit',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(date) + ' UTC'
    );
  }
  return (
    new Intl.DateTimeFormat('tr-TR', {
      timeZone: 'Europe/Istanbul',
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(date) + ' TSİ'
  );
}

export function formatChartTickMark(
  time: Time,
  tickMarkType: TickMarkType,
  timezone: TimezonePreference
): string | null {
  const date = chartTimeToDate(time);
  if (!date) return null;
  const tz = timezone === 'UTC' ? 'UTC' : 'Europe/Istanbul';
  const locale = timezone === 'UTC' ? 'en-GB' : 'tr-TR';
  const kind = tickMarkType as number;

  if (kind === 0) {
    return new Intl.DateTimeFormat(locale, { timeZone: tz, year: 'numeric' }).format(date);
  }
  if (kind === 1) {
    return new Intl.DateTimeFormat(locale, { timeZone: tz, month: 'short' }).format(date);
  }
  if (kind === 2) {
    return new Intl.DateTimeFormat(locale, {
      timeZone: tz,
      day: '2-digit',
      month: 'short',
    }).format(date);
  }
  if (kind === 4) {
    return new Intl.DateTimeFormat(locale, {
      timeZone: tz,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(date);
  }
  return new Intl.DateTimeFormat(locale, {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

export interface CandleChartProps {
  series: CandleSeriesResponse | null | undefined;
  interval: CandleInterval;
  onIntervalChange: (next: CandleInterval) => void;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  timezone?: TimezonePreference;
  height?: number;
}

/**
 * F03.5 / F03.6:
 * - Mum grafiği yalnız gerçek sunucu mumlarına bağlanır; eksik mumlar (gap) asla sahte düz çizgiyle doldurulmaz.
 * - Grafik mum aralığı (interval) işlem vadesinden (horizonSeconds) tamamen bağımsızdır.
 */
export function CandleChart({
  series,
  interval,
  onIntervalChange,
  loading = false,
  error = null,
  onRetry,
  timezone = 'Europe/Istanbul',
  height = 340,
}: CandleChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const areaSeriesRef = useRef<ISeriesApi<'Area'> | null>(null);
  const lastFitKeyRef = useRef<string | null>(null);
  const [canvasSupported, setCanvasSupported] = useState(true);
  const [chartStyle, setChartStyle] = useState<ChartStyle>('CANDLE');

  const formattedBars = useMemo(() => {
    if (!series?.bars) return [];
    const sorted = [...series.bars].sort(
      (a, b) => new Date(a.openTime).getTime() - new Date(b.openTime).getTime()
    );
    return sorted.map((bar) => ({
      time: Math.floor(new Date(bar.openTime).getTime() / 1000) as Time,
      open: toChartNumber(bar.open),
      high: toChartNumber(bar.high),
      low: toChartNumber(bar.low),
      close: toChartNumber(bar.close),
      volume: toChartNumber(bar.volume),
      raw: bar,
    }));
  }, [series]);

  const hasBars = formattedBars.length > 0;

  useEffect(() => {
    const el = containerRef.current;
    if (!el || !hasBars) return;

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
        width: el.clientWidth || 640,
        height,
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
        localization: {
          timeFormatter: (time: Time) => formatChartCrosshairTime(time, timezone),
        },
        rightPriceScale: {
          borderColor: '#e5e5e5',
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
      if (chartStyle === 'CANDLE') {
        candleSeriesRef.current = chart.addCandlestickSeries({
          upColor: '#0a0a0a',
          downColor: '#dc2626',
          borderVisible: false,
          wickUpColor: '#0a0a0a',
          wickDownColor: '#dc2626',
        });
        areaSeriesRef.current = null;
      } else {
        areaSeriesRef.current = chart.addAreaSeries({
          lineColor: '#0a0a0a',
          topColor: 'rgba(10, 10, 10, 0.14)',
          bottomColor: 'rgba(10, 10, 10, 0.0)',
          lineWidth: 2,
        });
        candleSeriesRef.current = null;
      }

      lastFitKeyRef.current = null;
      setCanvasSupported(true);
    } catch {
      // jsdom gibi Canvas olmayan test ortamlarında tablo özetine güvenli geçiş
      setCanvasSupported(false);
    }

    const resizeObserver =
      typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver((entries) => {
            const entry = entries[0];
            if (entry && chart) {
              chart.applyOptions({ width: Math.max(240, Math.floor(entry.contentRect.width)) });
            }
          })
        : null;

    if (resizeObserver && chart) {
      resizeObserver.observe(el);
    }

    return () => {
      resizeObserver?.disconnect();
      candleSeriesRef.current = null;
      areaSeriesRef.current = null;
      chartRef.current = null;
      chart?.remove();
    };
  }, [hasBars, height, chartStyle, timezone]);

  useEffect(() => {
    if (formattedBars.length === 0) return;
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
    } else {
      return;
    }

    const fitKey = `${series?.instrumentId ?? ''}:${interval}:${chartStyle}:${timezone}`;
    if (lastFitKeyRef.current !== fitKey) {
      lastFitKeyRef.current = fitKey;
      chartRef.current?.timeScale().fitContent();
    }
  }, [formattedBars, chartStyle, interval, series?.instrumentId, timezone]);

  const latestBar = series?.bars?.[series.bars.length - 1] ?? null;

  return (
    <Card className="overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#e5e5e5] px-4 py-2.5">
        <div className="flex flex-wrap items-center gap-2">
          {chartStyle === 'CANDLE' ? (
            <BarChart2 className="h-4 w-4 text-[#0a0a0a]" aria-hidden="true" />
          ) : (
            <TrendingUp className="h-4 w-4 text-[#0a0a0a]" aria-hidden="true" />
          )}
          <span className="text-xs font-semibold text-[#0a0a0a]">
            {chartStyle === 'CANDLE' ? 'Mum Grafiği' : 'Alan Grafiği'}
            {series?.symbol ? ` · ${series.symbol}` : ''}
          </span>
          {series && series.quality !== 'FRESH' && <QualityBadge quality={series.quality} />}
          {series && series.gapCount > 0 && (
            <Badge tone="wait">
              <AlertTriangle className="h-3 w-3" aria-hidden="true" />
              {series.gapCount} Mum Boşluğu (Sahte Çubuk Eklenmedi)
            </Badge>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1" role="group" aria-label="Grafik türü">
            <button
              type="button"
              onClick={() => setChartStyle('CANDLE')}
              aria-pressed={chartStyle === 'CANDLE'}
              className={cn(
                'rounded-none border px-2 py-1 font-mono text-xs transition-colors',
                chartStyle === 'CANDLE'
                  ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white font-semibold'
                  : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
              )}
            >
              Mum
            </button>
            <button
              type="button"
              onClick={() => setChartStyle('AREA')}
              aria-pressed={chartStyle === 'AREA'}
              className={cn(
                'rounded-none border px-2 py-1 font-mono text-xs transition-colors',
                chartStyle === 'AREA'
                  ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white font-semibold'
                  : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
              )}
            >
              Alan
            </button>
          </div>

          <span className="h-4 w-px bg-[#d4d4d4]" aria-hidden="true" />

          <div className="flex items-center gap-1" role="group" aria-label="Grafik zaman aralığı">
            {INTERVAL_OPTIONS.map((item) => (
              <button
                key={item.value}
                type="button"
                onClick={() => onIntervalChange(item.value)}
                aria-pressed={interval === item.value}
                className={cn(
                  'rounded-none border px-2 py-1 font-mono text-xs transition-colors',
                  interval === item.value
                    ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white font-semibold'
                    : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
                )}
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="p-3">
        {loading ? (
          <Skeleton className="w-full" />
        ) : error ? (
          <ErrorBanner error={error} title="Mum verisi yüklenemedi" onRetry={onRetry} />
        ) : !series || series.bars.length === 0 ? (
          <EmptyState
            title="Mum verisi bulunmuyor"
            description="Seçilen sözleşme ve zaman aralığı için henüz doğrulanmış OHLCV mumu mevcut değil."
          />
        ) : (
          <div className="space-y-2">
            {latestBar && (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-none border border-[#e5e5e5] bg-[#f5f5f5] px-3 py-1.5 font-mono text-[11px] text-[#0a0a0a] tabular-nums">
                <div className="flex flex-wrap items-center gap-3">
                  <span>Açılış: {formatPrice(latestBar.open)}</span>
                  <span className="font-semibold text-[#0a0a0a]">Yüksek: {formatPrice(latestBar.high)}</span>
                  <span className="text-[#dc2626]">Düşük: {formatPrice(latestBar.low)}</span>
                  <span>Kapanış: {formatPrice(latestBar.close)}</span>
                  <span className="text-[#737373]">Hacim: {formatPrice(latestBar.volume, 2)}</span>
                </div>
                <span className="text-[10px] text-[#737373]">
                  Son Mum: {formatTimestamp(latestBar.openTime, timezone)}
                </span>
              </div>
            )}

            {canvasSupported ? (
              <div
                ref={containerRef}
                data-testid="candle-chart-canvas"
                style={{ height: `${height}px` }}
                className="w-full overflow-hidden rounded-none border border-[#e5e5e5] bg-white"
              />
            ) : (
              <div
                data-testid="candle-chart-fallback"
                className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3 font-mono text-xs text-[#0a0a0a]"
              >
                Toplam {series.bars.length} doğrulanmış mum yüklendi ({series.interval}).
              </div>
            )}
          </div>
        )}
      </div>
    </Card>
  );
}

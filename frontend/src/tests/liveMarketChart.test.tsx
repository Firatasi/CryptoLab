import type { Time } from 'lightweight-charts';
import { describe, expect, it } from 'vitest';
import { formatChartCrosshairTime, formatChartTickMark } from '@/features/market/CandleChart';
import {
  classifyChartSeriesUpdate,
  mergeLiveSnapshotIntoCandles,
} from '@/features/market/liveMarketStream';
import type { CandleSeriesResponse, MarketSnapshot } from '@/shared/contracts/types';

function makeSeries(interval: CandleSeriesResponse['interval'] = '1m'): CandleSeriesResponse {
  return {
    instrumentId: 'BTCUSDT',
    symbol: 'BTCUSDT',
    interval,
    asOf: '2026-11-14T00:13:20.000Z',
    quality: 'FRESH',
    gapCount: 0,
    bars: [
      {
        openTime: '2026-11-14T00:12:00.000Z',
        closeTime: '2026-11-14T00:12:59.999Z',
        open: '90000.00',
        high: '90100.00',
        low: '89950.00',
        close: '90050.00',
        volume: '12.5',
        isComplete: true,
      },
      {
        openTime: '2026-11-14T00:13:00.000Z',
        closeTime: '2026-11-14T00:13:59.999Z',
        open: '90050.00',
        high: '90120.00',
        low: '90020.00',
        close: '90080.00',
        volume: '4.2',
        isComplete: false,
      },
    ],
  };
}

function makeSnapshot(overrides: Partial<MarketSnapshot> = {}): MarketSnapshot {
  return {
    instrumentId: 'BTCUSDT',
    symbol: 'BTCUSDT',
    markPrice: '90150.00',
    lastPrice: '90155.00',
    bid: '90154.00',
    ask: '90156.00',
    sourceTime: '2026-11-14T00:13:45.000Z',
    receivedAt: '2026-11-14T00:13:45.100Z',
    sequence: 101,
    quality: 'FRESH',
    ...overrides,
  };
}

describe('liveMarketStream & chart timezone formatting', () => {
  it('updates current open candle close/high/low from live snapshot without altering history', () => {
    const base = makeSeries('1m');
    const merged = mergeLiveSnapshotIntoCandles(base, makeSnapshot());

    expect(merged).toBeDefined();
    expect(merged?.bars).toHaveLength(2);
    expect(merged?.bars[1].close).toBe('90155.00');
    expect(merged?.bars[1].high).toBe('90155.00');
    expect(merged?.bars[1].low).toBe('90020.00');

    const retraced = mergeLiveSnapshotIntoCandles(
      merged,
      makeSnapshot({ lastPrice: '90010.00', sourceTime: '2026-11-14T00:13:50.000Z' })
    );
    expect(retraced?.bars[1].high).toBe('90155.00');
    expect(retraced?.bars[1].low).toBe('90010.00');
    expect(retraced?.bars[1].close).toBe('90010.00');
  });

  it('opens strictly the next contiguous bucket on fresh rollover and refuses multi-bar gaps', () => {
    const base = makeSeries('1m');
    const nextMinute = mergeLiveSnapshotIntoCandles(
      base,
      makeSnapshot({
        lastPrice: '90210.50',
        sourceTime: '2026-11-14T00:14:02.000Z',
        quality: 'FRESH',
      })
    );

    expect(nextMinute?.bars).toHaveLength(3);
    expect(nextMinute?.bars[1].isComplete).toBe(true);
    expect(nextMinute?.bars[2]).toMatchObject({
      openTime: '2026-11-14T00:14:00.000Z',
      open: '90210.50',
      high: '90210.50',
      low: '90210.50',
      close: '90210.50',
      isComplete: false,
    });

    const multiBarGap = mergeLiveSnapshotIntoCandles(
      base,
      makeSnapshot({
        lastPrice: '90500.00',
        sourceTime: '2026-11-14T00:16:05.000Z',
        quality: 'FRESH',
      })
    );
    expect(multiBarGap).toBe(base);
  });

  it('formats chart crosshair and tick marks in Europe/Istanbul (TSI UTC+3) vs UTC', () => {
    const utcTimestamp = Math.floor(Date.parse('2026-11-14T00:13:00.000Z') / 1000) as Time;

    const istCrosshair = formatChartCrosshairTime(utcTimestamp, 'Europe/Istanbul');
    const utcCrosshair = formatChartCrosshairTime(utcTimestamp, 'UTC');
    expect(istCrosshair).toContain('03:13');
    expect(utcCrosshair).toContain('00:13');

    const istTick = formatChartTickMark(utcTimestamp, 3, 'Europe/Istanbul');
    const utcTick = formatChartTickMark(utcTimestamp, 3, 'UTC');
    expect(istTick).toContain('03:13');
    expect(utcTick).toContain('00:13');
  });

  it('classifies chart series updates into O(1) update-last, append-one, or full reset on key/instance change', () => {
    const seriesA = { id: 'candle-series-a' };
    const seriesB = { id: 'candle-series-b' };

    const initial = classifyChartSeriesUpdate(null, 'BTCUSDT:1m:CANDLE', seriesA, 100, 160, 220, 3);
    expect(initial.mode).toBe('reset');

    const tickSameBar = classifyChartSeriesUpdate(
      initial.nextMeta,
      'BTCUSDT:1m:CANDLE',
      seriesA,
      100,
      160,
      220,
      3
    );
    expect(tickSameBar.mode).toBe('update-last');

    const rolledBar = classifyChartSeriesUpdate(
      tickSameBar.nextMeta,
      'BTCUSDT:1m:CANDLE',
      seriesA,
      100,
      220,
      280,
      4
    );
    expect(rolledBar.mode).toBe('append-one');

    const switchedSymbol = classifyChartSeriesUpdate(
      rolledBar.nextMeta,
      'ETHUSDT:1m:CANDLE',
      seriesA,
      100,
      220,
      280,
      4
    );
    expect(switchedSymbol.mode).toBe('reset');

    const recreatedInstance = classifyChartSeriesUpdate(
      rolledBar.nextMeta,
      'BTCUSDT:1m:CANDLE',
      seriesB,
      100,
      220,
      280,
      4
    );
    expect(recreatedInstance.mode).toBe('reset');
  });
});

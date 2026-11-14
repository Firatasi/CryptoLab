import { useCallback, useMemo } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/shared/api/queryKeys';
import type {
  CandleBar,
  CandleInterval,
  CandleSeriesResponse,
  MarketSnapshot,
} from '@/shared/contracts/types';
import { useManagedSseStream } from '@/shared/stream/useManagedSseStream';

export interface LiveQuoteSnapshot extends MarketSnapshot {
  priceChange?: string;
  priceChangePercent?: string;
  high24h?: string;
  low24h?: string;
  quoteVolume24h?: string;
}

const INTERVAL_MS: Record<CandleInterval, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
  '4h': 14_400_000,
  '1d': 86_400_000,
};

function mergeQuoteTick(
  prev: LiveQuoteSnapshot | undefined,
  next: LiveQuoteSnapshot
): LiveQuoteSnapshot {
  if (!prev) {
    return next;
  }
  return {
    ...prev,
    ...next,
    indexPrice: next.indexPrice ?? prev.indexPrice,
    fundingRate: next.fundingRate ?? prev.fundingRate,
    priceChange: next.priceChange ?? prev.priceChange,
    priceChangePercent: next.priceChangePercent ?? prev.priceChangePercent,
    high24h: next.high24h ?? prev.high24h,
    low24h: next.low24h ?? prev.low24h,
    quoteVolume24h: next.quoteVolume24h ?? prev.quoteVolume24h,
  };
}

/**
 * Tekil market SSE stream (`/api/v1/market/stream`) aboneliğini yönetir
 * ve gelen `price.tick` karelerini doğrudan React Query quote önbelleğine yazar.
 */
export function useLiveMarketQuoteStream({
  enabled,
  instrumentId,
}: {
  enabled: boolean;
  instrumentId: string | null | undefined;
}) {
  const queryClient = useQueryClient();
  const normalizedId = instrumentId?.trim() || null;

  const resyncQueryKeys = useMemo(
    () => (enabled && normalizedId ? [queryKeys.market.quote(normalizedId)] : []),
    [enabled, normalizedId]
  );

  const onAcceptedEvent = useCallback(
    (event: { event: string; data: string }) => {
      if (!normalizedId || event.event !== 'price.tick' || !event.data) {
        return;
      }
      try {
        const parsed = JSON.parse(event.data) as Partial<LiveQuoteSnapshot>;
        const tickInstrument = parsed.instrumentId ?? parsed.symbol;
        if (tickInstrument && tickInstrument !== normalizedId) {
          return;
        }
        if (typeof parsed.lastPrice !== 'string' || !parsed.lastPrice) {
          return;
        }
        queryClient.setQueryData<LiveQuoteSnapshot>(
          queryKeys.market.quote(normalizedId),
          (prev) => mergeQuoteTick(prev, parsed as LiveQuoteSnapshot)
        );
      } catch {
        // Geçersiz JSON karesini yoksay; SSE sequence guard / REST yedek döngüsü tutarlılığı korur.
      }
    },
    [normalizedId, queryClient]
  );

  return useManagedSseStream({
    enabled: Boolean(enabled && normalizedId),
    path: normalizedId ? `/market/stream?instrumentId=${encodeURIComponent(normalizedId)}` : null,
    resyncQueryKeys,
    onAcceptedEvent,
  });
}

/**
 * Canlı anlık fiyat (snapshot/SSE) verisini aktif mum serisinin son barına uygular.
 * Tarihsel boşlukları sentetik olarak doldurmaz; yalnızca mevcut açık barı günceller
 * veya `FRESH` canlı akışta tam bir sonraki komşu zaman dilimine geçtiyse yeni bar açar.
 */
export function mergeLiveSnapshotIntoCandles(
  series: CandleSeriesResponse | undefined,
  snapshot: MarketSnapshot | undefined
): CandleSeriesResponse | undefined {
  if (!series || !snapshot || series.bars.length === 0) {
    return series;
  }

  const seriesInstrument = series.instrumentId || series.symbol;
  const snapshotInstrument = snapshot.instrumentId || snapshot.symbol;
  if (seriesInstrument && snapshotInstrument && seriesInstrument !== snapshotInstrument) {
    return series;
  }

  const lastPrice = snapshot.lastPrice;
  if (typeof lastPrice !== 'string' || !lastPrice) {
    return series;
  }

  const price = Number(lastPrice);
  if (!Number.isFinite(price) || price <= 0) {
    return series;
  }

  const intervalMs = INTERVAL_MS[series.interval];
  if (!intervalMs) {
    return series;
  }

  const snapshotTimeMs = Date.parse(snapshot.sourceTime || snapshot.receivedAt);
  const lastBar = series.bars[series.bars.length - 1];
  const lastOpenMs = Date.parse(lastBar.openTime);
  if (!Number.isFinite(snapshotTimeMs) || !Number.isFinite(lastOpenMs)) {
    return series;
  }

  const bucketOpenMs = Math.floor(snapshotTimeMs / intervalMs) * intervalMs;
  if (bucketOpenMs < lastOpenMs) {
    return series;
  }

  if (bucketOpenMs === lastOpenMs) {
    const prevHigh = Number(lastBar.high);
    const prevLow = Number(lastBar.low);
    const nextHigh =
      Number.isFinite(prevHigh) && price <= prevHigh ? lastBar.high : lastPrice;
    const nextLow = Number.isFinite(prevLow) && price >= prevLow ? lastBar.low : lastPrice;
    const nextClose = lastPrice;

    if (
      lastBar.close === nextClose &&
      lastBar.high === nextHigh &&
      lastBar.low === nextLow
    ) {
      return series;
    }

    const updatedBar: CandleBar = {
      ...lastBar,
      high: nextHigh,
      low: nextLow,
      close: nextClose,
    };
    return {
      ...series,
      asOf: snapshot.sourceTime || series.asOf,
      bars: [...series.bars.slice(0, -1), updatedBar],
    };
  }

  // Çoklu bar boşluğu varsa sentetik bar üretme; REST /candles yenilemesinin tamamlamasını bekle.
  if (bucketOpenMs !== lastOpenMs + intervalMs || snapshot.quality !== 'FRESH') {
    return series;
  }

  const completedPrevBar: CandleBar = {
    ...lastBar,
    isComplete: true,
  };
  const nextOpenBar: CandleBar = {
    openTime: new Date(bucketOpenMs).toISOString(),
    closeTime: new Date(bucketOpenMs + intervalMs - 1).toISOString(),
    open: lastPrice,
    high: lastPrice,
    low: lastPrice,
    close: lastPrice,
    volume: '0',
    isComplete: false,
  };

  return {
    ...series,
    asOf: snapshot.sourceTime || series.asOf,
    bars: [...series.bars.slice(0, -1), completedPrevBar, nextOpenBar],
  };
}

export interface ChartRenderMeta {
  seriesKey: string;
  seriesInstance: unknown;
  firstTime: number;
  lastTime: number;
  length: number;
}

/**
 * Grafik serisinin baştan `setData` yerine O(1) `.update(...)` ile güncellenip güncellenemeyeceğini belirler.
 */
export function classifyChartSeriesUpdate(
  prev: ChartRenderMeta | null,
  seriesKey: string,
  seriesInstance: unknown,
  firstTime: number,
  prevLastTime: number | null,
  lastTime: number,
  length: number
): { mode: 'update-last' | 'append-one' | 'reset'; nextMeta: ChartRenderMeta } {
  const nextMeta: ChartRenderMeta = {
    seriesKey,
    seriesInstance,
    firstTime,
    lastTime,
    length,
  };

  const isSameSeries =
    prev !== null &&
    prev.seriesKey === seriesKey &&
    prev.seriesInstance === seriesInstance &&
    prev.firstTime === firstTime;

  if (isSameSeries && prev.length === length && prev.lastTime === lastTime) {
    return { mode: 'update-last', nextMeta };
  }

  if (
    isSameSeries &&
    prev.length + 1 === length &&
    length >= 2 &&
    prevLastTime !== null &&
    prevLastTime === prev.lastTime
  ) {
    return { mode: 'append-one', nextMeta };
  }

  return { mode: 'reset', nextMeta };
}

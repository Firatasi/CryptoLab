import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

const UUID_TO_SYMBOL: Record<string, string> = {
  '11111111-1111-4111-8111-111111111111': 'BTCUSDT',
  '22222222-2222-4222-8222-222222222222': 'ETHUSDT',
  '33333333-3333-4333-8333-333333333333': 'SOLUSDT',
  '44444444-4444-4444-8444-444444444444': 'BNBUSDT',
  '55555555-5555-4555-8555-555555555555': 'XRPUSDT',
  '66666666-6666-4666-8666-666666666666': 'DOGEUSDT',
  '77777777-7777-4777-8777-777777777777': 'ADAUSDT',
  '12121212-1212-4212-8212-121212121212': 'AVAXUSDT',
  '13131313-1313-4313-8313-131313131313': 'LINKUSDT',
  '14141414-1414-4414-8414-141414141414': 'SUIUSDT',
};

const DIRECT_WS_MIN_INTERVAL_MS = 80;

export function resolveCanonicalSymbol(idOrSymbol: string | null | undefined): string | null {
  if (!idOrSymbol) {
    return null;
  }
  const trimmed = idOrSymbol.trim();
  if (!trimmed) {
    return null;
  }
  return UUID_TO_SYMBOL[trimmed.toLowerCase()] ?? trimmed.toUpperCase();
}

function instrumentsMatch(a: string | undefined, b: string | undefined): boolean {
  if (!a || !b) {
    return true;
  }
  if (a === b) {
    return true;
  }
  const symA = resolveCanonicalSymbol(a);
  const symB = resolveCanonicalSymbol(b);
  return Boolean(symA && symB && symA === symB);
}

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

function sanitizeQuoteSnapshotPrice(
  snapshot: Partial<LiveQuoteSnapshot>
): Partial<LiveQuoteSnapshot> {
  if (typeof snapshot.bid === 'string' && typeof snapshot.ask === 'string') {
    const bidNum = Number(snapshot.bid);
    const askNum = Number(snapshot.ask);
    if (Number.isFinite(bidNum) && bidNum > 0 && Number.isFinite(askNum) && askNum >= bidNum) {
      const lastNum = Number(snapshot.lastPrice);
      if (!Number.isFinite(lastNum) || lastNum <= 0 || lastNum < bidNum || lastNum > askNum) {
        return {
          ...snapshot,
          lastPrice: snapshot.bid,
        };
      }
    }
  }
  return snapshot;
}

/**
 * Tekil market SSE stream (`/api/v1/market/stream`) aboneliğini ve canlı `@bookTicker`
 * WebSocket tick akışını yönetir; gelen kareleri hem quote önbelleğine hem de açık
 * mum serilerine anlık (sub-second) olarak işler.
 */
export function useLiveMarketQuoteStream({
  enabled,
  instrumentId,
  symbol,
}: {
  enabled: boolean;
  instrumentId: string | null | undefined;
  symbol?: string | null | undefined;
}) {
  const queryClient = useQueryClient();
  const normalizedId = instrumentId?.trim() || null;
  const [directWsLive, setDirectWsLive] = useState(false);
  const [lastWsTickAt, setLastWsTickAt] = useState<number | null>(null);
  const isTestEnv = Boolean(
    import.meta.env && (import.meta.env.MODE === 'test' || import.meta.env.VITEST)
  );
  const canonicalSymbol = useMemo(() => {
    const explicit = symbol?.trim().toUpperCase();
    if (explicit && /^[A-Z0-9]{2,20}$/.test(explicit)) {
      return explicit;
    }
    const resolved = resolveCanonicalSymbol(normalizedId);
    return resolved && /^[A-Z0-9]{2,20}$/.test(resolved) ? resolved : null;
  }, [normalizedId, symbol]);

  const resyncQueryKeys = useMemo(
    () => (enabled && normalizedId ? [queryKeys.market.quote(normalizedId)] : []),
    [enabled, normalizedId]
  );

  const applyIncomingTick = useCallback(
    (rawParsed: Partial<LiveQuoteSnapshot>) => {
      if (!normalizedId) {
        return;
      }
      const parsed = sanitizeQuoteSnapshotPrice(rawParsed);
      const tickInstrument = parsed.instrumentId ?? parsed.symbol;
      if (
        tickInstrument &&
        !instrumentsMatch(tickInstrument, normalizedId) &&
        !(canonicalSymbol && instrumentsMatch(tickInstrument, canonicalSymbol))
      ) {
        return;
      }
      if (typeof parsed.lastPrice !== 'string' || !parsed.lastPrice) {
        return;
      }
      let nextSnapshot: LiveQuoteSnapshot | undefined;
      queryClient.setQueryData<LiveQuoteSnapshot>(
        queryKeys.market.quote(normalizedId),
        (prev) => {
          nextSnapshot = mergeQuoteTick(prev, {
            ...parsed,
            instrumentId: prev?.instrumentId ?? parsed.instrumentId ?? normalizedId,
            symbol: prev?.symbol ?? parsed.symbol ?? canonicalSymbol ?? undefined,
          } as LiveQuoteSnapshot);
          return nextSnapshot;
        }
      );
      if (nextSnapshot) {
        queryClient.setQueriesData<CandleSeriesResponse>(
          { queryKey: ['market', 'candles', normalizedId] },
          (prev) => mergeLiveSnapshotIntoCandles(prev, nextSnapshot)
        );
      }
    },
    [canonicalSymbol, normalizedId, queryClient]
  );

  const applyTickRef = useRef(applyIncomingTick);
  useEffect(() => {
    applyTickRef.current = applyIncomingTick;
  }, [applyIncomingTick]);

  const onAcceptedEvent = useCallback(
    (event: { event: string; data: string }) => {
      if (!normalizedId || event.event !== 'price.tick' || !event.data) {
        return;
      }
      try {
        const parsed = JSON.parse(event.data) as Partial<LiveQuoteSnapshot>;
        applyTickRef.current(parsed);
      } catch {
        // Geçersiz JSON karesini yoksay; SSE sequence guard / REST yedek döngüsü tutarlılığı korur.
      }
    },
    [normalizedId]
  );

  useEffect(() => {
    if (
      !enabled ||
      !normalizedId ||
      !canonicalSymbol ||
      isTestEnv ||
      typeof window === 'undefined' ||
      typeof WebSocket === 'undefined'
    ) {
      setDirectWsLive(false);
      return;
    }

    let closed = false;
    let socket: WebSocket | null = null;
    let lastPushedAt = 0;
    let pendingPayload: Partial<LiveQuoteSnapshot> | null = null;
    let flushTimer: ReturnType<typeof setTimeout> | null = null;

    const pushTick = (payload: Partial<LiveQuoteSnapshot>) => {
      const now = Date.now();
      lastPushedAt = now;
      setLastWsTickAt(now);
      applyTickRef.current(payload);
    };

    const flushPending = () => {
      flushTimer = null;
      if (closed || !pendingPayload) {
        return;
      }
      const next = pendingPayload;
      pendingPayload = null;
      pushTick(next);
    };

    try {
      socket = new WebSocket(
        `wss://fstream.binance.com/ws/${canonicalSymbol.toLowerCase()}@bookTicker`
      );
      socket.onopen = () => {
        if (!closed) {
          setDirectWsLive(true);
        }
      };
      socket.onmessage = (msg) => {
        if (closed || typeof msg.data !== 'string') {
          return;
        }
        try {
          const frame = JSON.parse(msg.data) as {
            b?: string;
            a?: string;
            E?: number;
            T?: number;
          };
          const bidPrice = typeof frame.b === 'string' ? frame.b.trim() : '';
          const askPrice = typeof frame.a === 'string' ? frame.a.trim() : '';
          if (!bidPrice || !(Number(bidPrice) > 0)) {
            return;
          }
          const tradeMs = Number(frame.E ?? frame.T ?? Date.now());
          const nowIso = new Date().toISOString();
          const sourceIso = Number.isFinite(tradeMs) ? new Date(tradeMs).toISOString() : nowIso;
          const payload: Partial<LiveQuoteSnapshot> = {
            instrumentId: normalizedId,
            symbol: canonicalSymbol,
            lastPrice: bidPrice,
            markPrice: bidPrice,
            bid: bidPrice,
            ask: askPrice && Number(askPrice) > 0 ? askPrice : bidPrice,
            sourceTime: sourceIso,
            receivedAt: nowIso,
            quality: 'FRESH',
          };

          const elapsed = Date.now() - lastPushedAt;
          if (elapsed >= DIRECT_WS_MIN_INTERVAL_MS) {
            if (flushTimer) {
              clearTimeout(flushTimer);
              flushTimer = null;
            }
            pendingPayload = null;
            pushTick(payload);
          } else {
            pendingPayload = payload;
            if (!flushTimer) {
              flushTimer = setTimeout(flushPending, DIRECT_WS_MIN_INTERVAL_MS - elapsed);
            }
          }
        } catch {
          // Bireysel bozuk kareyi yoksay
        }
      };
      socket.onerror = () => {
        if (!closed) {
          setDirectWsLive(false);
        }
      };
      socket.onclose = () => {
        if (!closed) {
          setDirectWsLive(false);
        }
      };
    } catch {
      setDirectWsLive(false);
    }

    return () => {
      closed = true;
      setDirectWsLive(false);
      if (flushTimer) {
        clearTimeout(flushTimer);
      }
      if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
        try {
          socket.close();
        } catch {
          // Kapanış hatalarını yut
        }
      }
    };
  }, [canonicalSymbol, enabled, isTestEnv, normalizedId]);

  const sseStream = useManagedSseStream({
    enabled: Boolean(enabled && normalizedId && (isTestEnv || !directWsLive)),
    path: normalizedId ? `/market/stream?instrumentId=${encodeURIComponent(normalizedId)}` : null,
    resyncQueryKeys,
    onAcceptedEvent,
  });

  return {
    status: directWsLive ? 'LIVE' : sseStream.status,
    lastHeartbeatAt: directWsLive ? lastWsTickAt : sseStream.lastHeartbeatAt,
  };
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

  const seriesInstrument = series.symbol || series.instrumentId;
  const snapshotInstrument = snapshot.symbol || snapshot.instrumentId;
  if (!instrumentsMatch(seriesInstrument, snapshotInstrument)) {
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

/**
 * Periyodik REST `/candles` yanıtı gelirken canlı ticklerin biriktirdiği
 * son mumun high/low fitillerini ve yeni dakika açılışını korur.
 */
export function reconcileIncomingCandles(
  prev: CandleSeriesResponse | undefined,
  next: CandleSeriesResponse
): CandleSeriesResponse {
  if (!prev || prev.bars.length === 0 || next.bars.length === 0) {
    return next;
  }
  if (
    prev.interval !== next.interval ||
    !instrumentsMatch(prev.symbol || prev.instrumentId, next.symbol || next.instrumentId)
  ) {
    return next;
  }
  const intervalMs = INTERVAL_MS[next.interval];
  if (!intervalMs) {
    return next;
  }

  const prevLast = prev.bars[prev.bars.length - 1];
  const nextLast = next.bars[next.bars.length - 1];
  const prevLastOpenMs = Date.parse(prevLast.openTime);
  const nextLastOpenMs = Date.parse(nextLast.openTime);
  if (!Number.isFinite(prevLastOpenMs) || !Number.isFinite(nextLastOpenMs)) {
    return next;
  }

  if (prevLastOpenMs === nextLastOpenMs) {
    const pHigh = Number(prevLast.high);
    const nHigh = Number(nextLast.high);
    const pLow = Number(prevLast.low);
    const nLow = Number(nextLast.low);
    const prevAsOfMs = Date.parse(prev.asOf);
    const nextAsOfMs = Date.parse(next.asOf);
    const preferPrevClose =
      Number.isFinite(prevAsOfMs) && Number.isFinite(nextAsOfMs) && prevAsOfMs >= nextAsOfMs - 3_000;

    const mergedBar: CandleBar = {
      ...nextLast,
      high:
        Number.isFinite(pHigh) && (!Number.isFinite(nHigh) || pHigh > nHigh)
          ? prevLast.high
          : nextLast.high,
      low:
        Number.isFinite(pLow) && (!Number.isFinite(nLow) || pLow < nLow)
          ? prevLast.low
          : nextLast.low,
      close: preferPrevClose ? prevLast.close : nextLast.close,
    };
    return {
      ...next,
      asOf: preferPrevClose ? prev.asOf : next.asOf,
      bars: [...next.bars.slice(0, -1), mergedBar],
    };
  }

  if (prevLastOpenMs === nextLastOpenMs + intervalMs) {
    const completedNextLast: CandleBar = { ...nextLast, isComplete: true };
    return {
      ...next,
      asOf: prev.asOf,
      bars: [...next.bars.slice(0, -1), completedNextLast, prevLast],
    };
  }

  return next;
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

import type {
  CandleInterval,
  CandleSeriesResponse,
  CursorPage,
  InstrumentItem,
  MarketSnapshot,
} from '@/shared/contracts/types';
import { apiRequest } from '@/shared/api/apiClient';

export interface BinanceTickerItem {
  instrumentId: string;
  symbol: string;
  baseAsset: string;
  quoteAsset: string;
  exchangeId: string;
  contractType: string;
  lastPrice: string;
  markPrice: string;
  indexPrice: string;
  priceChange: string;
  priceChangePercent: string;
  highPrice: string;
  lowPrice: string;
  volume: string;
  quoteVolume: string;
  fundingRate: string;
  tradeCount: number;
  tickSize: string;
  stepSize: string;
  minNotional: string;
}

export interface BinanceTickersResponse {
  items: BinanceTickerItem[];
  asOf: string;
  source: string;
}

export const marketApi = {
  listTickers(params?: {
    search?: string;
    limit?: number;
    signal?: AbortSignal;
  }): Promise<BinanceTickersResponse> {
    const qs = new URLSearchParams();
    if (params?.search?.trim()) qs.set('search', params.search.trim());
    if (params?.limit) qs.set('limit', String(params.limit));
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return apiRequest<BinanceTickersResponse>(`/market/tickers${suffix}`, {
      method: 'GET',
      signal: params?.signal,
    });
  },

  listInstruments(params: {
    search?: string;
    cursor?: string | null;
    limit?: number;
    onlyTradable?: boolean;
    signal?: AbortSignal;
  }): Promise<CursorPage<InstrumentItem>> {
    const qs = new URLSearchParams();
    if (params.search?.trim()) qs.set('search', params.search.trim());
    if (params.cursor) qs.set('cursor', params.cursor);
    if (params.limit) qs.set('limit', String(params.limit));
    if (params.onlyTradable) qs.set('onlyTradable', 'true');
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return apiRequest<CursorPage<InstrumentItem>>(`/market/instruments${suffix}`, {
      method: 'GET',
      signal: params.signal,
    });
  },

  getSnapshot(instrumentId: string, signal?: AbortSignal): Promise<MarketSnapshot> {
    const qs = new URLSearchParams({ instrumentId });
    return apiRequest<MarketSnapshot>(`/market/quotes?${qs.toString()}`, {
      method: 'GET',
      signal,
    });
  },

  getCandles(
    params: {
      instrumentId: string;
      interval: CandleInterval;
      limit?: number;
    },
    signal?: AbortSignal
  ): Promise<CandleSeriesResponse> {
    const qs = new URLSearchParams({
      instrumentId: params.instrumentId,
      interval: params.interval,
      limit: String(params.limit ?? 120),
    });
    return apiRequest<CandleSeriesResponse>(`/market/candles?${qs.toString()}`, {
      method: 'GET',
      signal,
    });
  },
};


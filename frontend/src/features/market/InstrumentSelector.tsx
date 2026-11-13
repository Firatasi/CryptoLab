import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, SlidersHorizontal } from 'lucide-react';
import type { InstrumentItem } from '@/shared/contracts/types';
import { queryKeys } from '@/shared/api/queryKeys';
import { formatReasonCode } from '@/shared/formatting/formatters';
import { Badge, Button, Card, Skeleton } from '@/shared/ui/Primitives';
import { ErrorBanner, EmptyState } from '@/shared/ui/Feedback';
import { cn } from '@/shared/ui/cn';
import { marketApi } from './api';

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

export interface InstrumentSelectorProps {
  selectedInstrumentId: string | null;
  onSelectInstrument: (instrument: InstrumentItem) => void;
  hasSelectedInstrument?: boolean;
  compact?: boolean;
}

/**
 * Dinamik Enstrüman Kataloğu:
 * - Sabit coin enum'u yoktur; anahtar her zaman instrumentId (UUID) değeridir.
 * - Aynı sembolün farklı borsa/kontrat kayıtları ayrı satır olarak gösterilir.
 * - İşleme uygun olmayan sözleşmeler gizlenmez; capabilities ve reasonCodes açıkça gösterilir.
 */
export function InstrumentSelector({
  selectedInstrumentId,
  onSelectInstrument,
  hasSelectedInstrument,
  compact = false,
}: InstrumentSelectorProps) {
  const [rawSearch, setRawSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [onlyTradable, setOnlyTradable] = useState(false);
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([null]);
  const activeCursor = cursorHistory[cursorHistory.length - 1] ?? null;

  useEffect(() => {
    const handle = setTimeout(() => {
      setDebouncedSearch(rawSearch.trim());
      setCursorHistory([null]);
    }, 250);
    return () => clearTimeout(handle);
  }, [rawSearch]);

  const catalogQuery = useQuery({
    queryKey: queryKeys.market.instruments(debouncedSearch, activeCursor, onlyTradable),
    queryFn: ({ signal }) =>
      marketApi.listInstruments({
        search: debouncedSearch,
        cursor: activeCursor,
        limit: 50,
        onlyTradable,
        signal,
      }),
  });

  const items = catalogQuery.data?.items ?? [];
  const firstItem = catalogQuery.data?.items?.[0] ?? null;
  const targetLookup = selectedInstrumentId
    ? (UUID_TO_SYMBOL[selectedInstrumentId.toLowerCase()] ?? selectedInstrumentId).toUpperCase()
    : null;

  const matchedInCatalog = targetLookup
    ? items.find(
        (it) =>
          it.instrumentId.toUpperCase() === targetLookup ||
          it.symbol.toUpperCase() === targetLookup
      )
    : undefined;

  const lookupQuery = useQuery({
    queryKey: queryKeys.market.instruments(`LOOKUP:${targetLookup ?? ''}`, null, false),
    queryFn: ({ signal }) =>
      marketApi.listInstruments({
        search: targetLookup!,
        limit: 25,
        signal,
      }),
    enabled: Boolean(targetLookup) && !matchedInCatalog && debouncedSearch === '',
  });

  const matchedFromLookup = targetLookup
    ? lookupQuery.data?.items?.find(
        (it) =>
          it.instrumentId.toUpperCase() === targetLookup ||
          it.symbol.toUpperCase() === targetLookup
      ) ?? (lookupQuery.data?.items?.length === 1 ? lookupQuery.data.items[0] : undefined)
    : undefined;

  const matched = matchedInCatalog ?? matchedFromLookup;
  const displayItems =
    debouncedSearch === '' &&
    matchedFromLookup &&
    !items.some((it) => it.instrumentId === matchedFromLookup.instrumentId)
      ? [matchedFromLookup, ...items]
      : items;

  // İlk yüklemede veya URL UUID / sembolü ile gelindiğinde ilgili sözleşmeyi otomatik seç
  useEffect(() => {
    if (!targetLookup) {
      if (items.length > 0 && firstItem && hasSelectedInstrument === false) {
        onSelectInstrument(firstItem);
      }
      return;
    }
    if (
      matched &&
      (matched.instrumentId !== selectedInstrumentId || hasSelectedInstrument === false)
    ) {
      onSelectInstrument(matched);
      return;
    }
    if (
      hasSelectedInstrument === false &&
      !matched &&
      firstItem &&
      catalogQuery.isSuccess &&
      !lookupQuery.isFetching &&
      lookupQuery.isSuccess
    ) {
      onSelectInstrument(firstItem);
    }
  }, [
    catalogQuery.isSuccess,
    firstItem,
    hasSelectedInstrument,
    items.length,
    lookupQuery.isFetching,
    lookupQuery.isSuccess,
    matched,
    onSelectInstrument,
    selectedInstrumentId,
    targetLookup,
  ]);

  return (
    <Card className="flex h-full flex-col overflow-hidden">
      <div className="space-y-2.5 border-b border-[#e5e5e5] p-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-[#0a0a0a]">
            Sözleşmeler
          </h2>
          <button
            type="button"
            onClick={() => {
              setOnlyTradable((prev) => !prev);
              setCursorHistory([null]);
            }}
            aria-pressed={onlyTradable}
            className={cn(
              'inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-none border px-2 py-0.5 text-[10px] font-medium transition-colors',
              onlyTradable
                ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
            )}
          >
            <SlidersHorizontal className="h-3 w-3" aria-hidden="true" />
            İşleme Açık
          </button>
        </div>

        <div className="relative">
          <Search
            className="pointer-events-none absolute left-2.5 top-2.5 h-3.5 w-3.5 text-[#737373]"
            aria-hidden="true"
          />
          <input
            type="search"
            aria-label="Enstrüman veya borsa ara"
            placeholder="Sembol ara (BTC, ETH, SOL)..."
            value={rawSearch}
            onChange={(e) => setRawSearch(e.target.value)}
            className="h-8 w-full rounded-none border border-[#e5e5e5] bg-[#f5f5f5] pl-8 pr-2.5 text-xs text-[#0a0a0a] placeholder:text-[#737373] focus:border-[#0a0a0a] focus:bg-white focus:outline-none"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2 space-y-1">
        {catalogQuery.isLoading ? (
          <div className="space-y-2 p-1">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : catalogQuery.isError ? (
          <ErrorBanner
            error={catalogQuery.error}
            title="Enstrüman kataloğu yüklenemedi"
            onRetry={() => void catalogQuery.refetch()}
          />
        ) : displayItems.length === 0 ? (
          <EmptyState
            title="Eşleşen sözleşme bulunamadı"
            description="Arama filtresini temizleyerek tüm sözleşmeleri görüntüleyebilirsiniz."
          />
        ) : (
          displayItems.map((item) => {
            const isSelected =
              item.instrumentId === selectedInstrumentId ||
              (targetLookup !== null && item.symbol.toUpperCase() === targetLookup);
            const { paperTradable, analysisReady, reasonCodes } = item.capabilities;

            return (
              <button
                key={item.instrumentId}
                type="button"
                onClick={() => onSelectInstrument(item)}
                className={cn(
                  'w-full rounded-none border px-2.5 py-2 text-left transition-colors',
                  isSelected
                    ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                    : 'border-[#e5e5e5] bg-white text-[#0a0a0a] hover:border-[#0a0a0a] hover:bg-[#f5f5f5]'
                )}
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <span
                      className={cn(
                        'h-1.5 w-1.5 shrink-0 rounded-full',
                        paperTradable
                          ? isSelected
                            ? 'bg-emerald-400'
                            : 'bg-emerald-600'
                          : 'bg-amber-500'
                      )}
                    />
                    <span className="truncate font-mono text-xs font-bold">{item.symbol}</span>
                  </div>
                  {!compact && (
                    <span
                      className={cn(
                        'shrink-0 font-mono text-[10px]',
                        isSelected ? 'text-[#a3a3a3]' : 'text-[#737373]'
                      )}
                    >
                      {item.maxLeverage}x · PERP
                    </span>
                  )}
                </div>

                {!compact && (
                  <div className="mt-1.5 flex flex-wrap items-center gap-1">
                    <Badge tone={paperTradable ? 'success' : 'wait'}>
                      {paperTradable ? '● İşleme Açık' : '⏸ İşleme Kapalı'}
                    </Badge>
                    <Badge tone={analysisReady ? 'accent' : 'neutral'}>
                      {analysisReady ? 'AI Hazır' : 'AI Veri Bekliyor'}
                    </Badge>
                  </div>
                )}

                {reasonCodes.length > 0 && (
                  <div
                    className={cn(
                      'mt-1 truncate text-[10px]',
                      isSelected ? 'text-[#d4d4d4]' : 'text-[#737373]'
                    )}
                  >
                    {reasonCodes.map((code) => formatReasonCode(code)).join(' · ')}
                  </div>
                )}
              </button>
            );
          })
        )}
      </div>

      <div className="flex items-center justify-between border-t border-[#e5e5e5] px-3 py-2 text-[11px] text-[#737373]">
        <span>Sayfa #{cursorHistory.length}</span>
        <div className="flex items-center gap-1.5">
          <Button
            variant="secondary"
            size="xs"
            disabled={cursorHistory.length <= 1 || catalogQuery.isFetching}
            onClick={() => setCursorHistory((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev))}
          >
            Önceki
          </Button>
          <Button
            variant="secondary"
            size="xs"
            disabled={!catalogQuery.data?.nextCursor || catalogQuery.isFetching}
            onClick={() => {
              const next = catalogQuery.data?.nextCursor;
              if (next) {
                setCursorHistory((prev) => [...prev, next]);
              }
            }}
          >
            Sonraki
          </Button>
        </div>
      </div>
    </Card>
  );
}

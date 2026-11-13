import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowRight, BrainCircuit } from 'lucide-react';
import type { CandleInterval, InstrumentItem } from '@/shared/contracts/types';
import { queryKeys } from '@/shared/api/queryKeys';
import { useAuth } from '@/features/auth/AuthContext';
import { loadPersistedJobs } from '@/shared/storage/jobPersistence';
import { formatDecimal, formatReasonCode } from '@/shared/formatting/formatters';
import { Badge, Button, Card, CardHeader } from '@/shared/ui/Primitives';
import { useManagedSseStream } from '@/shared/stream/useManagedSseStream';
import { ConnectionStatusBadge } from '@/shared/ui/Feedback';
import { marketApi } from './api';
import { InstrumentSelector } from './InstrumentSelector';
import { PriceCard } from './PriceCard';
import { CandleChart } from './CandleChart';

export function MarketPage() {
  const { user, timezone } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [selectedInstrument, setSelectedInstrument] = useState<InstrumentItem | null>(null);

  const selectedId = selectedInstrument?.instrumentId ?? searchParams.get('instrumentId');
  const intervalParam = (searchParams.get('interval') as CandleInterval | null) ?? '15m';

  const stream = useManagedSseStream({
    enabled: Boolean(selectedId),
    path: selectedId ? `/market/stream?instrumentId=${encodeURIComponent(selectedId)}` : null,
    resyncQueryKeys: selectedId ? [queryKeys.market.quote(selectedId)] : [],
  });

  const quoteQuery = useQuery({
    queryKey: queryKeys.market.quote(selectedId ?? ''),
    queryFn: ({ signal }) => marketApi.getSnapshot(selectedId!, signal),
    enabled: Boolean(selectedId),
    refetchInterval: stream.status === 'LIVE' ? false : 8_000,
  });

  const candlesQuery = useQuery({
    queryKey: queryKeys.market.candles(selectedId ?? '', intervalParam),
    queryFn: ({ signal }) =>
      marketApi.getCandles({ instrumentId: selectedId!, interval: intervalParam }, signal),
    enabled: Boolean(selectedId),
  });

  const activeSessionId = loadPersistedJobs(user?.id).activeSessionId;

  const handleSelectInstrument = (inst: InstrumentItem) => {
    setSelectedInstrument(inst);
    const next = new URLSearchParams(searchParams);
    next.set('instrumentId', inst.instrumentId);
    setSearchParams(next, { replace: true });
  };

  const handleIntervalChange = (nextInterval: CandleInterval) => {
    const next = new URLSearchParams(searchParams);
    next.set('interval', nextInterval);
    setSearchParams(next, { replace: true });
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-[#0a0a0a]">
            Dinamik Piyasa Kataloğu ve Veri Kapsamı
          </h1>
          <p className="mt-0.5 text-xs text-[#737373]">
            Sözleşmeler sunucudan dinamik sorgulanır; mark fiyatı, son işlem ve veri boşlukları
            ayrı ayrı raporlanır.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <ConnectionStatusBadge state={stream.status} />
          {activeSessionId && selectedId && (
            <>
              <Link
                to={`/analysis/${activeSessionId}?instrumentId=${encodeURIComponent(selectedId)}`}
              >
                <Button
                  variant="secondary"
                  size="sm"
                  leftIcon={<BrainCircuit className="h-4 w-4" aria-hidden="true" />}
                >
                  AI Analizine Gönder
                </Button>
              </Link>
              <Link to={`/trade/${activeSessionId}?instrumentId=${encodeURIComponent(selectedId)}`}>
                <Button
                  variant="primary"
                  size="sm"
                  disabled={selectedInstrument ? !selectedInstrument.capabilities.paperTradable : false}
                  leftIcon={<ArrowRight className="h-4 w-4" aria-hidden="true" />}
                >
                  Sanal İşlem Ekranında Aç
                </Button>
              </Link>
            </>
          )}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[320px_1fr]">
        <div className="h-[680px]">
          <InstrumentSelector
            selectedInstrumentId={selectedId}
            onSelectInstrument={handleSelectInstrument}
          />
        </div>

        <div className="space-y-4">
          <PriceCard
            snapshot={quoteQuery.data}
            exchangeLabel={
              selectedInstrument
                ? `${selectedInstrument.exchangeId} · ${selectedInstrument.contractType}`
                : undefined
            }
            loading={quoteQuery.isLoading}
            error={quoteQuery.isError ? quoteQuery.error : null}
            onRetry={() => void quoteQuery.refetch()}
            timezone={timezone}
          />

          <CandleChart
            series={candlesQuery.data}
            interval={intervalParam}
            onIntervalChange={handleIntervalChange}
            loading={candlesQuery.isLoading}
            error={candlesQuery.isError ? candlesQuery.error : null}
            onRetry={() => void candlesQuery.refetch()}
            timezone={timezone}
            height={360}
          />

          {selectedInstrument && (
            <Card>
              <CardHeader
                title="Sözleşme Kuralları ve Uygunluk Durumu"
                subtitle={`instrumentId: ${selectedInstrument.instrumentId} · Sürüm v${selectedInstrument.version}`}
              />
              <div className="grid gap-3 p-4 sm:grid-cols-4">
                <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                  <div className="text-[11px] text-[#737373]">Fiyat Adımı (Tick Size)</div>
                  <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a] tabular-nums">
                    {formatDecimal(selectedInstrument.tickSize, {
                      minFractionDigits: 0,
                      maxFractionDigits: 8,
                    })}
                  </div>
                </div>
                <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                  <div className="text-[11px] text-[#737373]">Miktar Adımı (Step Size)</div>
                  <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a] tabular-nums">
                    {formatDecimal(selectedInstrument.stepSize, {
                      minFractionDigits: 0,
                      maxFractionDigits: 8,
                    })}
                  </div>
                </div>
                <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                  <div className="text-[11px] text-[#737373]">Min. Notional</div>
                  <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a] tabular-nums">
                    {formatDecimal(selectedInstrument.minNotional, { minFractionDigits: 2 })}{' '}
                    {selectedInstrument.settlementAsset}
                  </div>
                </div>
                <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                  <div className="text-[11px] text-[#737373]">Maks. Kaldıraç</div>
                  <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a] tabular-nums">
                    {selectedInstrument.maxLeverage}x
                  </div>
                </div>
              </div>

              {selectedInstrument.capabilities.reasonCodes.length > 0 && (
                <div className="border-t border-[#e5e5e5] px-4 py-3">
                  <div className="text-xs font-medium text-[#0a0a0a]">Kısıt Nedenleri:</div>
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {selectedInstrument.capabilities.reasonCodes.map((code) => (
                      <Badge key={code} tone="wait">
                        {formatReasonCode(code)}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

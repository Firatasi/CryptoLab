import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertTriangle, BrainCircuit, CheckCircle2, History, X } from 'lucide-react';
import type { CandleInterval, InstrumentItem } from '@/shared/contracts/types';
import { queryKeys } from '@/shared/api/queryKeys';
import { useAuth } from '@/features/auth/AuthContext';
import { savePersistedJobs } from '@/shared/storage/jobPersistence';
import { useManagedSseStream } from '@/shared/stream/useManagedSseStream';
import { formatMoney, formatSignedMoney } from '@/shared/formatting/formatters';
import { marketApi } from '@/features/market/api';
import { InstrumentSelector } from '@/features/market/InstrumentSelector';
import { PriceCard } from '@/features/market/PriceCard';
import { CandleChart } from '@/features/market/CandleChart';
import {
  mergeLiveSnapshotIntoCandles,
  useLiveMarketQuoteStream,
} from '@/features/market/liveMarketStream';
import { sessionsApi } from '@/features/sessions/api';
import { SessionStatusBadge, walletPnlToneClass } from '@/features/sessions/SessionsPage';
import { Badge, Button, Card } from '@/shared/ui/Primitives';
import { ErrorBanner } from '@/shared/ui/Feedback';
import { cn } from '@/shared/ui/cn';
import { tradingApi } from './api';
import { OrderTicket, type AiProposalSeed } from './OrderTicket';
import { OpenOrdersTable, PositionsTable } from './PositionsAndOrders';

export function TradeWorkspacePage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const { user, timezone } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const queryClient = useQueryClient();
  const [selectedInstrument, setSelectedInstrument] = useState<InstrumentItem | null>(null);
  const [blotterTab, setBlotterTab] = useState<'POSITIONS' | 'ORDERS'>('POSITIONS');
  const [autoExitAlert, setAutoExitAlert] = useState<{ tone: 'danger' | 'success'; message: string } | null>(null);
  const [aiSeed, setAiSeed] = useState<AiProposalSeed | null>(
    () => (location.state as { aiProposalSeed?: AiProposalSeed } | null)?.aiProposalSeed ?? null
  );

  const urlInstrumentId = searchParams.get('instrumentId');
  const prevUrlInstrumentRef = useRef<string | null>(urlInstrumentId);

  useEffect(() => {
    const incomingSeed =
      (location.state as { aiProposalSeed?: AiProposalSeed } | null)?.aiProposalSeed ?? null;
    if (incomingSeed) {
      setAiSeed(incomingSeed);
    }
  }, [location.key, location.state]);

  useEffect(() => {
    if (prevUrlInstrumentRef.current === urlInstrumentId) return;
    prevUrlInstrumentRef.current = urlInstrumentId;
    if (!urlInstrumentId || !selectedInstrument) return;
    const target = urlInstrumentId.toUpperCase();
    if (
      selectedInstrument.instrumentId.toUpperCase() !== target &&
      selectedInstrument.symbol.toUpperCase() !== target
    ) {
      setSelectedInstrument(null);
    }
  }, [urlInstrumentId, selectedInstrument]);

  const handleSelectInstrument = useCallback(
    (inst: InstrumentItem) => {
      setSelectedInstrument(inst);
      setSearchParams(
        (prev) => {
          if (prev.get('instrumentId') === inst.instrumentId) {
            return prev;
          }
          const next = new URLSearchParams(prev);
          next.set('instrumentId', inst.instrumentId);
          return next;
        },
        { replace: true }
      );
    },
    [setSearchParams]
  );

  const selectedInstrumentId =
    selectedInstrument?.instrumentId ?? urlInstrumentId ?? aiSeed?.symbol ?? null;
  const chartInterval = (searchParams.get('interval') as CandleInterval | null) ?? '15m';

  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: ({ signal }) => sessionsApi.getById(sessionId, signal),
    enabled: Boolean(sessionId),
  });

  const effectiveSessionId = sessionQuery.data?.id ?? sessionId;

  useEffect(() => {
    if (sessionQuery.data?.id && sessionQuery.data.id !== sessionId) {
      navigate(`/trade/${sessionQuery.data.id}${location.search}`, {
        replace: true,
        state: location.state,
      });
    }
  }, [location.search, location.state, navigate, sessionId, sessionQuery.data?.id]);

  // Oturum SSE akışı: changedResources, position.liquidated ve resync olaylarında ilgili sunucu sorgularını tazeler
  const sessionStream = useManagedSseStream({
    enabled: Boolean(effectiveSessionId),
    path: effectiveSessionId ? `/sim/sessions/${encodeURIComponent(effectiveSessionId)}/stream` : null,
    resyncQueryKeys: [
      queryKeys.sessions.detail(effectiveSessionId),
      queryKeys.trading.positions(effectiveSessionId),
      queryKeys.trading.openOrders(effectiveSessionId),
      queryKeys.account.summary(effectiveSessionId),
    ],
    onAcceptedEvent: (evt) => {
      if (evt.event === 'position.liquidated' || evt.event === 'position.closed' || evt.event === 'session.updated') {
        void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(effectiveSessionId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.trading.positions(effectiveSessionId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.trading.openOrders(effectiveSessionId) });
        void queryClient.invalidateQueries({ queryKey: queryKeys.account.summary(effectiveSessionId) });
        void queryClient.invalidateQueries({ queryKey: ['account', 'trades', effectiveSessionId] });
      }
      if (evt.event === 'position.liquidated') {
        let symbol = 'Pozisyon';
        try {
          const parsed = JSON.parse(evt.data) as { symbol?: string };
          if (parsed.symbol) {
            symbol = parsed.symbol;
          }
        } catch {
          // ham metin fallback
        }
        setAutoExitAlert({
          tone: 'danger',
          message: `${symbol} açık pozisyonu likidasyon seviyesine ulaştığı için sistem tarafından otomatik kapatıldı.`,
        });
      }
      if (evt.event === 'position.closed') {
        let symbol = 'Pozisyon';
        let reason: string | undefined;
        let exitPrice: string | undefined;
        try {
          const parsed = JSON.parse(evt.data) as {
            symbol?: string;
            reason?: string;
            exitPrice?: string;
          };
          if (parsed.symbol) {
            symbol = parsed.symbol;
          }
          reason = parsed.reason;
          exitPrice = parsed.exitPrice;
        } catch {
          // ham metin fallback
        }
        const priceSuffix = exitPrice ? ` · ${formatMoney(exitPrice)}` : '';
        if (reason === 'TAKE_PROFIT') {
          setAutoExitAlert({
            tone: 'success',
            message: `${symbol} pozisyonu Kâr Al (TP${priceSuffix}) seviyesine ulaştığı için sistem tarafından otomatik kapatıldı.`,
          });
        } else {
          setAutoExitAlert({
            tone: 'danger',
            message: `${symbol} pozisyonu Zarar Durdur (SL${priceSuffix}) seviyesine ulaştığı için sistem tarafından otomatik kapatıldı.`,
          });
        }
      }
    },
  });

  useEffect(() => {
    const resolvedId = sessionQuery.data?.id || sessionId;
    if (resolvedId) {
      savePersistedJobs(user?.id, (prev) => ({ ...prev, activeSessionId: resolvedId }));
    }
  }, [sessionId, sessionQuery.data?.id, user?.id]);

  const marketStream = useLiveMarketQuoteStream({
    enabled: Boolean(selectedInstrumentId),
    instrumentId: selectedInstrumentId,
  });

  const quoteQuery = useQuery({
    queryKey: queryKeys.market.quote(selectedInstrumentId ?? ''),
    queryFn: ({ signal }) => marketApi.getSnapshot(selectedInstrumentId!, signal),
    enabled: Boolean(selectedInstrumentId),
    refetchInterval: marketStream.status === 'LIVE' ? false : 2_000,
  });

  const candlesQuery = useQuery({
    queryKey: queryKeys.market.candles(selectedInstrumentId ?? '', chartInterval),
    queryFn: ({ signal }) =>
      marketApi.getCandles(
        { instrumentId: selectedInstrumentId!, interval: chartInterval },
        signal
      ),
    enabled: Boolean(selectedInstrumentId),
    refetchInterval: 3_000,
  });

  const liveCandles = useMemo(
    () => mergeLiveSnapshotIntoCandles(candlesQuery.data, quoteQuery.data),
    [candlesQuery.data, quoteQuery.data]
  );

  const positionsQuery = useQuery({
    queryKey: queryKeys.trading.positions(effectiveSessionId),
    queryFn: ({ signal }) => tradingApi.listPositions(effectiveSessionId, signal),
    enabled: Boolean(effectiveSessionId),
    refetchInterval: (query) =>
      (query.state.data?.items?.length ?? 0) > 0
        ? 2_000
        : sessionStream.status === 'LIVE'
          ? false
          : 8_000,
  });

  const openOrdersQuery = useQuery({
    queryKey: queryKeys.trading.openOrders(effectiveSessionId),
    queryFn: ({ signal }) => tradingApi.listOpenOrders(effectiveSessionId, signal),
    enabled: Boolean(effectiveSessionId),
    refetchInterval: sessionStream.status === 'LIVE' ? false : 8_000,
  });

  const positions = positionsQuery.data?.items ?? [];
  const openOrders = openOrdersQuery.data?.items ?? [];

  const prevOpenPositionsCountRef = useRef<number | null>(null);
  useEffect(() => {
    const prev = prevOpenPositionsCountRef.current;
    prevOpenPositionsCountRef.current = positions.length;
    if (prev !== null && prev > positions.length && effectiveSessionId) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(effectiveSessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.account.summary(effectiveSessionId) });
      void queryClient.invalidateQueries({ queryKey: ['account', 'trades', effectiveSessionId] });
    }
  }, [effectiveSessionId, positions.length, queryClient]);

  return (
    <div className="space-y-3">
      {/* Üst Oturum ve Hızlı Eylem Çubuğu */}
      <Card className="px-4 py-2.5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2.5">
            <Badge tone="paper">
              {sessionQuery.data?.name || 'Sanal Cüzdan'}
            </Badge>
            {sessionQuery.data && <SessionStatusBadge status={sessionQuery.data.status} />}
            {sessionQuery.data && (
              <div className="hidden items-center gap-4 border-l border-[#e5e5e5] pl-3 font-mono text-xs tabular-nums sm:flex">
                <span className="text-[#737373]">
                  Kullanılabilir Bakiye:{' '}
                  <strong className="text-[#0a0a0a]">
                    {formatMoney(
                      sessionQuery.data.availableBalance ?? sessionQuery.data.initialDeposit,
                      sessionQuery.data.currency
                    )}
                  </strong>
                </span>
                <span className="text-[#737373]">
                  İzole Marjin:{' '}
                  <strong className="text-[#0a0a0a]">
                    {formatMoney(
                      sessionQuery.data.isolatedMargin ?? '0',
                      sessionQuery.data.currency
                    )}
                  </strong>
                </span>
                {sessionQuery.data.netPnl !== undefined && (
                  <span className="text-[#737373]">
                    Mevcut K/Z:{' '}
                    <strong className={walletPnlToneClass(sessionQuery.data.netPnl)}>
                      {formatSignedMoney(
                        sessionQuery.data.netPnl,
                        sessionQuery.data.currency
                      )}
                    </strong>
                  </span>
                )}
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            <Link
              to={`/analysis/${sessionQuery.data?.id || sessionId}${
                selectedInstrumentId
                  ? `?instrumentId=${encodeURIComponent(selectedInstrumentId)}`
                  : ''
              }`}
            >
              <Button
                variant="secondary"
                size="xs"
                leftIcon={<BrainCircuit className="h-3.5 w-3.5 text-[#0a0a0a]" aria-hidden="true" />}
              >
                AI Analiz
              </Button>
            </Link>
            <Link to={`/history/${sessionQuery.data?.id || sessionId}`}>
              <Button
                variant="secondary"
                size="xs"
                leftIcon={<History className="h-3.5 w-3.5" aria-hidden="true" />}
              >
                İşlem Geçmişi
              </Button>
            </Link>
          </div>
        </div>
      </Card>

      {autoExitAlert && (
        <div
          role="status"
          className={cn(
            'flex items-center justify-between gap-3 rounded-none border px-4 py-2.5 text-xs',
            autoExitAlert.tone === 'success'
              ? 'border-[#16a34a] bg-emerald-50 text-[#15803d]'
              : 'border-[#dc2626] bg-red-50 text-[#dc2626]',
          )}
        >
          <div className="flex items-center gap-2 font-medium">
            {autoExitAlert.tone === 'success' ? (
              <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />
            ) : (
              <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
            )}
            <span>{autoExitAlert.message}</span>
          </div>
          <button
            type="button"
            aria-label="Pozisyon kapanış bildirimini kapat"
            onClick={() => setAutoExitAlert(null)}
            className={cn(
              'shrink-0 p-0.5',
              autoExitAlert.tone === 'success'
                ? 'text-[#15803d]/80 hover:text-[#15803d]'
                : 'text-[#dc2626]/80 hover:text-[#dc2626]',
            )}
          >
            <X className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        </div>
      )}

      {sessionQuery.isError && (
        <ErrorBanner
          error={sessionQuery.error}
          title="Oturum bilgisi yüklenemedi"
          onRetry={() => void sessionQuery.refetch()}
        />
      )}

      {/* 3 Sütunlu Kurumsal Terminal Düzeni */}
      <div className="grid gap-3 lg:grid-cols-[240px_minmax(0,1fr)_360px]">
        {/* Sol Sütun: Dinamik Enstrüman Kataloğu */}
        <div className="h-[520px] lg:h-[780px]">
          <InstrumentSelector
            selectedInstrumentId={selectedInstrumentId}
            hasSelectedInstrument={Boolean(selectedInstrument)}
            onSelectInstrument={handleSelectInstrument}
            compact
          />
        </div>

        {/* Orta Sütun: Fiyat Özeti + Mum Grafiği + Pozisyon/Emir Blotter */}
        <div className="space-y-3 min-w-0">
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
            series={liveCandles}
            interval={chartInterval}
            onIntervalChange={(nextInterval) => {
              const next = new URLSearchParams(searchParams);
              next.set('interval', nextInterval);
              setSearchParams(next, { replace: true });
            }}
            loading={candlesQuery.isLoading}
            error={candlesQuery.isError ? candlesQuery.error : null}
            onRetry={() => void candlesQuery.refetch()}
            timezone={timezone}
            height={300}
          />

          {/* Alt Blotter: Açık Pozisyonlar ve Açık Limit Emirler */}
          <Card className="overflow-hidden">
            <div className="flex items-center justify-between border-b border-[#e5e5e5] px-4 py-2">
              <div className="flex items-center gap-2" role="tablist" aria-label="Pozisyon ve emir sekmeleri">
                <button
                  type="button"
                  role="tab"
                  aria-selected={blotterTab === 'POSITIONS'}
                  onClick={() => setBlotterTab('POSITIONS')}
                  className={cn(
                    'rounded-none border px-3 py-1 text-xs font-semibold transition-colors',
                    blotterTab === 'POSITIONS'
                      ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                      : 'border-transparent text-[#737373] hover:text-[#0a0a0a]'
                  )}
                >
                  Açık Pozisyonlar ({positions.length})
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={blotterTab === 'ORDERS'}
                  onClick={() => setBlotterTab('ORDERS')}
                  className={cn(
                    'rounded-none border px-3 py-1 text-xs font-semibold transition-colors',
                    blotterTab === 'ORDERS'
                      ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                      : 'border-transparent text-[#737373] hover:text-[#0a0a0a]'
                  )}
                >
                  Açık Limit Emirler ({openOrders.length})
                </button>
              </div>
            </div>

            <div className="p-3">
              {blotterTab === 'POSITIONS' ? (
                positionsQuery.isError ? (
                  <ErrorBanner
                    error={positionsQuery.error}
                    title="Pozisyonlar yüklenemedi"
                    onRetry={() => void positionsQuery.refetch()}
                  />
                ) : (
                  <PositionsTable
                    sessionId={effectiveSessionId}
                    positions={positions}
                    timezone={timezone}
                  />
                )
              ) : openOrdersQuery.isError ? (
                <ErrorBanner
                  error={openOrdersQuery.error}
                  title="Açık emirler yüklenemedi"
                  onRetry={() => void openOrdersQuery.refetch()}
                />
              ) : (
                <OpenOrdersTable
                  sessionId={effectiveSessionId}
                  orders={openOrders}
                  timezone={timezone}
                />
              )}
            </div>
          </Card>
        </div>

        {/* Sağ Sütun: 6-Durumlu Emir Formu ve Sunucu Önizleme Onayı */}
        <div>
          <OrderTicket
            sessionId={effectiveSessionId}
            instrument={selectedInstrument}
            sessionStatus={sessionQuery.data?.status ?? 'RUNNING'}
            sessionRiskPolicyId={sessionQuery.data?.riskPolicyId ?? null}
            marketSnapshot={quoteQuery.data ?? null}
            aiProposalSeed={aiSeed}
            onClearAiSeed={() => setAiSeed(null)}
            onOrderSettled={(cmd) => {
              if (cmd.status === 'NEW') {
                setBlotterTab('ORDERS');
              } else if (cmd.status === 'FILLED') {
                setBlotterTab('POSITIONS');
              }
              void positionsQuery.refetch();
              void openOrdersQuery.refetch();
            }}
            timezone={timezone}
          />
        </div>
      </div>
    </div>
  );
}

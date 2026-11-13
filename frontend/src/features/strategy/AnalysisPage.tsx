import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Search, X } from 'lucide-react';
import { createAnalysis, getAnalysis, getProposal } from './api';
import { useAuth } from '@/features/auth/AuthContext';
import { marketApi } from '@/features/market/api';
import { sessionsApi } from '@/features/sessions/api';
import { queryKeys } from '@/shared/api/queryKeys';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import {
  loadPersistedJobs,
  savePersistedJobs,
} from '@/shared/storage/jobPersistence';
import {
  formatHorizon,
  formatPrice,
  formatTimestamp,
} from '@/shared/formatting/formatters';
import { toUserFacingErrorMessage } from '@/shared/api/apiErrors';
import { toDecimalInputString } from '@/shared/formatting/decimalParser';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  SideBadge,
} from '@/shared/ui/Primitives';
import { FieldGroup, SelectInput, TextInput } from '@/shared/ui/FormControls';
import { ErrorBanner } from '@/shared/ui/Feedback';
import type {
  AnalysisJobStatus,
  ExplanationStatus,
  HorizonSeconds,
} from '@/shared/contracts/types';
import type { AiProposalSeed } from '@/features/trading/OrderTicket';

const DEFAULT_BTC_INSTRUMENT_ID = '11111111-1111-4111-8111-111111111111';
const FALLBACK_SESSION_UUID = '00000000-0000-4000-8000-000000000001';

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

function ensureUuid(val: string, fallback: string): string {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(val)
    ? val
    : fallback;
}

const TERMINAL_ANALYSIS_STATUSES: AnalysisJobStatus[] = [
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
];

function ExplanationStatusBadge({ status }: { status?: ExplanationStatus | null }) {
  if (!status) return <Badge tone="neutral">YOK</Badge>;
  switch (status) {
    case 'READY':
      return <Badge tone="success">EĞİTİMLİ AÇIKLAMA MODELİ (READY)</Badge>;
    case 'TEMPLATE':
      return <Badge tone="wait">ŞABLON AÇIKLAMA (TEMPLATE — Yedek)</Badge>;
    case 'PENDING':
      return <Badge tone="accent">AÇIKLAMA BEKLENİYOR</Badge>;
    case 'UNAVAILABLE':
      return <Badge tone="danger">AÇIKLAMA KULLANILAMAZ</Badge>;
    default:
      return <Badge tone="neutral">{status}</Badge>;
  }
}

export function AnalysisPage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { user, timezone } = useAuth();

  const initialInstrument = searchParams.get('instrumentId') || DEFAULT_BTC_INSTRUMENT_ID;
  const [instrumentId, setInstrumentId] = useState(initialInstrument);
  const [instrumentSearch, setInstrumentSearch] = useState('');
  const [horizonSeconds, setHorizonSeconds] = useState<HorizonSeconds>(3600);
  const [riskLevel, setRiskLevel] = useState<'LOW' | 'MEDIUM' | 'HIGH'>('MEDIUM');
  const [riskOverridden, setRiskOverridden] = useState(false);
  const [analysisSymbolMap, setAnalysisSymbolMap] = useState<Record<string, string>>(() => {
    const persisted = loadPersistedJobs(user?.id);
    return persisted.analysisSymbolById ?? {};
  });
  const [activeAnalysisId, setActiveAnalysisId] = useState<string | null>(() => {
    const persisted = loadPersistedJobs(user?.id);
    return persisted.analysisBySession?.[sessionId] ?? null;
  });

  useEffect(() => {
    if (!user?.id || !sessionId) return;
    savePersistedJobs(user.id, (prev) => ({
      ...prev,
      analysisBySession: {
        ...(prev.analysisBySession ?? {}),
        ...(activeAnalysisId ? { [sessionId]: activeAnalysisId } : {}),
      },
      analysisSymbolById: {
        ...(prev.analysisSymbolById ?? {}),
        ...analysisSymbolMap,
      },
    }));
  }, [activeAnalysisId, analysisSymbolMap, sessionId, user?.id]);

  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: ({ signal }) => sessionsApi.getById(sessionId, signal),
    enabled: Boolean(sessionId),
  });

  useEffect(() => {
    if (riskOverridden || !sessionQuery.data?.riskPolicyId) return;
    const pid = sessionQuery.data.riskPolicyId;
    if (pid === '99999999-9999-4999-8999-999999999998') {
      setRiskLevel('LOW');
    } else if (pid === '99999999-9999-4999-8999-999999999997') {
      setRiskLevel('HIGH');
    } else {
      setRiskLevel('MEDIUM');
    }
  }, [riskOverridden, sessionQuery.data?.riskPolicyId]);

  const sessionMaxLevBadge =
    sessionQuery.data?.riskPolicyId === '99999999-9999-4999-8999-999999999998'
      ? 'Oturum Sınırı: 5x'
      : sessionQuery.data?.riskPolicyId === '99999999-9999-4999-8999-999999999997'
        ? 'Oturum Sınırı: 50x'
        : 'Oturum Sınırı: 20x';

  const instrumentsQuery = useQuery({
    queryKey: queryKeys.market.instruments('ALL_USDM', null, false),
    queryFn: ({ signal }) => marketApi.listInstruments({ limit: 1000, signal }),
  });

  const instruments = instrumentsQuery.data?.items ?? [];
  const trimmedSearch = instrumentSearch.trim().toUpperCase();
  const filteredInstruments = trimmedSearch
    ? instruments.filter(
        (i) =>
          i.symbol.toUpperCase().includes(trimmedSearch) ||
          i.baseAsset.toUpperCase().includes(trimmedSearch),
      )
    : instruments;

  useEffect(() => {
    if (filteredInstruments.length === 0) return;
    const matchById = filteredInstruments.find((i) => i.instrumentId === instrumentId);
    if (matchById) return;
    const lookupSymbol = UUID_TO_SYMBOL[instrumentId.toLowerCase()] ?? instrumentId;
    const matchBySymbol = filteredInstruments.find(
      (i) => i.symbol.toUpperCase() === lookupSymbol.toUpperCase(),
    );
    if (matchBySymbol) {
      setInstrumentId(matchBySymbol.instrumentId);
    } else {
      setInstrumentId(filteredInstruments[0].instrumentId);
    }
  }, [filteredInstruments, instrumentId]);

  const analysisQuery = useQuery({
    queryKey: queryKeys.strategy.analysis(activeAnalysisId ?? 'none'),
    queryFn: ({ signal }) => getAnalysis(activeAnalysisId!, signal),
    enabled: Boolean(activeAnalysisId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (!status || TERMINAL_ANALYSIS_STATUSES.includes(status)) {
        return false;
      }
      return 1200;
    },
  });

  const proposalId = analysisQuery.data?.proposalId ?? null;
  const proposalQuery = useQuery({
    queryKey: queryKeys.strategy.proposal(proposalId ?? 'none', 1),
    queryFn: ({ signal }) => getProposal(proposalId!, signal),
    enabled:
      Boolean(proposalId) &&
      analysisQuery.data?.status === 'SUCCEEDED' &&
      (analysisQuery.data?.action === 'LONG' || analysisQuery.data?.action === 'SHORT'),
  });

  const startAnalysisMutation = useMutation({
    mutationFn: async () => {
      const matched = instruments.find(
        (i) =>
          i.instrumentId === instrumentId ||
          i.symbol.toUpperCase() === instrumentId.toUpperCase(),
      );
      const resolvedInstrumentId = matched?.instrumentId || instrumentId || DEFAULT_BTC_INSTRUMENT_ID;
      const res = await createAnalysis(
        {
          sessionId: ensureUuid(sessionId, FALLBACK_SESSION_UUID),
          instrumentId: resolvedInstrumentId,
          horizonSeconds,
          riskLevel,
        },
        createIdempotencyKey(),
      );
      return {
        ...res,
        symbol:
          matched?.symbol ??
          UUID_TO_SYMBOL[resolvedInstrumentId.toLowerCase()] ??
          resolvedInstrumentId,
      };
    },
    onSuccess: (res) => {
      setAnalysisSymbolMap((prev) => ({ ...prev, [res.analysisId]: res.symbol }));
      setActiveAnalysisId(res.analysisId);
    },
  });

  const analysis = analysisQuery.data;
  const proposal = proposalQuery.data;
  const selectedInstrumentMeta = instruments.find((i) => i.instrumentId === instrumentId);

  useEffect(() => {
    if (!analysis?.id || !analysis.proposalId) return;
    const knownSymbol =
      analysisSymbolMap[analysis.id] ??
      UUID_TO_SYMBOL[analysis.instrumentId.toLowerCase()];
    if (!knownSymbol || analysisSymbolMap[analysis.proposalId] === knownSymbol) return;
    setAnalysisSymbolMap((prev) => ({
      ...prev,
      [analysis.proposalId!]: knownSymbol,
    }));
  }, [analysis?.id, analysis?.instrumentId, analysis?.proposalId, analysisSymbolMap]);

  const handleTransferProposalToTicket = () => {
    if (!proposal) return;
    const proposalMatchedMeta = instruments.find(
      (i) =>
        i.instrumentId.toUpperCase() === proposal.instrumentId.toUpperCase() ||
        i.symbol.toUpperCase() === proposal.instrumentId.toUpperCase(),
    );
    const transferInstrument =
      analysisSymbolMap[proposal.proposalId] ??
      (analysis ? analysisSymbolMap[analysis.id] : undefined) ??
      UUID_TO_SYMBOL[proposal.instrumentId.toLowerCase()] ??
      (analysis ? UUID_TO_SYMBOL[analysis.instrumentId.toLowerCase()] : undefined) ??
      proposalMatchedMeta?.symbol ??
      proposal.instrumentId;
    const normalizedLimit = toDecimalInputString(proposal.intent.limitPrice, 8);
    const seed: AiProposalSeed = {
      proposalId: proposal.proposalId,
      revision: proposal.revision,
      symbol: transferInstrument,
      side: proposal.intent.side,
      orderType: proposal.intent.orderType,
      limitPrice: normalizedLimit ? normalizedLimit : null,
      stopLoss: toDecimalInputString(proposal.intent.stopLoss, 8),
      takeProfit: toDecimalInputString(proposal.intent.takeProfit, 8),
      suggestedLeverage: toDecimalInputString(proposal.intent.suggestedLeverage, 2) || '2',
      referenceEntry: toDecimalInputString(proposal.intent.referencePrice, 8) || null,
      horizonSeconds: proposal.intent.horizonSeconds,
      expiryPolicy: proposal.intent.expiryPolicy,
    };
    navigate(
      `/trade/${encodeURIComponent(sessionId)}?instrumentId=${encodeURIComponent(
        transferInstrument,
      )}`,
      {
        state: { aiProposalSeed: seed },
      },
    );
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-none border border-[#e5e5e5] bg-white px-4 py-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-lg font-bold tracking-tight text-[#0a0a0a]">
              AI Strateji Analizi
            </h1>
            {sessionQuery.data?.name && (
              <Badge tone="accent">{sessionQuery.data.name}</Badge>
            )}
            {sessionQuery.data && (
              <Badge tone="paper">
                {sessionQuery.data.status === 'RUNNING' ? 'Aktif' : sessionQuery.data.status}
              </Badge>
            )}
          </div>
          <p className="mt-0.5 text-xs text-[#737373]">
            Seçilen sözleşme ve zaman dilimi için yapay zeka destekli yön, risk ve hedef fiyat
            analizi üretir.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        {/* Sol Form: Analiz Başlatma */}
        <div className="space-y-4 lg:col-span-4">
          <Card>
            <CardHeader
              title="Analiz Parametreleri"
              subtitle="İncelemek istediğiniz vadeli işlem sözleşmesini ve zaman ufkunu seçin."
            />
            <form
              onSubmit={(e) => {
                e.preventDefault();
                startAnalysisMutation.mutate();
              }}
              className="space-y-4 p-4"
            >
              <FieldGroup
                label="Enstrüman (Kontrat)"
                rightAddon={
                  instruments.length > 0
                    ? `${filteredInstruments.length} / ${instruments.length}`
                    : undefined
                }
              >
                {(id) => (
                  <div className="space-y-2">
                    <div className="relative">
                      <Search
                        className="pointer-events-none absolute left-2.5 top-2.5 h-3.5 w-3.5 text-[#737373]"
                        aria-hidden="true"
                      />
                      <TextInput
                        value={instrumentSearch}
                        onChange={(e) => setInstrumentSearch(e.target.value)}
                        placeholder="Sembol ara (örn. BTC, ETH, SOL, PEPE)..."
                        aria-label="Enstrüman ara"
                        className="pl-8 pr-8 font-mono text-xs"
                      />
                      {instrumentSearch && (
                        <button
                          type="button"
                          onClick={() => setInstrumentSearch('')}
                          aria-label="Aramayı temizle"
                          className="absolute right-2 top-2 p-0.5 text-[#737373] hover:text-[#0a0a0a]"
                        >
                          <X className="h-3.5 w-3.5" aria-hidden="true" />
                        </button>
                      )}
                    </div>

                    <SelectInput
                      id={id}
                      value={instrumentId}
                      onChange={(e) => setInstrumentId(e.target.value)}
                      disabled={instruments.length > 0 && filteredInstruments.length === 0}
                    >
                      {instruments.length === 0 ? (
                        <option value={DEFAULT_BTC_INSTRUMENT_ID}>
                          BTCUSDT (BINANCE_USD_M · PERPETUAL)
                        </option>
                      ) : filteredInstruments.length === 0 ? (
                        <option value="">Eşleşen kontrat bulunamadı</option>
                      ) : (
                        filteredInstruments.map((item) => (
                          <option key={item.instrumentId} value={item.instrumentId}>
                            {item.symbol} ({item.exchangeId} · {item.contractType})
                            {!item.capabilities.analysisReady ? ' [Analiz Kapalı]' : ''}
                          </option>
                        ))
                      )}
                    </SelectInput>
                  </div>
                )}
              </FieldGroup>

              {selectedInstrumentMeta && !selectedInstrumentMeta.capabilities.analysisReady && (
                <div
                  role="alert"
                  className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-3 text-xs text-[#0a0a0a]"
                >
                  <strong>Enstrüman Analize Hazır Değil:</strong>{' '}
                  {selectedInstrumentMeta.capabilities.reasonCodes.join(', ') || 'Yetersiz veri'}
                </div>
              )}

              <FieldGroup label="Tahmin Ufku (Zaman Dilimi)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={String(horizonSeconds)}
                    onChange={(e) =>
                      setHorizonSeconds(Number(e.target.value) as HorizonSeconds)
                    }
                  >
                    <option value="60">1 Dakika</option>
                    <option value="900">15 Dakika</option>
                    <option value="3600">1 Saat</option>
                    <option value="86400">1 Gün</option>
                    <option value="604800">1 Hafta</option>
                  </SelectInput>
                )}
              </FieldGroup>

              <div>
                <div className="mb-1.5 flex items-center justify-between gap-2 text-xs">
                  <span className="font-semibold text-[#0a0a0a]">
                    Risk &amp; Kaldıraç Optimizasyonu
                  </span>
                  <span className="font-mono text-[11px] text-[#737373]">
                    {sessionMaxLevBadge}
                  </span>
                </div>
                <div
                  role="group"
                  aria-label="Risk Seviyesi"
                  className="grid grid-cols-3 gap-1.5 rounded-none border border-[#e5e5e5] bg-[#fafafa] p-1"
                >
                  {(
                    [
                      {
                        id: 'LOW',
                        label: 'Düşük',
                        sub: 'Koruma',
                        activeCls:
                          'border-emerald-600 bg-emerald-50 text-emerald-800 shadow-xs',
                      },
                      {
                        id: 'MEDIUM',
                        label: 'Orta',
                        sub: 'Dengeli',
                        activeCls:
                          'border-amber-600 bg-amber-50 text-amber-800 shadow-xs',
                      },
                      {
                        id: 'HIGH',
                        label: 'Yüksek',
                        sub: 'Maks. Kâr',
                        activeCls:
                          'border-rose-600 bg-rose-50 text-rose-800 shadow-xs',
                      },
                    ] as const
                  ).map((opt) => {
                    const selected = riskLevel === opt.id;
                    return (
                      <button
                        key={opt.id}
                        type="button"
                        aria-pressed={selected}
                        onClick={() => {
                          setRiskOverridden(true);
                          setRiskLevel(opt.id);
                        }}
                        className={`flex flex-col items-center justify-center rounded-none border px-2 py-1.5 text-center transition ${
                          selected
                            ? opt.activeCls
                            : 'border-transparent bg-transparent text-[#525252] hover:bg-white hover:text-[#0a0a0a]'
                        }`}
                      >
                        <span className="text-xs font-semibold leading-tight">
                          {opt.label}
                        </span>
                        <span className="text-[10px] opacity-80">{opt.sub}</span>
                      </button>
                    );
                  })}
                </div>
                <p className="mt-1 text-[11px] text-[#737373]">
                  Seçilen varlık volatilitesi (ATR) ve oturum sınırına göre likidasyon öncesi optimal kaldıraç önerilir.
                </p>
              </div>

              {startAnalysisMutation.isError && (
                <ErrorBanner
                  error={startAnalysisMutation.error}
                  title="Analiz Başlatılamadı"
                />
              )}

              <Button
                type="submit"
                variant="primary"
                className="w-full"
                loading={startAnalysisMutation.isPending}
                disabled={
                  (instruments.length > 0 && filteredInstruments.length === 0) ||
                  (selectedInstrumentMeta
                    ? !selectedInstrumentMeta.capabilities.analysisReady
                    : false)
                }
              >
                AI Analizi Başlat
              </Button>
            </form>
          </Card>
        </div>

        {/* Sağ Panel: Analiz Durumu & Sonuç */}
        <div className="space-y-4 lg:col-span-8">
          {!activeAnalysisId ? (
            <Card className="p-12 text-center">
              <h2 className="text-base font-semibold text-[#0a0a0a]">
                Analiz Sonucu Burada Görünecek
              </h2>
              <p className="mt-1 text-xs text-[#737373]">
                Soldaki panelden sözleşme ve zaman dilimi seçip &ldquo;AI Analizi Başlat&rdquo;
                butonuna tıklayın.
              </p>
            </Card>
          ) : analysisQuery.isLoading ? (
            <Card className="p-12 text-center text-sm text-[#737373]">
              Analiz durumu sorgulanıyor...
            </Card>
          ) : analysisQuery.isError ? (
            <ErrorBanner
              error={analysisQuery.error}
              onRetry={() => void analysisQuery.refetch()}
            />
          ) : analysis ? (
            <div className="space-y-4">
              <Card className="p-4">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#e5e5e5] pb-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge
                      tone={
                        analysis.status === 'SUCCEEDED'
                          ? 'success'
                          : analysis.status === 'FAILED'
                            ? 'danger'
                            : 'accent'
                      }
                    >
                      {analysis.status}
                    </Badge>
                    <Badge tone="neutral">
                      {analysisSymbolMap[analysis.id] ??
                        instruments.find((i) => i.instrumentId === analysis.instrumentId)?.symbol ??
                        UUID_TO_SYMBOL[analysis.instrumentId] ??
                        selectedInstrumentMeta?.symbol ??
                        analysis.instrumentId}
                    </Badge>
                    <Badge tone="neutral">{formatHorizon(analysis.horizonSeconds)}</Badge>
                  </div>
                  <div className="text-xs text-[#737373]">
                    Veri Zamanı:{' '}
                    <span className="font-mono text-[#0a0a0a]">
                      {formatTimestamp(analysis.asOf, timezone)}
                    </span>
                  </div>
                </div>

                {/* QUEUED / WAITING_DATA / RUNNING */}
                {(analysis.status === 'QUEUED' ||
                  analysis.status === 'WAITING_DATA' ||
                  analysis.status === 'RUNNING') && (
                  <div className="mt-4 rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4">
                    <div className="text-sm font-semibold text-[#0a0a0a]">
                      Analiz İşleniyor ({analysis.status})
                    </div>
                    <p className="mt-0.5 text-xs text-[#525252]">
                      Piyasa anlık görüntüsü, maliyet modeli ve aktif model paketi
                      değerlendiriliyor...
                    </p>
                  </div>
                )}

                {/* FAILED / CANCELLED / EXPIRED */}
                {(analysis.status === 'FAILED' ||
                  analysis.status === 'CANCELLED' ||
                  analysis.status === 'EXPIRED') && (
                  <div
                    role="alert"
                    className="mt-4 rounded-none border border-[#dc2626] bg-red-50 p-4 text-xs text-[#dc2626]"
                  >
                    <div className="font-semibold text-[#dc2626]">
                      {analysis.status === 'FAILED'
                        ? 'Teknik Analiz Hatası (FAILED) — Bu bir ekonomik WAIT kararı değildir'
                        : 'Analiz Tamamlanamadı'}
                    </div>
                    <p className="mt-1 text-[#0a0a0a]">
                      {toUserFacingErrorMessage(
                        analysis.error,
                        'Analiz işlemi geçici bir aksaklık nedeniyle tamamlanamadı. Lütfen birkaç saniye sonra tekrar deneyin.',
                      )}
                    </p>
                  </div>
                )}

                {/* SUCCEEDED + WAIT */}
                {analysis.status === 'SUCCEEDED' && analysis.action === 'WAIT' && (
                  <div className="mt-4 space-y-4">
                    <div
                      data-testid="analysis-wait-card"
                      className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <Badge tone="wait">KARAR: WAIT (EKONOMİK ÇEKİMSERLİK)</Badge>
                          <span className="text-xs font-medium text-[#0a0a0a]">
                            İşlem Açılmaması Öneriliyor
                          </span>
                        </div>
                      </div>
                      <p className="mt-2 text-xs text-[#525252]">
                        {analysis.explanation
                          ? analysis.explanation
                          : 'Karar motoru maliyet, likidite veya belirsizlik kısıtları nedeniyle seçili risk seviyesinde pozisyon açmamanın daha güvenli olduğunu belirledi.'}
                      </p>
                      {riskLevel !== 'HIGH' && (
                        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-[#e5e5e5] pt-2.5">
                          <span className="text-[11px] text-[#525252]">
                            Daha aktif fırsat ve işlem önerisi görmek için yüksek risk modunu kullanabilirsiniz.
                          </span>
                          <button
                            type="button"
                            onClick={() => {
                              setRiskOverridden(true);
                              setRiskLevel('HIGH');
                            }}
                            className="border border-[#0a0a0a] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#0a0a0a] hover:bg-[#0a0a0a] hover:text-white transition-colors"
                          >
                            Yüksek (Maks. Kâr) Moduna Geç
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {/* SUCCEEDED + LONG / SHORT */}
                {analysis.status === 'SUCCEEDED' &&
                  (analysis.action === 'LONG' || analysis.action === 'SHORT') && (
                    <div className="mt-4 space-y-4">
                      <div
                        data-testid="analysis-directional-card"
                        className={`rounded-none border p-4 ${
                          analysis.action === 'LONG'
                            ? 'border-[#0a0a0a] bg-[#f5f5f5]'
                            : 'border-[#dc2626] bg-red-50'
                        }`}
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex items-center gap-2">
                            <SideBadge side={analysis.action} />
                          </div>
                          <span className="text-xs text-[#525252]">
                            AI Kantitatif Piyasa Önerisi
                          </span>
                        </div>
                      </div>

                      {proposal && (
                        <Card className="border-[#0a0a0a] bg-white">
                          <CardHeader
                            title="AI Emir Önerisi"
                            subtitle="Bu öneriyi emir formuna aktararak sunucu yürütme planını inceleyip onaylayabilirsiniz."
                            action={
                              <Button
                                variant="primary"
                                size="sm"
                                onClick={handleTransferProposalToTicket}
                                data-testid="transfer-proposal-btn"
                              >
                                Bu Öneriyi Emir Formuna Aktar
                              </Button>
                            }
                          />
                          <div className="grid grid-cols-2 gap-3 p-4 text-xs sm:grid-cols-4">
                            <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-2.5">
                              <div className="text-[#737373]">Önerilen Yön / Tip</div>
                              <div className="mt-1 font-mono font-semibold text-[#0a0a0a]">
                                {proposal.intent.side} · {proposal.intent.orderType}
                              </div>
                            </div>
                            <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-2.5">
                              <div className="text-[#737373]">Önerilen Kaldıraç</div>
                              <div className="mt-1 font-mono font-semibold text-[#0a0a0a]">
                                {proposal.intent.suggestedLeverage}x
                              </div>
                            </div>
                            <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-2.5">
                              <div className="text-[#737373]">Zarar Durdur / Kâr Al</div>
                              <div className="mt-1 font-mono text-[#0a0a0a]">
                                SL: {formatPrice(proposal.intent.stopLoss)} | TP:{' '}
                                {formatPrice(proposal.intent.takeProfit)}
                              </div>
                            </div>
                            <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-2.5">
                              <div className="text-[#737373]">Referans Fiyat / ATR</div>
                              <div className="mt-1 font-mono font-semibold text-[#0a0a0a]">
                                {formatPrice(proposal.intent.referencePrice)} (ATR:{' '}
                                {formatPrice(proposal.intent.verifiedAtr)})
                              </div>
                            </div>
                          </div>
                        </Card>
                      )}
                    </div>
                  )}

                {/* Açıklama Katmanı */}
                {analysis.status === 'SUCCEEDED' &&
                  (analysis.action !== 'WAIT' ||
                    analysis.explanationStatus === 'TEMPLATE' ||
                    analysis.explanationStatus === 'READY') && (
                    <div className="mt-4 rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-4">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-xs font-semibold uppercase tracking-wider text-[#0a0a0a]">
                          AI Değerlendirme Özeti
                        </span>
                        <ExplanationStatusBadge status={analysis.explanationStatus} />
                      </div>
                      <p className="mt-2 text-xs leading-relaxed text-[#525252]">
                        {analysis.explanation ||
                          (analysis.explanationStatus === 'TEMPLATE'
                            ? `Analiz Özeti: Seçilen sözleşme (${formatHorizon(
                                analysis.horizonSeconds,
                              )}) için piyasa koşulları ve maliyet dengesi incelenerek ${
                                analysis.action
                              } kararı oluşturuldu.`
                            : 'Bu analiz için ek değerlendirme metni hazırlanıyor.')}
                      </p>
                    </div>
                  )}
              </Card>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

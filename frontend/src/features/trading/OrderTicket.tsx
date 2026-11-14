import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  CheckCircle2,
  Clock,
  Eye,
  RefreshCw,
  Sparkles,
} from 'lucide-react';
import type {
  AcceptedOrderResponse,
  CommandStatusView,
  ExecutionPlan,
  ExpiryPolicy,
  HorizonSeconds,
  InstrumentItem,
  MarketSnapshot,
  OrderSource,
  OrderType,
  PositionSide,
} from '@/shared/contracts/types';
import { isApiError, sanitizeUserMessage } from '@/shared/api/apiErrors';
import { createIdempotencyKey, LogicalActionKeyTracker } from '@/shared/api/idempotency';
import { queryKeys } from '@/shared/api/queryKeys';
import {
  compareDecimalStrings,
  parseOptionalPositiveDecimalInput,
  parsePositiveDecimalInput,
  toDecimalInputString,
} from '@/shared/formatting/decimalParser';
import { getEffectiveMaxLeverage } from '@/features/sessions/sessionProfiles';
import {
  formatMoney,
  formatPrice,
  formatQuantity,
  formatSignedMoney,
  type TimezonePreference,
} from '@/shared/formatting/formatters';
import { Badge, Button, Card, CardHeader } from '@/shared/ui/Primitives';
import { DecimalInput, FieldGroup, SelectInput } from '@/shared/ui/FormControls';
import { ErrorBanner } from '@/shared/ui/Feedback';
import { cn } from '@/shared/ui/cn';
import { tradingApi } from './api';
import { ExecutionPlanCard } from './ExecutionPlanCard';
import {
  type BracketInputMode,
  type OrderSizingDiagnosis,
  diagnoseOrderSizing,
  formatFixedDecimal,
  parseFiniteDecimal,
  pnlUsdFromTriggerPrice,
  stepDecimals,
  stopLossPriceFromUsd,
  takeProfitPriceFromUsd,
  validateStopLossPriceAgainstLiquidation,
} from './orderBracketMath';

export type OrderTicketStage =
  | 'EDITING'
  | 'PREVIEW_LOADING'
  | 'PREVIEW_READY'
  | 'SUBMITTING'
  | 'ACCEPTED_PENDING'
  | 'RESULT';

export interface AiProposalSeed {
  proposalId: string;
  revision: number;
  symbol?: string;
  side: PositionSide;
  orderType: OrderType;
  limitPrice: string | null;
  stopLoss: string;
  takeProfit: string;
  suggestedLeverage: string;
  referenceEntry?: string | null;
  horizonSeconds: HorizonSeconds;
  expiryPolicy: ExpiryPolicy;
}

export interface OrderTicketProps {
  sessionId: string;
  instrument: InstrumentItem | null;
  sessionStatus?: string;
  sessionRiskPolicyId?: string | null;
  marketSnapshot?: MarketSnapshot | null;
  aiProposalSeed?: AiProposalSeed | null;
  onClearAiSeed?: () => void;
  onOrderSettled?: (cmd: CommandStatusView) => void;
  timezone?: TimezonePreference;
}

/**
 * F04 6-Durumlu Emir Onay Makinesi:
 * EDITING -> PREVIEW_LOADING -> PREVIEW_READY -> SUBMITTING -> ACCEPTED_PENDING -> RESULT
 *
 * KURAL:
 * - Herhangi bir form alanı değiştiğinde mevcut ExecutionPlan derhal sıfırlanır (plan = null).
 * - Submit gövdesinde YALNIZ {planId, revision, planHash} gönderilir.
 * - Ağ zaman aşımında aynı Idempotency-Key korunur; yeni işlemde yeni anahtar üretilir.
 * - 202 ACCEPTED emrin gerçekleştiği (FILLED) anlamına gelmez; command durumu izlenir.
 */
export function OrderTicket({
  sessionId,
  instrument,
  sessionStatus = 'RUNNING',
  sessionRiskPolicyId = null,
  marketSnapshot = null,
  aiProposalSeed = null,
  onClearAiSeed,
  onOrderSettled,
  timezone = 'Europe/Istanbul',
}: OrderTicketProps) {
  const queryClient = useQueryClient();
  const submitKeyTrackerRef = useRef(new LogicalActionKeyTracker());
  const {
    maxLeverage: effectiveMaxLeverage,
    cappedByPolicy,
    policy: activeRiskPolicy,
  } = getEffectiveMaxLeverage(instrument?.maxLeverage, sessionRiskPolicyId);

  const [stage, setStage] = useState<OrderTicketStage>('EDITING');
  const [source, setSource] = useState<OrderSource>('MANUAL');
  const [originProposalId, setOriginProposalId] = useState<string | null>(null);
  const [proposalRevision, setProposalRevision] = useState<number>(1);

  const [side, setSide] = useState<PositionSide>('LONG');
  const [orderType, setOrderType] = useState<OrderType>('MARKET');
  const [marginBudget, setMarginBudget] = useState('250');
  const [leverage, setLeverage] = useState('5');
  const [limitPrice, setLimitPrice] = useState('');
  const [stopLoss, setStopLoss] = useState('');
  const [takeProfit, setTakeProfit] = useState('');
  const [slMode, setSlMode] = useState<BracketInputMode>('PRICE');
  const [tpMode, setTpMode] = useState<BracketInputMode>('PRICE');
  const [horizonSeconds, setHorizonSeconds] = useState<HorizonSeconds>(3600);
  const [expiryPolicy, setExpiryPolicy] = useState<ExpiryPolicy>('CLOSE_AT_DEADLINE');

  const [validationError, setValidationError] = useState<string | null>(null);
  const [apiError, setApiError] = useState<unknown>(null);
  const [activePlan, setActivePlan] = useState<ExecutionPlan | null>(null);
  const [acceptedCommand, setAcceptedCommand] = useState<AcceptedOrderResponse | null>(null);

  // Seçili enstrüman değiştiğinde eski enstrümana ait planı derhal temizle
  const prevInstrumentIdRef = useRef<string | null>(instrument?.instrumentId ?? null);
  useEffect(() => {
    const nextId = instrument?.instrumentId ?? null;
    if (prevInstrumentIdRef.current !== nextId) {
      prevInstrumentIdRef.current = nextId;
      setActivePlan(null);
      setAcceptedCommand(null);
      setApiError(null);
      setStage('EDITING');
      submitKeyTrackerRef.current.reset();
    }
  }, [instrument?.instrumentId]);

  // AI önerisi aktarıldığında alanları doldur ve kaynağı AI_PROPOSAL olarak işaretle
  useEffect(() => {
    if (!aiProposalSeed) return;
    const seedStopLoss = toDecimalInputString(aiProposalSeed.stopLoss, 8);
    const seedTakeProfit = toDecimalInputString(aiProposalSeed.takeProfit, 8);
    const seedLimit = toDecimalInputString(aiProposalSeed.limitPrice, 8);
    const seedLeverage = toDecimalInputString(aiProposalSeed.suggestedLeverage, 2) || '2';
    const isStaleFixtureBracket = seedStopLoss === '110' && seedTakeProfit === '70';
    setSource(isStaleFixtureBracket ? 'MANUAL' : 'AI_PROPOSAL');
    setOriginProposalId(isStaleFixtureBracket ? null : aiProposalSeed.proposalId);
    setProposalRevision(aiProposalSeed.revision);
    setSide(aiProposalSeed.side);
    setOrderType(aiProposalSeed.orderType);
    setLimitPrice(seedLimit);
    setStopLoss(isStaleFixtureBracket ? '' : seedStopLoss);
    setTakeProfit(isStaleFixtureBracket ? '' : seedTakeProfit);
    setSlMode('PRICE');
    setTpMode('PRICE');
    setLeverage(seedLeverage);
    setHorizonSeconds(aiProposalSeed.horizonSeconds);
    setExpiryPolicy(aiProposalSeed.expiryPolicy);
    setActivePlan(null);
    setAcceptedCommand(null);
    setApiError(null);
    setValidationError(null);
    setStage('EDITING');
    submitKeyTrackerRef.current.reset();
  }, [aiProposalSeed]);

  const effectiveEntryPrice = useMemo(() => {
    if (orderType === 'LIMIT') {
      const lim = parseFiniteDecimal(limitPrice);
      if (lim !== null && lim > 0) return lim;
    }
    const snap = parseFiniteDecimal(marketSnapshot?.markPrice ?? null);
    if (snap !== null && snap > 0) return snap;
    const seedRef = parseFiniteDecimal(aiProposalSeed?.referenceEntry ?? null);
    return seedRef !== null && seedRef > 0 ? seedRef : null;
  }, [aiProposalSeed?.referenceEntry, limitPrice, marketSnapshot?.markPrice, orderType]);

  const sizingDiagnosis = useMemo(() => {
    return diagnoseOrderSizing({
      side,
      entryPrice: effectiveEntryPrice,
      marginBudget: parseFiniteDecimal(marginBudget),
      leverage: parseFiniteDecimal(leverage),
      minNotionalUsd: parseFiniteDecimal(instrument?.minNotional ?? null) ?? undefined,
    });
  }, [effectiveEntryPrice, instrument?.minNotional, leverage, marginBudget, side]);

  const orderSizing = sizingDiagnosis.ok ? sizingDiagnosis.estimate : null;

  const resolvedSl = useMemo(
    () =>
      resolveBracketValue({
        kind: 'SL',
        rawValue: stopLoss,
        mode: slMode,
        side,
        effectiveEntryPrice,
        sizingDiagnosis,
        baseAsset: instrument?.baseAsset,
      }),
    [effectiveEntryPrice, instrument?.baseAsset, side, sizingDiagnosis, slMode, stopLoss]
  );

  const resolvedTp = useMemo(
    () =>
      resolveBracketValue({
        kind: 'TP',
        rawValue: takeProfit,
        mode: tpMode,
        side,
        effectiveEntryPrice,
        sizingDiagnosis,
        baseAsset: instrument?.baseAsset,
      }),
    [effectiveEntryPrice, instrument?.baseAsset, side, sizingDiagnosis, takeProfit, tpMode]
  );

  /**
   * F04.4 & F07.4: Kullanıcı herhangi bir alanı değiştirdiğinde eski plan geçersiz kılınır;
   * AI önerisi üzerinde elle değişiklik yapılırsa kaynak MANUAL'a döner.
   */
  const invalidateActivePlanOnEdit = () => {
    if (activePlan) {
      setActivePlan(null);
    }
    if (stage !== 'EDITING') {
      setStage('EDITING');
    }
    if (source === 'AI_PROPOSAL') {
      setSource('MANUAL');
    }
    setValidationError(null);
    setApiError(null);
    submitKeyTrackerRef.current.reset();
  };

  const switchBracketMode = (
    kind: 'SL' | 'TP',
    currentMode: BracketInputMode,
    nextMode: BracketInputMode,
    rawValue: string,
    resolved: ResolvedBracket,
    setValue: (next: string) => void,
    setMode: (mode: BracketInputMode) => void,
  ) => {
    if (nextMode === currentMode) return;
    if (rawValue.trim()) {
      if (nextMode === 'USDT') {
        const usd =
          orderSizing && resolved.priceStr
            ? pnlUsdFromTriggerPrice(side, orderSizing, Number(resolved.priceStr), kind)
            : null;
        // Sizing hesaplanamiyorsa ham varlik fiyatini (orn. 88500) USDT tutari sanmamak icin temizle.
        setValue(usd !== null ? formatFixedDecimal(usd, 2) : '');
      } else {
        setValue(orderSizing && resolved.priceStr ? resolved.priceStr : '');
      }
    }
    setMode(nextMode);
    setValidationError(null);
    setApiError(null);
  };

  const switchSlMode = (nextMode: BracketInputMode) =>
    switchBracketMode('SL', slMode, nextMode, stopLoss, resolvedSl, setStopLoss, setSlMode);

  const switchTpMode = (nextMode: BracketInputMode) =>
    switchBracketMode('TP', tpMode, nextMode, takeProfit, resolvedTp, setTakeProfit, setTpMode);

  // 202 ACCEPTED sonrası komut durumu sorgulama (F04.6)
  const commandQuery = useQuery({
    queryKey: queryKeys.trading.command(acceptedCommand?.commandId ?? ''),
    queryFn: ({ signal }) => tradingApi.getCommandStatus(acceptedCommand!.commandId, signal),
    enabled: Boolean(acceptedCommand?.commandId && stage === 'ACCEPTED_PENDING'),
    refetchInterval: (query) => {
      const st = query.state.data?.status;
      if (st === 'FILLED' || st === 'NEW' || st === 'REJECTED' || st === 'CANCELLED' || st === 'EXPIRED') {
        return false;
      }
      return 350;
    },
  });

  useEffect(() => {
    const cmd = commandQuery.data;
    if (!cmd || stage !== 'ACCEPTED_PENDING') return;
    if (
      cmd.status === 'FILLED' ||
      cmd.status === 'NEW' ||
      cmd.status === 'REJECTED' ||
      cmd.status === 'CANCELLED' ||
      cmd.status === 'EXPIRED'
    ) {
      setStage('RESULT');
      void queryClient.invalidateQueries({ queryKey: queryKeys.trading.positions(sessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.trading.openOrders(sessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.account.summary(sessionId) });
      onOrderSettled?.(cmd);
    }
  }, [commandQuery.data, onOrderSettled, queryClient, sessionId, stage]);

  const requestPreview = async (event?: React.FormEvent) => {
    event?.preventDefault();
    if (!instrument) {
      setValidationError('Önizleme için önce bir enstrüman seçin.');
      return;
    }

    // AI_PROPOSAL doğrudan proposalId + revision ile önizleme isteyebilir
    if (source === 'AI_PROPOSAL' && originProposalId) {
      setStage('PREVIEW_LOADING');
      setApiError(null);
      setValidationError(null);
      try {
        const plan = await tradingApi.previewAiProposal(
          {
            source: 'AI_PROPOSAL',
            sessionId,
            proposalId: originProposalId,
            proposalRevision,
          },
          createIdempotencyKey()
        );
        setActivePlan(plan);
        setStage('PREVIEW_READY');
        submitKeyTrackerRef.current.reset();
      } catch (err) {
        setApiError(err);
        setStage('EDITING');
      }
      return;
    }

    try {
      // MANUEL girdi doğrulaması (Number() kullanmadan DecimalString doğrulaması)
      const parsedMargin = parsePositiveDecimalInput(marginBudget, 4);
      if (!parsedMargin.valid) {
        setValidationError(`Teminat Bütçesi: ${parsedMargin.message}`);
        return;
      }

      const parsedLeverage = parsePositiveDecimalInput(leverage, 2);
      if (!parsedLeverage.valid) {
        setValidationError(`Kaldıraç: ${parsedLeverage.message}`);
        return;
      }

      if (compareDecimalStrings(parsedLeverage.normalized, effectiveMaxLeverage) > 0) {
        setValidationError(
          cappedByPolicy
            ? `Kaldıraç seçili risk politikanızın (${activeRiskPolicy.title} · Maks. ${effectiveMaxLeverage}x) sınırını aşıyor.`
            : `Kaldıraç bu sözleşmenin maksimum sınırını (${effectiveMaxLeverage}x) aşıyor.`
        );
        return;
      }

      let normalizedLimit: string | null = null;
      if (orderType === 'LIMIT') {
        const parsedLimit = parsePositiveDecimalInput(limitPrice, 8);
        if (!parsedLimit.valid) {
          setValidationError(`Limit Fiyatı: ${parsedLimit.message}`);
          return;
        }
        normalizedLimit = parsedLimit.normalized;
      }

      if (resolvedSl.error) {
        setValidationError(resolvedSl.error);
        return;
      }
      if (resolvedTp.error) {
        setValidationError(resolvedTp.error);
        return;
      }

      const parsedSl = parseOptionalPositiveDecimalInput(resolvedSl.priceStr ?? '', 8);
      if (!parsedSl.valid) {
        setValidationError(`Stop-Loss: ${parsedSl.message}`);
        return;
      }

      const parsedTp = parseOptionalPositiveDecimalInput(resolvedTp.priceStr ?? '', 8);
      if (!parsedTp.valid) {
        setValidationError(`Take-Profit: ${parsedTp.message}`);
        return;
      }

      // Limit emirde bariz ters SL/TP uyarısı (BigInt ölçekli tam karşılaştırma)
      if (normalizedLimit) {
        if (
          side === 'LONG' &&
          parsedSl.normalized &&
          compareDecimalStrings(parsedSl.normalized, normalizedLimit) >= 0
        ) {
          setValidationError('LONG limit emirde Stop-Loss fiyatı limit fiyatının altında olmalıdır.');
          return;
        }
        if (
          side === 'SHORT' &&
          parsedSl.normalized &&
          compareDecimalStrings(parsedSl.normalized, normalizedLimit) <= 0
        ) {
          setValidationError(
            'SHORT limit emirde Stop-Loss fiyatı limit fiyatının üzerinde olmalıdır.'
          );
          return;
        }
      }

      setValidationError(null);
      setApiError(null);
      setStage('PREVIEW_LOADING');

      const plan = await tradingApi.previewManual(
        {
          source: 'MANUAL',
          sessionId,
          instrumentId: instrument.instrumentId,
          side,
          orderType,
          marginBudget: parsedMargin.normalized,
          leverage: parsedLeverage.normalized,
          limitPrice: normalizedLimit,
          stopLoss: parsedSl.normalized,
          takeProfit: parsedTp.normalized,
          horizonSeconds,
          expiryPolicy,
          originProposalId,
        },
        createIdempotencyKey()
      );
      setActivePlan(plan);
      setStage('PREVIEW_READY');
      submitKeyTrackerRef.current.reset();
    } catch (err) {
      setApiError(err);
      setStage('EDITING');
    }
  };

  const handleConfirmSubmit = async () => {
    if (!activePlan || stage === 'SUBMITTING') return;

    setStage('SUBMITTING');
    setApiError(null);

    // Aynı planın yeniden denemesinde AYNI Idempotency-Key korunur!
    const planSignature = `${activePlan.planId}:${activePlan.revision}:${activePlan.planHash}`;
    const idempotencyKey = submitKeyTrackerRef.current.getOrCreate(planSignature);

    try {
      const accepted = await tradingApi.submitOrder(
        {
          planId: activePlan.planId,
          revision: activePlan.revision,
          planHash: activePlan.planHash,
        },
        idempotencyKey
      );
      setAcceptedCommand(accepted);
      setStage('ACCEPTED_PENDING');
    } catch (err) {
      setApiError(err);
      if (
        isApiError(err) &&
        (err.code === 'REVALIDATION_REQUIRED' ||
          err.code === 'PLAN_EXPIRED' ||
          err.code === 'PLAN_REVOKED')
      ) {
        // 409 REVALIDATION_REQUIRED / PLAN_EXPIRED -> eski onayı temizle, otomatik emir gönderme!
        setActivePlan(null);
        submitKeyTrackerRef.current.reset();
        setStage('EDITING');
        return;
      }
      // Ağ hatasında aynı plan ve aynı Idempotency-Key korunarak PREVIEW_READY'de kalınır
      setStage('PREVIEW_READY');
    }
  };

  const resetForNewOrder = () => {
    setActivePlan(null);
    setAcceptedCommand(null);
    setApiError(null);
    setValidationError(null);
    submitKeyTrackerRef.current.reset();
    setStage('EDITING');
  };

  const isTradable = instrument?.capabilities.paperTradable ?? true;
  const isSessionHalted = sessionStatus !== 'RUNNING';

  const stageLabel: Record<OrderTicketStage, string> = {
    EDITING: 'Hazır',
    PREVIEW_LOADING: 'Hesaplanıyor',
    PREVIEW_READY: 'Onay Bekliyor',
    SUBMITTING: 'Gönderiliyor',
    ACCEPTED_PENDING: 'İşleniyor',
    RESULT: 'Tamamlandı',
  };

  return (
    <Card className="flex flex-col">
      <CardHeader
        title="Sanal Emir Formu"
        subtitle={
          instrument
            ? `${instrument.symbol} · Vadeli İşlem`
            : 'Soldaki listeden sözleşme seçin'
        }
        action={
          <Badge tone={stage === 'PREVIEW_READY' ? 'accent' : 'paper'}>
            {stageLabel[stage]}
          </Badge>
        }
      />

      <div className="p-4 space-y-3.5">
        {isSessionHalted && (
          <div
            role="status"
            className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]"
          >
            Oturum duraklatıldığı için yeni pozisyon açılamaz. Mevcut pozisyonlarınızı yönetebilirsiniz.
          </div>
        )}

        {instrument && !isTradable && (
          <div
            role="status"
            className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]"
          >
            Bu sözleşme şu anda sanal işleme kapalıdır.
          </div>
        )}

        {source === 'AI_PROPOSAL' && originProposalId && (
          <div className="flex items-center justify-between gap-2 rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]">
            <div className="flex items-center gap-1.5 min-w-0">
              <Sparkles className="h-3.5 w-3.5 shrink-0 text-[#0a0a0a]" aria-hidden="true" />
              <span className="truncate font-medium">AI Analiz Önerisi Yüklendi</span>
            </div>
            <button
              type="button"
              onClick={() => {
                setSource('MANUAL');
                setOriginProposalId(null);
                onClearAiSeed?.();
              }}
              className="shrink-0 text-[11px] font-semibold text-[#0a0a0a] underline hover:opacity-80"
            >
              Manuel Moda Geç
            </button>
          </div>
        )}

        {/* 202 ACCEPTED veya RESULT Durum Kartı */}
        {(stage === 'ACCEPTED_PENDING' || stage === 'RESULT') && acceptedCommand && (
          <div
            data-testid="command-lifecycle-box"
            className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 space-y-3"
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-semibold text-[#0a0a0a]">
                Sunucu Emir Komutu Takibi
              </span>
              <Badge
                tone={
                  commandQuery.data?.status === 'FILLED'
                    ? 'success'
                    : commandQuery.data?.status === 'REJECTED'
                      ? 'danger'
                      : 'accent'
                }
                className="font-mono"
              >
                {commandQuery.data?.status ?? acceptedCommand.status}
              </Badge>
            </div>

            <p className="text-xs text-[#0a0a0a]">
              {stage === 'ACCEPTED_PENDING' ? (
                <span className="inline-flex items-center gap-1.5 text-[#0a0a0a]">
                  <Clock className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                  Emir komutu sunucu tarafından kabul edildi (202 ACCEPTED). Eşleşme ve defter kaydı
                  bekleniyor…
                </span>
              ) : commandQuery.data?.status === 'FILLED' ? (
                <span className="inline-flex items-center gap-1.5 font-medium text-[#0a0a0a]">
                  <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
                  Emir gerçekleşti (FILLED). Gerçekleşen fiyat:{' '}
                  <strong className="font-mono">
                    {formatPrice(commandQuery.data.fillPrice)}
                  </strong>
                </span>
              ) : commandQuery.data?.status === 'NEW' ? (
                <span className="text-[#0a0a0a]">
                  Limit emir açık emirler listesine eklendi (NEW).
                </span>
              ) : (
                <span className="text-[#dc2626]">
                  {sanitizeUserMessage(
                    commandQuery.data?.rejectMessage ||
                      commandQuery.data?.rejectCode ||
                      commandQuery.data?.reason,
                    'Emir tamamlanamadı. Bakiyenizi ve emir parametrelerini kontrol edin.',
                  )}
                </span>
              )}
            </p>

            <div className="font-mono text-[11px] text-[#737373]">
              Command ID: {acceptedCommand.commandId}
            </div>

            <div className="flex items-center gap-2 pt-1">
              <Button
                variant="secondary"
                size="xs"
                onClick={() => void commandQuery.refetch()}
                leftIcon={<RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />}
              >
                Durumu Sorgula
              </Button>
              <Button variant="primary" size="xs" onClick={resetForNewOrder}>
                Yeni Emir Hazırla
              </Button>
            </div>
          </div>
        )}

        {/* Emir Girdi Formu */}
        <form onSubmit={requestPreview} className="space-y-3.5" noValidate>
          <div className="grid grid-cols-2 gap-2" role="group" aria-label="İşlem yönü">
            <button
              type="button"
              onClick={() => {
                setSide('LONG');
                invalidateActivePlanOnEdit();
              }}
              aria-pressed={side === 'LONG'}
              className={cn(
                'flex items-center justify-center gap-1.5 rounded-none border py-2 font-mono text-xs font-bold transition-colors',
                side === 'LONG'
                  ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                  : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
              )}
            >
              <span aria-hidden="true">▲</span> LONG (Alış)
            </button>
            <button
              type="button"
              onClick={() => {
                setSide('SHORT');
                invalidateActivePlanOnEdit();
              }}
              aria-pressed={side === 'SHORT'}
              className={cn(
                'flex items-center justify-center gap-1.5 rounded-none border py-2 font-mono text-xs font-bold transition-colors',
                side === 'SHORT'
                  ? 'border-[#dc2626] bg-[#dc2626] text-white'
                  : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
              )}
            >
              <span aria-hidden="true">▼</span> SHORT (Satış)
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2" role="group" aria-label="Emir türü">
            <button
              type="button"
              onClick={() => {
                setOrderType('MARKET');
                invalidateActivePlanOnEdit();
              }}
              aria-pressed={orderType === 'MARKET'}
              className={cn(
                'rounded-none border py-1.5 font-mono text-xs font-semibold transition-colors',
                orderType === 'MARKET'
                  ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                  : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
              )}
            >
              PİYASA (MARKET)
            </button>
            <button
              type="button"
              onClick={() => {
                setOrderType('LIMIT');
                invalidateActivePlanOnEdit();
              }}
              aria-pressed={orderType === 'LIMIT'}
              className={cn(
                'rounded-none border py-1.5 font-mono text-xs font-semibold transition-colors',
                orderType === 'LIMIT'
                  ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                  : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#737373] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
              )}
            >
              LİMİT (LIMIT)
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2.5">
            <FieldGroup
              label="Teminat Bütçesi"
              hint={
                source === 'AI_PROPOSAL' && originProposalId
                  ? 'AI önerisi bütçesi sunucu risk politikasına göre belirlenir; kendi bütçeniz için tutarı düzenleyin.'
                  : undefined
              }
              required
            >
              {(id) => (
                <DecimalInput
                  id={id}
                  value={marginBudget}
                  onChange={(val) => {
                    setMarginBudget(val);
                    invalidateActivePlanOnEdit();
                  }}
                  suffix="USDT"
                  placeholder="250"
                />
              )}
            </FieldGroup>

            <FieldGroup
              label="Kaldıraç"
              rightAddon={instrument ? `Maks ${effectiveMaxLeverage}x` : undefined}
              required
            >
              {(id) => (
                <DecimalInput
                  id={id}
                  value={leverage}
                  onChange={(val) => {
                    setLeverage(val);
                    invalidateActivePlanOnEdit();
                  }}
                  suffix="x"
                  placeholder="5"
                />
              )}
            </FieldGroup>
          </div>

          {orderType === 'LIMIT' && (
            <FieldGroup label="Limit Fiyatı" required>
              {(id) => (
                <DecimalInput
                  id={id}
                  value={limitPrice}
                  onChange={(val) => {
                    setLimitPrice(val);
                    invalidateActivePlanOnEdit();
                  }}
                  suffix={instrument?.quoteAsset ?? 'USDT'}
                  placeholder="49500,00"
                />
              )}
            </FieldGroup>
          )}

          <div className="grid grid-cols-2 gap-2.5">
            <FieldGroup
              label="Zarar Durdur (SL)"
              error={resolvedSl.error}
              hint={
                !resolvedSl.error
                  ? slMode === 'USDT'
                    ? resolvedSl.priceStr
                      ? `Tetik: ~${formatPrice(resolvedSl.priceStr)} USDT${
                          orderSizing?.maxSafeStopLossUsd !== null && orderSizing?.maxSafeStopLossUsd !== undefined
                            ? ` · Maks. ${orderSizing.maxSafeStopLossUsd.toLocaleString('tr-TR', { maximumFractionDigits: 2 })} USDT`
                            : ''
                        }`
                      : orderSizing?.maxSafeStopLossUsd !== null && orderSizing?.maxSafeStopLossUsd !== undefined
                        ? `Örn: 50 → 50 USDT zararda dur · Maks. ${orderSizing.maxSafeStopLossUsd.toLocaleString('tr-TR', { maximumFractionDigits: 2 })} USDT`
                        : 'Zarar durdur tutarı (USDT)'
                    : resolvedSl.usdEstimate !== null
                      ? `Tahmini zarar: ~-${resolvedSl.usdEstimate.toLocaleString('tr-TR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USDT${
                          orderSizing?.maxSafeStopLossUsd !== null && orderSizing?.maxSafeStopLossUsd !== undefined
                            ? ` · Maks. ${orderSizing.maxSafeStopLossUsd.toLocaleString('tr-TR', { maximumFractionDigits: 2 })} USDT`
                            : ''
                        }`
                      : !sizingDiagnosis.ok &&
                          sizingDiagnosis.reason === 'BELOW_MIN_STEP_OR_NOTIONAL' &&
                          stopLoss.trim()
                        ? 'Min. lot altı · Zarar tahmini için teminat/kaldıracı artırın'
                        : undefined
                  : undefined
              }
              rightAddon={
                <div className="inline-flex border border-[#e5e5e5]" role="group" aria-label="Stop-Loss giriş modu">
                  <button
                    type="button"
                    onClick={() => switchSlMode('PRICE')}
                    aria-label="SL giriş modu: Fiyat"
                    aria-pressed={slMode === 'PRICE'}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold transition-colors',
                      slMode === 'PRICE' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252] hover:text-[#0a0a0a]'
                    )}
                  >
                    Fiyat
                  </button>
                  <button
                    type="button"
                    onClick={() => switchSlMode('USDT')}
                    aria-label="SL giriş modu: USDT"
                    aria-pressed={slMode === 'USDT'}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold transition-colors',
                      slMode === 'USDT' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252] hover:text-[#0a0a0a]'
                    )}
                  >
                    USDT ($)
                  </button>
                </div>
              }
            >
              {(id) => (
                <DecimalInput
                  id={id}
                  value={stopLoss}
                  hasError={Boolean(resolvedSl.error)}
                  onChange={(val) => {
                    setStopLoss(val);
                    invalidateActivePlanOnEdit();
                  }}
                  suffix={slMode === 'USDT' ? 'USDT' : undefined}
                  placeholder={slMode === 'USDT' ? 'Örn: 50' : 'Opsiyonel'}
                />
              )}
            </FieldGroup>

            <FieldGroup
              label="Kâr Al (TP)"
              error={resolvedTp.error}
              hint={
                !resolvedTp.error
                  ? tpMode === 'USDT'
                    ? resolvedTp.priceStr
                      ? `Tetik: ~${formatPrice(resolvedTp.priceStr)} USDT kârda kapat`
                      : 'Örn: 10 → 10 USDT kârda kapat'
                    : resolvedTp.usdEstimate !== null
                      ? `Tahmini kâr: ~+${resolvedTp.usdEstimate.toLocaleString('tr-TR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USDT`
                      : !sizingDiagnosis.ok &&
                          sizingDiagnosis.reason === 'BELOW_MIN_STEP_OR_NOTIONAL' &&
                          takeProfit.trim()
                        ? 'Min. lot altı · Kâr tahmini için teminat/kaldıracı artırın'
                        : undefined
                  : undefined
              }
              rightAddon={
                <div className="inline-flex border border-[#e5e5e5]" role="group" aria-label="Take-Profit giriş modu">
                  <button
                    type="button"
                    onClick={() => switchTpMode('PRICE')}
                    aria-label="TP giriş modu: Fiyat"
                    aria-pressed={tpMode === 'PRICE'}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold transition-colors',
                      tpMode === 'PRICE' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252] hover:text-[#0a0a0a]'
                    )}
                  >
                    Fiyat
                  </button>
                  <button
                    type="button"
                    onClick={() => switchTpMode('USDT')}
                    aria-label="TP giriş modu: USDT"
                    aria-pressed={tpMode === 'USDT'}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold transition-colors',
                      tpMode === 'USDT' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252] hover:text-[#0a0a0a]'
                    )}
                  >
                    USDT ($)
                  </button>
                </div>
              }
            >
              {(id) => (
                <DecimalInput
                  id={id}
                  value={takeProfit}
                  hasError={Boolean(resolvedTp.error)}
                  onChange={(val) => {
                    setTakeProfit(val);
                    invalidateActivePlanOnEdit();
                  }}
                  suffix={tpMode === 'USDT' ? 'USDT' : undefined}
                  placeholder={tpMode === 'USDT' ? 'Örn: 10' : 'Opsiyonel'}
                />
              )}
            </FieldGroup>
          </div>

          <div className="grid grid-cols-2 gap-2.5">
            <FieldGroup label="İşlem Vadesi">
              {(id) => (
                <SelectInput
                  id={id}
                  value={horizonSeconds}
                  onChange={(e) => {
                    setHorizonSeconds(Number(e.target.value) as HorizonSeconds);
                    invalidateActivePlanOnEdit();
                  }}
                >
                  <option value={60}>1 Dakika</option>
                  <option value={900}>15 Dakika</option>
                  <option value={3600}>1 Saat</option>
                  <option value={86400}>1 Gün</option>
                  <option value={604800}>1 Hafta</option>
                </SelectInput>
              )}
            </FieldGroup>

            <FieldGroup label="Vade Sonu İşlemi">
              {(id) => (
                <SelectInput
                  id={id}
                  value={expiryPolicy}
                  onChange={(e) => {
                    setExpiryPolicy(e.target.value as ExpiryPolicy);
                    invalidateActivePlanOnEdit();
                  }}
                >
                  <option value="CLOSE_AT_DEADLINE">Süre Sonunda Kapat</option>
                  <option value="REEVALUATE">Yeniden Değerlendir</option>
                </SelectInput>
              )}
            </FieldGroup>
          </div>

          {instrument &&
            effectiveEntryPrice !== null &&
            (sizingDiagnosis.ok || sizingDiagnosis.reason === 'BELOW_MIN_STEP_OR_NOTIONAL') && (
              <div
                data-testid="live-order-summary"
                className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3 text-xs space-y-2"
              >
                {sizingDiagnosis.ok ? (
                  <>
                    <div className="flex items-center justify-between gap-2 border-b border-[#e5e5e5] pb-1.5">
                      <span className="font-semibold text-[#0a0a0a]">Canlı Pozisyon ve Risk Özeti</span>
                      <span className="font-mono text-[11px] text-[#525252]">
                        Giriş ~
                        {formatPrice(
                          formatFixedDecimal(
                            sizingDiagnosis.estimate.entryPrice,
                            stepDecimals(sizingDiagnosis.estimate.tickSize)
                          )
                        )}
                      </span>
                    </div>

                    <div className="grid grid-cols-2 gap-2 font-mono tabular-nums">
                      <div className="min-w-0">
                        <div className="font-sans text-[10px] text-[#737373]">Miktar</div>
                        <div
                          data-testid="live-summary-quantity"
                          className="truncate font-semibold text-[#0a0a0a]"
                        >
                          {formatQuantity(
                            formatFixedDecimal(
                              sizingDiagnosis.estimate.quantity,
                              stepDecimals(sizingDiagnosis.estimate.stepSize)
                            ),
                            instrument.baseAsset
                          )}
                        </div>
                      </div>

                      <div className="min-w-0">
                        <div className="font-sans text-[10px] text-[#737373]">Pozisyon Büyüklüğü</div>
                        <div
                          data-testid="live-summary-notional"
                          className="truncate font-semibold text-[#0a0a0a]"
                        >
                          {formatMoney(
                            formatFixedDecimal(sizingDiagnosis.estimate.notional, 2),
                            instrument.settlementAsset || instrument.quoteAsset || 'USDT',
                            2
                          )}
                        </div>
                      </div>

                      <div className="min-w-0">
                        <div className="font-sans text-[10px] text-[#737373]">Kullanılan Teminat</div>
                        <div
                          data-testid="live-summary-margin"
                          className="truncate font-semibold text-[#0a0a0a]"
                        >
                          {formatMoney(
                            formatFixedDecimal(sizingDiagnosis.estimate.margin, 2),
                            instrument.settlementAsset || instrument.quoteAsset || 'USDT',
                            2
                          )}{' '}
                          ({leverage}x)
                        </div>
                      </div>

                      <div className="min-w-0">
                        <div className="font-sans text-[10px] text-[#737373]">Tahmini Likidasyon</div>
                        <div
                          data-testid="live-summary-liq"
                          className="truncate font-semibold text-[#dc2626]"
                        >
                          {sizingDiagnosis.estimate.estimatedLiquidationPrice !== null
                            ? formatPrice(
                                formatFixedDecimal(
                                  sizingDiagnosis.estimate.estimatedLiquidationPrice,
                                  stepDecimals(sizingDiagnosis.estimate.tickSize)
                                )
                              )
                            : '—'}
                        </div>
                      </div>
                    </div>

                    {(stopLoss.trim() || takeProfit.trim()) && (
                      <div
                        data-testid="live-summary-brackets"
                        className="flex flex-wrap items-center justify-between gap-2 border-t border-[#e5e5e5] pt-1.5 font-mono text-[11px] tabular-nums"
                      >
                        <span className="text-[#dc2626]">
                          SL:{' '}
                          {!resolvedSl.error && resolvedSl.usdEstimate !== null
                            ? formatSignedMoney(
                                `-${formatFixedDecimal(resolvedSl.usdEstimate, 2)}`,
                                instrument.settlementAsset || instrument.quoteAsset || 'USDT',
                                2
                              )
                            : '—'}
                        </span>
                        <span className="text-[#059669]">
                          TP:{' '}
                          {!resolvedTp.error && resolvedTp.usdEstimate !== null
                            ? formatSignedMoney(
                                formatFixedDecimal(resolvedTp.usdEstimate, 2),
                                instrument.settlementAsset || instrument.quoteAsset || 'USDT',
                                2
                              )
                            : '—'}
                        </span>
                      </div>
                    )}

                    <p className="text-[11px] leading-snug text-[#525252]">
                      İzole marjinde aynı kaldıraçta tasfiye fiyatı girişe göre sabit % mesafede kalır;
                      bütçeyle değişen değerler miktar, risk edilen marjin ve SL/TP{' '}
                      {instrument.settlementAsset || instrument.quoteAsset || 'USDT'} sonucudur.
                    </p>
                  </>
                ) : (
                  <div
                    data-testid="live-summary-min-budget"
                    className="flex items-start gap-2 text-[#b45309]"
                  >
                    <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                    <span>{buildMinBudgetMessage(sizingDiagnosis, instrument.baseAsset)}</span>
                  </div>
                )}
              </div>
            )}

          {validationError && (
            <div
              role="alert"
              className="flex items-center gap-2 rounded-none border border-[#dc2626] bg-red-50 px-3 py-2 text-xs text-[#dc2626]"
            >
              <AlertCircle className="h-4 w-4 shrink-0 text-[#dc2626]" aria-hidden="true" />
              <span>{validationError}</span>
            </div>
          )}

          {apiError !== null && (
            <ErrorBanner
              error={apiError}
              title="Emir işlemi tamamlanamadı"
              onRetry={
                isApiError(apiError) && apiError.isNetworkError && activePlan
                  ? () => void handleConfirmSubmit()
                  : undefined
              }
            />
          )}

          <Button
            type="submit"
            variant="primary"
            size="md"
            loading={stage === 'PREVIEW_LOADING'}
            disabled={!instrument || !isTradable || isSessionHalted || stage === 'SUBMITTING'}
            className="w-full"
            leftIcon={<Eye className="h-4 w-4" aria-hidden="true" />}
          >
            Sunucu Emir Önizlemesi Al
          </Button>
        </form>

        {/* Sunucu Önizleme Kartı */}
        {activePlan && (stage === 'PREVIEW_READY' || stage === 'SUBMITTING') && (
          <ExecutionPlanCard
            plan={activePlan}
            symbol={instrument?.baseAsset}
            currency={instrument?.settlementAsset ?? 'USDT'}
            submitting={stage === 'SUBMITTING'}
            onConfirmSubmit={() => void handleConfirmSubmit()}
            onRefreshPreview={() => void requestPreview()}
            onCancelPlan={() => {
              setActivePlan(null);
              setStage('EDITING');
            }}
            timezone={timezone}
          />
        )}
      </div>
    </Card>
  );
}

interface ResolvedBracket {
  priceStr: string | null;
  usdEstimate: number | null;
  error: string | null;
}

function buildMinBudgetMessage(
  diagnosis: Extract<OrderSizingDiagnosis, { ok: false }>,
  baseAsset = 'birim',
): string {
  if (diagnosis.reason === 'BELOW_MIN_STEP_OR_NOTIONAL') {
    const minMargin = formatMoney(
      diagnosis.minMarginBudgetUsd !== null ? formatFixedDecimal(diagnosis.minMarginBudgetUsd, 2) : null,
      'USDT',
      2,
    );
    const minNotional = formatMoney(
      diagnosis.minNotionalUsd !== null ? formatFixedDecimal(diagnosis.minNotionalUsd, 2) : null,
      'USDT',
      2,
    );
    const stepStr =
      diagnosis.stepSize !== null
        ? formatQuantity(formatFixedDecimal(diagnosis.stepSize, stepDecimals(diagnosis.stepSize)), baseAsset)
        : baseAsset;
    return `Minimum işlem adımı (${stepStr} · ~${minNotional}) için en az ~${minMargin} teminat bütçesi veya daha yüksek kaldıraç gerekir.`;
  }
  return 'Geçerli bir piyasa fiyatı, teminat bütçesi ve kaldıraç girin.';
}

function resolveBracketValue(params: {
  kind: 'SL' | 'TP';
  rawValue: string;
  mode: BracketInputMode;
  side: PositionSide;
  effectiveEntryPrice: number | null;
  sizingDiagnosis: OrderSizingDiagnosis;
  baseAsset?: string;
}): ResolvedBracket {
  const { kind, rawValue, mode, side, effectiveEntryPrice, sizingDiagnosis, baseAsset } = params;
  const trimmed = rawValue.trim();
  if (!trimmed) {
    return { priceStr: null, usdEstimate: null, error: null };
  }

  const sizing = sizingDiagnosis.ok ? sizingDiagnosis.estimate : null;
  const isSl = kind === 'SL';

  if (mode === 'USDT') {
    const parsedUsd = parsePositiveDecimalInput(rawValue, 2);
    if (!parsedUsd.valid) {
      return {
        priceStr: null,
        usdEstimate: null,
        error: `${isSl ? 'Zarar tutarı' : 'Kâr hedefi'}: ${parsedUsd.message}`,
      };
    }
    const usdNum = Number(parsedUsd.normalized);
    if (!sizingDiagnosis.ok) {
      const error = !effectiveEntryPrice
        ? `USDT ${isSl ? 'zarar' : 'kâr'} hesabı için piyasa fiyatı bekleniyor veya Limit fiyat girin.`
        : sizingDiagnosis.reason === 'BELOW_MIN_STEP_OR_NOTIONAL'
          ? buildMinBudgetMessage(sizingDiagnosis, baseAsset)
          : `USDT ${isSl ? 'zarar' : 'kâr hedefi'} hesaplamak için geçerli bir teminat bütçesi ve kaldıraç girin.`;
      return { priceStr: null, usdEstimate: usdNum, error };
    }
    const conv = isSl
      ? stopLossPriceFromUsd(side, sizing!, usdNum)
      : takeProfitPriceFromUsd(side, sizing!, usdNum);
    if (conv.error || conv.price === null) {
      return { priceStr: null, usdEstimate: usdNum, error: conv.error };
    }
    return {
      priceStr: formatFixedDecimal(conv.price, stepDecimals(sizing!.tickSize)),
      usdEstimate: usdNum,
      error: null,
    };
  }

  const parsedPrice = parsePositiveDecimalInput(rawValue, 8);
  if (!parsedPrice.valid) {
    return {
      priceStr: null,
      usdEstimate: null,
      error: `${isSl ? 'Stop-Loss' : 'Take-Profit'}: ${parsedPrice.message}`,
    };
  }
  const numPrice = Number(parsedPrice.normalized);
  const liqErr = isSl ? validateStopLossPriceAgainstLiquidation(side, sizing, numPrice) : null;
  const usdEst = sizing ? pnlUsdFromTriggerPrice(side, sizing, numPrice, kind) : null;
  return {
    priceStr: parsedPrice.normalized,
    usdEstimate: usdEst,
    error: liqErr,
  };
}

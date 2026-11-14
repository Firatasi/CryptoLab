import { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, Clock, RefreshCw, ShieldAlert } from 'lucide-react';
import type { ExecutionPlan } from '@/shared/contracts/types';
import {
  formatHorizon,
  formatMoney,
  formatPrice,
  formatQuantity,
  formatSignedMoney,
  formatTimestamp,
  type TimezonePreference,
} from '@/shared/formatting/formatters';
import { Badge, Button, SideBadge } from '@/shared/ui/Primitives';
import {
  estimateSizingFromOpenPosition,
  formatFixedDecimal,
  parseFiniteDecimal,
  pnlUsdFromTriggerPrice,
} from './orderBracketMath';

export interface ExecutionPlanCardProps {
  plan: ExecutionPlan;
  symbol?: string;
  currency?: string;
  submitting: boolean;
  onConfirmSubmit: () => void;
  onRefreshPreview: () => void;
  onCancelPlan: () => void;
  timezone?: TimezonePreference;
}

/**
 * F04.3 / F04.4 / F04.5:
 * Sunucu tarafından imzalanmış kesin ExecutionPlan kartı.
 * Miktar, marjin, komisyon, tasfiye tahmini ve fiyat tolerans sınırları doğrudan backend'den okunur.
 */
export function ExecutionPlanCard({
  plan,
  symbol,
  currency = 'USDT',
  submitting,
  onConfirmSubmit,
  onRefreshPreview,
  onCancelPlan,
  timezone = 'Europe/Istanbul',
}: ExecutionPlanCardProps) {
  const [remainingSec, setRemainingSec] = useState<number>(() =>
    computeRemainingSeconds(plan.validUntil)
  );

  useEffect(() => {
    setRemainingSec(computeRemainingSeconds(plan.validUntil));
    const timer = setInterval(() => {
      setRemainingSec(computeRemainingSeconds(plan.validUntil));
    }, 1000);
    return () => clearInterval(timer);
  }, [plan.validUntil]);

  const isClientExpired = remainingSec <= 0;
  const planSizing = estimateSizingFromOpenPosition({
    side: plan.side,
    quantity: parseFiniteDecimal(plan.quantity),
    entryPrice: parseFiniteDecimal(plan.limitPrice ?? plan.referenceEntry),
    margin: parseFiniteDecimal(plan.marginRequired),
  });
  const slTrigger = parseFiniteDecimal(plan.stopLoss);
  const tpTrigger = parseFiniteDecimal(plan.takeProfit);
  const slUsd =
    planSizing && slTrigger !== null
      ? pnlUsdFromTriggerPrice(plan.side, planSizing, slTrigger, 'SL')
      : null;
  const tpUsd =
    planSizing && tpTrigger !== null
      ? pnlUsdFromTriggerPrice(plan.side, planSizing, tpTrigger, 'TP')
      : null;

  return (
    <div
      data-testid="execution-plan-card"
      className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 space-y-3.5"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#e5e5e5] pb-2.5">
        <div className="flex items-center gap-2">
          <SideBadge side={plan.side} />
          <Badge tone="accent">{plan.orderType}</Badge>
          <Badge tone={plan.source === 'AI_PROPOSAL' ? 'paper' : 'neutral'}>
            {plan.source === 'AI_PROPOSAL' ? 'AI Önerisi Planı' : 'Manuel Plan'}
          </Badge>
        </div>

        <div className="flex items-center gap-1.5 font-mono text-xs">
          <Clock
            className={isClientExpired ? 'h-3.5 w-3.5 text-[#dc2626]' : 'h-3.5 w-3.5 text-[#0a0a0a]'}
            aria-hidden="true"
          />
          <span
            data-testid="plan-ttl-indicator"
            className={isClientExpired ? 'font-semibold text-[#dc2626]' : 'text-[#0a0a0a]'}
          >
            {isClientExpired ? 'Süresi Doldu (TTL)' : `TTL: ${remainingSec} sn`}
          </span>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 text-xs font-mono tabular-nums">
        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Miktar (q)</div>
          <div data-testid="plan-quantity" className="mt-0.5 truncate text-sm font-bold text-[#0a0a0a]">
            {formatQuantity(plan.quantity, symbol)}
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Giriş Fiyatı</div>
          <div className="mt-0.5 truncate text-sm font-bold text-[#0a0a0a]">
            {formatPrice(plan.limitPrice ?? plan.referenceEntry)}
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Pozisyon Büyüklüğü</div>
          <div className="mt-0.5 truncate text-[#0a0a0a]">{formatMoney(plan.notional, currency)}</div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Gerekli Marjin</div>
          <div className="mt-0.5 truncate text-[#0a0a0a] font-semibold">
            {formatMoney(plan.marginRequired, currency)} ({plan.leverage}x)
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Açılış Ücreti</div>
          <div className="mt-0.5 truncate text-[#0a0a0a]">
            {formatMoney(plan.openFeeEstimate, currency)}
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#dc2626] bg-red-50 p-2">
          <div className="truncate font-sans text-[11px] text-[#dc2626]">Tasfiye Fiyatı</div>
          <div className="mt-0.5 truncate font-semibold text-[#dc2626]">
            {formatPrice(plan.estimatedLiquidationPrice)}
          </div>
          <div className="mt-0.5 truncate font-sans text-[10px] text-[#dc2626]/80">
            Risk edilen marjin: {formatMoney(plan.marginRequired, currency, 2)} ({plan.leverage}x)
          </div>
        </div>

        <div className="col-span-2 min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Zarar Durdur / Kâr Al (SL / TP)</div>
          <div className="mt-0.5 truncate text-[#0a0a0a]">
            SL: {formatPrice(plan.stopLoss)}
            {slUsd !== null ? ` (${formatSignedMoney(`-${formatFixedDecimal(slUsd, 2)}`, currency, 2)})` : ''} ·{' '}
            TP: {formatPrice(plan.takeProfit)}
            {tpUsd !== null ? ` (${formatSignedMoney(formatFixedDecimal(tpUsd, 2), currency, 2)})` : ''}
          </div>
        </div>

        <div className="col-span-2 min-w-0 rounded-none border border-[#e5e5e5] bg-white p-2">
          <div className="truncate font-sans text-[11px] text-[#737373]">Fiyat Onay Bandı</div>
          <div className="mt-0.5 truncate text-[#0a0a0a]">
            {formatPrice(plan.priceLowerBound)} – {formatPrice(plan.priceUpperBound)}
          </div>
        </div>
      </div>

      <div className="rounded-none border border-[#e5e5e5] bg-white px-3 py-2 text-[11px] text-[#737373] space-y-1">
        <div className="flex flex-wrap justify-between gap-2">
          <span>Vade: {formatHorizon(plan.horizonSeconds)}</span>
          <span className="truncate">{plan.expiryPolicy === 'CLOSE_AT_DEADLINE' ? 'Süre Sonunda Kapat' : 'Açık Bırak'}</span>
        </div>
        <div className="flex flex-wrap justify-between gap-2 font-mono text-[10px] text-[#737373]">
          <span>
            Plan #{plan.planId.slice(0, 8)} · Rev {plan.revision}
          </span>
          <span className="truncate" title={plan.planHash}>Hash: {plan.planHash.slice(0, 16)}…</span>
        </div>
        <div className="truncate text-[10px] text-[#737373]">
          Geçerlilik: {formatTimestamp(plan.validUntil, timezone)}
        </div>
      </div>

      {plan.warnings.length > 0 && (
        <div
          role="region"
          aria-label="Sunucu plan uyarıları"
          className="rounded-none border border-[#0a0a0a] bg-white p-2.5 text-xs text-[#0a0a0a] space-y-1"
        >
          <div className="flex items-center gap-1.5 font-semibold text-[#0a0a0a]">
            <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span>Sunucu Risk ve Komisyon Uyarıları</span>
          </div>
          <ul className="list-disc pl-4 space-y-0.5 text-[11px] text-[#525252]">
            {plan.warnings.map((warn, i) => (
              <li key={`${warn}-${i}`}>{warn}</li>
            ))}
          </ul>
        </div>
      )}

      {isClientExpired ? (
        <div className="space-y-2">
          <div className="flex items-center gap-2 rounded-none border border-[#dc2626] bg-red-50 px-3 py-2 text-xs text-[#dc2626]">
            <ShieldAlert className="h-4 w-4 shrink-0 text-[#dc2626]" aria-hidden="true" />
            <span>
              Bu planın onay süresi doldu. Eski planla emir gönderilemez; lütfen güncel fiyatlarla
              yeniden önizleme alın.
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="primary"
              size="md"
              className="flex-1"
              onClick={onRefreshPreview}
              leftIcon={<RefreshCw className="h-4 w-4" aria-hidden="true" />}
            >
              Güncel Önizleme Al
            </Button>
            <Button variant="secondary" size="md" onClick={onCancelPlan}>
              Vazgeç
            </Button>
          </div>
        </div>
      ) : (
        <div className="flex items-center gap-2 pt-1">
          <Button
            variant={plan.side === 'LONG' ? 'long' : 'short'}
            size="md"
            loading={submitting}
            onClick={onConfirmSubmit}
            className="flex-1"
            leftIcon={<CheckCircle2 className="h-4 w-4" aria-hidden="true" />}
          >
            Planı Onayla ve Emri Gönder
          </Button>
          <Button variant="secondary" size="md" disabled={submitting} onClick={onCancelPlan}>
            Düzenle
          </Button>
        </div>
      )}
    </div>
  );
}

function computeRemainingSeconds(validUntilIso: string): number {
  const target = new Date(validUntilIso).getTime();
  if (Number.isNaN(target)) return 0;
  return Math.max(0, Math.ceil((target - Date.now()) / 1000));
}

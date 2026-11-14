import type { PositionSide } from '@/shared/contracts/types';

export type BracketInputMode = 'PRICE' | 'USDT';

export const DEFAULT_MAINTENANCE_MARGIN_RATE = 0.005;
export const DEFAULT_MIN_NOTIONAL_USD = 5;

export interface OrderSizingEstimate {
  entryPrice: number;
  quantity: number;
  notional: number;
  margin: number;
  tickSize: number;
  stepSize: number;
  estimatedLiquidationPrice: number | null;
  /** Likidasyon sınırına kadarki brüt azami zarar (USDT). */
  rawLiquidationLossUsd: number | null;
  /** Likidasyon tetiklenmeden önce güvenle girilebilecek azami zarar (USDT). */
  maxSafeStopLossUsd: number | null;
}

export type OrderSizingDiagnosis =
  | { ok: true; estimate: OrderSizingEstimate }
  | {
      ok: false;
      reason: 'MISSING_INPUTS' | 'BELOW_MIN_STEP_OR_NOTIONAL';
      minMarginBudgetUsd: number | null;
      minNotionalUsd: number | null;
      stepSize: number | null;
    };

export function parseFiniteDecimal(raw: string | number | null | undefined): number | null {
  if (raw === null || raw === undefined) return null;
  const text = String(raw).trim().replace(',', '.');
  if (!text) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Backend SessionController.inferStepSize ile birebir uyumludur.
 */
export function inferStepSize(referencePrice: number): number {
  if (referencePrice >= 1000) return 0.001;
  if (referencePrice >= 10) return 0.01;
  if (referencePrice >= 1) return 0.1;
  return 1;
}

/**
 * Backend SessionController.inferTickSize ile birebir uyumludur.
 */
export function inferTickSize(referencePrice: number): number {
  if (referencePrice >= 100) return 0.01;
  if (referencePrice >= 1) return 0.001;
  return 0.00001;
}

export function stepDecimals(stepOrTick: number): number {
  if (!(stepOrTick > 0) || stepOrTick >= 1) return 0;
  return Math.min(8, Math.max(0, Math.round(-Math.log10(stepOrTick))));
}

export function roundPriceToTick(
  price: number,
  tickSize: number,
  sideSafeDirection: 'NEAREST' | 'ABOVE' | 'BELOW' = 'NEAREST',
): number {
  if (!(price > 0) || !(tickSize > 0)) return price;
  const ratio = price / tickSize;
  let steps: number;
  if (sideSafeDirection === 'ABOVE') {
    steps = Math.ceil(ratio - 1e-9);
  } else if (sideSafeDirection === 'BELOW') {
    steps = Math.floor(ratio + 1e-9);
  } else {
    steps = Math.round(ratio);
  }
  const decimals = stepDecimals(tickSize);
  return Number((steps * tickSize).toFixed(decimals));
}

export function formatFixedDecimal(value: number, maxDecimals: number): string {
  if (!Number.isFinite(value)) return '';
  return value.toFixed(maxDecimals).replace(/\.?0+$/, '');
}

/**
 * Backend Finance.liquidation (tek kademe r = 0.005) ile uyumludur.
 */
export function estimateLiquidationPrice(
  side: PositionSide,
  quantity: number,
  entryPrice: number,
  margin: number,
  mmr: number = DEFAULT_MAINTENANCE_MARGIN_RATE,
): number | null {
  if (!(quantity > 0) || !(entryPrice > 0) || !(margin > 0)) return null;
  const notional = quantity * entryPrice;
  const price =
    side === 'LONG'
      ? (notional - margin) / (quantity * (1 - mmr))
      : (notional + margin) / (quantity * (1 + mmr));
  return Number.isFinite(price) && price > 0 ? price : null;
}

/**
 * Teminat + kaldıraç + giriş fiyatından boyutlandırmayı ve minimum adım / notional durumunu teşhis eder.
 */
export function diagnoseOrderSizing(params: {
  side: PositionSide;
  entryPrice: number | null;
  marginBudget: number | null;
  leverage: number | null;
  minNotionalUsd?: number;
}): OrderSizingDiagnosis {
  const {
    side,
    entryPrice,
    marginBudget,
    leverage,
    minNotionalUsd = DEFAULT_MIN_NOTIONAL_USD,
  } = params;
  if (
    entryPrice === null ||
    marginBudget === null ||
    leverage === null ||
    !(entryPrice > 0) ||
    !(marginBudget > 0) ||
    !(leverage >= 1)
  ) {
    return {
      ok: false,
      reason: 'MISSING_INPUTS',
      minMarginBudgetUsd: null,
      minNotionalUsd: null,
      stepSize: null,
    };
  }

  const stepSize = inferStepSize(entryPrice);
  const tickSize = inferTickSize(entryPrice);
  const rawQty = (marginBudget * leverage) / entryPrice;
  const flooredSteps = Math.floor(rawQty / stepSize + 1e-12);
  const stepDec = stepDecimals(stepSize);
  const quantity = Number((flooredSteps * stepSize).toFixed(stepDec));
  const notional = quantity * entryPrice;
  const effectiveMinNotional = Math.max(minNotionalUsd, stepSize * entryPrice);

  if (!(quantity > 0) || notional + 1e-9 < minNotionalUsd) {
    const requiredSteps = Math.max(1, Math.ceil(effectiveMinNotional / (stepSize * entryPrice) - 1e-12));
    const exactMinNotional = requiredSteps * stepSize * entryPrice;
    const minMargin = Math.ceil((exactMinNotional / leverage) * 100) / 100;
    return {
      ok: false,
      reason: 'BELOW_MIN_STEP_OR_NOTIONAL',
      minMarginBudgetUsd: minMargin,
      minNotionalUsd: Number(exactMinNotional.toFixed(2)),
      stepSize,
    };
  }

  const margin = notional / leverage;
  const estimatedLiquidationPrice = estimateLiquidationPrice(side, quantity, entryPrice, margin);

  let rawLiquidationLossUsd: number | null = null;
  let maxSafeStopLossUsd: number | null = null;
  if (estimatedLiquidationPrice !== null) {
    const rawLoss =
      side === 'LONG'
        ? quantity * (entryPrice - estimatedLiquidationPrice)
        : quantity * (estimatedLiquidationPrice - entryPrice);
    if (rawLoss > 0) {
      rawLiquidationLossUsd = rawLoss;
      // En az 1 tick veya 0.01 USDT pay bırakarak stop fiyatının likidasyon fiyatının gerisinde kalmasını garanti et.
      const tickGuardUsd = Math.max(tickSize * quantity * 1.1, 0.01);
      const safeUsd = Math.floor(Math.max(0, rawLoss - tickGuardUsd) * 100) / 100;
      maxSafeStopLossUsd = safeUsd > 0 ? safeUsd : 0;
    }
  }

  return {
    ok: true,
    estimate: {
      entryPrice,
      quantity,
      notional,
      margin,
      tickSize,
      stepSize,
      estimatedLiquidationPrice,
      rawLiquidationLossUsd,
      maxSafeStopLossUsd,
    },
  };
}

/**
 * Teminat + kaldıraç + giriş fiyatından adet, marjin, tahmini likidasyon fiyatı ve azami güvenli SL zararını hesaplar.
 */
export function estimateOrderSizing(params: {
  side: PositionSide;
  entryPrice: number | null;
  marginBudget: number | null;
  leverage: number | null;
}): OrderSizingEstimate | null {
  const diagnosed = diagnoseOrderSizing(params);
  return diagnosed.ok ? diagnosed.estimate : null;
}

/**
 * Mevcut açık bir pozisyon için doğrudan q / entryPrice / margin değerlerinden tahmin hesaplar.
 */
export function estimateSizingFromOpenPosition(params: {
  side: PositionSide;
  quantity: number | null;
  entryPrice: number | null;
  margin: number | null;
}): OrderSizingEstimate | null {
  const { side, quantity, entryPrice, margin } = params;
  if (
    quantity === null ||
    entryPrice === null ||
    margin === null ||
    !(quantity > 0) ||
    !(entryPrice > 0) ||
    !(margin > 0)
  ) {
    return null;
  }
  const tickSize = inferTickSize(entryPrice);
  const stepSize = inferStepSize(entryPrice);
  const estimatedLiquidationPrice = estimateLiquidationPrice(side, quantity, entryPrice, margin);

  let rawLiquidationLossUsd: number | null = null;
  let maxSafeStopLossUsd: number | null = null;
  if (estimatedLiquidationPrice !== null) {
    const rawLoss =
      side === 'LONG'
        ? quantity * (entryPrice - estimatedLiquidationPrice)
        : quantity * (estimatedLiquidationPrice - entryPrice);
    if (rawLoss > 0) {
      rawLiquidationLossUsd = rawLoss;
      const tickGuardUsd = Math.max(tickSize * quantity * 1.1, 0.01);
      const safeUsd = Math.floor(Math.max(0, rawLoss - tickGuardUsd) * 100) / 100;
      maxSafeStopLossUsd = safeUsd > 0 ? safeUsd : 0;
    }
  }

  return {
    entryPrice,
    quantity,
    notional: quantity * entryPrice,
    margin,
    tickSize,
    stepSize,
    estimatedLiquidationPrice,
    rawLiquidationLossUsd,
    maxSafeStopLossUsd,
  };
}

/**
 * USDT zarar hedefinden Stop-Loss tetik fiyatına çevirir.
 */
export function stopLossPriceFromUsd(
  side: PositionSide,
  sizing: OrderSizingEstimate,
  targetLossUsd: number,
): { price: number | null; error: string | null } {
  if (!(targetLossUsd > 0)) {
    return { price: null, error: 'Stop-Loss zarar tutarı sıfırdan büyük olmalıdır.' };
  }

  if (sizing.maxSafeStopLossUsd !== null && targetLossUsd > sizing.maxSafeStopLossUsd) {
    const liqText =
      sizing.rawLiquidationLossUsd !== null
        ? sizing.rawLiquidationLossUsd.toLocaleString('tr-TR', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          })
        : 'mevcut teminat';
    const maxText = sizing.maxSafeStopLossUsd.toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    return {
      price: null,
      error: `Bu pozisyon yaklaşık ${liqText} USDT zararda likid oluyor. Zarar durdur için en fazla ${maxText} USDT zarar girebilirsiniz.`,
    };
  }

  const delta = targetLossUsd / sizing.quantity;
  const rawPrice = side === 'LONG' ? sizing.entryPrice - delta : sizing.entryPrice + delta;
  if (!(rawPrice > 0)) {
    return {
      price: null,
      error: 'Girilen zarar tutarı pozisyon değerini aşıyor. Lütfen daha küçük bir zarar tutarı girin.',
    };
  }

  const rounded = roundPriceToTick(
    rawPrice,
    sizing.tickSize,
    side === 'LONG' ? 'ABOVE' : 'BELOW',
  );
  if (!(rounded > 0)) {
    return {
      price: null,
      error: 'Hesaplanan Stop-Loss fiyatı sıfır veya negatif olamaz.',
    };
  }

  if (
    sizing.estimatedLiquidationPrice !== null &&
    ((side === 'LONG' && rounded <= sizing.estimatedLiquidationPrice) ||
      (side === 'SHORT' && rounded >= sizing.estimatedLiquidationPrice))
  ) {
    const maxText = (sizing.maxSafeStopLossUsd ?? 0).toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    return {
      price: null,
      error: `Stop-Loss tetik fiyatı tasfiye seviyesine denk geliyor. En fazla ${maxText} USDT zarar girebilirsiniz.`,
    };
  }

  return { price: rounded, error: null };
}

/**
 * USDT kâr hedefinden Take-Profit tetik fiyatına çevirir.
 */
export function takeProfitPriceFromUsd(
  side: PositionSide,
  sizing: OrderSizingEstimate,
  targetProfitUsd: number,
): { price: number | null; error: string | null } {
  if (!(targetProfitUsd > 0)) {
    return { price: null, error: 'Take-Profit kâr hedefi sıfırdan büyük olmalıdır.' };
  }
  const delta = targetProfitUsd / sizing.quantity;
  const rawPrice = side === 'LONG' ? sizing.entryPrice + delta : sizing.entryPrice - delta;
  if (!(rawPrice > 0)) {
    return {
      price: null,
      error: 'Girilen kâr hedefi fiyat seviyesini sıfırın altına düşürüyor. Daha düşük bir USDT hedefi girin.',
    };
  }
  const rounded = roundPriceToTick(rawPrice, sizing.tickSize, 'NEAREST');
  if (
    !(rounded > 0) ||
    (side === 'LONG' && rounded <= sizing.entryPrice) ||
    (side === 'SHORT' && rounded >= sizing.entryPrice)
  ) {
    return {
      price: null,
      error: 'Kâr hedefi fiyat adımına yuvarlandığında giriş fiyatından ayrışmıyor; biraz daha büyük bir tutar girin.',
    };
  }
  return { price: rounded, error: null };
}

/**
 * Verilen SL/TP fiyatının karşılık geldiği yaklaşık brüt USDT zarar/kâr tutarını döndürür.
 */
export function pnlUsdFromTriggerPrice(
  side: PositionSide,
  sizing: OrderSizingEstimate,
  triggerPrice: number,
  kind: 'SL' | 'TP',
): number | null {
  if (!(triggerPrice > 0) || !(sizing.quantity > 0) || !(sizing.entryPrice > 0)) return null;
  if (kind === 'SL') {
    const loss =
      side === 'LONG'
        ? sizing.quantity * (sizing.entryPrice - triggerPrice)
        : sizing.quantity * (triggerPrice - sizing.entryPrice);
    return loss > 0 ? Number(loss.toFixed(2)) : null;
  }
  const profit =
    side === 'LONG'
      ? sizing.quantity * (triggerPrice - sizing.entryPrice)
      : sizing.quantity * (sizing.entryPrice - triggerPrice);
  return profit > 0 ? Number(profit.toFixed(2)) : null;
}

/**
 * Fiyat modunda girilen Stop-Loss fiyatının tasfiye (likidasyon) sınırını aşıp aşmadığını doğrular.
 */
export function validateStopLossPriceAgainstLiquidation(
  side: PositionSide,
  sizing: OrderSizingEstimate | null,
  stopPrice: number | null,
): string | null {
  if (!sizing || stopPrice === null || !(stopPrice > 0) || sizing.estimatedLiquidationPrice === null) {
    return null;
  }
  const breaches =
    side === 'LONG'
      ? stopPrice <= sizing.estimatedLiquidationPrice
      : stopPrice >= sizing.estimatedLiquidationPrice;
  if (!breaches) return null;

  const liqPriceText = formatFixedDecimal(sizing.estimatedLiquidationPrice, stepDecimals(sizing.tickSize));
  const maxText =
    sizing.maxSafeStopLossUsd !== null
      ? sizing.maxSafeStopLossUsd.toLocaleString('tr-TR', {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        })
      : '0,00';
  return `Girilen Stop-Loss (${stopPrice}) likidasyon seviyesini (${liqPriceText}) aşıyor. Pozisyon daha erken likid olur; en fazla ${maxText} USDT zarar girebilirsiniz.`;
}

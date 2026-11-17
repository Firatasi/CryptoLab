import type { OrderType, PositionSide } from '@/shared/contracts/types';

export type BracketInputMode = 'PRICE' | 'USDT';

export const DEFAULT_MAINTENANCE_MARGIN_RATE = 0.005;
export const DEFAULT_MIN_NOTIONAL_USD = 5;

const VIP_RULE_PROFILE_ID = '88888888-8888-4888-8888-888888888889';
const SCALPING_RULE_PROFILE_ID = '88888888-8888-4888-8888-888888888890';

/**
 * Backend SimulationMarketCatalog.resolveTakerFeeRate ile birebir uyumlu komisyon oranları.
 */
export function resolveOrderFeeRates(
  ruleProfileId?: string | null,
  orderType: OrderType = 'MARKET',
): { openRate: number; closeMarketRate: number } {
  const isLimit = orderType === 'LIMIT';
  if (ruleProfileId === VIP_RULE_PROFILE_ID) {
    return { openRate: isLimit ? 0.0001 : 0.00025, closeMarketRate: 0.00025 };
  }
  if (ruleProfileId === SCALPING_RULE_PROFILE_ID) {
    return { openRate: isLimit ? 0 : 0.0003, closeMarketRate: 0.0003 };
  }
  return { openRate: isLimit ? 0.0002 : 0.0004, closeMarketRate: 0.0004 };
}

export interface OrderSizingEstimate {
  entryPrice: number;
  quantity: number;
  notional: number;
  margin: number;
  tickSize: number;
  stepSize: number;
  openFeeRate: number;
  closeMarketFeeRate: number;
  estimatedOpenFeeUsd: number;
  requiredCashUsd: number;
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
      /** Lot adımı sağlanamasa bile bütçe ve kaldıraca göre tahmini likidasyon zararı (USDT). */
      budgetRawLiquidationLossUsd: number | null;
      /** Lot adımı sağlanamasa bile bütçe ve kaldıraca göre azami güvenli SL (USDT). */
      budgetMaxSafeStopLossUsd: number | null;
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
 * Bakiye/teminat bütçesi ve kaldıraçtan bağımsız adım yuvarlaması olmadan teorik likidasyon zarar sınırını hesaplar.
 */
function computeBudgetLiquidationBounds(
  side: PositionSide,
  effectiveMarginBudget: number | null,
  leverage: number | null,
): { rawLoss: number | null; maxSafeUsd: number | null } {
  if (
    effectiveMarginBudget === null ||
    leverage === null ||
    !(effectiveMarginBudget > 0) ||
    !(leverage >= 1)
  ) {
    return { rawLoss: null, maxSafeUsd: null };
  }
  const mmr = DEFAULT_MAINTENANCE_MARGIN_RATE;
  const denom = side === 'LONG' ? 1 - mmr : 1 + mmr;
  const numeratorRatio = Math.max(0, 1 - leverage * mmr);
  const rawLoss = (effectiveMarginBudget * numeratorRatio) / denom;
  const safeUsd = Math.floor(Math.max(0, rawLoss - 0.01) * 100) / 100;
  return {
    rawLoss: rawLoss > 0 ? rawLoss : 0,
    maxSafeUsd: safeUsd > 0 ? safeUsd : 0,
  };
}

/**
 * Likidasyon sınırı aşıldığında OrderTicket ve modal genelinde tek biçimli uyarı metni üretir.
 */
export function buildLiquidationExceededMessage(
  rawLiquidationLossUsd: number | null,
  maxSafeStopLossUsd: number | null,
): string {
  const liqText =
    rawLiquidationLossUsd !== null
      ? rawLiquidationLossUsd.toLocaleString('tr-TR', {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        })
      : 'mevcut teminat';
  const maxText = (maxSafeStopLossUsd ?? 0).toLocaleString('tr-TR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return `Bu pozisyon yaklaşık ${liqText} USDT zararda likid oluyor. Zarar durdur için en fazla ${maxText} USDT zarar girebilirsiniz.`;
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
  availableBalance?: number | null;
  ruleProfileId?: string | null;
  orderType?: OrderType;
}): OrderSizingDiagnosis {
  const {
    side,
    entryPrice,
    marginBudget,
    leverage,
    minNotionalUsd = DEFAULT_MIN_NOTIONAL_USD,
    availableBalance = null,
    ruleProfileId = null,
    orderType = 'MARKET',
  } = params;
  const { openRate: openFeeRate, closeMarketRate: closeMarketFeeRate } = resolveOrderFeeRates(
    ruleProfileId,
    orderType,
  );

  const effectiveMarginBudget =
    marginBudget !== null &&
    marginBudget > 0 &&
    availableBalance !== null &&
    availableBalance !== undefined &&
    availableBalance > 0
      ? Math.min(marginBudget, availableBalance)
      : marginBudget;

  const budgetBounds = computeBudgetLiquidationBounds(side, effectiveMarginBudget, leverage);

  if (
    entryPrice === null ||
    effectiveMarginBudget === null ||
    leverage === null ||
    !(entryPrice > 0) ||
    !(effectiveMarginBudget > 0) ||
    !(leverage >= 1)
  ) {
    return {
      ok: false,
      reason: 'MISSING_INPUTS',
      minMarginBudgetUsd: null,
      minNotionalUsd: null,
      stepSize: null,
      budgetRawLiquidationLossUsd: budgetBounds.rawLoss,
      budgetMaxSafeStopLossUsd: budgetBounds.maxSafeUsd,
    };
  }

  const stepSize = inferStepSize(entryPrice);
  const tickSize = inferTickSize(entryPrice);
  const rawQty = (effectiveMarginBudget * leverage) / entryPrice;
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
      budgetRawLiquidationLossUsd: budgetBounds.rawLoss,
      budgetMaxSafeStopLossUsd: budgetBounds.maxSafeUsd,
    };
  }

  const margin = notional / leverage;
  const estimatedOpenFeeUsd = notional * openFeeRate;
  const requiredCashUsd = margin + estimatedOpenFeeUsd;
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
      openFeeRate,
      closeMarketFeeRate,
      estimatedOpenFeeUsd,
      requiredCashUsd,
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
    openFeeRate: 0.0004,
    closeMarketFeeRate: 0.0004,
    estimatedOpenFeeUsd: quantity * entryPrice * 0.0004,
    requiredCashUsd: margin + quantity * entryPrice * 0.0004,
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
    return {
      price: null,
      error: buildLiquidationExceededMessage(sizing.rawLiquidationLossUsd, sizing.maxSafeStopLossUsd),
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

/**
 * Fiyat modunda girilen SL/TP'nin referans fiyata (yeni emirde giriş, açık pozisyonda markPrice) göre doğru tarafta olup olmadığını doğrular.
 */
export function validateBracketPriceDirection(params: {
  side: PositionSide;
  kind: 'SL' | 'TP';
  referencePrice: number | null;
  triggerPrice: number | null;
}): string | null {
  const { side, kind, referencePrice, triggerPrice } = params;
  if (
    referencePrice === null ||
    triggerPrice === null ||
    !(referencePrice > 0) ||
    !(triggerPrice > 0)
  ) {
    return null;
  }
  const refText = formatFixedDecimal(referencePrice, stepDecimals(inferTickSize(referencePrice)));
  if (kind === 'SL') {
    if (side === 'LONG' && triggerPrice >= referencePrice) {
      return `LONG pozisyonda Stop-Loss fiyatı (${triggerPrice}) referans fiyatın (${refText}) altında olmalıdır.`;
    }
    if (side === 'SHORT' && triggerPrice <= referencePrice) {
      return `SHORT pozisyonda Stop-Loss fiyatı (${triggerPrice}) referans fiyatın (${refText}) üzerinde olmalıdır.`;
    }
    return null;
  }
  if (side === 'LONG' && triggerPrice <= referencePrice) {
    return `LONG pozisyonda Take-Profit fiyatı (${triggerPrice}) referans fiyatın (${refText}) üzerinde olmalıdır.`;
  }
  if (side === 'SHORT' && triggerPrice >= referencePrice) {
    return `SHORT pozisyonda Take-Profit fiyatı (${triggerPrice}) referans fiyatın (${refText}) altında olmalıdır.`;
  }
  return null;
}

/**
 * Verilen fiyatın SessionController.inferTickSize adımıyla uyumlu olup olmadığını doğrular.
 */
export function validatePriceTickAlignment(price: number | null): string | null {
  if (price === null || !(price > 0)) return null;
  const tick = inferTickSize(price);
  const steps = price / tick;
  if (Math.abs(steps - Math.round(steps)) > 1e-6) {
    const tickText = formatFixedDecimal(tick, stepDecimals(tick));
    return `Limit fiyatı bu fiyat aralığı için geçerli adımın (${tickText}) katı olmalıdır.`;
  }
  return null;
}

/**
 * Verilen tetik fiyatında açılan pozisyonun brüt K/Z tutarını işaretli (+ kâr, - zarar) döndürür.
 */
export function signedPnlUsdAtTriggerPrice(
  side: PositionSide,
  entryPrice: number,
  quantity: number,
  triggerPrice: number,
): number | null {
  if (!(entryPrice > 0) || !(quantity > 0) || !(triggerPrice > 0)) return null;
  const raw =
    side === 'LONG'
      ? quantity * (triggerPrice - entryPrice)
      : quantity * (entryPrice - triggerPrice);
  return Number.isFinite(raw) ? Number(raw.toFixed(2)) : null;
}

/**
 * Gidiş-dönüş (açılış + kapanış piyasa taker) tahmini komisyon toplamını (USDT) döndürür.
 */
export function estimateRoundTripFeeUsd(params: {
  quantity: number;
  entryPrice: number;
  triggerPrice: number;
  openFeeRate: number;
  closeMarketFeeRate: number;
  fixedOpenFeeUsd?: number | null;
}): number {
  const { quantity, entryPrice, triggerPrice, openFeeRate, closeMarketFeeRate, fixedOpenFeeUsd } = params;
  if (!(quantity > 0) || !(entryPrice > 0) || !(triggerPrice > 0)) return 0;
  const openFee =
    fixedOpenFeeUsd !== null && fixedOpenFeeUsd !== undefined && Number.isFinite(fixedOpenFeeUsd)
      ? fixedOpenFeeUsd
      : quantity * entryPrice * openFeeRate;
  const closeFee = quantity * triggerPrice * closeMarketFeeRate;
  return Number((openFee + closeFee).toFixed(2));
}

/**
 * Komisyon dahil kullanılabilir bakiyeye sığan en yüksek teminat bütçesini döndürür.
 */
export function maxSafeMarginBudgetForAvailableBalance(
  availableBalance: number | null,
  leverage: number | null,
  openFeeRate: number,
): number | null {
  if (
    availableBalance === null ||
    leverage === null ||
    !(availableBalance > 0) ||
    !(leverage >= 1)
  ) {
    return null;
  }
  const safe = availableBalance / (1 + leverage * Math.max(0, openFeeRate));
  const floored = Math.floor(safe * 100) / 100;
  return floored > 0 ? floored : 0;
}

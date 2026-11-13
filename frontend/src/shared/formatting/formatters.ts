import type { DecimalString, HorizonSeconds } from '@/shared/contracts/types';
import { decimalSign } from './decimalParser';

export type TimezonePreference = 'Europe/Istanbul' | 'UTC';

export interface DecimalFormatOptions {
  minFractionDigits?: number;
  maxFractionDigits?: number;
  signed?: boolean;
}

/**
 * DecimalString değeri JavaScript Number'a çevirmeden (hassasiyet kaybı olmadan)
 * Türkçe binlik (.) ve ondalık (,) gösterimine biçimlendirir.
 * Null/eksik veriyi asla sahte "0,00" olarak göstermez; "—" döner.
 */
export function formatDecimal(
  value: DecimalString | number | null | undefined,
  options: DecimalFormatOptions = {}
): string {
  if (value === null || value === undefined) {
    return '—';
  }
  const raw = String(value).trim();
  if (!raw) {
    return '—';
  }

  const isNegative = raw.startsWith('-');
  const unsigned = isNegative ? raw.slice(1) : raw.startsWith('+') ? raw.slice(1) : raw;
  if (!/^(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(unsigned)) {
    return raw;
  }

  const [intPart, rawFrac = ''] = unsigned.split('.');
  const minFrac = options.minFractionDigits ?? 2;
  const maxFrac = Math.max(minFrac, options.maxFractionDigits ?? 8);

  let frac = rawFrac.slice(0, maxFrac);
  while (frac.length > minFrac && frac.endsWith('0')) {
    frac = frac.slice(0, -1);
  }
  frac = frac.padEnd(minFrac, '0');

  const groupedInt = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  const digitsAllZero = /^0+$/.test(intPart + frac);

  let prefix = '';
  if (!digitsAllZero) {
    if (isNegative) {
      prefix = '−';
    } else if (options.signed) {
      prefix = '+';
    }
  }

  return frac.length > 0 ? `${prefix}${groupedInt},${frac}` : `${prefix}${groupedInt}`;
}

export function formatMoney(
  value: DecimalString | number | null | undefined,
  currency = 'USDT',
  maxFractionDigits = 4
): string {
  const formatted = formatDecimal(value, { minFractionDigits: 2, maxFractionDigits });
  return formatted === '—' ? '—' : `${formatted} ${currency}`;
}

export function formatSignedMoney(
  value: DecimalString | number | null | undefined,
  currency = 'USDT',
  maxFractionDigits = 4
): string {
  const formatted = formatDecimal(value, {
    minFractionDigits: 2,
    maxFractionDigits,
    signed: true,
  });
  return formatted === '—' ? '—' : `${formatted} ${currency}`;
}

export function formatPrice(
  value: DecimalString | null | undefined,
  maxFractionDigits = 6
): string {
  return formatDecimal(value, { minFractionDigits: 2, maxFractionDigits });
}

export function formatQuantity(
  value: DecimalString | null | undefined,
  symbol?: string
): string {
  const formatted = formatDecimal(value, { minFractionDigits: 0, maxFractionDigits: 8 });
  if (formatted === '—') return '—';
  return symbol ? `${formatted} ${symbol}` : formatted;
}

/**
 * Sunucudan gelen oran değerini (örn. "0.0005" veya 0.542) dize tabanlı basamak kaydırma ile yüzdeye çevirir.
 */
export function formatRatioPercent(
  ratio: DecimalString | number | null | undefined,
  maxFractionDigits = 4
): string {
  if (ratio === null || ratio === undefined) return '—';
  const raw = String(ratio).trim();
  if (!/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(raw)) return '—';

  const neg = raw.startsWith('-');
  const abs = neg ? raw.slice(1) : raw;
  const [intPart, fracPart = ''] = abs.split('.');
  const padded = fracPart.padEnd(2, '0');
  const shiftedInt = (intPart + padded.slice(0, 2)).replace(/^0+(?=\d)/, '');
  const shiftedFrac = padded.slice(2);
  const candidate = shiftedFrac ? `${neg ? '-' : ''}${shiftedInt}.${shiftedFrac}` : `${neg ? '-' : ''}${shiftedInt}`;

  return `%${formatDecimal(candidate, { minFractionDigits: 2, maxFractionDigits })}`;
}

export function formatTimestamp(
  iso: string | null | undefined,
  tz: TimezonePreference = 'Europe/Istanbul'
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;

  if (tz === 'UTC') {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(
      date.getUTCHours()
    )}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())} UTC`;
  }

  return (
    new Intl.DateTimeFormat('tr-TR', {
      timeZone: 'Europe/Istanbul',
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(date) + ' TSİ'
  );
}

export function formatHorizon(seconds: HorizonSeconds | number): string {
  switch (seconds) {
    case 60:
      return '1 Dakika (60 sn)';
    case 900:
      return '15 Dakika (900 sn)';
    case 3600:
      return '1 Saat (3600 sn)';
    case 86400:
      return '1 Gün (86400 sn)';
    case 604800:
      return '1 Hafta (604800 sn)';
    default:
      return `${seconds} sn`;
  }
}

export function pnlToneClass(value: DecimalString | number | null | undefined): string {
  const sign = decimalSign(value);
  if (sign === 1) return 'text-[#0a0a0a] font-semibold';
  if (sign === -1) return 'text-[#dc2626]';
  return 'text-[#737373]';
}

const REASON_CODE_LABELS: Record<string, string> = {
  QUANT_MODEL_V3: 'Çoklu Zaman Dilimi Kantitatif Model (v3)',
  QUANT_MODEL_V4: 'Çoklu Rejim GBDT & Üçlü Bariyer Modeli (v4)',
  QUANT_ADAPTIVE_POLICY: 'Dinamik Risk & Kaldıraç Politikası',
  HTF_TREND_CONFIRMED: 'Üst Zaman Dilimi Trend Teyidi',
  HTF_COUNTER_TREND: 'Üst Zaman Dilimi Karşı Trend Riski',
  TREND_MOMENTUM_ALIGNED: 'Trend ve Momentum Uyumlu',
  ORDER_FLOW_CONFIRMED: 'Agresif Emir Akışı Onaylı',
  ORDER_FLOW_DIVERGENCE: 'Emir Akışı Uyumsuzluğu',
  LIQUIDITY_SWEEP_ALIGNED: 'Likidite Süpürmesi (SMC) Teyitli',
  SQUEEZE_BREAKOUT_SETUP: 'Volatilite Sıkışması Kırılımı',
  VOLUME_CONFIRMED: 'Hacim Desteği Onaylı',
  CHOPPY_REGIME: 'Yatay / Kararsız Piyasa Rejimi',
  CHOPPY_MARKET_WAIT: 'Yatay Piyasa Bekleme Kapısı',
  MEAN_REVERSION_EXHAUSTION: 'Aşırı Uzama / Dönüş Riski',
  HIGH_FUNDING_CAUTION: 'Yüksek Fonlama Oranı Uyarısı',
  LOW_MODEL_CONFIDENCE: 'Model Güven Eşiği Altında',
  CONFIDENCE_BELOW_RISK_THRESHOLD: 'Seçili Risk Seviyesi Eşiği Altında',
  EDGE_BELOW_COST_HURDLE: 'Beklenen Net Getiri Maliyet Eşiği Altında',
  INSUFFICIENT_HISTORY: 'Yetersiz geçmiş mum serisi',
  INSUFFICIENT_DATA: 'Yetersiz veri kapsamı',
  STALE_MARK: 'Mark fiyatı güncelliğini yitirdi (Bayat veri)',
  STALE_QUOTE: 'Alış/satış kotasyonu güncelliğini yitirdi',
  STALE_CANDLES: 'Mum verisi gecikmeli',
  STALE_DATA: 'Piyasa verisi bayat',
  CANDLE_GAP: 'Mum serisinde veri boşluğu (gap) var',
  MISSING_VOLUME: 'Hacim verisi eksik',
  ZERO_VOLUME_BASELINE: 'Sıfır hacim referansı',
  OUT_OF_DOMAIN: 'Model eğitim dağılımı dışında (Out-of-Domain)',
  EDGE_BELOW_COST: 'Beklenen getiri işlem maliyetinin altında',
  LOW_CONFIDENCE: 'Sinyal güven aralığı yetersiz',
  VOLATILITY_REGIME_UNSAFE: 'Aşırı oynaklık rejimi — işlem açılmadı',
  PAPER_TRADING_DISABLED: 'Sanal işleme kapalı sözleşme',
  WARMING_UP: 'Veri akışı ısınma aşamasında',
  DELISTING_SOON: 'Sözleşme yakında listeden çıkarılacak',
  UNSUPPORTED_SETTLEMENT: 'Desteklenmeyen uzlaşı para birimi (yalnız USDT)',
  BUDGET_EXHAUSTED: 'Tarama analiz bütçesi doldu',
  NOT_ANALYSIS_READY: 'AI analizi için veri kapsamı hazır değil',
};

export function formatReasonCode(code: string): string {
  return REASON_CODE_LABELS[code] ? `${REASON_CODE_LABELS[code]} (${code})` : code;
}


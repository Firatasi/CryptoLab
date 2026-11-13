import type { DecimalString } from '@/shared/contracts/types';

export type DecimalParseErrorCode =
  | 'EMPTY'
  | 'AMBIGUOUS_SEPARATOR'
  | 'SCIENTIFIC_NOT_ALLOWED'
  | 'NEGATIVE_NOT_ALLOWED'
  | 'NON_POSITIVE'
  | 'TOO_MANY_DECIMALS'
  | 'INVALID_FORMAT';

export type DecimalParseResult =
  | { valid: true; normalized: DecimalString }
  | { valid: false; code: DecimalParseErrorCode; message: string };

const CANONICAL_DECIMAL_RE = /^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/;

/**
 * Backend JSON payload'larından gelebilen sayısal (number) veya nullish değerleri
 * form durumları için güvenli, bilimsel gösterimsiz ondalık metne dönüştürür.
 */
export function toDecimalInputString(
  rawValue: string | number | null | undefined,
  maxFractionDigits = 8
): string {
  if (rawValue === null || rawValue === undefined) {
    return '';
  }
  if (typeof rawValue === 'number') {
    if (!Number.isFinite(rawValue)) {
      return '';
    }
    if (rawValue === 0) {
      return '0';
    }
    if (Number.isInteger(rawValue)) {
      return String(rawValue);
    }
    return rawValue.toFixed(maxFractionDigits).replace(/\.?0+$/, '');
  }
  return typeof rawValue === 'string' ? rawValue : String(rawValue);
}

/**
 * Kullanıcı girdisini (örn. "1000,50" veya "1000.50") hassasiyet kaybetmeden
 * sözleşmedeki kanonik DecimalString biçimine normalize eder.
 * Binlik ayraç belirsizliklerini ("1.000,50", "1,000,000") açıkça reddeder.
 */
export function parsePositiveDecimalInput(
  rawInput: string | number | null | undefined,
  maxFractionDigits = 8
): DecimalParseResult {
  const trimmed = toDecimalInputString(rawInput, maxFractionDigits).trim();
  if (!trimmed) {
    return { valid: false, code: 'EMPTY', message: 'Değer girilmelidir.' };
  }

  if (/[eE]/.test(trimmed)) {
    return {
      valid: false,
      code: 'SCIENTIFIC_NOT_ALLOWED',
      message: 'Bilimsel gösterim kullanılamaz; düz ondalık sayı girin.',
    };
  }

  const dotCount = (trimmed.match(/\./g) ?? []).length;
  const commaCount = (trimmed.match(/,/g) ?? []).length;

  if ((dotCount > 0 && commaCount > 0) || dotCount > 1 || commaCount > 1) {
    return {
      valid: false,
      code: 'AMBIGUOUS_SEPARATOR',
      message:
        'Binlik ayraç kullanmayın; ondalık kısım için tek virgül veya tek nokta girin (örn. 1000,50).',
    };
  }

  const normalizedCandidate = trimmed.replace(',', '.');

  if (normalizedCandidate.startsWith('-')) {
    return {
      valid: false,
      code: 'NEGATIVE_NOT_ALLOWED',
      message: 'Negatif değer girilemez.',
    };
  }

  if (!CANONICAL_DECIMAL_RE.test(normalizedCandidate)) {
    return {
      valid: false,
      code: 'INVALID_FORMAT',
      message: 'Geçerli bir ondalık sayı girin (örn. 250 veya 0,50).',
    };
  }

  const [, fraction = ''] = normalizedCandidate.split('.');
  if (fraction.length > maxFractionDigits) {
    return {
      valid: false,
      code: 'TOO_MANY_DECIMALS',
      message: `En fazla ${maxFractionDigits} ondalık basamak girilebilir.`,
    };
  }

  // Sıfır kontrolü (yalnız karakter düzeyinde; Number() dönüşümü yok)
  const digitsOnly = normalizedCandidate.replace('.', '');
  if (/^0+$/.test(digitsOnly)) {
    return {
      valid: false,
      code: 'NON_POSITIVE',
      message: 'Değer sıfırdan büyük olmalıdır.',
    };
  }

  return { valid: true, normalized: stripRedundantTrailingZeros(normalizedCandidate) };
}

/**
 * Opsiyonel alanlar (örn. Stop Loss, Take Profit) için ayrıştırıcı:
 * boş bırakılırsa { valid: true, normalized: null } döner.
 */
export function parseOptionalPositiveDecimalInput(
  rawInput: string | number | null | undefined,
  maxFractionDigits = 8
): { valid: true; normalized: DecimalString | null } | { valid: false; code: DecimalParseErrorCode; message: string } {
  const text = toDecimalInputString(rawInput, maxFractionDigits);
  if (!text.trim()) {
    return { valid: true, normalized: null };
  }
  return parsePositiveDecimalInput(text, maxFractionDigits);
}

function stripRedundantTrailingZeros(canonical: string): DecimalString {
  if (!canonical.includes('.')) {
    return canonical;
  }
  const [intPart, fracPart] = canonical.split('.');
  const strippedFrac = fracPart.replace(/0+$/, '');
  return strippedFrac.length > 0 ? `${intPart}.${strippedFrac}` : intPart;
}

/**
 * İki kanonik DecimalString değerini JavaScript Number kullanmadan BigInt ölçekleme ile karşılaştırır.
 * Yalnız istemci tarafı form ipuçları (örn. SL/TP yön uyarısı) için kullanılır; finansal hesap yapmaz.
 * Döndürür: -1 (a < b), 0 (a == b), 1 (a > b)
 */
export function compareDecimalStrings(
  a: DecimalString | number,
  b: DecimalString | number
): -1 | 0 | 1 {
  const cleanA = toDecimalInputString(a, 12).trim();
  const cleanB = toDecimalInputString(b, 12).trim();
  if (!CANONICAL_DECIMAL_RE.test(cleanA) || !CANONICAL_DECIMAL_RE.test(cleanB)) {
    throw new Error('INVALID_CANONICAL_DECIMAL');
  }

  const negA = cleanA.startsWith('-');
  const negB = cleanB.startsWith('-');
  const absA = negA ? cleanA.slice(1) : cleanA;
  const absB = negB ? cleanB.slice(1) : cleanB;

  const [intA, fracA = ''] = absA.split('.');
  const [intB, fracB = ''] = absB.split('.');
  const scale = Math.max(fracA.length, fracB.length);

  const scaledA = BigInt(intA + fracA.padEnd(scale, '0')) * (negA ? -1n : 1n);
  const scaledB = BigInt(intB + fracB.padEnd(scale, '0')) * (negB ? -1n : 1n);

  if (scaledA < scaledB) return -1;
  if (scaledA > scaledB) return 1;
  return 0;
}

export function decimalSign(value: DecimalString | number | null | undefined): -1 | 0 | 1 | null {
  if (value === null || value === undefined) return null;
  const str = String(value).trim();
  if (!str || !CANONICAL_DECIMAL_RE.test(str)) return null;
  const digits = str.replace('-', '').replace('.', '');
  if (/^0+$/.test(digits)) return 0;
  return str.startsWith('-') ? -1 : 1;
}


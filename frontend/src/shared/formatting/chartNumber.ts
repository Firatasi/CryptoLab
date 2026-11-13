import type { DecimalString } from '@/shared/contracts/types';

/**
 * YALNIZ HTML5 Canvas grafik çizimi (lightweight-charts) için tek yönlü dönüşüm.
 * Buradan dönen sayı ASLA bir API isteğine, emir gövdesine veya bakiye hesabına geri yazılamaz.
 */
export function toChartNumber(value: DecimalString): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`CHART_NUMBER_INVALID: ${value}`);
  }
  return parsed;
}


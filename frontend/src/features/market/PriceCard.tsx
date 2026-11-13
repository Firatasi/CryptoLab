import { AlertTriangle, Clock } from 'lucide-react';
import type { MarketSnapshot } from '@/shared/contracts/types';
import {
  formatPrice,
  formatRatioPercent,
  formatTimestamp,
  type TimezonePreference,
} from '@/shared/formatting/formatters';
import { Badge, Card, Skeleton } from '@/shared/ui/Primitives';
import { ErrorBanner } from '@/shared/ui/Feedback';

export interface PriceCardProps {
  snapshot: MarketSnapshot | null | undefined;
  exchangeLabel?: string;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  timezone?: TimezonePreference;
}

/**
 * F01.2 / F01.3:
 * - Mark Fiyatı (risk ve tasfiye otoritesi) ile Son İşlem (Last), Bid ve Ask ayrı kutularda gösterilir.
 * - Yükleme durumunda asla sahte "0,00" üretilmez; skeleton gösterilir.
 */
export function PriceCard({
  snapshot,
  exchangeLabel,
  loading = false,
  error = null,
  onRetry,
  timezone = 'Europe/Istanbul',
}: PriceCardProps) {
  if (loading) {
    return (
      <Card className="p-4 space-y-3" aria-busy="true">
        <div className="flex items-center justify-between">
          <Skeleton className="h-5 w-36" />
          <Skeleton className="h-5 w-28" />
        </div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      </Card>
    );
  }

  if (error) {
    return <ErrorBanner error={error} title="Piyasa kotasyonu alınamadı" onRetry={onRetry} />;
  }

  if (!snapshot) {
    return (
      <Card className="p-4 text-xs text-[#737373]">
        Fiyat özeti görüntülemek için katalogdan bir enstrüman seçin.
      </Card>
    );
  }

  const isDegraded = snapshot.quality !== 'FRESH';

  return (
    <Card className="p-3.5 space-y-2.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-mono text-base font-bold text-[#0a0a0a]">{snapshot.symbol}</span>
          {exchangeLabel && <Badge tone="neutral">{exchangeLabel}</Badge>}
        </div>

        <div className="flex items-center gap-1.5 font-mono text-[11px] text-[#737373]">
          <Clock className="h-3.5 w-3.5 text-[#737373]" aria-hidden="true" />
          <span>{formatTimestamp(snapshot.sourceTime, timezone)}</span>
        </div>
      </div>

      {isDegraded && (
        <div
          role="status"
          className="flex items-center gap-2 rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]"
        >
          <AlertTriangle className="h-4 w-4 shrink-0 text-[#0a0a0a]" aria-hidden="true" />
          <span>
            Veri kalitesi <strong className="font-mono">{snapshot.quality}</strong> durumunda.
            Simülasyon motoru bayat mark fiyatıyla yeni emir veya tasfiye yürütmeyi reddedebilir.
          </span>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        <div className="min-w-0 rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2">
          <div className="truncate text-[10px] font-semibold uppercase tracking-wide text-[#525252]">
            Mark Fiyatı
          </div>
          <div
            data-testid="mark-price-value"
            className="mt-0.5 truncate font-mono text-sm font-bold tabular-nums text-[#0a0a0a] xl:text-base"
          >
            {formatPrice(snapshot.markPrice)}
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white px-3 py-2">
          <div className="truncate text-[10px] font-medium uppercase tracking-wide text-[#737373]">
            Son İşlem
          </div>
          <div
            data-testid="last-price-value"
            className="mt-0.5 truncate font-mono text-sm font-semibold tabular-nums text-[#0a0a0a] xl:text-base"
          >
            {formatPrice(snapshot.lastPrice)}
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white px-3 py-2">
          <div className="truncate text-[10px] font-medium uppercase tracking-wide text-[#737373]">
            En İyi Alış
          </div>
          <div className="mt-0.5 truncate font-mono text-sm font-semibold tabular-nums text-emerald-700">
            {formatPrice(snapshot.bid)}
          </div>
        </div>

        <div className="min-w-0 rounded-none border border-[#e5e5e5] bg-white px-3 py-2">
          <div className="truncate text-[10px] font-medium uppercase tracking-wide text-[#737373]">
            En İyi Satış
          </div>
          <div className="mt-0.5 truncate font-mono text-sm font-semibold tabular-nums text-[#dc2626]">
            {formatPrice(snapshot.ask)}
          </div>
        </div>

        <div className="col-span-2 min-w-0 rounded-none border border-[#e5e5e5] bg-white px-3 py-2 sm:col-span-1">
          <div className="truncate text-[10px] font-medium uppercase tracking-wide text-[#737373]">
            Fonlama
          </div>
          <div className="mt-0.5 truncate font-mono text-sm font-semibold tabular-nums text-[#0a0a0a]">
            {snapshot.fundingRate ? formatRatioPercent(snapshot.fundingRate, 4) : '—'}
          </div>
        </div>
      </div>
    </Card>
  );
}

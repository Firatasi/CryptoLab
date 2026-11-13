import React, { useEffect, useState } from 'react';
import {
  AlertTriangle,
  Inbox,
  RefreshCw,
  ShieldCheck,
  Wifi,
  X,
} from 'lucide-react';
import type { StreamConnectionState } from '@/shared/contracts/types';
import { isApiError, sanitizeUserMessage, toUserFacingErrorMessage } from '@/shared/api/apiErrors';
import { Badge, Button } from './Primitives';
import { cn } from './cn';

const FIELD_LABELS_TR: Record<string, string> = {
  email: 'E-posta',
  password: 'Parola',
  displayName: 'Görünen Ad',
  captchaAnswer: 'Güvenlik Kodu',
  stopLoss: 'Zarar Durdur (SL)',
  takeProfit: 'Kâr Al (TP)',
  leverage: 'Kaldıraç',
  marginBudget: 'Marjin Bütçesi',
  limitPrice: 'Limit Fiyatı',
  initialDeposit: 'Başlangıç Bakiyesi',
  name: 'Ad',
  subject: 'Konu',
  message: 'Mesaj',
  senderName: 'Ad Soyad',
  senderEmail: 'E-posta',
};

export function ErrorBanner({
  error,
  title = 'İşlem tamamlanamadı',
  onRetry,
  className,
}: {
  error: unknown;
  title?: string;
  onRetry?: () => void;
  className?: string;
}) {
  if (!error) return null;

  const apiErr = isApiError(error) ? error : null;
  const message = toUserFacingErrorMessage(error);

  return (
    <div
      role="alert"
      className={cn(
        'rounded-none border border-[#dc2626] bg-red-50/70 px-4 py-3 text-sm text-[#0a0a0a]',
        className
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2.5">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-[#dc2626]" aria-hidden="true" />
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-[#dc2626]">{title}</span>
              {apiErr?.retryable && (
                <span className="border border-[#0a0a0a] bg-[#f5f5f5] px-1.5 py-0.5 text-[11px] text-[#0a0a0a]">
                  Yeniden denenebilir
                </span>
              )}
            </div>
            <p className="text-xs leading-relaxed text-[#0a0a0a]">{message}</p>

            {apiErr && apiErr.fields.length > 0 && (
              <ul className="mt-2 list-disc space-y-0.5 pl-4 text-xs text-[#dc2626]">
                {apiErr.fields.map((f, idx) => (
                  <li key={`${f.field}-${idx}`}>
                    <span className="font-semibold">
                      {FIELD_LABELS_TR[f.field] ?? f.field}:
                    </span>{' '}
                    {sanitizeUserMessage(f.message, 'Lütfen bu alanı kontrol edin.')}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>

        {onRetry && (
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              size="xs"
              onClick={onRetry}
              leftIcon={<RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />}
            >
              Tekrar Dene
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
  icon?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center rounded-none border border-dashed border-[#d4d4d4] bg-[#f5f5f5]/60 px-6 py-10 text-center">
      <div className="mb-3 border border-[#e5e5e5] bg-white p-3 text-[#737373]">
        {icon ?? <Inbox className="h-5 w-5" aria-hidden="true" />}
      </div>
      <h3 className="text-sm font-semibold text-[#0a0a0a]">{title}</h3>
      <p className="mt-1 max-w-md text-xs leading-relaxed text-[#737373]">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ConnectionStatusBadge({ state }: { state: StreamConnectionState }) {
  switch (state) {
    case 'LIVE':
      return (
        <Badge tone="success" className="font-mono">
          <span aria-hidden="true">●</span> SSE Canlı
        </Badge>
      );
    case 'CONNECTING':
      return (
        <Badge tone="neutral" className="font-mono">
          <span aria-hidden="true">◌</span> Bağlanıyor
        </Badge>
      );
    case 'RECONNECTING':
      return (
        <Badge tone="wait" className="font-mono">
          <span aria-hidden="true">↻</span> Yeniden Bağlanıyor
        </Badge>
      );
    case 'DEGRADED_POLLING':
      return (
        <Badge tone="wait" className="font-mono">
          <Wifi className="h-3 w-3" aria-hidden="true" /> REST Yoklama (Polling)
        </Badge>
      );
    case 'STALE':
      return (
        <Badge tone="danger" className="font-mono">
          <span aria-hidden="true">▲</span> Akış Bayat
        </Badge>
      );
    default:
      return (
        <Badge tone="neutral" className="font-mono">
          <span aria-hidden="true">○</span> Akış Kapalı
        </Badge>
      );
  }
}

export function SimulationDisclaimerBar({ apiMode = 'live' }: { apiMode?: string }) {
  const [visible, setVisible] = useState(true);
  const [fading, setFading] = useState(false);
  const isFixtureOrHybrid = apiMode === 'fixture' || apiMode === 'hybrid';

  useEffect(() => {
    const fadeTimer = window.setTimeout(() => {
      setFading(true);
    }, 14400);
    const hideTimer = window.setTimeout(() => {
      setVisible(false);
    }, 15000);

    return () => {
      window.clearTimeout(fadeTimer);
      window.clearTimeout(hideTimer);
    };
  }, []);

  if (!visible) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        'fixed top-16 right-5 z-50 w-[calc(100%-2.5rem)] max-w-sm border border-white/15 bg-[#0a0a0a]/75 px-4 py-3 text-[11px] text-white/90 shadow-2xl backdrop-blur-md transition-all duration-500',
        fading ? 'translate-x-4 opacity-0' : 'translate-x-0 opacity-100'
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-2.5">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-white" aria-hidden="true" />
          <div className="space-y-1 leading-relaxed">
            <div className="font-semibold uppercase tracking-wider text-white">
              Sanal işlem · Gerçek para kullanılmaz
            </div>
            <div className="text-white/75">
              — Tüm bakiyeler sanal USDT cinsindendir; borsa emir defterine gerçek emir iletilmez.
            </div>
            {isFixtureOrHybrid && (
              <div className="mt-1.5 inline-block border border-white/25 bg-white/10 px-2 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider text-white">
                GELİŞTİRME MODU ({apiMode.toUpperCase()}) — Canlı Üretim Verisi Değil
              </div>
            )}
          </div>
        </div>
        <button
          type="button"
          aria-label="Bildirimi kapat"
          onClick={() => setVisible(false)}
          className="shrink-0 p-0.5 text-white/60 transition-colors hover:text-white"
        >
          <X className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

interface ErrorBoundaryState {
  error: Error | null;
}

export class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  ErrorBoundaryState
> {
  public state: ErrorBoundaryState = { error: null };

  public static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  public render() {
    if (this.state.error) {
      return (
        <div className="mx-auto max-w-xl p-8">
          <ErrorBanner
            error={this.state.error}
            title="Beklenmeyen Arayüz Hatası"
            onRetry={() => this.setState({ error: null })}
          />
        </div>
      );
    }
    return this.props.children;
  }
}

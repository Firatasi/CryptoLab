import React from 'react';
import { Loader2 } from 'lucide-react';
import type { AnalysisAction, MarketQuality, PositionSide } from '@/shared/contracts/types';
import { cn } from './cn';

export type ButtonVariant =
  | 'primary'
  | 'secondary'
  | 'ghost'
  | 'long'
  | 'short'
  | 'danger'
  | 'warning';

export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leftIcon?: React.ReactNode;
}

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    'bg-[#0a0a0a] text-white font-medium hover:bg-[#262626] active:bg-black border border-[#0a0a0a]',
  secondary:
    'bg-[#f5f5f5] text-[#0a0a0a] hover:bg-[#e5e5e5] border border-[#e5e5e5] font-medium',
  ghost:
    'bg-transparent text-[#525252] hover:text-[#0a0a0a] hover:bg-[#f5f5f5] border border-transparent',
  long:
    'bg-[#0a0a0a] text-white font-semibold hover:bg-[#262626] border border-[#0a0a0a]',
  short:
    'bg-[#dc2626] text-white font-semibold hover:bg-red-700 border border-[#dc2626]',
  danger:
    'bg-white text-[#dc2626] hover:bg-[#dc2626] hover:text-white border border-[#dc2626] font-medium',
  warning:
    'bg-[#f5f5f5] text-[#0a0a0a] hover:bg-[#e5e5e5] border border-[#0a0a0a] font-medium',
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  xs: 'h-7 px-2.5 text-xs gap-1.5',
  sm: 'h-8 px-3.5 text-xs gap-1.5',
  md: 'h-9 px-4 text-sm gap-2',
  lg: 'h-11 px-6 text-sm gap-2',
};

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    leftIcon,
    disabled,
    className,
    children,
    type = 'button',
    ...rest
  },
  ref
) {
  const isDisabled = Boolean(disabled || loading);
  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex items-center justify-center rounded-none tracking-tight transition-colors select-none disabled:opacity-40 disabled:cursor-not-allowed',
        VARIANT_CLASSES[variant],
        SIZE_CLASSES[size],
        className
      )}
      {...rest}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin shrink-0" aria-hidden="true" /> : leftIcon}
      {children}
    </button>
  );
});

export type BadgeTone =
  | 'neutral'
  | 'accent'
  | 'paper'
  | 'long'
  | 'short'
  | 'wait'
  | 'danger'
  | 'success';

const BADGE_TONES: Record<BadgeTone, string> = {
  neutral: 'bg-[#f5f5f5] text-[#525252] border-[#e5e5e5]',
  accent: 'bg-[#0a0a0a] text-white border-[#0a0a0a]',
  paper: 'bg-white text-[#0a0a0a] border-[#0a0a0a]',
  long: 'bg-[#0a0a0a] text-white border-[#0a0a0a]',
  short: 'bg-[#dc2626] text-white border-[#dc2626]',
  wait: 'bg-[#f5f5f5] text-[#0a0a0a] border-[#737373]',
  danger: 'bg-red-50 text-[#dc2626] border-[#dc2626]',
  success: 'bg-[#f5f5f5] text-[#0a0a0a] border-[#0a0a0a]',
};

export function Badge({
  tone = 'neutral',
  className,
  children,
}: {
  tone?: BadgeTone;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-none border px-2 py-0.5 text-[11px] font-medium uppercase tracking-widest',
        BADGE_TONES[tone],
        className
      )}
    >
      {children}
    </span>
  );
}

/**
 * Yön göstergesi ASLA yalnız renge dayanmaz; ok/sembol ve metin birlikte sunulur.
 */
export function SideBadge({ side }: { side: PositionSide | AnalysisAction | string }) {
  if (side === 'LONG') {
    return (
      <Badge tone="long" className="font-mono font-semibold">
        <span aria-hidden="true">▲</span> LONG
      </Badge>
    );
  }
  if (side === 'SHORT') {
    return (
      <Badge tone="short" className="font-mono font-semibold">
        <span aria-hidden="true">▼</span> SHORT
      </Badge>
    );
  }
  if (side === 'WAIT') {
    return (
      <Badge tone="wait" className="font-mono font-semibold">
        <span aria-hidden="true">⏸</span> BEKLE (WAIT)
      </Badge>
    );
  }
  return <Badge tone="neutral">{side}</Badge>;
}

export function QualityBadge({ quality }: { quality: MarketQuality | string | null | undefined }) {
  switch (quality) {
    case 'FRESH':
      return (
        <Badge tone="success">
          <span aria-hidden="true">●</span> Taze Veri (FRESH)
        </Badge>
      );
    case 'STALE':
      return (
        <Badge tone="wait">
          <span aria-hidden="true">▲</span> Bayat Veri (STALE)
        </Badge>
      );
    case 'GAP':
      return (
        <Badge tone="wait">
          <span aria-hidden="true">◆</span> Veri Boşluğu (GAP)
        </Badge>
      );
    case 'WARMING':
      return (
        <Badge tone="neutral">
          <span aria-hidden="true">◌</span> Isınıyor (WARMING)
        </Badge>
      );
    case 'UNAVAILABLE':
      return (
        <Badge tone="danger">
          <span aria-hidden="true">✕</span> Veri Yok (UNAVAILABLE)
        </Badge>
      );
    default:
      return <Badge tone="neutral">{quality || 'Bilinmiyor'}</Badge>;
  }
}

export function Card({
  className,
  children,
  ...rest
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-none border border-[#e5e5e5] bg-white text-[#0a0a0a]',
        className
      )}
      {...rest}
    >
      {children}
    </div>
  );
}

export function CardHeader({
  title,
  subtitle,
  action,
  className,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex flex-wrap items-center justify-between gap-3 border-b border-[#e5e5e5] px-5 py-3.5',
        className
      )}
    >
      <div>
        <h2 className="text-sm font-semibold text-[#0a0a0a] tracking-tight">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-[#737373]">{subtitle}</p>}
      </div>
      {action && <div className="flex items-center gap-2">{action}</div>}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      role="status"
      aria-label="Yükleniyor"
      className={cn('animate-pulse rounded-none bg-[#f5f5f5] border border-[#e5e5e5]', className)}
    />
  );
}

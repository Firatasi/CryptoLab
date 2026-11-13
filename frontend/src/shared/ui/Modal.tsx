import React, { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';
import { Button } from './Primitives';
import { cn } from './cn';

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  maxWidthClass?: string;
}

/**
 * WCAG 2.1 AA uyumlu erişilebilir iletişim kutusu:
 * - role="dialog" + aria-modal="true" + aria-labelledby
 * - Escape tuşu ile kapanma
 * - Açıldığında ilk odaklanabilir öğeye geçiş, kapandığında önceki öğeye odak iadesi
 * - Tab / Shift+Tab odak tuzağı (focus trap)
 */
export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  maxWidthClass = 'max-w-lg',
}: ModalProps) {
  const titleId = useId();
  const descId = useId();
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    if (!open) return;

    previousFocusRef.current = document.activeElement as HTMLElement | null;

    const dialogEl = dialogRef.current;
    if (dialogEl) {
      const focusable = getFocusableElements(dialogEl);
      (focusable[0] ?? dialogEl).focus();
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onCloseRef.current();
        return;
      }

      if (event.key === 'Tab' && dialogRef.current) {
        const focusable = getFocusableElements(dialogRef.current);
        if (focusable.length === 0) {
          event.preventDefault();
          return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement as HTMLElement | null;

        if (event.shiftKey && active === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && active === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      previousFocusRef.current?.focus?.();
    };
  }, [open]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-[2px]"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className={cn(
          'w-full rounded-none border border-[#0a0a0a] bg-white text-[#0a0a0a]',
          maxWidthClass
        )}
      >
        <div className="flex items-start justify-between gap-3 border-b border-[#e5e5e5] px-5 py-4">
          <div>
            <h2 id={titleId} className="text-base font-semibold tracking-tight text-[#0a0a0a]">
              {title}
            </h2>
            {description && (
              <p id={descId} className="mt-1 text-xs text-[#737373]">
                {description}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Pencereyi kapat"
            className="rounded-none p-1 text-[#737373] hover:bg-[#f5f5f5] hover:text-[#0a0a0a]"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>

        <div className="max-h-[75vh] overflow-y-auto px-5 py-4">{children}</div>

        {footer && (
          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-[#e5e5e5] bg-[#f5f5f5]/50 px-5 py-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}

function getFocusableElements(root: HTMLElement): HTMLElement[] {
  const selector =
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
  return Array.from(root.querySelectorAll<HTMLElement>(selector));
}

export interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  variant?: 'danger' | 'warning' | 'primary';
  loading?: boolean;
  children?: React.ReactNode;
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel,
  cancelLabel = 'Vazgeç',
  variant = 'danger',
  loading = false,
  children,
}: ConfirmDialogProps) {
  return (
    <Modal
      open={open}
      onClose={loading ? () => undefined : onClose}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button variant={variant} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children ?? (
        <p className="text-xs leading-relaxed text-[#525252]">
          Bu işlem sunucuya mutasyon isteği gönderir. Devam etmek istediğinizden emin misiniz?
        </p>
      )}
    </Modal>
  );
}

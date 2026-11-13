import React, { useId } from 'react';
import { AlertCircle } from 'lucide-react';
import { cn } from './cn';

export interface FieldGroupProps {
  label: string;
  htmlFor?: string;
  hint?: string;
  error?: string | null;
  required?: boolean;
  rightAddon?: React.ReactNode;
  children: (id: string) => React.ReactNode;
  className?: string;
}

export function FieldGroup({
  label,
  htmlFor,
  hint,
  error,
  required,
  rightAddon,
  children,
  className,
}: FieldGroupProps) {
  const generatedId = useId();
  const fieldId = htmlFor ?? generatedId;

  return (
    <div className={cn('min-w-0 space-y-1.5', className)}>
      <div className="flex items-center justify-between gap-2">
        <label
          htmlFor={fieldId}
          className="min-w-0 truncate text-[11px] font-semibold tracking-normal text-[#404040]"
          title={label}
        >
          {label}
          {required && <span className="ml-1 text-[#dc2626]">*</span>}
        </label>
        {rightAddon && <div className="shrink-0 text-[11px] font-mono text-[#737373]">{rightAddon}</div>}
      </div>
      {children(fieldId)}
      {error ? (
        <p role="alert" className="flex items-center gap-1.5 text-xs text-[#dc2626]">
          <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span>{error}</span>
        </p>
      ) : hint ? (
        <p className="text-[11px] text-[#737373]">{hint}</p>
      ) : null}
    </div>
  );
}

export interface TextInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  hasError?: boolean;
  suffix?: string;
}

export const TextInput = React.forwardRef<HTMLInputElement, TextInputProps>(function TextInput(
  { hasError, suffix, className, ...rest },
  ref
) {
  return (
    <div className="relative flex items-center">
      <input
        ref={ref}
        className={cn(
          'h-9 w-full rounded-none border bg-[#f5f5f5] px-3 text-sm text-[#0a0a0a] placeholder:text-[#737373] transition-colors',
          hasError
            ? 'border-[#dc2626] focus:border-[#dc2626]'
            : 'border-[#e5e5e5] hover:border-[#a3a3a3] focus:border-[#0a0a0a] focus:bg-white',
          suffix && 'pr-14',
          className
        )}
        aria-invalid={hasError || undefined}
        {...rest}
      />
      {suffix && (
        <span className="pointer-events-none absolute right-3 text-xs font-mono text-[#737373]">
          {suffix}
        </span>
      )}
    </div>
  );
});

export interface DecimalInputProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type' | 'onChange' | 'value'> {
  value: string;
  onChange: (rawValue: string) => void;
  suffix?: string;
  hasError?: boolean;
}

/**
 * Finansal değer girişi ham string olarak korunur; tarayıcının type="number" ok tuşu
 * veya kayan nokta yuvarlama hatalarına maruz bırakılmaz.
 */
export function DecimalInput({
  value,
  onChange,
  suffix,
  hasError,
  className,
  ...rest
}: DecimalInputProps) {
  return (
    <TextInput
      type="text"
      inputMode="decimal"
      autoComplete="off"
      spellCheck={false}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      suffix={suffix}
      hasError={hasError}
      className={cn('font-mono tabular-nums', className)}
      {...rest}
    />
  );
}

export interface SelectInputProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  hasError?: boolean;
}

export function SelectInput({ hasError, className, children, ...rest }: SelectInputProps) {
  return (
    <select
      className={cn(
        'h-9 w-full rounded-none border bg-[#f5f5f5] px-3 text-sm text-[#0a0a0a] transition-colors',
        hasError
          ? 'border-[#dc2626] focus:border-[#dc2626]'
          : 'border-[#e5e5e5] hover:border-[#a3a3a3] focus:border-[#0a0a0a] focus:bg-white',
        className
      )}
      aria-invalid={hasError || undefined}
      {...rest}
    >
      {children}
    </select>
  );
}

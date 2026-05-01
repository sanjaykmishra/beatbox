import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

export function Eyebrow({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`eyebrow ${className}`}>{children}</div>;
}

/**
 * Banner-shaped notification for things the user must see — backend rejections, validation
 * errors, non-blocking concerns, or page-level guidance. Persistent (caller decides when to
 * dismiss) — for transient confirmations like "Saved." use the toast pattern in {@code Toast.tsx}.
 *
 * Variant guidance:
 *   danger  — backend rejected / validation failed / save failed
 *   warning — non-blocking concern (trial ending, grandfathered pricing, billing cancelled)
 *   success — rarely a banner; prefer toast for confirmations
 *   info    — page-level guidance / hint
 */
export type AlertTone = 'danger' | 'warning' | 'success' | 'info';

const ALERT_CLASSES: Record<AlertTone, { wrap: string; title: string; body: string; icon: string }> =
  {
    danger: {
      wrap: 'bg-red-50 border-red-200',
      title: 'text-red-900',
      body: 'text-red-700',
      icon: 'text-red-500',
    },
    warning: {
      wrap: 'bg-amber-50 border-amber-200',
      title: 'text-amber-900',
      body: 'text-amber-800',
      icon: 'text-amber-500',
    },
    success: {
      wrap: 'bg-emerald-50 border-emerald-200',
      title: 'text-emerald-900',
      body: 'text-emerald-800',
      icon: 'text-emerald-500',
    },
    info: {
      wrap: 'bg-blue-50 border-blue-200',
      title: 'text-blue-900',
      body: 'text-blue-800',
      icon: 'text-blue-500',
    },
  };

const ALERT_ICONS: Record<AlertTone, string> = {
  danger: '⚠',
  warning: '⚠',
  success: '✓',
  info: 'ℹ',
};

export function Alert({
  tone = 'danger',
  title,
  children,
  onDismiss,
  action,
  className = '',
}: {
  tone?: AlertTone;
  title?: string;
  children?: ReactNode;
  onDismiss?: () => void;
  action?: { label: string; onClick: () => void };
  className?: string;
}) {
  const c = ALERT_CLASSES[tone];
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={`rounded-lg border px-4 py-3 flex items-start gap-3 ${c.wrap} ${className}`}
    >
      <span
        aria-hidden
        className={`text-base leading-tight flex-none mt-0.5 ${c.icon}`}
      >
        {ALERT_ICONS[tone]}
      </span>
      <div className="flex-1 min-w-0">
        {title && <p className={`text-sm font-semibold ${c.title}`}>{title}</p>}
        {children && (
          <p className={`text-sm ${c.body} ${title ? 'mt-0.5' : ''}`}>{children}</p>
        )}
      </div>
      {action && (
        <button
          onClick={action.onClick}
          className={`text-sm font-medium ${c.title} hover:underline flex-none`}
        >
          {action.label}
        </button>
      )}
      {onDismiss && (
        <button
          onClick={onDismiss}
          aria-label="Dismiss"
          className="text-gray-400 hover:text-gray-700 flex-none text-lg leading-none"
        >
          ×
        </button>
      )}
    </div>
  );
}

export type PillTone = 'red' | 'amber' | 'blue' | 'green' | 'gray';

const PILL_CLASSES: Record<PillTone, string> = {
  red: 'bg-red-100 text-red-700',
  amber: 'bg-amber-100 text-amber-800',
  blue: 'bg-blue-100 text-blue-700',
  green: 'bg-emerald-100 text-emerald-800',
  gray: 'bg-gray-100 text-gray-600',
};

export function Pill({
  tone = 'gray',
  children,
  title,
  className = '',
}: {
  tone?: PillTone;
  children: ReactNode;
  title?: string;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full text-[11px] font-medium px-2 py-0.5 ${PILL_CLASSES[tone]} ${className}`}
      title={title}
    >
      {children}
    </span>
  );
}

type BtnProps = ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode };

export const PrimaryButton = forwardRef<HTMLButtonElement, BtnProps>(function PrimaryButton(
  { children, className = '', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      {...rest}
      className={`ink-btn rounded-lg text-white px-4 py-2.5 text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${className}`}
    >
      {children}
    </button>
  );
});

export const SecondaryButton = forwardRef<HTMLButtonElement, BtnProps>(function SecondaryButton(
  { children, className = '', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      {...rest}
      className={`rounded-lg border border-gray-300 bg-white text-gray-700 px-3 py-2 text-sm font-medium hover:border-gray-400 hover:bg-gray-50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${className}`}
    >
      {children}
    </button>
  );
});

export function PrimaryLink({
  to,
  children,
  className = '',
}: {
  to: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Link
      to={to}
      className={`ink-btn rounded-lg text-white px-4 py-2.5 text-sm font-medium transition-colors inline-flex items-center ${className}`}
    >
      {children}
    </Link>
  );
}

export function SecondaryLink({
  to,
  children,
  className = '',
}: {
  to: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Link
      to={to}
      className={`rounded-lg border border-gray-300 bg-white text-gray-700 px-3 py-2 text-sm font-medium hover:border-gray-400 hover:bg-gray-50 transition-colors inline-flex items-center ${className}`}
    >
      {children}
    </Link>
  );
}

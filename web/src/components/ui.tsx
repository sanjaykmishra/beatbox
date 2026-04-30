import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

export function Eyebrow({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`eyebrow ${className}`}>{children}</div>;
}

export type PillTone = 'red' | 'amber' | 'blue' | 'green' | 'gray';

const PILL_CLASSES: Record<PillTone, string> = {
  red: 'bg-red-50 text-red-700',
  amber: 'bg-amber-50 text-amber-800',
  blue: 'bg-blue-50 text-blue-700',
  green: 'bg-emerald-50 text-emerald-700',
  gray: 'bg-gray-100 text-gray-700',
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

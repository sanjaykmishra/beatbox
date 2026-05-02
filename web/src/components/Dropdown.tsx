import {
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Link } from 'react-router-dom';

/**
 * Lightweight dropdown menu — a labeled button that opens a menu of items below it. Click outside
 * or press Escape to close. Each item is either a {@link Link} (internal navigation) or a button
 * (custom click handler).
 *
 * Styled to match {@link PrimaryLink} so it can sit alongside the workspace's existing CTAs
 * without looking out of place.
 */

export type DropdownItem = {
  label: string;
  to?: string;
  onClick?: () => void;
};

export function Dropdown({
  label,
  items,
  className = '',
}: {
  label: string;
  items: DropdownItem[];
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const id = useId();

  useEffect(() => {
    if (!open) return;
    function onDocMouseDown(e: MouseEvent) {
      if (!rootRef.current) return;
      if (!rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={rootRef} className={`relative inline-block ${className}`}>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((o) => !o)}
        className="ink-btn rounded-lg text-white px-4 py-2.5 text-sm font-medium transition-colors inline-flex items-center gap-1.5"
      >
        {label}
        <span aria-hidden className="text-xs opacity-80">▾</span>
      </button>
      {open && (
        <div
          id={id}
          role="menu"
          className="absolute left-0 top-full mt-1 min-w-[180px] bg-white border border-gray-200 rounded-lg shadow-lg py-1 z-50"
        >
          {items.map((it, i) => (
            <DropdownRow key={i} item={it} onSelect={() => setOpen(false)} />
          ))}
        </div>
      )}
    </div>
  );
}

function DropdownRow({ item, onSelect }: { item: DropdownItem; onSelect: () => void }) {
  const cls =
    'block px-3 py-2 text-sm text-gray-700 hover:bg-gray-50 hover:text-ink rounded-md mx-1';
  if (item.to) {
    return (
      <Link role="menuitem" to={item.to} className={cls} onClick={onSelect}>
        {item.label}
      </Link>
    );
  }
  return (
    <button
      type="button"
      role="menuitem"
      onClick={() => {
        item.onClick?.();
        onSelect();
      }}
      className={`w-full text-left ${cls}`}
    >
      {item.label}
    </button>
  );
}

export function MenuItem({
  children,
  to,
  onClick,
}: {
  children: ReactNode;
  to?: string;
  onClick?: () => void;
}) {
  // Convenience component for callers building dropdowns inline rather than passing the items
  // array. Not used by the basic Dropdown above; kept here for future surfaces.
  if (to) return <Link to={to}>{children}</Link>;
  return (
    <button type="button" onClick={onClick}>
      {children}
    </button>
  );
}

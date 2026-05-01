import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import type { AlertTone } from './ui';

/**
 * Transient confirmations / notifications. Use this for things the user just did successfully
 * (`success`) or short heads-ups (`info`); use {@link Alert} for persistent banners that need
 * action.
 *
 * <p>Mount {@link ToastProvider} at the application root once, then call {@link useToast} from
 * any component:
 *
 * <pre>
 *   const toast = useToast();
 *   toast.success('Saved.');
 *   toast.error('Couldn\'t save — try again.');
 * </pre>
 *
 * <p>Toasts stack top-right, slide in, auto-dismiss after the configured TTL (default 3.5s).
 */
type ToastEntry = {
  id: number;
  tone: AlertTone;
  title?: string;
  body?: string;
  ttlMs: number;
};

type ToastApi = {
  show: (t: { tone?: AlertTone; title?: string; body?: string; ttlMs?: number }) => void;
  success: (body: string, title?: string) => void;
  info: (body: string, title?: string) => void;
  warning: (body: string, title?: string) => void;
  error: (body: string, title?: string) => void;
};

const ToastContext = createContext<ToastApi | null>(null);

const DEFAULT_TTL_MS = 3500;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastEntry[]>([]);
  // Track timers so unmount/dismiss cleans up.
  const timers = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id);
    if (timer) clearTimeout(timer);
    timers.current.delete(id);
    setItems((cur) => cur.filter((t) => t.id !== id));
  }, []);

  const api = useMemo<ToastApi>(() => {
    function show(t: { tone?: AlertTone; title?: string; body?: string; ttlMs?: number }) {
      const id = Date.now() + Math.floor(Math.random() * 1000);
      const entry: ToastEntry = {
        id,
        tone: t.tone ?? 'info',
        title: t.title,
        body: t.body,
        ttlMs: t.ttlMs ?? DEFAULT_TTL_MS,
      };
      setItems((cur) => [...cur, entry]);
      const timer = setTimeout(() => dismiss(id), entry.ttlMs);
      timers.current.set(id, timer);
    }
    return {
      show,
      success: (body, title) => show({ tone: 'success', body, title }),
      info: (body, title) => show({ tone: 'info', body, title }),
      warning: (body, title) => show({ tone: 'warning', body, title }),
      error: (body, title) => show({ tone: 'danger', body, title, ttlMs: 6000 }),
    };
  }, [dismiss]);

  // Clean up any pending timers on unmount.
  useEffect(() => {
    const map = timers.current;
    return () => {
      map.forEach((t) => clearTimeout(t));
      map.clear();
    };
  }, []);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastStack items={items} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error('useToast() called outside <ToastProvider>');
  }
  return ctx;
}

const TONE_CLASSES: Record<AlertTone, { wrap: string; icon: string; title: string; body: string }> =
  {
    danger: {
      wrap: 'bg-white border-red-200 shadow-lg',
      icon: 'text-red-500',
      title: 'text-red-900',
      body: 'text-red-700',
    },
    warning: {
      wrap: 'bg-white border-amber-200 shadow-lg',
      icon: 'text-amber-500',
      title: 'text-amber-900',
      body: 'text-amber-800',
    },
    success: {
      wrap: 'bg-white border-emerald-200 shadow-lg',
      icon: 'text-emerald-500',
      title: 'text-emerald-900',
      body: 'text-emerald-800',
    },
    info: {
      wrap: 'bg-white border-blue-200 shadow-lg',
      icon: 'text-blue-500',
      title: 'text-blue-900',
      body: 'text-blue-800',
    },
  };

const ICONS: Record<AlertTone, string> = {
  danger: '⚠',
  warning: '⚠',
  success: '✓',
  info: 'ℹ',
};

function ToastStack({
  items,
  onDismiss,
}: {
  items: ToastEntry[];
  onDismiss: (id: number) => void;
}) {
  if (typeof document === 'undefined') return null;
  return createPortal(
    <div
      aria-live="polite"
      aria-atomic="true"
      className="fixed top-4 right-4 z-[1000] flex flex-col gap-2 pointer-events-none"
    >
      {items.map((t) => {
        const c = TONE_CLASSES[t.tone];
        return (
          <div
            key={t.id}
            role={t.tone === 'danger' ? 'alert' : 'status'}
            className={`pointer-events-auto rounded-lg border ${c.wrap} px-4 py-3 min-w-[260px] max-w-sm flex items-start gap-3 toast-enter`}
          >
            <span aria-hidden className={`text-base leading-tight flex-none mt-0.5 ${c.icon}`}>
              {ICONS[t.tone]}
            </span>
            <div className="flex-1 min-w-0">
              {t.title && <p className={`text-sm font-semibold ${c.title}`}>{t.title}</p>}
              {t.body && (
                <p className={`text-sm ${c.body} ${t.title ? 'mt-0.5' : ''}`}>{t.body}</p>
              )}
            </div>
            <button
              onClick={() => onDismiss(t.id)}
              aria-label="Dismiss"
              className="text-gray-400 hover:text-gray-700 flex-none text-lg leading-none"
            >
              ×
            </button>
          </div>
        );
      })}
    </div>,
    document.body,
  );
}

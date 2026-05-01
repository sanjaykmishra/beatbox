import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';

/**
 * Modal confirmation dialog. Drop-in replacement for the native {@code window.confirm()}, which
 * renders an unstyled, browser-chrome dialog that breaks the product's visual identity.
 *
 * Mount {@link ConfirmDialogProvider} once at the app root, then call {@link useConfirm} from any
 * component:
 *
 * <pre>
 *   const confirm = useConfirm();
 *   if (!(await confirm({ title: 'Remove this item?', tone: 'danger' }))) return;
 *   remove.mutate();
 * </pre>
 *
 * Resolves true on confirm, false on cancel / Esc / click-outside.
 */

export type ConfirmTone = 'danger' | 'default';

type ConfirmOptions = {
  title: string;
  body?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: ConfirmTone;
};

type PendingConfirm = ConfirmOptions & { resolve: (ok: boolean) => void };

const ConfirmContext = createContext<((opts: ConfirmOptions) => Promise<boolean>) | null>(null);

export function ConfirmDialogProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<PendingConfirm | null>(null);

  const confirm = useCallback((opts: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      setPending({ ...opts, resolve });
    });
  }, []);

  const finish = useCallback(
    (ok: boolean) => {
      if (!pending) return;
      pending.resolve(ok);
      setPending(null);
    },
    [pending],
  );

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {pending && <ConfirmDialog pending={pending} onResult={finish} />}
    </ConfirmContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useConfirm() {
  const ctx = useContext(ConfirmContext);
  if (!ctx) {
    throw new Error('useConfirm() called outside <ConfirmDialogProvider>');
  }
  return ctx;
}

function ConfirmDialog({
  pending,
  onResult,
}: {
  pending: PendingConfirm;
  onResult: (ok: boolean) => void;
}) {
  const tone = pending.tone ?? 'default';
  const confirmBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    confirmBtnRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onResult(false);
      } else if (e.key === 'Enter') {
        e.preventDefault();
        onResult(true);
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onResult]);

  const confirmBtnClass =
    tone === 'danger'
      ? 'bg-red-600 hover:bg-red-700 text-white'
      : 'bg-gray-900 hover:bg-black text-white';

  if (typeof document === 'undefined') return null;
  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      className="fixed inset-0 z-[1100] flex items-center justify-center p-4 bg-black/40"
      onMouseDown={(e) => {
        // Click outside the panel cancels.
        if (e.target === e.currentTarget) onResult(false);
      }}
    >
      <div className="bg-white rounded-lg shadow-xl border border-gray-200 max-w-sm w-full p-5">
        <h2 id="confirm-dialog-title" className="text-base font-semibold text-gray-900">
          {pending.title}
        </h2>
        {pending.body && (
          <div className="mt-2 text-sm text-gray-600">{pending.body}</div>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={() => onResult(false)}
            className="px-3 py-1.5 text-sm font-medium rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50"
          >
            {pending.cancelLabel ?? 'Cancel'}
          </button>
          <button
            ref={confirmBtnRef}
            type="button"
            onClick={() => onResult(true)}
            className={`px-3 py-1.5 text-sm font-medium rounded-md ${confirmBtnClass}`}
          >
            {pending.confirmLabel ?? (tone === 'danger' ? 'Delete' : 'Confirm')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

export type Crumb = { label: string; to?: string };

export function BrowserFrame({
  crumbs,
  rightSlot,
  children,
}: {
  crumbs: Crumb[];
  rightSlot?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
      <div className="flex items-center gap-3 px-4 h-10 bg-gray-50 border-b border-gray-200">
        <div className="flex items-center gap-1.5 flex-none">
          <span className="h-3 w-3 rounded-full bg-gray-300" aria-hidden />
          <span className="h-3 w-3 rounded-full bg-gray-300" aria-hidden />
          <span className="h-3 w-3 rounded-full bg-gray-300" aria-hidden />
        </div>
        <div className="flex-1 min-w-0 font-mono text-[12px] text-gray-500 truncate">
          {crumbs.map((c, i) => {
            const isLast = i === crumbs.length - 1;
            const linked = !isLast && c.to;
            return (
              <span key={i}>
                {i > 0 && <span className="mx-1.5 text-gray-300">/</span>}
                {linked ? (
                  <Link to={c.to!} className="hover:text-gray-900 hover:underline">
                    {c.label}
                  </Link>
                ) : (
                  <span className={isLast ? 'text-gray-700' : ''}>{c.label}</span>
                )}
              </span>
            );
          })}
        </div>
        {rightSlot && <div className="flex items-center gap-2 flex-none">{rightSlot}</div>}
      </div>
      <div className="p-8">{children}</div>
    </div>
  );
}

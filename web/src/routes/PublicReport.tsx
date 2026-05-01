import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

/**
 * Unauthenticated landing for shared report links — `/r/:token`. The API endpoint
 * `/v1/public/reports/:token` returns a fully-rendered HTML report (or a generic 404 body when the
 * token is missing/expired/revoked). We fetch it and inject the body into a sandboxed iframe so
 * the rendered styles don't collide with the SPA shell, and external visitors see the report
 * without bouncing through auth.
 */
export function PublicReport() {
  const { token = '' } = useParams();
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(`/v1/public/reports/${encodeURIComponent(token)}`)
      .then((r) => {
        if (!r.ok) {
          if (!cancelled) setError(true);
          return null;
        }
        return r.text();
      })
      .then((text) => {
        if (!cancelled && text != null) setHtml(text);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  if (error) return <PublicLinkUnavailable />;
  if (html == null) {
    return (
      <div className="min-h-screen flex items-center justify-center text-sm text-gray-500">
        Loading…
      </div>
    );
  }
  return (
    <iframe
      title="Report"
      srcDoc={html}
      sandbox="allow-same-origin"
      className="w-full h-screen border-0"
    />
  );
}

/** Public-facing 404. No "back to clients" CTA — visitors here aren't agency users. */
export function PublicLinkUnavailable() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center text-center p-12 bg-gray-50">
      <div className="text-2xl font-semibold tracking-tightish text-ink">
        This link is no longer available.
      </div>
      <p className="mt-3 text-sm text-gray-600 max-w-md">
        If you were expecting to see a report, ask the sender for an updated link.
      </p>
    </div>
  );
}

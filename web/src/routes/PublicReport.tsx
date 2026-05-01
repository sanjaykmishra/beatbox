import { useState } from 'react';
import { useParams } from 'react-router-dom';

/**
 * Unauthenticated landing for shared report links — `/r/:token`. The API endpoint
 * `/v1/public/reports/:token` returns a fully-rendered HTML report (or a generic 404 body when the
 * token is missing/expired/revoked), and we point an iframe directly at it.
 *
 * <p>We use iframe {@code src=} (not {@code srcDoc=}) so the iframe document's base origin is the
 * same as the API host. This is required because the rendered HTML contains relative URLs for
 * screenshots ({@code /v1/screenshots/...}); under {@code srcDoc} those resolve against
 * {@code about:srcdoc} and 404. The SPA URL stays as {@code /r/:token} — only the iframe
 * navigates internally.
 */
export function PublicReport() {
  const { token = '' } = useParams();
  const [loaded, setLoaded] = useState(false);

  return (
    <div className="relative min-h-screen">
      {!loaded && (
        <div className="absolute inset-0 flex items-center justify-center text-sm text-gray-500 pointer-events-none">
          Loading…
        </div>
      )}
      <iframe
        title="Report"
        src={`/v1/public/reports/${encodeURIComponent(token)}`}
        onLoad={() => setLoaded(true)}
        className="w-full h-screen border-0"
      />
    </div>
  );
}

/**
 * Inject a {@code <base href>} into rendered HTML so relative URLs (notably
 * {@code /v1/screenshots/...}) resolve to the SPA origin instead of {@code about:srcdoc}.
 * Browsers honor base inside srcDoc; resources then load from the same origin that proxies
 * /v1/* to the API.
 *
 * <p>Used by every iframe that renders server-produced report HTML via {@code srcDoc}
 * (ClientReports past-reports preview, ReportPreview post-Generate preview). Without it,
 * screenshot images render as broken — the report ships with relative paths like
 * {@code <img src="/v1/screenshots/{ws}/{file}.png">}, and an about:srcdoc base doesn't
 * resolve those to anything fetchable.
 *
 * <p>Inserts immediately after the opening {@code <head>} tag if present; otherwise prepends.
 */
export function injectBase(html: string, baseUrl: string): string {
  const tag = `<base href="${baseUrl.replace(/"/g, '&quot;')}/">`;
  const headOpen = /<head\b[^>]*>/i.exec(html);
  if (headOpen) {
    const at = headOpen.index + headOpen[0].length;
    return html.slice(0, at) + tag + html.slice(at);
  }
  return tag + html;
}

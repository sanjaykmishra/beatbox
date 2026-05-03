// Shared URL parser used by NewReport (initial creation) and ReportReview (the
// "+ Add URLs" modal that lets the user keep adding URLs after the report exists).
// Keeping a single implementation matters: the backend's CoverageController.normalize
// rejects anything not http(s)://, so both UI surfaces auto-prepend "https://" and
// dedupe before submitting.
export function parseUrls(raw: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const piece of raw.split(/[\s,]+/)) {
    let u = piece.trim();
    if (!u) continue;
    if (!/^https?:\/\//i.test(u)) u = `https://${u}`;
    try {
      new URL(u);
    } catch {
      continue;
    }
    if (!seen.has(u)) {
      seen.add(u);
      out.push(u);
    }
  }
  return out;
}

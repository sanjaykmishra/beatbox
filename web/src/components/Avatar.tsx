// Initials-and-color avatar for clients/workspaces. Uses the entity's brand color when set;
// otherwise picks a deterministic muted color from the name so each row stays visually distinct.

// Tailwind ~100 background + ~900 text — pastel pairs that match the wireframe avatars.
// Agencies who set primary_color get the saturated brand path below.
const PALETTE: { bg: string; fg: string }[] = [
  { bg: '#FEE2E2', fg: '#7F1D1D' }, // red
  { bg: '#FEF3C7', fg: '#78350F' }, // amber
  { bg: '#DBEAFE', fg: '#1E3A8A' }, // blue
  { bg: '#DCFCE7', fg: '#14532D' }, // green
  { bg: '#E0E7FF', fg: '#312E81' }, // indigo
  { bg: '#FCE7F3', fg: '#831843' }, // pink
  { bg: '#EDE9FE', fg: '#4C1D95' }, // violet
  { bg: '#CCFBF1', fg: '#134E4A' }, // teal
];

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h;
}

function pickColor(seed: string): { bg: string; fg: string } {
  return PALETTE[hash(seed) % PALETTE.length];
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '·';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

const SIZE_CLASSES: Record<string, string> = {
  sm: 'h-8 w-8 text-xs',
  md: 'h-10 w-10 text-sm',
  lg: 'h-14 w-14 text-base',
};

export function Avatar({
  name,
  logoUrl,
  primaryColor,
  size = 'md',
}: {
  name: string;
  logoUrl?: string | null;
  primaryColor?: string | null;
  size?: 'sm' | 'md' | 'lg';
}) {
  const cls = SIZE_CLASSES[size] ?? SIZE_CLASSES.md;
  if (logoUrl) {
    return <img src={logoUrl} alt="" className={`${cls} rounded-lg object-cover flex-none`} />;
  }
  if (primaryColor) {
    return (
      <div
        className={`${cls} rounded-lg flex-none flex items-center justify-center font-semibold text-white tracking-tight`}
        style={{ background: `#${primaryColor}` }}
        aria-hidden
      >
        {initials(name)}
      </div>
    );
  }
  const { bg, fg } = pickColor(name);
  return (
    <div
      className={`${cls} rounded-lg flex-none flex items-center justify-center font-semibold tracking-tight`}
      style={{ background: bg, color: fg }}
      aria-hidden
    >
      {initials(name)}
    </div>
  );
}

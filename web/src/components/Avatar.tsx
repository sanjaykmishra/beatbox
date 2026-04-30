// Initials-and-color avatar for clients/workspaces. Uses the entity's brand color when set;
// otherwise picks a deterministic muted color from the name so each row stays visually distinct.

// Tailwind ~200 background + ~800 text — saturated enough to read distinctly without
// fighting the muted page palette. Agencies who set primary_color use the brand path below.
const PALETTE: { bg: string; fg: string }[] = [
  { bg: '#FECACA', fg: '#991B1B' }, // red
  { bg: '#FDE68A', fg: '#92400E' }, // amber
  { bg: '#BFDBFE', fg: '#1E40AF' }, // blue
  { bg: '#A7F3D0', fg: '#166534' }, // emerald
  { bg: '#C7D2FE', fg: '#3730A3' }, // indigo
  { bg: '#FBCFE8', fg: '#9D174D' }, // pink
  { bg: '#DDD6FE', fg: '#5B21B6' }, // violet
  { bg: '#99F6E4', fg: '#115E59' }, // teal
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

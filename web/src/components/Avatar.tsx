// Initials-and-color avatar for clients/workspaces. Uses the entity's brand color when set;
// otherwise picks a deterministic muted color from the name so each row stays visually distinct.

const PALETTE = [
  '#1F2937', // slate
  '#4F46E5', // indigo
  '#0891B2', // cyan
  '#059669', // emerald
  '#B45309', // amber
  '#BE123C', // rose
  '#7C3AED', // violet
  '#0F766E', // teal
];

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h;
}

function pickColor(seed: string): string {
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
  const bg = primaryColor ? `#${primaryColor}` : pickColor(name);
  return (
    <div
      className={`${cls} rounded-lg flex-none flex items-center justify-center font-semibold text-white tracking-tight`}
      style={{ background: bg }}
      aria-hidden
    >
      {initials(name)}
    </div>
  );
}

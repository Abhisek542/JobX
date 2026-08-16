/**
 * Company "logos" — initials on a deterministic hue, ported from the mockup's
 * logoStyle()/logoText().
 *
 * HONESTY (frontend_constraints.md §12): no backend field returns a logo URL and
 * we deliberately do not call an external logo service, so the same company
 * always gets the same colour derived from its name and nothing is fetched.
 */
export function logoStyle(name: string): string {
  let h = 0;
  for (const ch of name ?? '') h = (h * 31 + ch.charCodeAt(0)) % 360;
  return `background:linear-gradient(148deg,hsl(${h} 62% 52%),hsl(${(h + 26) % 360} 68% 42%))`;
}

export function logoText(name: string): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  const raw = parts.length > 1 ? parts[0][0] + parts[1][0] : parts[0].slice(0, 2);
  return raw.toUpperCase();
}

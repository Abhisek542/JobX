/**
 * AuthResponse carries an email and nothing else — no display name, no role
 * (frontend_constraints.md §5). Everything the UI shows as "who you are" is
 * derived from the local-part here, in one place, so it can't drift.
 */
export function displayName(email: string): string {
  const local = (email ?? '').split('@')[0]?.split(/[._-]/)[0] ?? '';
  if (!local) return 'You';
  return local.charAt(0).toUpperCase() + local.slice(1);
}

export function initials(email: string): string {
  const parts = (email ?? '').split('@')[0]?.split(/[._-]/).filter(Boolean) ?? [];
  if (!parts.length) return '?';
  return parts
    .slice(0, 2)
    .map((p) => p.charAt(0).toUpperCase())
    .join('');
}

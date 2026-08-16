/** Mirrors com.jobx.dto.AuthResponse. No display name and no role
 *  (frontend_constraints.md §5, §15) — the sidebar name and avatar initials are
 *  derived from the email local-part. */
export interface AuthResponse {
  token: string;
  expiresAt: string;
  userId: string;
  email: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

/** What we keep in localStorage between reloads. */
export interface Session {
  token: string;
  expiresAt: string;
  userId: string;
  email: string;
}

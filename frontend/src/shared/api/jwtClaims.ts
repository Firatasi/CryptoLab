import type { UserAuthority, UserRole } from '@/shared/contracts/types';

export interface DecodedJwtClaims {
  sub?: string;
  roles?: string[];
  authorities?: string[];
  exp?: number;
}

const KNOWN_ROLES: ReadonlySet<string> = new Set(['USER', 'TRAINER', 'ADMIN']);

/**
 * Bellek içi accessToken'ın payload bölümünü YALNIZ arayüz görünürlüğü (örn. MODEL_PUBLISH)
 * amacıyla çözümler. İmza doğrulaması ve gerçek güvenlik kararı her zaman backend'dedir.
 */
export function decodeJwtClaimsUnsafe(token: string | null): DecodedJwtClaims | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const base64Url = parts[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
    const jsonText = atob(padded);
    const parsed: unknown = JSON.parse(jsonText);
    if (!parsed || typeof parsed !== 'object') return null;
    return parsed as DecodedJwtClaims;
  } catch {
    return null;
  }
}

export function normalizeRoles(rawRoles: readonly string[] | undefined): UserRole[] {
  if (!rawRoles) return [];
  const result: UserRole[] = [];
  for (const role of rawRoles) {
    const cleaned = role.startsWith('ROLE_') ? role.slice(5) : role;
    if (KNOWN_ROLES.has(cleaned) && !result.includes(cleaned as UserRole)) {
      result.push(cleaned as UserRole);
    }
  }
  return result;
}

export function normalizeAuthorities(rawAuthorities: readonly string[] | undefined): UserAuthority[] {
  if (!rawAuthorities) return [];
  return Array.from(new Set(rawAuthorities.filter((a) => typeof a === 'string' && a.length > 0)));
}


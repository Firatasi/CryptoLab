import { useCallback, useEffect, useMemo, useState } from 'react';
import { useAuth, type AuthContextValue } from '@/features/auth/AuthContext';

const FAVORITES_PREFIX = 'cryptolab.favorites.';
const FAVORITES_EVENT = 'cryptolab:favorites-changed';

function normalizeSymbol(symbol: string | null | undefined): string {
  return (symbol ?? '').trim().toUpperCase();
}

function loadFavoritesForUser(userId: string | null | undefined): string[] {
  if (!userId || typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(`${FAVORITES_PREFIX}${userId}`);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const seen = new Set<string>();
    const result: string[] = [];
    for (const entry of parsed) {
      if (typeof entry !== 'string') continue;
      const normalized = normalizeSymbol(entry);
      if (normalized && !seen.has(normalized)) {
        seen.add(normalized);
        result.push(normalized);
      }
    }
    return result;
  } catch {
    return [];
  }
}

function saveFavoritesForUser(userId: string, symbols: string[]): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(`${FAVORITES_PREFIX}${userId}`, JSON.stringify(symbols));
    window.dispatchEvent(
      new CustomEvent(FAVORITES_EVENT, {
        detail: { userId, favorites: symbols },
      }),
    );
  } catch {
    // Storage kapalıysa bile çalışma belleğinde kalmaya devam eder
  }
}

/**
 * Giriş yapmış kullanıcının favori (yıldızlı) sembollerini kullanıcı ID'sine izole biçimde saklar
 * ve mevcut sıralamayı bozmadan yalnız favorileri listenin en üstüne taşır.
 */
export function useFavoriteInstruments() {
  let auth: AuthContextValue | null = null;
  try {
    // useAuth icindeki tek hook (useContext) kosulsuz calisir; AuthProvider disindaki izole testlerde guvenle null'a duser.
    auth = useAuth();
  } catch {
    auth = null;
  }
  const userId = auth?.user?.id ?? null;
  const canFavorite = Boolean(userId);

  const [favorites, setFavorites] = useState<string[]>(() => loadFavoritesForUser(userId));

  useEffect(() => {
    setFavorites(loadFavoritesForUser(userId));
  }, [userId]);

  useEffect(() => {
    if (!userId || typeof window === 'undefined') return;

    const handleCustomSync = (evt: Event) => {
      const detail = (evt as CustomEvent<{ userId?: string; favorites?: string[] }>).detail;
      if (detail?.userId === userId && Array.isArray(detail.favorites)) {
        setFavorites(detail.favorites);
      }
    };

    const handleStorageSync = (evt: StorageEvent) => {
      if (evt.key === `${FAVORITES_PREFIX}${userId}`) {
        setFavorites(loadFavoritesForUser(userId));
      }
    };

    window.addEventListener(FAVORITES_EVENT, handleCustomSync);
    window.addEventListener('storage', handleStorageSync);
    return () => {
      window.removeEventListener(FAVORITES_EVENT, handleCustomSync);
      window.removeEventListener('storage', handleStorageSync);
    };
  }, [userId]);

  const favoriteSet = useMemo(() => new Set(favorites), [favorites]);

  const isFavorite = useCallback(
    (symbol: string | null | undefined) => {
      if (!canFavorite) return false;
      const normalized = normalizeSymbol(symbol);
      return normalized.length > 0 && favoriteSet.has(normalized);
    },
    [canFavorite, favoriteSet],
  );

  const toggleFavorite = useCallback(
    (symbol: string) => {
      if (!userId) return;
      const normalized = normalizeSymbol(symbol);
      if (!normalized) return;

      setFavorites((prev) => {
        const next = prev.includes(normalized)
          ? prev.filter((item) => item !== normalized)
          : [...prev, normalized];
        saveFavoritesForUser(userId, next);
        return next;
      });
    },
    [userId],
  );

  const partitionByFavorites = useCallback(
    <T extends { symbol: string }>(items: readonly T[]): T[] => {
      if (!canFavorite || favoriteSet.size === 0 || items.length <= 1) {
        return [...items];
      }
      const starred: T[] = [];
      const rest: T[] = [];
      for (const item of items) {
        if (favoriteSet.has(normalizeSymbol(item.symbol))) {
          starred.push(item);
        } else {
          rest.push(item);
        }
      }
      return starred.length > 0 ? [...starred, ...rest] : [...items];
    },
    [canFavorite, favoriteSet],
  );

  return {
    canFavorite,
    favorites,
    isFavorite,
    toggleFavorite,
    partitionByFavorites,
  };
}

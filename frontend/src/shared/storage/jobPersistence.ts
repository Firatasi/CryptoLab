import type { TimezonePreference } from '@/shared/formatting/formatters';

/**
 * GÜVENLİK KURALI:
 * Bu modül ASLA access token, refresh token, parola veya gizli anahtar saklamaz.
 * Yalnızca sayfa yenilendiğinde (F5) uzun süren asenkron işlerin (analiz, tarama, replay, eğitim)
 * takibini sürdürebilmek için kullanıcıya göre izole edilmiş job UUID referanslarını ve saat dilimi
 * tercihini tutar. Oturum kapandığında kullanıcıya ait iş kayıtları temizlenir.
 */

const JOBS_PREFIX = 'cryptolab.jobs.';
const PREFS_KEY = 'cryptolab.ui.preferences';

export interface PersistedUserJobs {
  activeSessionId?: string | null;
  analysisBySession?: Record<string, string>;
  analysisSymbolById?: Record<string, string>;
  scanBySession?: Record<string, string>;
  activeReplayId?: string | null;
  activeTrainingRunId?: string | null;
}

export interface UiPreferences {
  timezone: TimezonePreference;
}

export function loadPersistedJobs(userId: string | null | undefined): PersistedUserJobs {
  if (!userId || typeof window === 'undefined') return {};
  try {
    const raw = window.sessionStorage.getItem(`${JOBS_PREFIX}${userId}`);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as PersistedUserJobs) : {};
  } catch {
    return {};
  }
}

export function savePersistedJobs(
  userId: string | null | undefined,
  updater: (prev: PersistedUserJobs) => PersistedUserJobs
): void {
  if (!userId || typeof window === 'undefined') return;
  try {
    const prev = loadPersistedJobs(userId);
    const next = updater(prev);
    window.sessionStorage.setItem(`${JOBS_PREFIX}${userId}`, JSON.stringify(next));
  } catch {
    // Storage kapalıysa uygulama çalışmaya devam eder
  }
}

export function clearAllPersistedUserJobs(): void {
  if (typeof window === 'undefined') return;
  try {
    const keysToRemove: string[] = [];
    for (let i = 0; i < window.sessionStorage.length; i += 1) {
      const key = window.sessionStorage.key(i);
      if (key && key.startsWith(JOBS_PREFIX)) {
        keysToRemove.push(key);
      }
    }
    for (const key of keysToRemove) {
      window.sessionStorage.removeItem(key);
    }
  } catch {
    // Sessizce geç
  }
}

export function loadUiPreferences(): UiPreferences {
  if (typeof window === 'undefined') return { timezone: 'Europe/Istanbul' };
  try {
    const raw = window.localStorage.getItem(PREFS_KEY);
    if (!raw) return { timezone: 'Europe/Istanbul' };
    const parsed = JSON.parse(raw) as Partial<UiPreferences>;
    return {
      timezone: parsed.timezone === 'UTC' ? 'UTC' : 'Europe/Istanbul',
    };
  } catch {
    return { timezone: 'Europe/Istanbul' };
  }
}

export function saveUiPreferences(prefs: UiPreferences): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch {
    // Sessizce geç
  }
}


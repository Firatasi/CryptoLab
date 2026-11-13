import type { ApiErrorPayload, TokenResponse } from '@/shared/contracts/types';
import { ApiError, toApiErrorFromPayload } from './apiErrors';

/**
 * GÜVENLİK KURALI:
 * Access token YALNIZ JavaScript modül belleğinde (in-memory) tutulur.
 * localStorage, sessionStorage, IndexedDB veya URL query parametresine ASLA yazılmaz.
 * Refresh token HttpOnly + SameSite=Strict çerez olarak tarayıcı tarafından taşınır.
 */
let inMemoryAccessToken: string | null = null;
let singleFlightRefreshPromise: Promise<string | null> | null = null;
let onSessionExpiredCallback: (() => void) | null = null;

export const API_BASE_PATH = '/api/v1';

export function getAccessToken(): string | null {
  return inMemoryAccessToken;
}

export function setAccessToken(token: string | null): void {
  inMemoryAccessToken = token;
}

export function registerSessionExpiredHandler(handler: (() => void) | null): void {
  onSessionExpiredCallback = handler;
}

export interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  idempotencyKey?: string;
  csrf?: boolean;
  skipAuth?: boolean;
  skipRefreshOn401?: boolean;
  signal?: AbortSignal;
  responseType?: 'json' | 'text';
}

/**
 * Eşzamanlı 401 yanıtlarında tek bir /auth/refresh isteği (single-flight) yürütür.
 * AuthController.java: consumes = "application/json", X-CSRF = "1" ve Origin zorunludur.
 */
export async function refreshAccessTokenSingleFlight(): Promise<string | null> {
  if (singleFlightRefreshPromise) {
    return singleFlightRefreshPromise;
  }

  singleFlightRefreshPromise = (async () => {
    try {
      const response = await fetch(`${API_BASE_PATH}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'X-CSRF': '1',
        },
        body: JSON.stringify({}),
      });

      if (!response.ok) {
        inMemoryAccessToken = null;
        return null;
      }

      const data = (await response.json()) as TokenResponse;
      if (!data?.accessToken) {
        inMemoryAccessToken = null;
        return null;
      }

      inMemoryAccessToken = data.accessToken;
      return data.accessToken;
    } catch {
      inMemoryAccessToken = null;
      return null;
    } finally {
      singleFlightRefreshPromise = null;
    }
  })();

  return singleFlightRefreshPromise;
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const normalizedPath = path.startsWith('/api/')
    ? path
    : `${API_BASE_PATH}${path.startsWith('/') ? path : `/${path}`}`;

  const executeOnce = async (tokenOverride?: string | null): Promise<Response> => {
    const headers: Record<string, string> = {
      Accept: options.responseType === 'text' ? 'text/csv, text/plain, */*' : 'application/json',
      ...(options.headers ?? {}),
    };

    const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
    if (options.body !== undefined && !isFormData && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json';
    }

    if (options.idempotencyKey) {
      headers['Idempotency-Key'] = options.idempotencyKey;
    }

    if (options.csrf) {
      headers['X-CSRF'] = '1';
    }

    const activeToken = tokenOverride !== undefined ? tokenOverride : inMemoryAccessToken;
    if (!options.skipAuth && activeToken) {
      headers.Authorization = `Bearer ${activeToken}`;
    }

    const fetchBody: BodyInit | undefined =
      options.body === undefined
        ? undefined
        : isFormData
          ? (options.body as FormData)
          : typeof options.body === 'string'
            ? options.body
            : JSON.stringify(options.body);

    try {
      return await fetch(normalizedPath, {
        method,
        credentials: 'include',
        headers,
        body: fetchBody,
        signal: options.signal,
      });
    } catch (networkErr) {
      if (networkErr instanceof DOMException && networkErr.name === 'AbortError') {
        throw networkErr;
      }
      throw new ApiError({
        status: 0,
        code: 'NETWORK_ERROR',
        message:
          'Sunucuyla bağlantı kurulamadı veya istek zaman aşımına uğradı. İnternet bağlantınızı kontrol edip tekrar deneyin.',
        retryable: true,
        isNetworkError: true,
      });
    }
  };

  let response = await executeOnce();

  const isAuthEndpoint =
    normalizedPath.startsWith(`${API_BASE_PATH}/auth/login`) ||
    normalizedPath.startsWith(`${API_BASE_PATH}/auth/register`) ||
    normalizedPath.startsWith(`${API_BASE_PATH}/auth/refresh`) ||
    normalizedPath.startsWith(`${API_BASE_PATH}/auth/logout`);

  if (response.status === 401 && !options.skipRefreshOn401 && !isAuthEndpoint) {
    const refreshedToken = await refreshAccessTokenSingleFlight();
    if (refreshedToken) {
      // Aynı Idempotency-Key korunarak yalnızca 1 kez yeniden gönderilir (sonsuz döngü olmaz).
      response = await executeOnce(refreshedToken);
    } else {
      onSessionExpiredCallback?.();
    }
  }

  if (!response.ok) {
    let errorPayload: ApiErrorPayload | null = null;
    try {
      const contentType = response.headers.get('content-type') ?? '';
      if (contentType.includes('application/json')) {
        errorPayload = (await response.json()) as ApiErrorPayload;
      }
    } catch {
      errorPayload = null;
    }
    throw toApiErrorFromPayload(response.status, errorPayload);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  if (options.responseType === 'text') {
    return (await response.text()) as unknown as T;
  }

  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}


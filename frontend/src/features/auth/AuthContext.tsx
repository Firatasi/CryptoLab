import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type {
  AuthenticatedUser,
  LoginRequest,
  RegisterRequest,
  UserAuthority,
  UserResponse,
  UserRole,
} from '@/shared/contracts/types';
import {
  getAccessToken,
  refreshAccessTokenSingleFlight,
  registerSessionExpiredHandler,
  setAccessToken,
} from '@/shared/api/apiClient';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import {
  decodeJwtClaimsUnsafe,
  normalizeAuthorities,
  normalizeRoles,
} from '@/shared/api/jwtClaims';
import { abortAllActiveStreams } from '@/shared/stream/useManagedSseStream';
import {
  clearAllPersistedUserJobs,
  loadUiPreferences,
  saveUiPreferences,
} from '@/shared/storage/jobPersistence';
import type { TimezonePreference } from '@/shared/formatting/formatters';
import { authApi } from './api';

export type AuthStatus = 'BOOTSTRAPPING' | 'AUTHENTICATED' | 'UNAUTHENTICATED';

export interface AuthContextValue {
  status: AuthStatus;
  user: AuthenticatedUser | null;
  timezone: TimezonePreference;
  setTimezone: (tz: TimezonePreference) => void;
  login: (payload: LoginRequest) => Promise<AuthenticatedUser>;
  register: (payload: RegisterRequest, idempotencyKey: string) => Promise<UserResponse>;
  logout: () => Promise<void>;
  hasRole: (...roles: UserRole[]) => boolean;
  hasAuthority: (authority: UserAuthority) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function buildAuthenticatedUser(me: UserResponse, token: string | null): AuthenticatedUser {
  const claims = decodeJwtClaimsUnsafe(token);
  const mergedRoles = normalizeRoles([...(me.roles ?? []), ...(claims?.roles ?? [])]);
  const authorities = normalizeAuthorities(claims?.authorities);
  return {
    id: me.id,
    displayName: me.displayName,
    roles: mergedRoles.length > 0 ? mergedRoles : ['USER'],
    authorities,
  };
}

export function AuthProvider({
  children,
  skipInitialRefresh = false,
}: {
  children: React.ReactNode;
  skipInitialRefresh?: boolean;
}) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AuthStatus>(
    skipInitialRefresh ? 'UNAUTHENTICATED' : 'BOOTSTRAPPING'
  );
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [timezone, setTimezoneState] = useState<TimezonePreference>(
    () => loadUiPreferences().timezone
  );

  const wipeClientSession = useCallback(() => {
    abortAllActiveStreams();
    setAccessToken(null);
    clearAllPersistedUserJobs();
    queryClient.clear();
    setUser(null);
    setStatus('UNAUTHENTICATED');
  }, [queryClient]);

  useEffect(() => {
    registerSessionExpiredHandler(() => {
      wipeClientSession();
    });
    return () => {
      registerSessionExpiredHandler(null);
    };
  }, [wipeClientSession]);

  useEffect(() => {
    if (skipInitialRefresh) return;
    let cancelled = false;

    const bootstrap = async () => {
      try {
        const token = getAccessToken() ?? (await refreshAccessTokenSingleFlight());
        if (!token) {
          if (!cancelled) {
            setStatus('UNAUTHENTICATED');
          }
          return;
        }
        const me = await authApi.me();
        if (!cancelled) {
          setUser(buildAuthenticatedUser(me, getAccessToken()));
          setStatus('AUTHENTICATED');
        }
      } catch {
        if (!cancelled) {
          wipeClientSession();
        }
      }
    };

    void bootstrap();
    return () => {
      cancelled = true;
    };
  }, [skipInitialRefresh, wipeClientSession]);

  const login = useCallback(
    async (payload: LoginRequest): Promise<AuthenticatedUser> => {
      // Önceki kullanıcı kalıntılarını temizle
      abortAllActiveStreams();
      queryClient.clear();

      const issued = await authApi.login(payload);
      setAccessToken(issued.accessToken);
      const me = await authApi.me();
      const authenticated = buildAuthenticatedUser(me, issued.accessToken);
      setUser(authenticated);
      setStatus('AUTHENTICATED');
      return authenticated;
    },
    [queryClient]
  );

  const register = useCallback(
    async (payload: RegisterRequest, idempotencyKey: string): Promise<UserResponse> => {
      return authApi.register(payload, idempotencyKey);
    },
    []
  );

  const logout = useCallback(async (): Promise<void> => {
    try {
      await authApi.logout(createIdempotencyKey());
    } catch {
      // Sunucu erişilemese bile istemci belleği, akışlar ve önbellek kesin olarak temizlenir
    } finally {
      wipeClientSession();
    }
  }, [wipeClientSession]);

  const setTimezone = useCallback((tz: TimezonePreference) => {
    setTimezoneState(tz);
    saveUiPreferences({ timezone: tz });
  }, []);

  const hasRole = useCallback(
    (...roles: UserRole[]) => {
      if (!user) return false;
      return roles.some((role) => user.roles.includes(role));
    },
    [user]
  );

  const hasAuthority = useCallback(
    (authority: UserAuthority) => {
      if (!user) return false;
      return user.authorities.includes(authority);
    },
    [user]
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      timezone,
      setTimezone,
      login,
      register,
      logout,
      hasRole,
      hasAuthority,
    }),
    [status, user, timezone, setTimezone, login, register, logout, hasRole, hasAuthority]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return ctx;
}


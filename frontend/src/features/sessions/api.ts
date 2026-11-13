import type { CreateSessionRequest, SimulationSession } from '@/shared/contracts/types';
import { apiRequest } from '@/shared/api/apiClient';

export const sessionsApi = {
  list(signal?: AbortSignal): Promise<{ items: SimulationSession[] }> {
    return apiRequest<{ items: SimulationSession[] }>('/sim/sessions', {
      method: 'GET',
      signal,
    });
  },

  getById(sessionId: string, signal?: AbortSignal): Promise<SimulationSession> {
    return apiRequest<SimulationSession>(`/sim/sessions/${sessionId}`, {
      method: 'GET',
      signal,
    });
  },

  create(payload: CreateSessionRequest, idempotencyKey: string): Promise<SimulationSession> {
    return apiRequest<SimulationSession>('/sim/sessions', {
      method: 'POST',
      body: payload,
      idempotencyKey,
    });
  },

  halt(sessionId: string, idempotencyKey: string): Promise<SimulationSession> {
    return apiRequest<SimulationSession>(`/sim/sessions/${sessionId}/halt`, {
      method: 'POST',
      body: {},
      idempotencyKey,
    });
  },

  resume(sessionId: string, idempotencyKey: string): Promise<SimulationSession> {
    return apiRequest<SimulationSession>(`/sim/sessions/${sessionId}/resume`, {
      method: 'POST',
      body: {},
      idempotencyKey,
    });
  },

  closeAllPositions(
    sessionId: string,
    idempotencyKey: string
  ): Promise<{ sessionId: string; closeRequestedCount: number }> {
    return apiRequest<{ sessionId: string; closeRequestedCount: number }>(
      `/sim/sessions/${sessionId}/close-all`,
      {
        method: 'POST',
        body: {},
        idempotencyKey,
      }
    );
  },

  deleteSession(
    sessionId: string,
    idempotencyKey: string
  ): Promise<{ sessionId: string; deleted: boolean }> {
    return apiRequest<{ sessionId: string; deleted: boolean }>(
      `/sim/sessions/${sessionId}/delete`,
      {
        method: 'POST',
        body: {},
        idempotencyKey,
      }
    );
  },
};


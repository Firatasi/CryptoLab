import { apiRequest } from '@/shared/api/apiClient';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import type {
  AccountSummaryResponse,
  CreateReplayAccepted,
  CreateReplayRequest,
  ReplayResultView,
  TradeHistoryResponse,
} from '@/shared/contracts/types';

export async function getAccountSummary(
  sessionId: string,
  signal?: AbortSignal,
): Promise<AccountSummaryResponse> {
  const qs = new URLSearchParams({ sessionId });
  return apiRequest<AccountSummaryResponse>(`/account/me/summary?${qs.toString()}`, {
    method: 'GET',
    signal,
  });
}

export async function getTradeHistory(params: {
  sessionId: string;
  cursor?: string | null;
  limit?: number;
  signal?: AbortSignal;
}): Promise<TradeHistoryResponse> {
  const qs = new URLSearchParams({
    sessionId: params.sessionId,
    limit: String(params.limit ?? 25),
  });
  if (params.cursor) {
    qs.set('cursor', params.cursor);
  }
  return apiRequest<TradeHistoryResponse>(`/account/me/trades?${qs.toString()}`, {
    method: 'GET',
    signal: params.signal,
  });
}

export async function createReplay(
  body: CreateReplayRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<CreateReplayAccepted> {
  return apiRequest<CreateReplayAccepted>('/sim/replays', {
    method: 'POST',
    idempotencyKey,
    body,
  });
}

export async function getReplay(
  replayId: string,
  signal?: AbortSignal,
): Promise<ReplayResultView> {
  return apiRequest<ReplayResultView>(
    `/sim/replays/${encodeURIComponent(replayId)}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function cancelReplay(
  replayId: string,
  idempotencyKey = createIdempotencyKey(),
): Promise<{ replayId: string; status: string }> {
  return apiRequest<{ replayId: string; status: string }>(
    `/sim/replays/${encodeURIComponent(replayId)}/cancel`,
    {
      method: 'POST',
      idempotencyKey,
      body: {},
    },
  );
}

export async function downloadReplayCsvText(replayId: string): Promise<string> {
  return apiRequest<string>(`/sim/replays/${encodeURIComponent(replayId)}/csv`, {
    method: 'GET',
    responseType: 'text',
  });
}

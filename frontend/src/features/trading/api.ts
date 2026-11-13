import type {
  AcceptedOrderResponse,
  AiPreviewRequest,
  ClosePositionRequest,
  CommandStatusView,
  ExecutionPlan,
  ManualPreviewRequest,
  OpenOrderView,
  PositionView,
  SubmitOrderRequest,
  UpdateBracketsRequest,
} from '@/shared/contracts/types';
import { apiRequest } from '@/shared/api/apiClient';

export const tradingApi = {
  previewManual(payload: ManualPreviewRequest, idempotencyKey: string): Promise<ExecutionPlan> {
    return apiRequest<ExecutionPlan>('/sim/order-previews', {
      method: 'POST',
      body: payload,
      idempotencyKey,
    });
  },

  previewAiProposal(payload: AiPreviewRequest, idempotencyKey: string): Promise<ExecutionPlan> {
    return apiRequest<ExecutionPlan>('/sim/order-previews', {
      method: 'POST',
      body: payload,
      idempotencyKey,
    });
  },

  /**
   * SÖZLEŞME KURALI (contracts/http/submit-order.schema.json & SubmitOrderRequest.java):
   * additionalProperties: false — Gövdede YALNIZ planId, revision ve planHash gönderilir.
   * quantity, side, leverage veya fee asla tekrar gönderilmez.
   */
  submitOrder(
    plan: Pick<ExecutionPlan, 'planId' | 'revision' | 'planHash'>,
    idempotencyKey: string
  ): Promise<AcceptedOrderResponse> {
    const strictPayload: SubmitOrderRequest = {
      planId: plan.planId,
      revision: plan.revision,
      planHash: plan.planHash,
    };
    return apiRequest<AcceptedOrderResponse>('/sim/orders', {
      method: 'POST',
      body: strictPayload,
      idempotencyKey,
    });
  },

  getCommandStatus(commandId: string, signal?: AbortSignal): Promise<CommandStatusView> {
    return apiRequest<CommandStatusView>(`/sim/commands/${commandId}`, {
      method: 'GET',
      signal,
    });
  },

  listPositions(sessionId: string, signal?: AbortSignal): Promise<{ items: PositionView[] }> {
    const qs = new URLSearchParams({ sessionId });
    return apiRequest<{ items: PositionView[] }>(`/sim/positions?${qs.toString()}`, {
      method: 'GET',
      signal,
    });
  },

  closePosition(
    positionId: string,
    payload: ClosePositionRequest,
    idempotencyKey: string
  ): Promise<AcceptedOrderResponse> {
    return apiRequest<AcceptedOrderResponse>(`/sim/positions/${positionId}/close`, {
      method: 'POST',
      body: payload,
      idempotencyKey,
    });
  },

  updateBrackets(
    positionId: string,
    payload: UpdateBracketsRequest,
    idempotencyKey: string
  ): Promise<PositionView> {
    return apiRequest<PositionView>(`/sim/positions/${positionId}/brackets`, {
      method: 'PATCH',
      body: payload,
      idempotencyKey,
    });
  },

  listOpenOrders(sessionId: string, signal?: AbortSignal): Promise<{ items: OpenOrderView[] }> {
    const qs = new URLSearchParams({ sessionId });
    return apiRequest<{ items: OpenOrderView[] }>(`/sim/orders?${qs.toString()}`, {
      method: 'GET',
      signal,
    });
  },

  cancelOrder(
    orderId: string,
    expectedVersion: number,
    idempotencyKey: string
  ): Promise<{ orderId: string; status: string }> {
    return apiRequest<{ orderId: string; status: string }>(`/sim/orders/${orderId}/cancel`, {
      method: 'POST',
      body: { expectedVersion },
      idempotencyKey,
    });
  },
};


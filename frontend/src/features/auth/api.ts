import type {
  AdminMessageStatus,
  AdminOverviewResponse,
  CaptchaChallengeResponse,
  ContactMessageRequest,
  ContactMessageResponse,
  LoginRequest,
  RegisterRequest,
  TokenResponse,
  UserResponse,
} from '@/shared/contracts/types';
import { apiRequest } from '@/shared/api/apiClient';

export const authApi = {
  getCaptcha(): Promise<CaptchaChallengeResponse> {
    return apiRequest<CaptchaChallengeResponse>('/auth/captcha', {
      method: 'GET',
      skipAuth: true,
      skipRefreshOn401: true,
    });
  },

  submitContactMessage(payload: ContactMessageRequest): Promise<ContactMessageResponse> {
    return apiRequest<ContactMessageResponse>('/auth/contact', {
      method: 'POST',
      body: payload,
      skipAuth: true,
      skipRefreshOn401: true,
    });
  },

  adminOverview(): Promise<AdminOverviewResponse> {
    return apiRequest<AdminOverviewResponse>('/auth/admin/overview', {
      method: 'GET',
    });
  },

  updateAdminMessageStatus(
    id: string,
    status: AdminMessageStatus,
  ): Promise<ContactMessageResponse> {
    return apiRequest<ContactMessageResponse>(
      `/auth/admin/messages/${encodeURIComponent(id)}/status`,
      {
        method: 'PATCH',
        body: { status },
      },
    );
  },

  register(payload: RegisterRequest, idempotencyKey: string): Promise<UserResponse> {
    return apiRequest<UserResponse>('/auth/register', {
      method: 'POST',
      body: payload,
      idempotencyKey,
      skipAuth: true,
      skipRefreshOn401: true,
    });
  },

  login(payload: LoginRequest): Promise<TokenResponse> {
    return apiRequest<TokenResponse>('/auth/login', {
      method: 'POST',
      body: payload,
      skipAuth: true,
      skipRefreshOn401: true,
    });
  },

  me(): Promise<UserResponse> {
    return apiRequest<UserResponse>('/auth/me', {
      method: 'GET',
    });
  },

  logout(idempotencyKey: string): Promise<void> {
    return apiRequest<void>('/auth/logout', {
      method: 'POST',
      body: {},
      csrf: true,
      idempotencyKey,
      skipRefreshOn401: true,
    });
  },
};


import { apiRequest } from '@/shared/api/apiClient';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import type {
  AnalysisView,
  CreateAnalysisAccepted,
  CreateAnalysisRequest,
  CreateScanRequest,
  ProposalView,
  ScanJobView,
} from '@/shared/contracts/types';

export async function createAnalysis(
  body: CreateAnalysisRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<CreateAnalysisAccepted> {
  return apiRequest<CreateAnalysisAccepted>('/strategy/analyses', {
    method: 'POST',
    idempotencyKey,
    body,
  });
}

export async function getAnalysis(
  analysisId: string,
  signal?: AbortSignal,
): Promise<AnalysisView> {
  return apiRequest<AnalysisView>(
    `/strategy/analyses/${encodeURIComponent(analysisId)}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function getProposal(
  proposalId: string,
  signal?: AbortSignal,
): Promise<ProposalView> {
  return apiRequest<ProposalView>(
    `/strategy/proposals/${encodeURIComponent(proposalId)}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function createScan(
  body: CreateScanRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<{ scanId: string; status: string; statusUrl?: string }> {
  return apiRequest<{ scanId: string; status: string; statusUrl?: string }>(
    '/strategy/scans',
    {
      method: 'POST',
      idempotencyKey,
      body,
    },
  );
}

export async function getScan(
  scanId: string,
  signal?: AbortSignal,
): Promise<ScanJobView> {
  return apiRequest<ScanJobView>(`/strategy/scans/${encodeURIComponent(scanId)}`, {
    method: 'GET',
    signal,
  });
}

export async function cancelScan(
  scanId: string,
  idempotencyKey = createIdempotencyKey(),
): Promise<{ scanId: string; status: string }> {
  return apiRequest<{ scanId: string; status: string }>(
    `/strategy/scans/${encodeURIComponent(scanId)}/cancel`,
    {
      method: 'POST',
      idempotencyKey,
      body: {},
    },
  );
}

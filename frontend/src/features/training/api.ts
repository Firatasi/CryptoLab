import { apiRequest } from '@/shared/api/apiClient';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import type {
  CreateDatasetRequest,
  CreateExampleJobRequest,
  CreateTrainingRunRequest,
  CursorPage,
  DatasetFrozenResponse,
  DeleteSourceRequest,
  ModelCardItem,
  ModelsListResponse,
  PromoteModelRequest,
  ReviewExampleRequest,
  RollbackModelRequest,
  SourceImportRequest,
  SourceJobResponse,
  SourcePagePreview,
  TrainingExampleItem,
  TrainingRunView,
  TrainingSourceItem,
} from '@/shared/contracts/types';

export const MAX_PDF_UPLOAD_BYTES = 20 * 1024 * 1024; // 20 MiB

export async function listTrainingSources(
  cursor?: string | null,
  signal?: AbortSignal,
): Promise<CursorPage<TrainingSourceItem>> {
  const qs = new URLSearchParams();
  if (cursor) qs.set('cursor', cursor);
  const suffix = qs.toString() ? `?${qs.toString()}` : '';
  return apiRequest<CursorPage<TrainingSourceItem>>(
    `/strategy/training/sources${suffix}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function uploadTrainingPdfSource(params: {
  file: File;
  title: string;
  author: string;
  rightsDeclaration: string;
  idempotencyKey?: string;
}): Promise<SourceJobResponse> {
  if (params.file.size > MAX_PDF_UPLOAD_BYTES) {
    throw new Error('PDF dosya boyutu 20 MiB sınırını aşıyor.');
  }
  const formData = new FormData();
  formData.append('file', params.file);
  formData.append('title', params.title);
  formData.append('author', params.author);
  formData.append('rightsDeclaration', params.rightsDeclaration);

  return apiRequest<SourceJobResponse>('/strategy/training/sources', {
    method: 'POST',
    idempotencyKey: params.idempotencyKey ?? createIdempotencyKey(),
    body: formData,
  });
}

export async function createTrainingUrlSource(
  body: SourceImportRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<SourceJobResponse> {
  return apiRequest<SourceJobResponse>('/strategy/training/source-imports', {
    method: 'POST',
    idempotencyKey,
    body,
  });
}

export async function getTrainingSourcePage(
  sourceId: string,
  pageNumber: number,
  signal?: AbortSignal,
): Promise<SourcePagePreview> {
  return apiRequest<SourcePagePreview>(
    `/strategy/training/sources/${encodeURIComponent(
      sourceId,
    )}/pages/${pageNumber}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function deleteTrainingSource(
  sourceId: string,
  body: DeleteSourceRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<TrainingSourceItem> {
  return apiRequest<TrainingSourceItem>(
    `/strategy/training/sources/${encodeURIComponent(sourceId)}`,
    {
      method: 'DELETE',
      idempotencyKey,
      body,
    },
  );
}

export async function createExampleJob(
  body: CreateExampleJobRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<{ jobId: string; status: string; stage: string }> {
  return apiRequest<{ jobId: string; status: string; stage: string }>(
    '/strategy/training/example-jobs',
    {
      method: 'POST',
      idempotencyKey,
      body,
    },
  );
}

/**
 * JsonContractGate.java satır 37:
 * Yalnızca cursor, datasetId, expectedVersion sorgu parametrelerine izin verilir!
 */
export async function listTrainingExamples(params?: {
  datasetId?: string | null;
  cursor?: string | null;
  signal?: AbortSignal;
}): Promise<CursorPage<TrainingExampleItem>> {
  const qs = new URLSearchParams();
  if (params?.datasetId) qs.set('datasetId', params.datasetId);
  if (params?.cursor) qs.set('cursor', params.cursor);
  const suffix = qs.toString() ? `?${qs.toString()}` : '';
  return apiRequest<CursorPage<TrainingExampleItem>>(
    `/strategy/training/examples${suffix}`,
    {
      method: 'GET',
      signal: params?.signal,
    },
  );
}

export async function reviewTrainingExample(
  exampleId: string,
  body: ReviewExampleRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<TrainingExampleItem> {
  return apiRequest<TrainingExampleItem>(
    `/strategy/training/examples/${encodeURIComponent(exampleId)}`,
    {
      method: 'PATCH',
      idempotencyKey,
      body,
    },
  );
}

export async function freezeTrainingDataset(
  body: CreateDatasetRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<DatasetFrozenResponse> {
  return apiRequest<DatasetFrozenResponse>('/strategy/training/datasets', {
    method: 'POST',
    idempotencyKey,
    body,
  });
}

export async function createTrainingRun(
  body: CreateTrainingRunRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<TrainingRunView> {
  return apiRequest<TrainingRunView>('/strategy/training/runs', {
    method: 'POST',
    idempotencyKey,
    body,
  });
}

export async function getTrainingRun(
  runId: string,
  signal?: AbortSignal,
): Promise<TrainingRunView> {
  return apiRequest<TrainingRunView>(
    `/strategy/training/runs/${encodeURIComponent(runId)}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function cancelTrainingRun(
  runId: string,
  idempotencyKey = createIdempotencyKey(),
): Promise<TrainingRunView> {
  return apiRequest<TrainingRunView>(
    `/strategy/training/runs/${encodeURIComponent(runId)}/cancel`,
    {
      method: 'POST',
      idempotencyKey,
      body: {},
    },
  );
}

export async function listTrainingModels(
  signal?: AbortSignal,
): Promise<ModelsListResponse> {
  return apiRequest<ModelsListResponse>('/strategy/training/models', {
    method: 'GET',
    signal,
  });
}

export async function getTrainingModelCard(
  modelId: string,
  signal?: AbortSignal,
): Promise<ModelCardItem> {
  return apiRequest<ModelCardItem>(
    `/strategy/training/models/${encodeURIComponent(modelId)}`,
    {
      method: 'GET',
      signal,
    },
  );
}

export async function promoteTrainingModel(
  modelId: string,
  body: PromoteModelRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<{ activeModelId: string; aliasVersion: number; status: string }> {
  return apiRequest<{ activeModelId: string; aliasVersion: number; status: string }>(
    `/strategy/training/models/${encodeURIComponent(modelId)}/promote`,
    {
      method: 'POST',
      idempotencyKey,
      body,
    },
  );
}

export async function rollbackTrainingModel(
  modelId: string,
  body: RollbackModelRequest,
  idempotencyKey = createIdempotencyKey(),
): Promise<{ activeModelId: string; aliasVersion: number; status: string }> {
  return apiRequest<{ activeModelId: string; aliasVersion: number; status: string }>(
    `/strategy/training/models/${encodeURIComponent(modelId)}/rollback`,
    {
      method: 'POST',
      idempotencyKey,
      body,
    },
  );
}

/**
 * CryptoLab — Gerçek Backend ve AI Sözleşme Tipleri (Contract 1.0.0)
 *
 * KURAL: Finansal değerler tel üzerinde ve bellekte DecimalString olarak taşınır;
 * asla JavaScript Number ile kâr/zarar, komisyon, marjin veya tasfiye hesabı yapılmaz.
 */

export type DecimalString = string;
export type UuidString = string;
export type IsoTimestamp = string;

export interface ApiErrorField {
  field: string;
  message: string;
}

export interface ApiErrorPayload {
  code: string;
  message?: string;
  traceId?: string;
  retryable?: boolean;
  details?: {
    fields?: ApiErrorField[];
    [key: string]: unknown;
  };
}

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

// ============================================================================
// A. Kimlik ve Oturum (identity-service: AuthController, AccessTokens)
// ============================================================================

export type UserRole = 'USER' | 'TRAINER' | 'ADMIN';
export type UserAuthority = 'MODEL_PUBLISH' | string;

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  captchaId: string;
  captchaAnswer: string;
}

export interface CaptchaChallengeResponse {
  captchaId: UuidString;
  imageDataUri: string;
  expiresInSeconds: number;
}

export interface ContactMessageRequest {
  senderName: string;
  senderEmail: string;
  subject: string;
  message: string;
  recipientEmail?: string;
}

export interface ContactMessageResponse {
  id: UuidString;
  recipientEmail: string;
  senderName: string;
  senderEmail: string;
  subject: string;
  message: string;
  status: AdminMessageStatus;
  createdAt: IsoTimestamp;
  readAt: IsoTimestamp | null;
}

export type AdminMessageStatus = 'NEW' | 'READ' | 'RESOLVED';

export interface AdminOverviewResponse {
  liveUserCount: number;
  activeSessionUsersCount: number;
  totalUserCount: number;
  newContactCount: number;
  totalContactCount: number;
  supportInbox: string;
  messages: ContactMessageResponse[];
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface TokenResponse {
  accessToken: string;
  expiresIn: number;
}

/** Gerçek backend UserResponse (id, displayName, roles) */
export interface UserResponse {
  id: UuidString;
  displayName: string;
  roles: string[];
}

/** Bellek içi JWT claims'den zenginleştirilmiş kimlik görünümü */
export interface AuthenticatedUser {
  id: UuidString;
  displayName: string;
  roles: UserRole[];
  authorities: UserAuthority[];
}

// ============================================================================
// B. Piyasa Kataloğu, Anlık Fiyat ve Mum Serisi (market-data-service)
// ============================================================================

export type MarketQuality = 'FRESH' | 'STALE' | 'GAP' | 'WARMING' | 'UNAVAILABLE';
export type CandleInterval = '1m' | '5m' | '15m' | '1h' | '4h' | '1d';

export interface InstrumentCapabilities {
  dataSupported: boolean;
  analysisReady: boolean;
  paperTradable: boolean;
  reasonCodes: string[];
}

export interface InstrumentItem {
  instrumentId: UuidString;
  symbol: string;
  baseAsset: string;
  quoteAsset: string;
  settlementAsset: string;
  exchangeId: string;
  contractType: string;
  tickSize: DecimalString;
  stepSize: DecimalString;
  minNotional: DecimalString;
  maxLeverage: DecimalString;
  version: number;
  capabilities: InstrumentCapabilities;
}

export interface MarketSnapshot {
  instrumentId: UuidString;
  symbol: string;
  markPrice: DecimalString;
  lastPrice: DecimalString | null;
  bid: DecimalString | null;
  ask: DecimalString | null;
  indexPrice?: DecimalString | null;
  fundingRate?: DecimalString | null;
  nextFundingTime?: IsoTimestamp | null;
  sourceTime: IsoTimestamp;
  receivedAt: IsoTimestamp;
  sequence: number;
  quality: MarketQuality;
}

export interface CandleBar {
  openTime: IsoTimestamp;
  closeTime: IsoTimestamp;
  open: DecimalString;
  high: DecimalString;
  low: DecimalString;
  close: DecimalString;
  volume: DecimalString;
  isComplete: boolean;
  hasGapBefore?: boolean;
}

export interface CandleSeriesResponse {
  instrumentId: UuidString;
  symbol: string;
  interval: CandleInterval;
  quality: MarketQuality;
  asOf: IsoTimestamp;
  gapCount: number;
  bars: CandleBar[];
}

// ============================================================================
// C. Sanal Oturum, Emir Önizleme, Kesin Onay ve Pozisyon (simulation-service)
// ============================================================================

export type SessionMode = 'LIVE_PAPER' | 'HISTORICAL_REPLAY';
export type SessionStatus = 'RUNNING' | 'HALTED' | 'COMPLETED';
export type PositionSide = 'LONG' | 'SHORT';
export type OrderType = 'MARKET' | 'LIMIT';
export type OrderSource = 'MANUAL' | 'AI_PROPOSAL';
export type ExpiryPolicy = 'REEVALUATE' | 'CLOSE_AT_DEADLINE';
export type HorizonSeconds = 60 | 900 | 3600 | 86400 | 604800;

export interface SimulationSession {
  id: UuidString;
  name?: string;
  mode: SessionMode;
  status: SessionStatus;
  currency: string;
  initialDeposit: DecimalString;
  availableBalance?: DecimalString;
  isolatedMargin?: DecimalString;
  equity?: DecimalString;
  unrealizedPnl?: DecimalString;
  realizedPnl?: DecimalString;
  netPnl?: DecimalString;
  netPnlPercent?: DecimalString;
  openPositionCount?: number;
  ruleProfileId: UuidString;
  riskPolicyId: UuidString;
  generation?: number;
  haltReason?: string | null;
  createdAt: IsoTimestamp;
  updatedAt?: IsoTimestamp;
}

export interface CreateSessionRequest {
  name?: string;
  initialDeposit: DecimalString;
  ruleProfileId: UuidString;
  riskPolicyId: UuidString;
}

/** ManualPreviewRequest.java ile birebir aynı alanlar */
export interface ManualPreviewRequest {
  source: 'MANUAL';
  sessionId: UuidString;
  instrumentId: UuidString;
  side: PositionSide;
  orderType: OrderType;
  marginBudget: DecimalString;
  leverage: DecimalString;
  limitPrice: DecimalString | null;
  stopLoss: DecimalString | null;
  takeProfit: DecimalString | null;
  horizonSeconds: HorizonSeconds;
  expiryPolicy: ExpiryPolicy;
  originProposalId?: UuidString | null;
}

/** AiPreviewRequest.java ile birebir aynı alanlar */
export interface AiPreviewRequest {
  source: 'AI_PROPOSAL';
  sessionId: UuidString;
  proposalId: UuidString;
  proposalRevision: number;
}

/** ExecutionPlan.java ve execution-plan.canonical.json ile birebir aynı alanlar */
export interface ExecutionPlan {
  planId: UuidString;
  revision: number;
  planHash: string;
  sessionId: UuidString;
  instrumentId: UuidString;
  instrumentVersion: number;
  source: OrderSource;
  proposalId: UuidString | null;
  positionGeneration: number;
  side: PositionSide;
  orderType: OrderType;
  quantity: DecimalString;
  referenceEntry: DecimalString;
  limitPrice: DecimalString | null;
  marginRequired: DecimalString;
  openFeeEstimate: DecimalString;
  notional: DecimalString;
  leverage: DecimalString;
  stopLoss: DecimalString | null;
  takeProfit: DecimalString | null;
  horizonSeconds: HorizonSeconds;
  expiryPolicy: ExpiryPolicy;
  priceLowerBound: DecimalString;
  priceUpperBound: DecimalString;
  maxOpenFee: DecimalString;
  maxSlippageBps: DecimalString;
  ruleProfileId: UuidString;
  riskPolicyVersion: number;
  createdAt: IsoTimestamp;
  validUntil: IsoTimestamp;
  estimatedLiquidationPrice: DecimalString;
  warnings: string[];
}

/** SubmitOrderRequest.java / submit-order.schema.json (additionalProperties: false) */
export interface SubmitOrderRequest {
  planId: UuidString;
  revision: number;
  planHash: string;
}

/** AcceptedOrderResponse.java (HTTP 202 — FILLED demek değildir!) */
export interface AcceptedOrderResponse {
  commandId: UuidString;
  status: string;
  statusUrl: string;
  acceptedAt: IsoTimestamp;
}

export type CommandLifecycleStatus =
  | 'ACCEPTED'
  | 'NEW'
  | 'FILLED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'EXPIRED';

export interface CommandStatusView {
  commandId: UuidString;
  sessionId: UuidString;
  orderId?: UuidString | null;
  positionId?: UuidString | null;
  status: CommandLifecycleStatus;
  reason?: string | null;
  rejectCode?: string | null;
  rejectMessage?: string | null;
  fillPrice?: DecimalString | null;
  filledQuantity?: DecimalString | null;
  feeCharged?: DecimalString | null;
  updatedAt: IsoTimestamp;
}

export interface OpenOrderView {
  orderId: UuidString;
  sessionId: UuidString;
  instrumentId: UuidString;
  symbol: string;
  side: PositionSide;
  orderType: OrderType;
  quantity: DecimalString;
  limitPrice: DecimalString | null;
  marginReserved: DecimalString;
  leverage: DecimalString;
  stopLoss: DecimalString | null;
  takeProfit: DecimalString | null;
  status: 'NEW' | 'CANCEL_PENDING' | 'CANCELLED' | 'FILLED' | 'REJECTED' | 'EXPIRED';
  version: number;
  createdAt: IsoTimestamp;
}

export type PositionStatus =
  | 'OPEN'
  | 'CLOSE_REQUESTED'
  | 'EXIT_PENDING_DATA'
  | 'LIQUIDATING'
  | 'CLOSED'
  | 'LIQUIDATED';

export interface PositionView {
  positionId: UuidString;
  sessionId: UuidString;
  instrumentId: UuidString;
  symbol: string;
  side: PositionSide;
  status: PositionStatus;
  quantity: DecimalString;
  entryPrice: DecimalString;
  markPrice: DecimalString | null;
  markAsOf: IsoTimestamp | null;
  isolatedMargin: DecimalString;
  maintenanceMargin: DecimalString | null;
  unrealizedPnl: DecimalString | null;
  estimatedLiquidationPrice: DecimalString | null;
  leverage: DecimalString;
  stopLoss: DecimalString | null;
  takeProfit: DecimalString | null;
  expiryPolicy: ExpiryPolicy;
  deadlineAt: IsoTimestamp | null;
  dataQuality: MarketQuality;
  closeRequested: boolean;
  version: number;
  openedAt: IsoTimestamp;
}

export interface ClosePositionRequest {
  expectedVersion: number;
  closeQuantity: DecimalString | null; // null => tam kapanış, dolu => kısmi/tam miktar (server doğrular)
}

export interface UpdateBracketsRequest {
  expectedVersion: number;
  stopLoss: DecimalString | null;
  takeProfit: DecimalString | null;
}

// ============================================================================
// D. AI Analiz ve Piyasa Taraması (strategy-service: StrategyController)
// ============================================================================

export type AnalysisJobStatus =
  | 'QUEUED'
  | 'WAITING_DATA'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export type AnalysisAction = 'LONG' | 'SHORT' | 'WAIT';
export type ExplanationStatus = 'PENDING' | 'READY' | 'TEMPLATE' | 'UNAVAILABLE' | 'NOT_APPLICABLE';

export type AnalysisRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export interface CreateAnalysisRequest {
  sessionId: UuidString;
  instrumentId: UuidString;
  horizonSeconds: HorizonSeconds;
  asOf?: IsoTimestamp;
  riskLevel?: AnalysisRiskLevel;
}

export interface CreateAnalysisAccepted {
  analysisId: UuidString;
  status: AnalysisJobStatus;
  statusUrl: string;
}

/** AnalysisJobRepository.AnalysisView */
export interface AnalysisView {
  id: UuidString;
  ownerId: UuidString;
  sessionId: UuidString;
  instrumentId: UuidString;
  horizonSeconds: HorizonSeconds;
  asOf: IsoTimestamp;
  status: AnalysisJobStatus;
  action: AnalysisAction | null;
  error: string | null;
  reasonCodes: string[];
  modelBundleId: UuidString | null;
  bundleReleaseStatus: string | null;
  featureSnapshotId: UuidString | null;
  proposalId: UuidString | null;
  explanationStatus: ExplanationStatus | null;
  explanation: string | null;
  createdAt: IsoTimestamp;
  updatedAt: IsoTimestamp;
}

export interface ProposalIntent {
  side: PositionSide;
  orderType: OrderType;
  limitPrice: DecimalString | null;
  stopLoss: DecimalString;
  takeProfit: DecimalString;
  suggestedLeverage: DecimalString;
  horizonSeconds: HorizonSeconds;
  expiryPolicy: ExpiryPolicy;
  referencePrice: DecimalString;
  verifiedAtr: DecimalString;
}

/** AnalysisJobRepository.ProposalView */
export interface ProposalView {
  proposalId: UuidString;
  revision: number;
  ownerId: UuidString;
  sessionId: UuidString;
  instrumentId: UuidString;
  generation: number;
  policyVersion: string;
  modelBundleId: UuidString;
  featureSnapshotId: UuidString;
  intent: ProposalIntent;
  validUntil: IsoTimestamp;
  state: string;
}

export interface CreateScanRequest {
  sessionId: UuidString;
  horizonSeconds: HorizonSeconds;
  cursor?: string | null;
  pageLimit?: number;
  maxAnalysesBudget?: number;
  asOf?: IsoTimestamp;
}

export interface CandidateOutcome {
  positionIndex: number;
  instrumentId: UuidString;
  eligible: boolean;
  skippedReason: string | null;
  analysisId: UuidString | null;
}

export type ScanStatus = 'QUEUED' | 'RUNNING' | 'PARTIAL' | 'COMPLETED' | 'SUCCEEDED' | 'CANCELLED' | 'FAILED';

/** ScanJobRepository.ScanJobView */
export interface ScanJobView {
  id: UuidString;
  ownerId: UuidString;
  sessionId: UuidString;
  horizonSeconds: HorizonSeconds;
  universeVersion: string;
  cursor: string | null;
  status: ScanStatus;
  totalKnown: number;
  evaluatedCount: number;
  skippedCount: number;
  reasonCounts: Record<string, number>;
  candidates: CandidateOutcome[];
  createdAt: IsoTimestamp;
  updatedAt: IsoTimestamp;
}

// ============================================================================
// E. Hesap Özeti ve İşlem Geçmişi (account-service & reporting-service)
// ============================================================================

export type ProjectionCoverage = 'READY' | 'BUILDING' | 'STALE';

/** AccountSummaryResponse.java ile birebir aynı alanlar */
export interface AccountSummaryResponse {
  sessionId: UuidString | null;
  mode: string;
  currency: string;
  realizedPnl: DecimalString | null;
  totalFees: DecimalString | null;
  signedFunding: DecimalString | null;
  liquidationFees: DecimalString | null;
  netRealizedPnl: DecimalString | null;
  unrealizedPnl: DecimalString | null;
  totalNetPnl: DecimalString | null;
  insuranceAdjustment: DecimalString | null;
  closedTradeCount: number | null;
  coverage: ProjectionCoverage | string;
  projectionAsOf: IsoTimestamp | null;
  lagMs: number | null;
}

export interface TradeHistoryItem {
  positionId: UuidString;
  sessionId: UuidString;
  instrumentId: UuidString;
  symbol: string;
  side: PositionSide;
  status: string;
  openedAt: IsoTimestamp;
  closedAt: IsoTimestamp | null;
  realizedPnl: DecimalString;
  totalFees: DecimalString;
  signedFunding: DecimalString;
  liquidationFees: DecimalString;
  netRealizedPnl: DecimalString;
  insuranceAdjustment: DecimalString;
}

/** TradeHistoryResponse.java ile birebir aynı alanlar */
export interface TradeHistoryResponse {
  sessionId: UuidString | null;
  mode: string;
  currency: string;
  items: TradeHistoryItem[];
  nextCursor: string | null;
  coverage: ProjectionCoverage | string;
  projectionAsOf: IsoTimestamp | null;
}

// ============================================================================
// F. Tarihsel Replay (simulation-service: ReplayController & ReplayService)
// ============================================================================

export type ReplayDataLayer = 'RECORDED_TICKS' | 'PAIRED_OHLC' | 'TRADE_ONLY';
export type ReplayFidelity = 'EXACT_WITHIN_MODEL' | 'APPROXIMATE' | 'DEGRADED' | 'INDETERMINATE';
export type ReplayStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'INDETERMINATE' | 'CANCELLED' | 'FAILED';

/** ReplayController.CreateReplayRequest ile birebir aynı alanlar (ruleProfileId!) */
export interface CreateReplayRequest {
  datasetId: UuidString;
  ruleProfileId: UuidString;
  policyArtifactId: UuidString;
  seed: number;
  datasetHash: string;
  ruleHash: string;
  policyHash: string;
  tiePolicy: string;
  dataLayer: ReplayDataLayer;
  initialDeposit: DecimalString;
  startTime?: IsoTimestamp | null;
}

export interface CreateReplayAccepted {
  replayId: UuidString;
  status: ReplayStatus;
  statusUrl: string;
}

/** ReplayService.ReplayResultView */
export interface ReplayResultView {
  replayId: UuidString;
  ownerId: UuidString;
  sessionId: UuidString;
  status: ReplayStatus;
  dataLayer: ReplayDataLayer;
  fidelity: ReplayFidelity;
  ambiguousBarCount: number;
  datasetHash: string;
  ruleHash: string;
  policyHash: string;
  seed: number;
  resultArtifactId: UuidString | null;
  grossPnl: DecimalString | number | null;
  netPnl: DecimalString | number | null;
  totalFees: DecimalString | number | null;
  signedFunding: DecimalString | number | null;
  liquidationFees: DecimalString | number | null;
  insuranceAdjustment: DecimalString | number | null;
  finalAvailable: DecimalString | number | null;
  economicChecksum: string | null;
  csvPayload?: string | null;
}

// ============================================================================
// G. Eğitim ve Model Yaşam Döngüsü (strategy-service: Training*Controller + JsonContractGate)
// ============================================================================

/** JsonContractGate.java satır 79/96/112 tarafından kabul edilen enum değerleri */
export type TrainingTaskType = 'NUMERIC' | 'SFT' | 'EXPLANATION';
export type ReviewDecision = 'APPROVED' | 'REJECTED';

export type ModelReleaseStatus =
  | 'CANDIDATE'
  | 'VALIDATED'
  | 'SHADOW'
  | 'ACTIVE'
  | 'REJECTED'
  | 'ARCHIVED'
  | 'RETIRED'
  | 'DEVELOPMENT_CANDIDATE'
  | 'PROMOTED_FIXTURE'
  | 'ROLLED_BACK_FIXTURE';

/** POST /api/v1/strategy/training/source-imports */
export interface SourceImportRequest {
  sourceUrl: string;
  title: string;
  rightsDeclaration: string;
  author?: string;
}

export interface SourceJobResponse {
  jobId: UuidString;
  sourceId: UuidString;
  status: string;
  stage: string;
  providerMode?: string;
}

export interface TrainingSourceItem {
  sourceId: UuidString;
  title: string;
  author?: string | null;
  rightsDeclaration?: string;
  version: number;
  status?: string;
  stage?: string;
  pageCount?: number;
  qualityIssues?: string[];
  providerMode?: string;
  createdAt?: IsoTimestamp;
}

export interface SourcePagePreview {
  sourceId: UuidString;
  page: number;
  previewText: string;
  qualityIssues?: string[];
  ocrStatus?: string | null;
}

/** DELETE /api/v1/strategy/training/sources/{id} — JsonContractGate tam şeması */
export interface DeleteSourceRequest {
  expectedVersion: number;
}

/** POST /api/v1/strategy/training/example-jobs — JsonContractGate tam şeması */
export interface CreateExampleJobRequest {
  sourceId: UuidString;
  taskType: TrainingTaskType;
}

export interface TrainingExampleItem {
  exampleId: UuidString;
  sourceId?: UuidString;
  datasetId?: UuidString | null;
  taskType?: string;
  promptSummary?: string;
  targetText?: string;
  reviewStatus: string;
  version: number;
  splitGroup?: 'train' | 'validation' | 'test';
  qualityWarnings?: string[];
}

/** PATCH /api/v1/strategy/training/examples/{id} — JsonContractGate tam şeması */
export interface ReviewExampleRequest {
  expectedVersion: number;
  reviewStatus: ReviewDecision;
  notes?: string;
}

/** POST /api/v1/strategy/training/datasets — JsonContractGate tam şeması */
export interface CreateDatasetRequest {
  name: string;
  taskType: TrainingTaskType;
  exampleIds: UuidString[];
}

export interface DatasetFrozenResponse {
  datasetId: UuidString;
  name: string;
  taskType?: string;
  kind?: string;
  immutable?: boolean;
  manifestHash: string;
  totalExamples?: number;
  splitCounts?: Record<string, number>;
}

/** POST /api/v1/strategy/training/runs — JsonContractGate tam şeması */
export interface CreateTrainingRunRequest {
  taskType: TrainingTaskType;
  datasetId: UuidString;
  config: Record<string, unknown>;
  seed: number;
}

export interface TrainingRunProgress {
  completedUnits: number;
  totalUnits: number;
  unit: string;
}

export interface TrainingRunView {
  runId: UuidString;
  taskType?: string;
  datasetId?: UuidString;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | string;
  stage: string;
  progress?: TrainingRunProgress | number | null;
  candidateModelId?: UuidString | null;
  authority?: string;
  providerMode?: string;
  error?: string | null;
  createdAt?: IsoTimestamp;
  updatedAt?: IsoTimestamp;
}

export interface ModelCardItem {
  modelId: UuidString;
  modelVersionId?: UuidString;
  task?: string;
  releaseStatus: ModelReleaseStatus | string;
  aliasVersion?: number;
  artifactHash?: string;
  featureVersion?: string;
  featureSchemaHash?: string;
  datasetId?: UuidString;
  splitManifestId?: UuidString;
  evaluationId?: UuidString;
  horizonSeconds?: number;
  seed?: number;
  gatePassed?: boolean;
  gateFailures?: string[];
  metrics?: Record<string, number | string | boolean | null>;
  createdAt?: IsoTimestamp;
}

export interface ModelsListResponse {
  activeModelId: UuidString | null;
  aliasVersion: number;
  items: ModelCardItem[];
}

/** POST /api/v1/strategy/training/models/{id}/promote — TrainingController tam şeması */
export interface PromoteModelRequest {
  expectedAliasVersion: number;
  evaluationId: UuidString;
}

/** POST /api/v1/strategy/training/models/{id}/rollback — TrainingController tam şeması */
export interface RollbackModelRequest {
  expectedAliasVersion: number;
  reason: string;
}

// ============================================================================
// H. Canlı Akış (SSE) Durumu
// ============================================================================

export type StreamConnectionState =
  | 'IDLE'
  | 'CONNECTING'
  | 'LIVE'
  | 'RECONNECTING'
  | 'DEGRADED_POLLING'
  | 'STALE'
  | 'DISCONNECTED';

export interface ParsedSseEvent {
  id: string | null;
  event: string;
  data: string;
}


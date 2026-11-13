import { http, HttpResponse } from 'msw';
import type {
  AcceptedOrderResponse,
  AccountSummaryResponse,
  AdminMessageStatus,
  AdminOverviewResponse,
  AnalysisView,
  CandleSeriesResponse,
  CaptchaChallengeResponse,
  CommandStatusView,
  ContactMessageResponse,
  ExecutionPlan,
  InstrumentItem,
  MarketSnapshot,
  ModelCardItem,
  PositionView,
  ProposalView,
  ReplayResultView,
  ScanJobView,
  SimulationSession,
  TradeHistoryResponse,
  TrainingRunView,
  TrainingSourceItem,
} from '@/shared/contracts/types';

function createFakeJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'EdDSA', typ: 'JWT' }));
  const body = btoa(JSON.stringify(payload));
  return `${header}.${body}.signature`;
}

export const TEST_TOKENS = {
  user: createFakeJwt({
    sub: 'usr-trader-1',
    roles: ['USER'],
    authorities: [],
    exp: 9999999999,
  }),
  trainerNoPublish: createFakeJwt({
    sub: 'usr-trainer-1',
    roles: ['TRAINER'],
    authorities: [],
    exp: 9999999999,
  }),
  trainerWithPublish: createFakeJwt({
    sub: 'usr-publisher-1',
    roles: ['TRAINER', 'ADMIN'],
    authorities: ['MODEL_PUBLISH'],
    exp: 9999999999,
  }),
  admin: createFakeJwt({
    sub: '00000000-0000-4000-8000-00000000a001',
    roles: ['ADMIN'],
    authorities: [],
    exp: 9999999999,
  }),
};

export const canonicalInstruments: InstrumentItem[] = [
  {
    instrumentId: 'BTCUSDT',
    symbol: 'BTCUSDT',
    baseAsset: 'BTC',
    quoteAsset: 'USDT',
    settlementAsset: 'USDT',
    exchangeId: 'BINANCE_USD_M',
    contractType: 'PERPETUAL',
    tickSize: '0.10',
    stepSize: '0.001',
    minNotional: '10.00',
    maxLeverage: '20',
    version: 1,
    capabilities: {
      dataSupported: true,
      analysisReady: true,
      paperTradable: true,
      reasonCodes: [],
    },
  },
  {
    instrumentId: 'ETHUSDT',
    symbol: 'ETHUSDT',
    baseAsset: 'ETH',
    quoteAsset: 'USDT',
    settlementAsset: 'USDT',
    exchangeId: 'BINANCE_USD_M',
    contractType: 'PERPETUAL',
    tickSize: '0.01',
    stepSize: '0.01',
    minNotional: '10.00',
    maxLeverage: '20',
    version: 1,
    capabilities: {
      dataSupported: true,
      analysisReady: true,
      paperTradable: true,
      reasonCodes: [],
    },
  },
  {
    instrumentId: 'ILLIQUSDT',
    symbol: 'ILLIQUSDT',
    baseAsset: 'ILLIQ',
    quoteAsset: 'USDT',
    settlementAsset: 'USDT',
    exchangeId: 'BINANCE_USD_M',
    contractType: 'PERPETUAL',
    tickSize: '0.0001',
    stepSize: '1',
    minNotional: '50.00',
    maxLeverage: '5',
    version: 1,
    capabilities: {
      dataSupported: true,
      analysisReady: false,
      paperTradable: false,
      reasonCodes: ['INSUFFICIENT_HISTORY', 'PAPER_TRADING_DISABLED'],
    },
  },
];

export const canonicalPriceSnapshot: MarketSnapshot = {
  instrumentId: 'BTCUSDT',
  symbol: 'BTCUSDT',
  markPrice: '64245.10',
  lastPrice: '64250.50',
  bid: '64250.00',
  ask: '64251.00',
  indexPrice: '64240.00',
  fundingRate: '0.00010000',
  nextFundingTime: '2026-11-06T16:00:00Z',
  sourceTime: '2026-11-06T14:30:00Z',
  receivedAt: '2026-11-06T14:30:00Z',
  sequence: 10,
  quality: 'FRESH',
};

export const canonicalCandles: CandleSeriesResponse = {
  instrumentId: 'BTCUSDT',
  symbol: 'BTCUSDT',
  interval: '15m',
  quality: 'FRESH',
  asOf: '2026-11-06T14:30:00Z',
  gapCount: 0,
  bars: [
    {
      openTime: '2026-11-06T14:00:00Z',
      closeTime: '2026-11-06T14:15:00Z',
      open: '64100.00',
      high: '64300.00',
      low: '64050.00',
      close: '64200.00',
      volume: '125.400',
      isComplete: true,
    },
    {
      openTime: '2026-11-06T14:15:00Z',
      closeTime: '2026-11-06T14:30:00Z',
      open: '64200.00',
      high: '64290.00',
      low: '64150.00',
      close: '64250.50',
      volume: '98.120',
      isComplete: false,
    },
  ],
};

export const canonicalSession: SimulationSession = {
  id: 'sess-1',
  mode: 'LIVE_PAPER',
  status: 'RUNNING',
  currency: 'USDT',
  initialDeposit: '10000.00',
  availableBalance: '8990.50',
  isolatedMargin: '1000.00',
  equity: '10142.30',
  unrealizedPnl: '151.80',
  realizedPnl: '-9.50',
  netPnl: '142.30',
  netPnlPercent: '1.42',
  openPositionCount: 1,
  ruleProfileId: 'rule-default-1',
  riskPolicyId: 'risk-default-1',
  generation: 1,
  createdAt: '2026-11-06T10:00:00Z',
  updatedAt: '2026-11-06T14:30:00Z',
};

export const canonicalPreview: ExecutionPlan = {
  planId: 'plan-101',
  revision: 1,
  planHash: 'sha256:planhash101abcdef',
  sessionId: 'sess-1',
  instrumentId: 'BTCUSDT',
  instrumentVersion: 1,
  source: 'MANUAL',
  proposalId: null,
  positionGeneration: 1,
  side: 'LONG',
  orderType: 'MARKET',
  quantity: '0.077',
  referenceEntry: '64250.00',
  limitPrice: null,
  marginRequired: '989.45',
  openFeeEstimate: '2.47',
  notional: '4947.25',
  leverage: '5',
  stopLoss: '62500.00',
  takeProfit: '67500.00',
  horizonSeconds: 3600,
  expiryPolicy: 'CLOSE_AT_DEADLINE',
  priceLowerBound: '64100.00',
  priceUpperBound: '64400.00',
  maxOpenFee: '3.50',
  maxSlippageBps: '15.0',
  ruleProfileId: 'rule-default-1',
  riskPolicyVersion: 1,
  createdAt: '2026-11-06T14:30:00Z',
  validUntil: '2099-01-01T00:00:00Z',
  estimatedLiquidationPrice: '51890.20',
  warnings: [],
};

export const canonicalPositions: PositionView[] = [
  {
    positionId: 'pos-1',
    sessionId: 'sess-1',
    instrumentId: 'BTCUSDT',
    symbol: 'BTCUSDT',
    side: 'LONG',
    status: 'OPEN',
    quantity: '0.077',
    entryPrice: '64000.00',
    markPrice: '64245.10',
    markAsOf: '2026-11-06T14:30:00Z',
    isolatedMargin: '985.60',
    maintenanceMargin: '49.28',
    unrealizedPnl: '18.87',
    estimatedLiquidationPrice: '51890.20',
    leverage: '5',
    stopLoss: '62500.00',
    takeProfit: '67500.00',
    expiryPolicy: 'CLOSE_AT_DEADLINE',
    deadlineAt: '2026-11-06T15:30:00Z',
    dataQuality: 'FRESH',
    closeRequested: false,
    version: 3,
    openedAt: '2026-11-06T14:00:00Z',
  },
];

export const canonicalAccountSummary: AccountSummaryResponse = {
  sessionId: 'sess-1',
  mode: 'LIVE_PAPER',
  currency: 'USDT',
  realizedPnl: '450.00',
  totalFees: '32.50',
  signedFunding: '-8.20',
  liquidationFees: '0.00',
  netRealizedPnl: '409.30',
  unrealizedPnl: '18.87',
  totalNetPnl: '428.17',
  insuranceAdjustment: '0.00',
  closedTradeCount: 4,
  coverage: 'READY',
  projectionAsOf: '2026-11-06T14:30:00Z',
  lagMs: 25,
};

export const canonicalTradeHistory: TradeHistoryResponse = {
  sessionId: 'sess-1',
  mode: 'LIVE_PAPER',
  currency: 'USDT',
  items: [
    {
      positionId: 'pos-0',
      sessionId: 'sess-1',
      instrumentId: 'BTCUSDT',
      symbol: 'BTCUSDT',
      side: 'LONG',
      status: 'CLOSED',
      openedAt: '2026-11-06T12:15:00Z',
      closedAt: '2026-11-06T13:15:00Z',
      realizedPnl: '120.00',
      totalFees: '1.60',
      signedFunding: '-0.40',
      liquidationFees: '0.00',
      netRealizedPnl: '118.00',
      insuranceAdjustment: '0.00',
    },
  ],
  nextCursor: 'hmac-cursor-page-2',
  coverage: 'READY',
  projectionAsOf: '2026-11-06T14:30:00Z',
};

export const handlers = [
  // Auth
  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as { email?: string; password?: string };
    const email = (body.email ?? 'trader@cryptolab.local').trim().toLowerCase();
    if (email === 'admin.com') {
      if (body.password && body.password !== 'cryptoadmin15') {
        return HttpResponse.json(
          { code: 'INVALID_CREDENTIALS', message: 'E-posta veya parola hatalı.' },
          { status: 401 },
        );
      }
      return HttpResponse.json({
        accessToken: TEST_TOKENS.admin,
        expiresIn: 900,
      });
    }
    if (email.includes('publisher')) {
      return HttpResponse.json({
        accessToken: TEST_TOKENS.trainerWithPublish,
        expiresIn: 900,
      });
    }
    if (email.includes('trainer')) {
      return HttpResponse.json({
        accessToken: TEST_TOKENS.trainerNoPublish,
        expiresIn: 900,
      });
    }
    return HttpResponse.json({
      accessToken: TEST_TOKENS.user,
      expiresIn: 900,
    });
  }),

  http.post('/api/v1/auth/register', async ({ request }) => {
    const idem = request.headers.get('Idempotency-Key');
    if (!idem) {
      return HttpResponse.json(
        { code: 'MISSING_IDEMPOTENCY_KEY', message: 'Idempotency-Key zorunludur.' },
        { status: 400 },
      );
    }
    const body = (await request.json()) as {
      displayName?: string;
      captchaId?: string;
      captchaAnswer?: string;
    };
    if (body.captchaId === 'expired-captcha') {
      return HttpResponse.json(
        { code: 'CAPTCHA_EXPIRED', message: 'Güvenlik doğrulama kodunun süresi doldu. Lütfen yenileyin.' },
        { status: 400 },
      );
    }
    if (!body.captchaId || !body.captchaAnswer?.trim() || body.captchaAnswer.trim().toUpperCase() === 'WRONG') {
      return HttpResponse.json(
        { code: 'CAPTCHA_INVALID', message: 'Güvenlik doğrulama kodu hatalı.' },
        { status: 400 },
      );
    }
    return HttpResponse.json(
      {
        id: 'usr-new-1',
        displayName: body.displayName || 'Yeni Kullanıcı',
        roles: ['USER'],
      },
      { status: 201 },
    );
  }),

  http.post('/api/v1/auth/refresh', ({ request }) => {
    if (request.headers.get('X-CSRF') !== '1') {
      return HttpResponse.json(
        { code: 'CSRF_REJECTED', message: 'CSRF doğrulaması başarısız.' },
        { status: 403 },
      );
    }
    return HttpResponse.json({
      accessToken: TEST_TOKENS.user,
      expiresIn: 900,
    });
  }),

  http.get('/api/v1/auth/captcha', () => {
    const challenge: CaptchaChallengeResponse = {
      captchaId: 'cap-mock-1',
      imageDataUri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjwvc3ZnPg==',
      expiresInSeconds: 300,
    };
    return HttpResponse.json(challenge);
  }),

  http.post('/api/v1/auth/contact', async ({ request }) => {
    const body = (await request.json()) as {
      senderName: string;
      senderEmail: string;
      subject: string;
      message: string;
      recipientEmail?: string;
    };
    const item: ContactMessageResponse = {
      id: 'msg-mock-1',
      recipientEmail: body.recipientEmail?.trim() || 'support@cryptolab.dev',
      senderName: body.senderName,
      senderEmail: body.senderEmail,
      subject: body.subject,
      message: body.message,
      status: 'NEW',
      createdAt: '2026-11-13T12:00:00Z',
      readAt: null,
    };
    return HttpResponse.json(item, { status: 201 });
  }),

  http.get('/api/v1/auth/admin/overview', ({ request }) => {
    const auth = request.headers.get('Authorization') ?? '';
    if (!auth.includes(TEST_TOKENS.admin)) {
      return HttpResponse.json(
        { code: 'FORBIDDEN', message: 'Bu işlem için ADMIN yetkisi gereklidir.' },
        { status: 403 },
      );
    }
    const overview: AdminOverviewResponse = {
      liveUserCount: 3,
      activeSessionUsersCount: 2,
      totalUserCount: 5,
      newContactCount: 1,
      totalContactCount: 1,
      supportInbox: 'support@cryptolab.dev',
      messages: [
        {
          id: 'msg-mock-1',
          recipientEmail: 'support@cryptolab.dev',
          senderName: 'Ayşe Yılmaz',
          senderEmail: 'ayse@ornek.com',
          subject: 'Teknik Destek Talebi',
          message: 'Binance Futures canlı verileri hakkında sorum var.',
          status: 'NEW',
          createdAt: '2026-11-13T12:00:00Z',
          readAt: null,
        },
      ],
    };
    return HttpResponse.json(overview);
  }),

  http.patch('/api/v1/auth/admin/messages/:id/status', async ({ params, request }) => {
    const auth = request.headers.get('Authorization') ?? '';
    if (!auth.includes(TEST_TOKENS.admin)) {
      return HttpResponse.json(
        { code: 'FORBIDDEN', message: 'Bu işlem için ADMIN yetkisi gereklidir.' },
        { status: 403 },
      );
    }
    const body = (await request.json()) as { status: AdminMessageStatus };
    const updated: ContactMessageResponse = {
      id: String(params.id),
      recipientEmail: 'support@cryptolab.dev',
      senderName: 'Ayşe Yılmaz',
      senderEmail: 'ayse@ornek.com',
      subject: 'Teknik Destek Talebi',
      message: 'Binance Futures canlı verileri hakkında sorum var.',
      status: body.status,
      createdAt: '2026-11-13T12:00:00Z',
      readAt: '2026-11-13T12:05:00Z',
    };
    return HttpResponse.json(updated);
  }),

  http.post('/api/v1/auth/logout', ({ request }) => {
    if (request.headers.get('X-CSRF') !== '1') {
      return HttpResponse.json(
        { code: 'CSRF_REJECTED', message: 'CSRF doğrulaması başarısız.' },
        { status: 403 },
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  http.get('/api/v1/auth/me', ({ request }) => {
    const auth = request.headers.get('Authorization') ?? '';
    if (!auth.startsWith('Bearer ')) {
      return HttpResponse.json(
        { code: 'UNAUTHORIZED', message: 'Oturum gerekli.' },
        { status: 401 },
      );
    }
    if (auth.includes(TEST_TOKENS.admin)) {
      return HttpResponse.json({
        id: '00000000-0000-4000-8000-00000000a001',
        displayName: 'Sistem Yöneticisi',
        roles: ['ADMIN'],
      });
    }
    if (auth.includes(TEST_TOKENS.trainerWithPublish)) {
      return HttpResponse.json({
        id: 'usr-publisher-1',
        displayName: 'Baş Model Yayınlayıcı',
        roles: ['TRAINER', 'ADMIN'],
      });
    }
    if (auth.includes(TEST_TOKENS.trainerNoPublish)) {
      return HttpResponse.json({
        id: 'usr-trainer-1',
        displayName: 'Kıdemli Eğitici',
        roles: ['TRAINER'],
      });
    }
    return HttpResponse.json({
      id: 'usr-trader-1',
      displayName: 'Sanal Yatırımcı',
      roles: ['USER'],
    });
  }),

  // Market
  http.get('/api/v1/market/tickers', () => {
    return HttpResponse.json({
      items: [
        {
          instrumentId: 'BTCUSDT',
          symbol: 'BTCUSDT',
          baseAsset: 'BTC',
          quoteAsset: 'USDT',
          exchangeId: 'BINANCE_USD_M',
          contractType: 'PERPETUAL',
          lastPrice: '64250.50',
          markPrice: '64245.10',
          indexPrice: '64240.00',
          priceChange: '1240.50',
          priceChangePercent: '1.97',
          highPrice: '65100.00',
          lowPrice: '62800.00',
          volume: '142500.00',
          quoteVolume: '9150000000.00',
          fundingRate: '0.00010000',
          tradeCount: 1850420,
          tickSize: '0.10',
          stepSize: '0.001',
          minNotional: '10.00',
        },
        {
          instrumentId: 'ETHUSDT',
          symbol: 'ETHUSDT',
          baseAsset: 'ETH',
          quoteAsset: 'USDT',
          exchangeId: 'BINANCE_USD_M',
          contractType: 'PERPETUAL',
          lastPrice: '3420.50',
          markPrice: '3419.80',
          indexPrice: '3419.10',
          priceChange: '-45.20',
          priceChangePercent: '-1.30',
          highPrice: '3510.00',
          lowPrice: '3380.00',
          volume: '485200.00',
          quoteVolume: '1650000000.00',
          fundingRate: '0.00008500',
          tradeCount: 942100,
          tickSize: '0.01',
          stepSize: '0.01',
          minNotional: '10.00',
        },
      ],
      asOf: '2026-11-06T14:30:00Z',
      source: 'BINANCE_USD_M',
    });
  }),

  http.get('/api/v1/market/instruments', ({ request }) => {
    const url = new URL(request.url);
    const search = (url.searchParams.get('search') ?? '').toLowerCase();
    const items = search
      ? canonicalInstruments.filter((i) =>
          i.symbol.toLowerCase().includes(search),
        )
      : canonicalInstruments;
    return HttpResponse.json({ items, nextCursor: null });
  }),

  http.get('/api/v1/market/quotes', ({ request }) => {
    const url = new URL(request.url);
    const instrumentId = url.searchParams.get('instrumentId') ?? 'BTCUSDT';
    return HttpResponse.json({
      ...canonicalPriceSnapshot,
      instrumentId,
      symbol: instrumentId,
    });
  }),

  http.get('/api/v1/market/candles', ({ request }) => {
    const url = new URL(request.url);
    const instrumentId = url.searchParams.get('instrumentId') ?? 'BTCUSDT';
    const interval =
      (url.searchParams.get('interval') as CandleSeriesResponse['interval']) ?? '15m';
    return HttpResponse.json({
      ...canonicalCandles,
      instrumentId,
      symbol: instrumentId,
      interval,
    });
  }),

  // Sessions & Stream
  http.get('/api/v1/sim/sessions', () => {
    return HttpResponse.json({ items: [canonicalSession] });
  }),

  http.post('/api/v1/sim/sessions', async ({ request }) => {
    const body = (await request.json()) as { initialDeposit?: string };
    return HttpResponse.json({
      ...canonicalSession,
      id: 'sess-created',
      initialDeposit: body.initialDeposit ?? '10000.00',
    });
  }),

  http.get('/api/v1/sim/sessions/:sessionId', ({ params }) => {
    return HttpResponse.json({
      ...canonicalSession,
      id: String(params.sessionId),
    });
  }),

  http.get('/api/v1/sim/sessions/:sessionId/stream', () => {
    return new HttpResponse(':heartbeat\n\n', {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    });
  }),

  http.post('/api/v1/sim/sessions/:sessionId/halt', ({ params }) => {
    return HttpResponse.json({
      ...canonicalSession,
      id: String(params.sessionId),
      status: 'HALTED',
    });
  }),

  http.post('/api/v1/sim/sessions/:sessionId/resume', ({ params }) => {
    return HttpResponse.json({
      ...canonicalSession,
      id: String(params.sessionId),
      status: 'RUNNING',
    });
  }),

  http.post('/api/v1/sim/sessions/:sessionId/close-all', ({ params }) => {
    return HttpResponse.json(
      {
        sessionId: String(params.sessionId),
        closeRequestedCount: 1,
      },
      { status: 202 },
    );
  }),

  // Trading & Positions
  http.post('/api/v1/sim/order-previews', async ({ request }) => {
    const body = (await request.json()) as Partial<ExecutionPlan>;
    return HttpResponse.json({
      ...canonicalPreview,
      instrumentId: body.instrumentId ?? 'BTCUSDT',
      side: body.side ?? 'LONG',
      orderType: body.orderType ?? 'MARKET',
      leverage: body.leverage ?? '5',
    });
  }),

  http.post('/api/v1/sim/orders', async ({ request }) => {
    const raw = (await request.json()) as Record<string, unknown>;
    const keys = Object.keys(raw).sort();
    if (
      keys.length !== 3 ||
      keys[0] !== 'planHash' ||
      keys[1] !== 'planId' ||
      keys[2] !== 'revision'
    ) {
      return HttpResponse.json(
        {
          code: 'UNKNOWN_FIELD',
          message:
            'POST /api/v1/sim/orders yalnızca {planId, revision, planHash} kabul eder.',
        },
        { status: 400 },
      );
    }
    return HttpResponse.json(
      {
        commandId: 'cmd-order-101',
        status: 'ACCEPTED',
        statusUrl: '/api/v1/sim/commands/cmd-order-101',
        acceptedAt: '2026-11-06T14:30:05Z',
      } satisfies AcceptedOrderResponse,
      { status: 202 },
    );
  }),

  http.get('/api/v1/sim/commands/:commandId', ({ params }) => {
    return HttpResponse.json({
      commandId: String(params.commandId),
      sessionId: 'sess-1',
      orderId: 'ord-101',
      positionId: 'pos-1',
      status: 'ACCEPTED',
      updatedAt: '2026-11-06T14:30:06Z',
    } satisfies CommandStatusView);
  }),

  http.get('/api/v1/sim/orders', () => {
    return HttpResponse.json({ items: [] });
  }),

  http.get('/api/v1/sim/positions', () => {
    return HttpResponse.json({ items: canonicalPositions });
  }),

  http.post('/api/v1/sim/positions/:positionId/close', async ({ request }) => {
    const raw = (await request.json()) as { expectedVersion?: number };
    if (typeof raw.expectedVersion !== 'number') {
      return HttpResponse.json(
        { code: 'VALIDATION_ERROR', message: 'expectedVersion zorunludur.' },
        { status: 400 },
      );
    }
    return HttpResponse.json(
      {
        commandId: 'cmd-pos-close-1',
        status: 'ACCEPTED',
        statusUrl: '/api/v1/sim/commands/cmd-pos-close-1',
        acceptedAt: '2026-11-06T14:31:00Z',
      } satisfies AcceptedOrderResponse,
      { status: 202 },
    );
  }),

  http.patch('/api/v1/sim/positions/:positionId/brackets', async ({ params }) => {
    return HttpResponse.json({
      ...canonicalPositions[0],
      positionId: String(params.positionId),
      version: 4,
    });
  }),

  // Strategy Analysis & Scan
  http.post('/api/v1/strategy/analyses', () => {
    return HttpResponse.json({
      analysisId: 'anl-1',
      status: 'QUEUED',
      statusUrl: '/api/v1/strategy/analyses/anl-1',
    });
  }),

  http.get('/api/v1/strategy/analyses/:analysisId', ({ params }) => {
    const id = String(params.analysisId);
    if (id === 'anl-failed') {
      return HttpResponse.json({
        id,
        ownerId: 'usr-trader-1',
        sessionId: 'sess-1',
        instrumentId: 'BTCUSDT',
        horizonSeconds: 3600,
        asOf: '2026-11-06T14:30:00Z',
        status: 'FAILED',
        action: null,
        error: 'MODEL_UNAVAILABLE: Aktif sayısal model bulunamadı.',
        reasonCodes: ['OUT_OF_DOMAIN'],
        modelBundleId: null,
        bundleReleaseStatus: null,
        featureSnapshotId: null,
        proposalId: null,
        explanationStatus: 'UNAVAILABLE',
        explanation: null,
        createdAt: '2026-11-06T14:30:00Z',
        updatedAt: '2026-11-06T14:30:02Z',
      } satisfies AnalysisView);
    }
    if (id === 'anl-long') {
      return HttpResponse.json({
        id,
        ownerId: 'usr-trader-1',
        sessionId: 'sess-1',
        instrumentId: 'BTCUSDT',
        horizonSeconds: 3600,
        asOf: '2026-11-06T14:30:00Z',
        status: 'SUCCEEDED',
        action: 'LONG',
        error: null,
        reasonCodes: [],
        modelBundleId: 'mdl-bundle-v1',
        bundleReleaseStatus: 'ACTIVE',
        featureSnapshotId: 'feat-1',
        proposalId: 'prop-1',
        explanationStatus: 'TEMPLATE',
        explanation: null,
        createdAt: '2026-11-06T14:30:00Z',
        updatedAt: '2026-11-06T14:30:02Z',
      } satisfies AnalysisView);
    }
    return HttpResponse.json({
      id,
      ownerId: 'usr-trader-1',
      sessionId: 'sess-1',
      instrumentId: 'BTCUSDT',
      horizonSeconds: 3600,
      asOf: '2026-11-06T14:30:00Z',
      status: 'SUCCEEDED',
      action: 'WAIT',
      error: null,
      reasonCodes: ['EDGE_BELOW_COST', 'LOW_CONFIDENCE'],
      modelBundleId: 'mdl-bundle-v1',
      bundleReleaseStatus: 'ACTIVE',
      featureSnapshotId: 'feat-1',
      proposalId: null,
      explanationStatus: 'TEMPLATE',
      explanation: null,
      createdAt: '2026-11-06T14:30:00Z',
      updatedAt: '2026-11-06T14:30:02Z',
    } satisfies AnalysisView);
  }),

  http.get('/api/v1/strategy/proposals/:proposalId', ({ params }) => {
    return HttpResponse.json({
      proposalId: String(params.proposalId),
      revision: 1,
      ownerId: 'usr-trader-1',
      sessionId: 'sess-1',
      instrumentId: 'BTCUSDT',
      generation: 1,
      policyVersion: 'v1',
      modelBundleId: 'mdl-bundle-v1',
      featureSnapshotId: 'feat-1',
      intent: {
        side: 'LONG',
        orderType: 'MARKET',
        limitPrice: null,
        stopLoss: '62500.00',
        takeProfit: '67500.00',
        suggestedLeverage: '5',
        horizonSeconds: 3600,
        expiryPolicy: 'CLOSE_AT_DEADLINE',
        referencePrice: '64250.00',
        verifiedAtr: '450.00',
      },
      validUntil: '2099-01-01T00:00:00Z',
      state: 'READY',
    } satisfies ProposalView);
  }),

  http.post('/api/v1/strategy/scans', () => {
    return HttpResponse.json({
      scanId: 'scan-1',
      status: 'PARTIAL',
    });
  }),

  http.get('/api/v1/strategy/scans/:scanId', ({ params }) => {
    return HttpResponse.json({
      id: String(params.scanId),
      ownerId: 'usr-trader-1',
      sessionId: 'sess-1',
      horizonSeconds: 3600,
      universeVersion: 'u-1',
      cursor: 'scan-cursor-2',
      status: 'PARTIAL',
      totalKnown: 120,
      evaluatedCount: 25,
      skippedCount: 15,
      reasonCounts: {
        EDGE_BELOW_COST: 10,
        INSUFFICIENT_HISTORY: 5,
      },
      candidates: [
        {
          positionIndex: 1,
          instrumentId: 'BTCUSDT',
          eligible: true,
          skippedReason: null,
          analysisId: 'anl-long',
        },
      ],
      createdAt: '2026-11-06T14:30:00Z',
      updatedAt: '2026-11-06T14:30:05Z',
    } satisfies ScanJobView);
  }),

  // Account & Replay
  http.get('/api/v1/account/me/summary', () => {
    return HttpResponse.json(canonicalAccountSummary);
  }),

  http.get('/api/v1/account/me/trades', () => {
    return HttpResponse.json(canonicalTradeHistory);
  }),

  http.post('/api/v1/sim/replays', () => {
    return HttpResponse.json({
      replayId: 'rpl-1',
      status: 'SUCCEEDED',
      statusUrl: '/api/v1/sim/replays/rpl-1',
    });
  }),

  http.get('/api/v1/sim/replays/:replayId', ({ params }) => {
    return HttpResponse.json({
      replayId: String(params.replayId),
      ownerId: 'usr-trader-1',
      sessionId: 'sess-replay-1',
      status: 'SUCCEEDED',
      fidelity: 'APPROXIMATE',
      dataLayer: 'TRADE_ONLY',
      ambiguousBarCount: 3,
      datasetHash: 'sha256:ds',
      ruleHash: 'sha256:rule',
      policyHash: 'sha256:pol',
      seed: 42,
      resultArtifactId: 'art-1',
      grossPnl: '350.00',
      netPnl: '310.40',
      totalFees: '31.20',
      signedFunding: '-8.40',
      liquidationFees: '0.00',
      insuranceAdjustment: '0.00',
      finalAvailable: '10310.40',
      economicChecksum: 'sha256:econ998877',
    } satisfies ReplayResultView);
  }),

  http.get('/api/v1/sim/replays/:replayId/csv', ({ request }) => {
    const auth = request.headers.get('Authorization') ?? '';
    if (!auth.startsWith('Bearer ')) {
      return HttpResponse.json(
        { code: 'UNAUTHORIZED', message: 'Yetkisiz CSV erişimi.' },
        { status: 401 },
      );
    }
    return new HttpResponse('positionId,netRealizedPnl\npos-0,118.00\n', {
      status: 200,
      headers: { 'Content-Type': 'text/csv' },
    });
  }),

  // Training
  http.get('/api/v1/strategy/training/sources', () => {
    return HttpResponse.json({
      items: [
        {
          sourceId: 'src-1',
          title: 'Mikroyapı ve Likidasyon Dinamikleri',
          author: 'CryptoLab',
          status: 'SUCCEEDED',
          stage: 'INDEXED',
          version: 1,
          pageCount: 12,
        } satisfies TrainingSourceItem,
      ],
      nextCursor: null,
    });
  }),

  http.post('/api/v1/strategy/training/sources', () => {
    return HttpResponse.json({
      jobId: 'job-src-1',
      sourceId: 'src-uploaded-1',
      status: 'QUEUED',
      stage: 'INGESTING',
    });
  }),

  http.post('/api/v1/strategy/training/source-imports', () => {
    return HttpResponse.json({
      jobId: 'job-url-1',
      sourceId: 'src-url-1',
      status: 'QUEUED',
      stage: 'FETCHING_URL',
    });
  }),

  http.get('/api/v1/strategy/training/sources/:sourceId/pages/:page', ({ params }) => {
    return HttpResponse.json({
      sourceId: String(params.sourceId),
      page: Number(params.page),
      previewText: 'Temizlenmiş eğitim kaynağı metin önizlemesi.',
    });
  }),

  http.delete('/api/v1/strategy/training/sources/:sourceId', ({ params }) => {
    return HttpResponse.json({
      sourceId: String(params.sourceId),
      title: 'Silinen Kaynak',
      status: 'DELETED',
      stage: 'DELETED',
      version: 2,
    });
  }),

  http.get('/api/v1/strategy/training/examples', () => {
    return HttpResponse.json({
      items: [
        {
          exampleId: 'ex-1',
          sourceId: 'src-1',
          datasetId: 'ds-1',
          taskType: 'EXPLANATION',
          promptSummary: 'WAIT karar nedenlerini özetle',
          targetText: 'Net kenar işlem ücretlerini karşılamıyor.',
          reviewStatus: 'PENDING',
          version: 1,
        },
      ],
      nextCursor: null,
    });
  }),

  http.patch('/api/v1/strategy/training/examples/:exampleId', async ({ params, request }) => {
    const body = (await request.json()) as {
      expectedVersion: number;
      reviewStatus: 'APPROVED' | 'REJECTED';
    };
    return HttpResponse.json({
      exampleId: String(params.exampleId),
      reviewStatus: body.reviewStatus,
      version: body.expectedVersion + 1,
    });
  }),

  http.post('/api/v1/strategy/training/example-jobs', () => {
    return HttpResponse.json({
      jobId: 'job-ex-1',
      status: 'QUEUED',
      stage: 'EXTRACTING',
    });
  }),

  http.post('/api/v1/strategy/training/datasets', async ({ request }) => {
    const body = (await request.json()) as { name: string };
    return HttpResponse.json({
      datasetId: 'ds-frozen-1',
      name: body.name,
      immutable: true,
      manifestHash: 'sha256:dsfrozen1',
    });
  }),

  http.post('/api/v1/strategy/training/runs', () => {
    return HttpResponse.json({
      runId: 'run-1',
      status: 'RUNNING',
      stage: 'FITTING_CALIBRATOR',
      candidateModelId: 'mdl-candidate-2',
    } satisfies TrainingRunView);
  }),

  http.get('/api/v1/strategy/training/runs/:runId', ({ params }) => {
    return HttpResponse.json({
      runId: String(params.runId),
      status: 'RUNNING',
      stage: 'FITTING_CALIBRATOR',
      candidateModelId: 'mdl-candidate-2',
    } satisfies TrainingRunView);
  }),

  http.get('/api/v1/strategy/training/models', () => {
    return HttpResponse.json({
      items: [
        {
          modelId: 'mdl-1',
          task: 'NUMERIC',
          releaseStatus: 'ACTIVE',
          datasetId: 'ds-1',
          evaluationId: 'eval-pass-1',
          aliasVersion: 3,
          gateFailures: ['Düşük likiditeli altcoinlerde WAIT eğilimi yüksektir.'],
        },
        {
          modelId: 'mdl-2',
          task: 'NUMERIC',
          releaseStatus: 'CANDIDATE',
          datasetId: 'ds-1',
          evaluationId: 'eval-pass-2',
          aliasVersion: 3,
        },
      ],
      activeModelId: 'mdl-1',
      aliasVersion: 3,
    });
  }),

  http.get('/api/v1/strategy/training/models/:modelId', ({ params }) => {
    const id = String(params.modelId);
    return HttpResponse.json({
      modelId: id,
      task: 'NUMERIC',
      releaseStatus: id === 'mdl-1' ? 'ACTIVE' : 'CANDIDATE',
      datasetId: 'ds-1',
      evaluationId: 'eval-pass-1',
      aliasVersion: 3,
      gateFailures: ['Düşük likiditeli altcoinlerde WAIT eğilimi yüksektir.'],
    } satisfies ModelCardItem);
  }),

  http.post('/api/v1/strategy/training/models/:modelId/promote', ({ params }) => {
    return HttpResponse.json({
      activeModelId: String(params.modelId),
      aliasVersion: 4,
      status: 'ACTIVE',
    });
  }),

  http.post('/api/v1/strategy/training/models/:modelId/rollback', ({ params }) => {
    return HttpResponse.json({
      activeModelId: String(params.modelId),
      aliasVersion: 4,
      status: 'ACTIVE',
    });
  }),
];

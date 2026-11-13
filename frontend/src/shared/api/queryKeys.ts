export const queryKeys = {
  auth: {
    me: ['auth', 'me'] as const,
  },
  sessions: {
    all: ['sim', 'sessions'] as const,
    detail: (sessionId: string) => ['sim', 'sessions', sessionId] as const,
  },
  market: {
    instruments: (search: string, cursor: string | null, onlyTradable: boolean) =>
      ['market', 'instruments', search, cursor ?? '', onlyTradable] as const,
    quote: (instrumentId: string) => ['market', 'quote', instrumentId] as const,
    candles: (instrumentId: string, interval: string) =>
      ['market', 'candles', instrumentId, interval] as const,
  },
  trading: {
    positions: (sessionId: string) => ['sim', 'positions', sessionId] as const,
    openOrders: (sessionId: string) => ['sim', 'orders', sessionId] as const,
    command: (commandId: string) => ['sim', 'command', commandId] as const,
  },
  strategy: {
    analysis: (analysisId: string) => ['strategy', 'analysis', analysisId] as const,
    proposal: (proposalId: string, revision: number) =>
      ['strategy', 'proposal', proposalId, revision] as const,
    scan: (scanId: string) => ['strategy', 'scan', scanId] as const,
  },
  account: {
    summary: (sessionId: string | null) => ['account', 'summary', sessionId ?? 'ALL'] as const,
    trades: (sessionId: string | null, cursor: string | null, limit: number) =>
      ['account', 'trades', sessionId ?? 'ALL', cursor ?? '', limit] as const,
  },
  replay: {
    detail: (replayId: string) => ['sim', 'replay', replayId] as const,
  },
  training: {
    sources: (cursor: string | null) => ['training', 'sources', cursor ?? ''] as const,
    sourceDetail: (sourceId: string) => ['training', 'source', sourceId] as const,
    sourcePage: (sourceId: string, page: number) =>
      ['training', 'source-page', sourceId, page] as const,
    examples: (datasetId: string | null, cursor: string | null) =>
      ['training', 'examples', datasetId ?? '', cursor ?? ''] as const,
    run: (runId: string) => ['training', 'run', runId] as const,
    models: ['training', 'models'] as const,
    modelDetail: (modelId: string) => ['training', 'model', modelId] as const,
  },
};


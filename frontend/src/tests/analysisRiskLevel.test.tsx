import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AnalysisPage } from '../features/strategy/AnalysisPage';
import * as strategyApi from '../features/strategy/api';
import { sessionsApi } from '../features/sessions/api';
import { marketApi } from '../features/market/api';
import { ErrorBanner } from '../shared/ui/Feedback';
import { ApiError, toApiErrorFromPayload, toUserFacingErrorMessage } from '../shared/api/apiErrors';

vi.mock('../features/auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', displayName: 'Test', email: 'test@cryptolab.dev', roles: ['USER'], authorities: [] },
    timezone: 'Europe/Istanbul',
    status: 'authenticated',
  }),
}));

describe('AnalysisPage riskLevel & horizon optimization selector', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();

    vi.spyOn(sessionsApi, 'getById').mockResolvedValue({
      id: '11111111-1111-4111-8111-111111111119',
      name: 'İleri Seviye 50x Test',
      mode: 'LIVE_PAPER',
      status: 'RUNNING',
      currency: 'USDT',
      initialDeposit: '10000',
      equity: '10000',
      availableBalance: '10000',
      isolatedMargin: '0',
      ruleProfileId: '88888888-8888-4888-8888-888888888888',
      riskPolicyId: '99999999-9999-4999-8999-999999999997', // 50x session -> defaults HIGH
      generation: 1,
      createdAt: '2026-11-13T10:00:00Z',
    });

    vi.spyOn(marketApi, 'listInstruments').mockResolvedValue({
      items: [
        {
          instrumentId: '11111111-1111-4111-8111-111111111111',
          symbol: 'BTCUSDT',
          baseAsset: 'BTC',
          quoteAsset: 'USDT',
          settlementAsset: 'USDT',
          exchangeId: 'BINANCE_USD_M',
          contractType: 'PERPETUAL',
          tickSize: '0.10',
          stepSize: '0.001',
          minNotional: '10.00',
          maxLeverage: '125',
          version: 1,
          capabilities: {
            dataSupported: true,
            analysisReady: true,
            paperTradable: true,
            reasonCodes: [],
          },
        },
      ],
      nextCursor: null,
    });
  });

  it('auto-syncs risk level from session policy and allows selecting Düşük/Orta/Yüksek before starting analysis', async () => {
    const createSpy = vi.spyOn(strategyApi, 'createAnalysis').mockResolvedValue({
      analysisId: '22222222-2222-4222-8222-222222222229',
      status: 'QUEUED',
      statusUrl: '/api/v1/strategy/analyses/22222222-2222-4222-8222-222222222229',
    });
    vi.spyOn(strategyApi, 'getAnalysis').mockResolvedValue({
      id: '22222222-2222-4222-8222-222222222229',
      ownerId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      sessionId: '11111111-1111-4111-8111-111111111119',
      instrumentId: '11111111-1111-4111-8111-111111111111',
      horizonSeconds: 3600,
      asOf: '2026-11-13T10:01:00Z',
      status: 'QUEUED',
      action: null,
      error: null,
      reasonCodes: [],
      modelBundleId: null,
      bundleReleaseStatus: null,
      featureSnapshotId: null,
      proposalId: null,
      explanationStatus: 'PENDING',
      explanation: null,
      createdAt: '2026-11-13T10:01:00Z',
      updatedAt: '2026-11-13T10:01:00Z',
    });

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/strategy/analysis/11111111-1111-4111-8111-111111111119']}>
          <Routes>
            <Route path="/strategy/analysis/:sessionId" element={<AnalysisPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const lowBtn = await screen.findByRole('button', { name: /Düşük/i });
    const highBtn = screen.getByRole('button', { name: /Yüksek/i });

    await waitFor(() => {
      expect(highBtn.getAttribute('aria-pressed')).toBe('true');
    });
    expect(screen.getByText(/Oturum Sınırı: 50x/)).toBeTruthy();

    fireEvent.click(lowBtn);
    expect(lowBtn.getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: /AI Analizi Başlat/i }));

    await waitFor(() => {
      expect(createSpy).toHaveBeenCalledTimes(1);
    });
    expect(createSpy.mock.calls[0][0].riskLevel).toBe('LOW');
    expect(createSpy.mock.calls[0][0].horizonSeconds).toBe(3600);
  });

  it('hides internal reason code badges and model UUIDs when WAIT or directional analysis completes', async () => {
    vi.spyOn(strategyApi, 'createAnalysis').mockResolvedValue({
      analysisId: '33333333-3333-4333-8333-333333333339',
      status: 'SUCCEEDED',
      statusUrl: '/api/v1/strategy/analyses/33333333-3333-4333-8333-333333333339',
    });
    vi.spyOn(strategyApi, 'getAnalysis').mockResolvedValue({
      id: '33333333-3333-4333-8333-333333333339',
      ownerId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      sessionId: '11111111-1111-4111-8111-111111111119',
      instrumentId: '11111111-1111-4111-8111-111111111111',
      horizonSeconds: 3600,
      asOf: '2026-11-13T10:01:00Z',
      status: 'SUCCEEDED',
      action: 'WAIT',
      error: null,
      reasonCodes: [
        'QUANT_MODEL_V3',
        'HTF_TREND_CONFIRMED',
        'VOLUME_CONFIRMED',
        'CHOPPY_REGIME',
        'EDGE_BELOW_COST_HURDLE',
      ],
      modelBundleId: '60287330-874a-51c0-b8a4-41df73b9ea95',
      bundleReleaseStatus: 'ACTIVE',
      featureSnapshotId: '44444444-4444-4444-8444-444444444449',
      proposalId: null,
      explanationStatus: 'NOT_APPLICABLE',
      explanation: 'Piyasa Gözlemi (1 Saat) | Kısa Vadeli Yön Eğilimi: Hafif Yükseliş (LONG) Eğilimi.',
      createdAt: '2026-11-13T10:01:00Z',
      updatedAt: '2026-11-13T10:01:00Z',
    });

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/strategy/analysis/11111111-1111-4111-8111-111111111119']}>
          <Routes>
            <Route path="/strategy/analysis/:sessionId" element={<AnalysisPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const startBtn = await screen.findByRole('button', { name: /AI Analizi Başlat/i });
    fireEvent.click(startBtn);

    await screen.findByTestId('analysis-wait-card');
    expect(screen.getByText(/Kısa Vadeli Yön Eğilimi: Hafif Yükseliş/i)).toBeTruthy();
    expect(screen.queryByText(/QUANT_MODEL_V3/i)).toBeNull();
    expect(screen.queryByText(/EDGE_BELOW_COST_HURDLE/i)).toBeNull();
    expect(screen.queryByText(/60287330-874a-51c0-b8a4-41df73b9ea95/i)).toBeNull();
  });

  it('renders end-user friendly errors without leaking Trace ID, HTTP codes, backend/Gateway terms, or internal enum badges', () => {
    const serverErr = new ApiError({
      status: 503,
      code: 'MODEL_UNAVAILABLE',
      message: 'MODEL_UNAVAILABLE: service down',
      traceId: 'trace-secret-123',
      retryable: true,
    });
    render(<ErrorBanner error={serverErr} title="İşlem tamamlanamadı" />);

    expect(screen.getByText(/Yapay zekâ analiz motoru şu anda yanıt veremiyor/i)).toBeTruthy();
    expect(screen.queryByText(/MODEL_UNAVAILABLE/i)).toBeNull();
    expect(screen.queryByText(/Tanılama/i)).toBeNull();
    expect(screen.queryByText(/trace-secret-123/i)).toBeNull();
    expect(screen.queryByText(/503/)).toBeNull();

    const notFound = toApiErrorFromPayload(404, null);
    expect(notFound.message).not.toMatch(/controller|backend|Gateway|404/i);
    expect(toUserFacingErrorMessage(new Error('TypeError: Failed to fetch'))).not.toMatch(/Failed to fetch|TypeError/i);
  });
});

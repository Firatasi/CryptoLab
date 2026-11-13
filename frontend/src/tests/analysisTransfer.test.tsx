import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { canonicalInstruments, TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';
import type { AnalysisView, InstrumentItem, ProposalView } from '@/shared/contracts/types';
import { formatChartCrosshairTime, formatChartTickMark } from '@/features/market/CandleChart';
import type { TickMarkType, UTCTimestamp } from 'lightweight-charts';

function build55Instruments(): InstrumentItem[] {
  const extras: InstrumentItem[] = Array.from({ length: 52 }, (_, idx) => {
    const sym = idx === 51 ? 'WIFUSDT' : `ALT${idx + 1}USDT`;
    const base = idx === 51 ? 'WIF' : `ALT${idx + 1}`;
    return {
      instrumentId: sym,
      symbol: sym,
      baseAsset: base,
      quoteAsset: 'USDT',
      settlementAsset: 'USDT',
      exchangeId: 'BINANCE_USD_M',
      contractType: 'PERPETUAL',
      tickSize: '0.0001',
      stepSize: '0.1',
      minNotional: '5.00',
      maxLeverage: '20',
      version: 1,
      capabilities: {
        dataSupported: true,
        analysisReady: true,
        paperTradable: true,
        reasonCodes: [],
      },
    };
  });
  return [...canonicalInstruments, ...extras];
}

function renderAt(initialEntries: string[]) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(appRoutes, { initialEntries });
  return {
    router,
    ...render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>,
    ),
  };
}

describe('AI Analiz -> Emir Terminali Enstrüman ve Grafik Aktarımı', () => {
  it('İlk 50 dışındaki coin (WIFUSDT) için analiz önerisini aktarırken grafik ve kotasyonu WIFUSDT açar', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    const all55 = build55Instruments();
    const wifUuid = '99999999-aaaa-4999-8999-999999999999';

    server.use(
      http.get('/api/v1/market/instruments', ({ request }) => {
        const url = new URL(request.url);
        const search = (url.searchParams.get('search') ?? '').trim().toUpperCase();
        const limit = Number(url.searchParams.get('limit') ?? '50');
        const filtered = search
          ? all55.filter(
              (item) =>
                item.symbol.toUpperCase().includes(search) ||
                item.baseAsset.toUpperCase().includes(search) ||
                (search === wifUuid.toUpperCase() && item.symbol === 'WIFUSDT'),
            )
          : all55;
        const page = filtered.slice(0, limit);
        return HttpResponse.json({
          items: page,
          nextCursor: filtered.length > limit ? String(limit) : null,
        });
      }),
      http.post('/api/v1/strategy/analyses', () => {
        return HttpResponse.json(
          { analysisId: 'anl-wif-1', status: 'QUEUED', statusUrl: '/api/v1/strategy/analyses/anl-wif-1' },
          { status: 202 },
        );
      }),
      http.get('/api/v1/strategy/analyses/anl-wif-1', () => {
        return HttpResponse.json({
          id: 'anl-wif-1',
          ownerId: 'usr-trader-1',
          sessionId: 'sess-1',
          instrumentId: wifUuid,
          horizonSeconds: 3600,
          asOf: '2026-11-13T00:00:00Z',
          status: 'SUCCEEDED',
          action: 'SHORT',
          error: null,
          reasonCodes: [],
          modelBundleId: 'mdl-bundle-v1',
          bundleReleaseStatus: 'ACTIVE',
          featureSnapshotId: 'feat-wif',
          proposalId: 'prop-wif-1',
          explanationStatus: 'TEMPLATE',
          explanation: null,
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:01Z',
        } satisfies AnalysisView);
      }),
      http.get('/api/v1/strategy/proposals/prop-wif-1', () => {
        return HttpResponse.json({
          proposalId: 'prop-wif-1',
          revision: 1,
          ownerId: 'usr-trader-1',
          sessionId: 'sess-1',
          instrumentId: wifUuid,
          generation: 1,
          policyVersion: 'policy-v1',
          modelBundleId: 'mdl-bundle-v1',
          featureSnapshotId: 'feat-wif',
          intent: {
            side: 'SHORT',
            orderType: 'MARKET',
            limitPrice: null,
            stopLoss: '1.95',
            takeProfit: '1.65',
            suggestedLeverage: '2',
            horizonSeconds: 3600,
            expiryPolicy: 'CLOSE_AT_DEADLINE',
            referencePrice: '1.85',
            verifiedAtr: '0.08',
          },
          validUntil: '2099-01-01T00:00:00Z',
          state: 'READY',
        } satisfies ProposalView);
      }),
      http.get('/api/v1/market/quotes', ({ request }) => {
        const instId = new URL(request.url).searchParams.get('instrumentId') ?? 'BTCUSDT';
        const isWif = instId.toUpperCase() === 'WIFUSDT';
        return HttpResponse.json({
          instrumentId: isWif ? 'WIFUSDT' : 'BTCUSDT',
          symbol: isWif ? 'WIFUSDT' : 'BTCUSDT',
          markPrice: isWif ? '1.8500' : '64245.10',
          lastPrice: isWif ? '1.8510' : '64250.50',
          bid: isWif ? '1.8500' : '64250.00',
          ask: isWif ? '1.8520' : '64251.00',
          indexPrice: isWif ? '1.8490' : '64240.00',
          fundingRate: '0.00010000',
          nextFundingTime: '2026-11-13T08:00:00Z',
          sourceTime: '2026-11-13T00:00:00Z',
          receivedAt: '2026-11-13T00:00:00Z',
          sequence: 11,
          quality: 'FRESH',
        });
      }),
      http.get('/api/v1/market/candles', ({ request }) => {
        const instId = new URL(request.url).searchParams.get('instrumentId') ?? 'BTCUSDT';
        const isWif = instId.toUpperCase() === 'WIFUSDT';
        return HttpResponse.json({
          instrumentId: isWif ? 'WIFUSDT' : 'BTCUSDT',
          symbol: isWif ? 'WIFUSDT' : 'BTCUSDT',
          interval: '15m',
          quality: 'FRESH',
          asOf: '2026-11-13T00:00:00Z',
          gapCount: 0,
          bars: [
            {
              openTime: '2026-11-13T00:00:00Z',
              closeTime: '2026-11-13T00:15:00Z',
              open: isWif ? '1.82' : '64100.00',
              high: isWif ? '1.88' : '64300.00',
              low: isWif ? '1.80' : '64050.00',
              close: isWif ? '1.85' : '64250.50',
              volume: '50000',
              isComplete: true,
            },
          ],
        });
      }),
    );

    const { router } = renderAt(['/analysis/sess-1']);

    const searchInput = await screen.findByPlaceholderText(/Sembol ara/i);
    await user.type(searchInput, 'WIF');

    await waitFor(() => {
      expect(screen.getByRole('option', { name: /WIFUSDT/i })).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /AI Analizi Başlat/i }));
    const transferBtn = await screen.findByTestId('transfer-proposal-btn');
    await user.click(transferBtn);

    // Emir terminaline otomatik olarak WIFUSDT grafik, fiyat ve AI önerisi ile geçmeli (asla BTCUSDT'ye dönmemeli)
    expect(await screen.findByText(/Mum Grafiği · WIFUSDT/i)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByTestId('mark-price-value')).toHaveTextContent(/1[.,]85/);
    });
    expect(router.state.location.pathname).toBe('/trade/sess-1');
    expect(router.state.location.search).toContain('instrumentId=WIFUSDT');
    expect(await screen.findByText(/WIFUSDT · Vadeli İşlem/i)).toBeInTheDocument();
    expect(await screen.findByText(/AI Analiz Önerisi Yüklendi/i)).toBeInTheDocument();
  });

  it('Sayfa yenilenip /analysis tekrar açıldığında da kaydedilmiş sembolle (WIFUSDT) aktarım yapar', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    const all55 = build55Instruments();
    const wifUuid = '99999999-aaaa-4999-8999-999999999999';

    sessionStorage.setItem(
      'cryptolab.jobs.usr-trader-1',
      JSON.stringify({
        analysisBySession: { 'sess-1': 'anl-wif-1' },
        analysisSymbolById: { 'anl-wif-1': 'WIFUSDT', 'prop-wif-1': 'WIFUSDT' },
      }),
    );

    server.use(
      http.get('/api/v1/market/instruments', ({ request }) => {
        const url = new URL(request.url);
        const search = (url.searchParams.get('search') ?? '').trim().toUpperCase();
        const limit = Number(url.searchParams.get('limit') ?? '50');
        const filtered = search
          ? all55.filter(
              (item) =>
                item.symbol.toUpperCase().includes(search) ||
                item.baseAsset.toUpperCase().includes(search),
            )
          : all55;
        return HttpResponse.json({
          items: filtered.slice(0, limit),
          nextCursor: null,
        });
      }),
      http.get('/api/v1/strategy/analyses/anl-wif-1', () => {
        return HttpResponse.json({
          id: 'anl-wif-1',
          ownerId: 'usr-trader-1',
          sessionId: 'sess-1',
          instrumentId: wifUuid,
          horizonSeconds: 3600,
          asOf: '2026-11-13T00:00:00Z',
          status: 'SUCCEEDED',
          action: 'LONG',
          error: null,
          reasonCodes: [],
          modelBundleId: 'mdl-bundle-v1',
          bundleReleaseStatus: 'ACTIVE',
          featureSnapshotId: 'feat-wif',
          proposalId: 'prop-wif-1',
          explanationStatus: 'TEMPLATE',
          explanation: null,
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:01Z',
        } satisfies AnalysisView);
      }),
      http.get('/api/v1/strategy/proposals/prop-wif-1', () => {
        return HttpResponse.json({
          proposalId: 'prop-wif-1',
          revision: 1,
          ownerId: 'usr-trader-1',
          sessionId: 'sess-1',
          instrumentId: wifUuid,
          generation: 1,
          policyVersion: 'policy-v1',
          modelBundleId: 'mdl-bundle-v1',
          featureSnapshotId: 'feat-wif',
          intent: {
            side: 'LONG',
            orderType: 'MARKET',
            limitPrice: null,
            stopLoss: '1.75',
            takeProfit: '2.10',
            suggestedLeverage: '2',
            horizonSeconds: 3600,
            expiryPolicy: 'CLOSE_AT_DEADLINE',
            referencePrice: '1.85',
            verifiedAtr: '0.08',
          },
          validUntil: '2099-01-01T00:00:00Z',
          state: 'READY',
        } satisfies ProposalView);
      }),
      http.get('/api/v1/market/quotes', ({ request }) => {
        const instId = new URL(request.url).searchParams.get('instrumentId') ?? 'BTCUSDT';
        const isWif = instId.toUpperCase() === 'WIFUSDT';
        return HttpResponse.json({
          instrumentId: isWif ? 'WIFUSDT' : 'BTCUSDT',
          symbol: isWif ? 'WIFUSDT' : 'BTCUSDT',
          markPrice: isWif ? '1.8500' : '64245.10',
          lastPrice: isWif ? '1.8510' : '64250.50',
          bid: isWif ? '1.8500' : '64250.00',
          ask: isWif ? '1.8520' : '64251.00',
          indexPrice: isWif ? '1.8490' : '64240.00',
          fundingRate: '0.00010000',
          nextFundingTime: '2026-11-13T08:00:00Z',
          sourceTime: '2026-11-13T00:00:00Z',
          receivedAt: '2026-11-13T00:00:00Z',
          sequence: 12,
          quality: 'FRESH',
        });
      }),
      http.get('/api/v1/market/candles', ({ request }) => {
        const instId = new URL(request.url).searchParams.get('instrumentId') ?? 'BTCUSDT';
        const isWif = instId.toUpperCase() === 'WIFUSDT';
        return HttpResponse.json({
          instrumentId: isWif ? 'WIFUSDT' : 'BTCUSDT',
          symbol: isWif ? 'WIFUSDT' : 'BTCUSDT',
          interval: '15m',
          quality: 'FRESH',
          asOf: '2026-11-13T00:00:00Z',
          gapCount: 0,
          bars: [
            {
              openTime: '2026-11-13T00:00:00Z',
              closeTime: '2026-11-13T00:15:00Z',
              open: isWif ? '1.82' : '64100.00',
              high: isWif ? '1.88' : '64300.00',
              low: isWif ? '1.80' : '64050.00',
              close: isWif ? '1.85' : '64250.50',
              volume: '50000',
              isComplete: true,
            },
          ],
        });
      }),
    );

    const { router } = renderAt(['/analysis/sess-1']);

    const transferBtn = await screen.findByTestId('transfer-proposal-btn');
    await user.click(transferBtn);

    expect(await screen.findByText(/Mum Grafiği · WIFUSDT/i)).toBeInTheDocument();
    expect(router.state.location.search).toContain('instrumentId=WIFUSDT');
  });

  it('Emir terminalinde Türkçe periyot etiketlerini (1dk..1G) ve Mum/Alan grafik türü seçimini sunar', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    const requestedIntervals: string[] = [];

    server.use(
      http.get('/api/v1/market/candles', ({ request }) => {
        const url = new URL(request.url);
        const reqInterval = (url.searchParams.get('interval') ?? '15m') as '15m' | '1h';
        requestedIntervals.push(reqInterval);
        return HttpResponse.json({
          instrumentId: 'BTCUSDT',
          symbol: 'BTCUSDT',
          interval: reqInterval,
          quality: 'FRESH',
          asOf: '2026-11-13T00:00:00Z',
          gapCount: 0,
          bars: [
            {
              openTime: '2026-11-13T00:00:00Z',
              closeTime: '2026-11-13T00:15:00Z',
              open: '64100.00',
              high: '64300.00',
              low: '64050.00',
              close: '64250.50',
              volume: '100',
              isComplete: true,
            },
          ],
        });
      }),
    );

    const { router } = renderAt(['/trade/sess-1?instrumentId=BTCUSDT']);

    expect(await screen.findByText(/Mum Grafiği · BTCUSDT/i)).toBeInTheDocument();
    for (const label of ['1dk', '5dk', '15dk', '1s', '4s', '1G']) {
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
    }

    const areaBtn = screen.getByRole('button', { name: 'Alan' });
    await user.click(areaBtn);
    expect(screen.getByText(/Alan Grafiği · BTCUSDT/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '1s' }));
    await waitFor(() => {
      expect(requestedIntervals).toContain('1h');
      expect(router.state.location.search).toContain('interval=1h');
    });
  });

  it('Grafik eksen saatlerini Europe/Istanbul (TSİ) diliminde biçimlendirir ve Son Mum saatini gösterir', async () => {
    setAccessToken(TEST_TOKENS.user);
    server.use(
      http.get('/api/v1/market/candles', () =>
        HttpResponse.json({
          instrumentId: 'BTCUSDT',
          symbol: 'BTCUSDT',
          interval: '15m',
          quality: 'FRESH',
          asOf: '2026-11-13T00:55:00Z',
          gapCount: 0,
          bars: [
            {
              openTime: '2026-11-13T00:45:00Z',
              closeTime: '2026-11-13T00:59:59Z',
              open: '70928.70',
              high: '71138.00',
              low: '70872.30',
              close: '71103.40',
              volume: '1550.50',
              isComplete: false,
            },
          ],
        }),
      ),
    );

    const epochSec = Math.floor(new Date('2026-11-13T00:45:00Z').getTime() / 1000) as UTCTimestamp;
    expect(formatChartTickMark(epochSec, 3 as TickMarkType, 'Europe/Istanbul')).toBe('03:45');
    expect(formatChartTickMark(epochSec, 3 as TickMarkType, 'UTC')).toBe('00:45');
    expect(formatChartCrosshairTime(epochSec, 'Europe/Istanbul')).toContain('03:45 TSİ');

    renderAt(['/trade/sess-1?instrumentId=BTCUSDT']);
    expect(await screen.findByText(/Son Mum:.*03:45:00 TSİ/i)).toBeInTheDocument();
  });
});

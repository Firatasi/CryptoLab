import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { canonicalInstruments, canonicalPreview, TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';
import type { CreateSessionRequest, ManualPreviewRequest } from '@/shared/contracts/types';

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

describe('Oturum Komisyon & Risk Profilleri ve 50x Kaldıraç Desteği', () => {
  it('Yeni cüzdan modalında VIP Kurumsal ve İleri Seviye 50x kartlarını seçip sunucuya doğru IDlerle gönderir', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    let capturedCreateBody: CreateSessionRequest | null = null;
    server.use(
      http.get('/api/v1/sim/sessions', () => HttpResponse.json({ items: [] })),
      http.post('/api/v1/sim/sessions', async ({ request }) => {
        const parsed = (await request.json()) as CreateSessionRequest;
        capturedCreateBody = parsed;
        return HttpResponse.json({
          id: 'sess-50x-vip',
          ownerId: 'usr-trader-1',
          name: parsed.name ?? '50x Cüzdan',
          status: 'RUNNING',
          initialDeposit: parsed.initialDeposit,
          ruleProfileId: parsed.ruleProfileId,
          riskPolicyId: parsed.riskPolicyId,
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:00Z',
        });
      }),
      http.get('/api/v1/sim/sessions/sess-50x-vip', () =>
        HttpResponse.json({
          id: 'sess-50x-vip',
          ownerId: 'usr-trader-1',
          name: '50x Cüzdan',
          status: 'RUNNING',
          initialDeposit: '10000',
          ruleProfileId: '88888888-8888-4888-8888-888888888889',
          riskPolicyId: '99999999-9999-4999-8999-999999999997',
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:00Z',
        }),
      ),
    );

    renderAt(['/sessions']);

    const openBtn = await screen.findByRole('button', { name: /Yeni Sanal Oturum Aç/i });
    await user.click(openBtn);

    const vipRadio = await screen.findByRole('radio', { name: /VIP Kurumsal/i });
    const lev50Radio = await screen.findByRole('radio', { name: /İleri Seviye 50x/i });

    await user.click(vipRadio);
    await user.click(lev50Radio);

    expect(vipRadio).toHaveAttribute('aria-checked', 'true');
    expect(lev50Radio).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText(/VIP Kurumsal \(%0,025 Alıcı\) · Maks\. 50x/i)).toBeInTheDocument();

    const submitBtn = screen.getByRole('button', { name: /Oturumu Oluştur ve Terminale Geç/i });
    await user.click(submitBtn);

    await waitFor(() => {
      expect(capturedCreateBody).not.toBeNull();
    });
    const createdPayload = capturedCreateBody as CreateSessionRequest | null;
    expect(createdPayload?.ruleProfileId).toBe('88888888-8888-4888-8888-888888888889');
    expect(createdPayload?.riskPolicyId).toBe('99999999-9999-4999-8999-999999999997');
  });

  it('50x risk politikasına sahip oturumda BTCUSDT için 50x kaldıraca izin verir ve önizleme ister', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    let capturedPreviewBody: ManualPreviewRequest | null = null;
    server.use(
      http.get('/api/v1/market/instruments', () =>
        HttpResponse.json({
          items: canonicalInstruments.map((item) =>
            item.symbol === 'BTCUSDT' ? { ...item, maxLeverage: '125' } : item,
          ),
          nextCursor: null,
        }),
      ),
      http.get('/api/v1/sim/sessions/sess-50x', () =>
        HttpResponse.json({
          id: 'sess-50x',
          ownerId: 'usr-trader-1',
          name: '50x Araştırma',
          status: 'RUNNING',
          initialDeposit: '10000.00',
          ruleProfileId: '88888888-8888-4888-8888-888888888888',
          riskPolicyId: '99999999-9999-4999-8999-999999999997',
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:00Z',
        }),
      ),
      http.post('/api/v1/sim/order-previews', async ({ request }) => {
        capturedPreviewBody = (await request.json()) as ManualPreviewRequest;
        return HttpResponse.json({
          ...canonicalPreview,
          planId: 'plan-50x-1',
          sessionId: 'sess-50x',
          instrumentId: capturedPreviewBody.instrumentId,
          side: 'LONG',
          orderType: 'MARKET',
          leverage: capturedPreviewBody.leverage ?? '50',
          quantity: '0.180',
          referenceEntry: '67500.00',
          marginRequired: '250.00',
          estimatedFee: '4.86',
          estimatedSlippage: '0.50',
          liquidationEstimate: '66200.00',
          validUntil: new Date(Date.now() + 60_000).toISOString(),
        });
      }),
    );

    renderAt(['/trade/sess-50x']);

    await screen.findByText('Maks 50x');
    const leverageInput = screen.getByLabelText(/Kaldıraç/i);
    await user.clear(leverageInput);
    await user.type(leverageInput, '50');

    const previewBtn = screen.getByRole('button', { name: /Sunucu Emir Önizlemesi Al/i });
    await user.click(previewBtn);

    await waitFor(() => {
      expect(capturedPreviewBody).not.toBeNull();
    });
    const previewPayload = capturedPreviewBody as ManualPreviewRequest | null;
    expect(previewPayload?.leverage).toBe('50');
    const planCard = await screen.findByTestId('execution-plan-card');
    expect(planCard).toHaveTextContent('(50x)');
  });

  it('20x veya 5x risk politikası seçiliyse kaldıracın politika sınırını aşmasını net Türkçe hata ile engeller', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    let previewCalled = false;
    server.use(
      http.get('/api/v1/market/instruments', () =>
        HttpResponse.json({
          items: canonicalInstruments.map((item) =>
            item.symbol === 'BTCUSDT' ? { ...item, maxLeverage: '125' } : item,
          ),
          nextCursor: null,
        }),
      ),
      http.get('/api/v1/sim/sessions/sess-20x', () =>
        HttpResponse.json({
          id: 'sess-20x',
          ownerId: 'usr-trader-1',
          name: '20x Standart',
          status: 'RUNNING',
          initialDeposit: '10000.00',
          ruleProfileId: '88888888-8888-4888-8888-888888888888',
          riskPolicyId: '99999999-9999-4999-8999-999999999999',
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:00Z',
        }),
      ),
      http.post('/api/v1/sim/order-previews', () => {
        previewCalled = true;
        return HttpResponse.json({}, { status: 400 });
      }),
    );

    renderAt(['/trade/sess-20x']);

    await screen.findByText('Maks 20x');
    const leverageInput = screen.getByLabelText(/Kaldıraç/i);
    await user.clear(leverageInput);
    await user.type(leverageInput, '25');

    const previewBtn = screen.getByRole('button', { name: /Sunucu Emir Önizlemesi Al/i });
    await user.click(previewBtn);

    expect(
      await screen.findByText(/Kaldıraç seçili risk politikanızın \(Dengeli İzole · Maks\. 20x\) sınırını aşıyor\./i),
    ).toBeInTheDocument();
    expect(previewCalled).toBe(false);
  });

  it('AI Analizinden sayısal JSON alanlarla (stopLoss/takeProfit number) aktarılan öneride 50x kaldıraç girip Sunucu Emir Önizlemesi Al tıklanınca hata vermez', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    let capturedPreviewBody: ManualPreviewRequest | null = null;
    server.use(
      http.get('/api/v1/market/instruments', () =>
        HttpResponse.json({
          items: canonicalInstruments.map((item) =>
            item.symbol === 'BTCUSDT' ? { ...item, maxLeverage: '125' } : item,
          ),
          nextCursor: null,
        }),
      ),
      http.get('/api/v1/sim/sessions/sess-50x-ai', () =>
        HttpResponse.json({
          id: 'sess-50x-ai',
          ownerId: 'usr-trader-1',
          name: '50x AI Oturumu',
          status: 'RUNNING',
          initialDeposit: '10000.00',
          ruleProfileId: '88888888-8888-4888-8888-888888888888',
          riskPolicyId: '99999999-9999-4999-8999-999999999997',
          createdAt: '2026-11-13T00:00:00Z',
          updatedAt: '2026-11-13T00:00:00Z',
        }),
      ),
      http.post('/api/v1/sim/order-previews', async ({ request }) => {
        const payload = (await request.json()) as ManualPreviewRequest;
        capturedPreviewBody = payload;
        return HttpResponse.json({
          ...canonicalPreview,
          planId: 'plan-50x-ai-1',
          sessionId: 'sess-50x-ai',
          instrumentId: payload.instrumentId,
          side: 'LONG',
          orderType: 'MARKET',
          leverage: payload.leverage ?? '50',
          quantity: '0.176',
          validUntil: new Date(Date.now() + 60_000).toISOString(),
        });
      }),
    );

    // Runtime'da strategy-service sayısal JSON (number) dönebildiği için kasıtlı olarak number enjekte edilir
    const queryClient = createTestQueryClient();
    const numericAiSeed = {
      proposalId: 'prop-num-1',
      revision: 1,
      symbol: 'BTCUSDT',
      side: 'LONG' as const,
      orderType: 'MARKET' as const,
      limitPrice: null,
      stopLoss: 69753.6936 as unknown as string,
      takeProfit: 74290.5192 as unknown as string,
      suggestedLeverage: 2 as unknown as string,
      horizonSeconds: 3600 as const,
      expiryPolicy: 'CLOSE_AT_DEADLINE' as const,
    };
    const router = createMemoryRouter(appRoutes, {
      initialEntries: [
        {
          pathname: '/trade/sess-50x-ai',
          search: '?instrumentId=BTCUSDT',
          state: { aiProposalSeed: numericAiSeed },
        },
      ],
    });
    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>,
    );

    await screen.findByText('Maks 50x');
    const leverageInput = screen.getByLabelText(/Kaldıraç/i);
    await user.clear(leverageInput);
    await user.type(leverageInput, '50');

    const previewBtn = screen.getByRole('button', { name: /Sunucu Emir Önizlemesi Al/i });
    await user.click(previewBtn);

    await waitFor(() => {
      expect(capturedPreviewBody).not.toBeNull();
    });
    const previewPayload = capturedPreviewBody as ManualPreviewRequest | null;
    expect(previewPayload?.leverage).toBe('50');
    expect(previewPayload?.stopLoss).toBe('69753.6936');
    expect(previewPayload?.takeProfit).toBe('74290.5192');
    expect(await screen.findByTestId('execution-plan-card')).toHaveTextContent('(50x)');
  });

  it('Oturum kartlarında cüzdana ait güncel varlık, mevcut net kâr/zarar, gerçekleşen ve açık PnL bilgisini gösterir', async () => {
    setAccessToken(TEST_TOKENS.user);

    server.use(
      http.get('/api/v1/sim/sessions', () =>
        HttpResponse.json({
          items: [
            {
              id: 'sess-profit',
              name: 'Kârlı Cüzdan',
              mode: 'LIVE_PAPER',
              status: 'RUNNING',
              currency: 'USDT',
              initialDeposit: '10000',
              availableBalance: '9728.1207',
              isolatedMargin: '249.8037',
              equity: '10150.4244',
              realizedPnl: '-22.0756',
              unrealizedPnl: '172.50',
              netPnl: '150.4244',
              netPnlPercent: '1.50',
              openPositionCount: 1,
              ruleProfileId: '88888888-8888-4888-8888-888888888888',
              riskPolicyId: '99999999-9999-4999-8999-999999999997',
              createdAt: '2026-11-13T03:45:05Z',
              updatedAt: '2026-11-13T03:45:05Z',
            },
            {
              id: 'sess-loss',
              name: 'Zararda Cüzdan',
              mode: 'LIVE_PAPER',
              status: 'HALTED',
              currency: 'USDT',
              initialDeposit: '10000',
              availableBalance: '9728.1207',
              isolatedMargin: '249.8037',
              equity: '9977.9244',
              realizedPnl: '-22.0756',
              unrealizedPnl: '0',
              netPnl: '-22.0756',
              netPnlPercent: '-0.22',
              openPositionCount: 0,
              ruleProfileId: '88888888-8888-4888-8888-888888888888',
              riskPolicyId: '99999999-9999-4999-8999-999999999997',
              createdAt: '2026-11-13T03:00:00Z',
              updatedAt: '2026-11-13T03:00:00Z',
            },
          ],
        }),
      ),
    );

    renderAt(['/sessions']);

    const profitBox = await screen.findByTestId('wallet-pnl-sess-profit');
    expect(profitBox).toHaveTextContent('Mevcut Kâr / Zarar:');
    expect(profitBox).toHaveTextContent('+150,4244 USDT');
    expect(profitBox).toHaveTextContent('(+1,50%)');
    expect(profitBox).toHaveTextContent('Gerçekleşen: −22,0756 USDT');
    expect(profitBox).toHaveTextContent('Açık PnL (1): +172,50 USDT');

    const lossBox = await screen.findByTestId('wallet-pnl-sess-loss');
    expect(lossBox).toHaveTextContent('−22,0756 USDT');
    expect(lossBox).toHaveTextContent('(−0,22%)');
    expect(lossBox).toHaveTextContent('Gerçekleşen: −22,0756 USDT');
    expect(lossBox).toHaveTextContent('Açık PnL: 0,00 USDT');
  });
});

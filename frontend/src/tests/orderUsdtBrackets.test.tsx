import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { canonicalPositions, TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { appRoutes } from '@/app/router';
import { AuthProvider } from '@/features/auth/AuthContext';
import { createTestQueryClient } from '@/app/App';
import {
  estimateLiquidationPrice,
  estimateOrderSizing,
  pnlUsdFromTriggerPrice,
  stopLossPriceFromUsd,
  takeProfitPriceFromUsd,
} from '@/features/trading/orderBracketMath';
import type { AiProposalSeed } from '@/features/trading/OrderTicket';
import type { MarketSnapshot, PositionView } from '@/shared/contracts/types';

function renderTradeRoute(initialEntry: string, state?: unknown) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [{ pathname: initialEntry.split('?')[0], search: initialEntry.includes('?') ? `?${initialEntry.split('?')[1]}` : '', state }],
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const CUSTOM_QUOTE: MarketSnapshot = {
  instrumentId: 'BTCUSDT',
  symbol: 'BTCUSDT',
  bid: '79995.00',
  ask: '80005.00',
  markPrice: '80000.00',
  indexPrice: '80000.00',
  lastPrice: '80000.00',
  change24hPercent: '1.25',
  volume24h: '123456',
  fundingRate: '0.0001',
  nextFundingAt: '2026-11-13T16:00:00Z',
  asOf: '2026-11-13T15:00:00Z',
  quality: 'FRESH',
};

describe('USDT Bazlı SL/TP Hesabı, Tasfiye Sınırı Doğrulaması ve Emir Akışı', () => {
  it('orderBracketMath: USDT zarar/kâr tutarını tetik fiyatına çevirir ve tasfiye sınırını korur', () => {
    expect(estimateLiquidationPrice('LONG', 0.1, 80000, 160)).toBeCloseTo(78793.9698, 2);

    // 20x kaldıraç, 250 USDT bütçe, 80000 giriş -> qty = floor(5000/80000/0.001)*0.001 = 0.062 BTC
    const sizing = estimateOrderSizing({
      side: 'LONG',
      entryPrice: 80000,
      marginBudget: 250,
      leverage: 20,
    });
    expect(sizing).not.toBeNull();
    expect(sizing!.quantity).toBe(0.062);

    const slConv = stopLossPriceFromUsd('LONG', sizing!, 50);
    expect(slConv.error).toBeNull();
    expect(slConv.price).not.toBeNull();
    // 50 / 0.062 = 806.45 -> ~79193.55
    expect(slConv.price!).toBeCloseTo(79193.55, 1);

    const tpConv = takeProfitPriceFromUsd('LONG', sizing!, 10);
    expect(tpConv.error).toBeNull();
    // 10 / 0.062 = 161.29 -> ~80161.29
    expect(tpConv.price!).toBeCloseTo(80161.29, 1);
    expect(pnlUsdFromTriggerPrice('LONG', sizing!, tpConv.price!, 'TP')).toBeCloseTo(10, 0);

    // Küçük bütçe (11 USDT @ 20x -> ~7.22 USDT'de likid): 50 USDT zarar reddedilmeli
    const tightSizing = estimateOrderSizing({
      side: 'LONG',
      entryPrice: 80000,
      marginBudget: 11,
      leverage: 20,
    });
    expect(tightSizing).not.toBeNull();
    expect(tightSizing!.maxSafeStopLossUsd).toBeLessThan(10);

    const tooLargeSl = stopLossPriceFromUsd('LONG', tightSizing!, 50);
    expect(tooLargeSl.price).toBeNull();
    expect(tooLargeSl.error).toMatch(/likid/i);
    expect(tooLargeSl.error).toMatch(/en fazla/i);
  });

  it('AI önerisi aktarıldığında USDT modunda 50$ zarar / 10$ kâr ile önizleme alır ve FILLED olunca Açık Pozisyonlara ekler', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    let capturedPreviewBody: Record<string, unknown> | null = null;
    const openedPosition: PositionView = {
      ...canonicalPositions[0],
      positionId: 'pos-ai-usdt-1',
      symbol: 'BTCUSDT',
      side: 'LONG',
      entryPrice: '80000.00',
      markPrice: '80010.00',
      isolatedMargin: '248.00',
      stopLoss: '79193.55',
      takeProfit: '80161.29',
    };
    let settled = false;

    server.use(
      http.get('/api/v1/market/quotes', () => HttpResponse.json(CUSTOM_QUOTE)),
      http.post('/api/v1/sim/order-previews', async ({ request }) => {
        capturedPreviewBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          planId: 'plan-usdt-1',
          revision: 1,
          planHash: 'sha256:planhashusdt1',
          sessionId: 'sess-1',
          instrumentId: 'BTCUSDT',
          instrumentVersion: 1,
          source: 'MANUAL',
          proposalId: 'prop-ai-77',
          positionGeneration: 1,
          side: 'LONG',
          orderType: 'MARKET',
          quantity: '0.062',
          referenceEntry: '80000.00',
          limitPrice: null,
          marginRequired: '248.00',
          openFeeEstimate: '1.984',
          notional: '4960.00',
          leverage: '20',
          stopLoss: String(capturedPreviewBody.stopLoss ?? ''),
          takeProfit: String(capturedPreviewBody.takeProfit ?? ''),
          horizonSeconds: 3600,
          expiryPolicy: 'CLOSE_AT_DEADLINE',
          priceLowerBound: '79000',
          priceUpperBound: '81000',
          maxOpenFee: '5',
          maxSlippageBps: '5',
          ruleProfileId: '88888888-8888-4888-8888-888888888888',
          riskPolicyVersion: 1,
          createdAt: new Date(Date.now() - 1000).toISOString(),
          validUntil: new Date(Date.now() + 60_000).toISOString(),
          estimatedLiquidationPrice: '76381.91',
          warnings: [],
        });
      }),
      http.post('/api/v1/sim/orders', () => {
        settled = true;
        return HttpResponse.json(
          {
            commandId: 'cmd-usdt-1',
            status: 'ACCEPTED',
            statusUrl: '/api/v1/sim/commands/cmd-usdt-1',
            acceptedAt: '2026-11-13T15:00:05Z',
          },
          { status: 202 },
        );
      }),
      http.get('/api/v1/sim/commands/cmd-usdt-1', () =>
        HttpResponse.json({
          commandId: 'cmd-usdt-1',
          sessionId: 'sess-1',
          orderId: 'ord-usdt-1',
          positionId: 'pos-ai-usdt-1',
          status: 'FILLED',
          fillPrice: '80000.00',
          filledQuantity: '0.062',
          updatedAt: '2026-11-13T15:00:06Z',
        }),
      ),
      http.get('/api/v1/sim/positions', () =>
        HttpResponse.json({ items: settled ? [openedPosition] : [] }),
      ),
    );

    const seed: AiProposalSeed = {
      proposalId: 'prop-ai-77',
      revision: 1,
      symbol: 'BTCUSDT',
      side: 'LONG',
      orderType: 'MARKET',
      limitPrice: null,
      stopLoss: '78500',
      takeProfit: '83000',
      suggestedLeverage: '20',
      referenceEntry: '80000',
      horizonSeconds: 3600,
      expiryPolicy: 'CLOSE_AT_DEADLINE',
    };

    renderTradeRoute('/trade/sess-1?instrumentId=BTCUSDT', { aiProposalSeed: seed });

    await screen.findByText(/AI Analiz Önerisi Yüklendi/i);

    await user.click(screen.getByRole('button', { name: /SL giriş modu: USDT/i }));
    const slInput = screen.getByLabelText(/Zarar Durdur \(SL\)/i);
    await user.clear(slInput);
    await user.type(slInput, '50');

    await user.click(screen.getByRole('button', { name: /TP giriş modu: USDT/i }));
    const tpInput = screen.getByLabelText(/Kâr Al \(TP\)/i);
    await user.clear(tpInput);
    await user.type(tpInput, '10');

    await user.click(await screen.findByRole('button', { name: /Sunucu Emir Önizlemesi Al/i }));

    await waitFor(() => {
      expect(capturedPreviewBody).not.toBeNull();
    });
    expect(capturedPreviewBody!.originProposalId).toBe('prop-ai-77');
    expect(Number(capturedPreviewBody!.stopLoss)).toBeCloseTo(79193.55, 1);
    expect(Number(capturedPreviewBody!.takeProfit)).toBeCloseTo(80161.29, 1);

    const confirmBtn = await screen.findByRole('button', { name: /Planı Onayla ve Emri Gönder/i });
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByTestId('position-row-pos-ai-usdt-1')).toBeInTheDocument();
    });
  });

  it('Pozisyon daha az zararda likid oluyorsa 50 USDT zarar girişini anında engeller ve max sınırı gösterir', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    let previewCalled = false;

    server.use(
      http.get('/api/v1/market/quotes', () => HttpResponse.json(CUSTOM_QUOTE)),
      http.post('/api/v1/sim/order-previews', () => {
        previewCalled = true;
        return HttpResponse.json({}, { status: 400 });
      }),
    );

    renderTradeRoute('/trade/sess-1?instrumentId=BTCUSDT');

    await screen.findByTestId('mark-price-value');

    const marginInput = screen.getByLabelText(/Teminat Bütçesi/i);
    await user.clear(marginInput);
    await user.type(marginInput, '11');

    const levInput = screen.getByLabelText(/Kaldıraç/i);
    await user.clear(levInput);
    await user.type(levInput, '20');

    await user.click(screen.getByRole('button', { name: /SL giriş modu: USDT/i }));
    const slInput = screen.getByLabelText(/Zarar Durdur \(SL\)/i);
    await user.clear(slInput);
    await user.type(slInput, '50');

    const alerts = await screen.findAllByRole('alert');
    expect(alerts.some((el) => /likid/i.test(el.textContent ?? '') && /en fazla/i.test(el.textContent ?? ''))).toBe(true);

    await user.click(await screen.findByRole('button', { name: /Sunucu Emir Önizlemesi Al/i }));
    expect(previewCalled).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { createTestQueryClient } from '@/app/App';
import { PositionsTable } from '@/features/trading/PositionsAndOrders';
import type { PositionView } from '@/shared/contracts/types';

function makeBtcPosition(overrides: Partial<PositionView>): PositionView {
  return {
    positionId: 'pos-default',
    sessionId: 'sess-1',
    instrumentId: 'BTCUSDT',
    symbol: 'BTCUSDT',
    side: 'LONG',
    status: 'OPEN',
    quantity: '0.05',
    entryPrice: '68000.00',
    markPrice: '68150.00',
    markAsOf: '2026-11-17T10:00:00Z',
    isolatedMargin: '340.00',
    maintenanceMargin: '17.00',
    unrealizedPnl: '7.50',
    estimatedLiquidationPrice: '61500.00',
    leverage: '10',
    stopLoss: '66000.00',
    takeProfit: '71000.00',
    expiryPolicy: 'CLOSE_AT_DEADLINE',
    deadlineAt: '2026-11-17T11:00:00Z',
    dataQuality: 'FRESH',
    closeRequested: false,
    version: 1,
    openedAt: '2026-11-17T10:00:00Z',
    ...overrides,
  };
}

describe('Aynı Enstrümanda Çoklu Açık Pozisyon Yönetimi', () => {
  it('aynı varlıktaki tüm açık pozisyonları listeler, hedeflenen tek pozisyonu kapatır ve SL/TP düzenlemesini yalnızca seçili pozisyona gönderir', async () => {
    const positions: PositionView[] = [
      makeBtcPosition({ positionId: 'pos-btc-long-1', side: 'LONG', quantity: '0.01', entryPrice: '67800.00', version: 1 }),
      makeBtcPosition({ positionId: 'pos-btc-long-2', side: 'LONG', quantity: '0.02', entryPrice: '68000.00', version: 2 }),
      makeBtcPosition({
        positionId: 'pos-btc-short-1',
        side: 'SHORT',
        quantity: '0.015',
        entryPrice: '68400.00',
        stopLoss: '69800.00',
        takeProfit: '65500.00',
        estimatedLiquidationPrice: '74900.00',
        version: 1,
      }),
    ];

    const closedIds: string[] = [];
    const bracketUpdates: Array<{ positionId: string; stopLoss: string | null; takeProfit: string | null }> = [];

    server.use(
      http.post('/api/v1/sim/positions/:positionId/close', ({ params }) => {
        const positionId = String(params.positionId);
        closedIds.push(positionId);
        return HttpResponse.json(
          {
            commandId: `cmd-close-${positionId}`,
            status: 'ACCEPTED',
            statusUrl: `/api/v1/sim/commands/cmd-close-${positionId}`,
            acceptedAt: '2026-11-17T10:05:00Z',
          },
          { status: 202 },
        );
      }),
      http.patch('/api/v1/sim/positions/:positionId/brackets', async ({ params, request }) => {
        const positionId = String(params.positionId);
        const body = (await request.json()) as { stopLoss: string | null; takeProfit: string | null };
        bracketUpdates.push({ positionId, stopLoss: body.stopLoss, takeProfit: body.takeProfit });
        const target = positions.find((p) => p.positionId === positionId) ?? positions[0];
        return HttpResponse.json({
          ...target,
          stopLoss: body.stopLoss,
          takeProfit: body.takeProfit,
          version: target.version + 1,
        });
      }),
    );

    const queryClient = createTestQueryClient();
    const user = userEvent.setup();

    render(
      <QueryClientProvider client={queryClient}>
        <PositionsTable sessionId="sess-1" positions={positions} />
      </QueryClientProvider>,
    );

    const rowLong1 = screen.getByTestId('position-row-pos-btc-long-1');
    const rowLong2 = screen.getByTestId('position-row-pos-btc-long-2');
    const rowShort1 = screen.getByTestId('position-row-pos-btc-short-1');

    expect(rowLong1).toBeInTheDocument();
    expect(rowLong2).toBeInTheDocument();
    expect(rowShort1).toBeInTheDocument();

    // Yalnızca 2. BTCUSDT LONG pozisyonunu kapat -> diğer ikisi açık ve etkileşimli kalmalı
    await user.click(within(rowLong2).getByRole('button', { name: 'Kapat' }));
    const closeDialog = await screen.findByRole('dialog');
    expect(within(closeDialog).getByText(/Pozisyon Kapat · BTCUSDT \(LONG\)/i)).toBeInTheDocument();
    await user.click(within(closeDialog).getByRole('button', { name: /Kapanış Komutu Gönder/i }));

    await waitFor(() => {
      expect(closedIds).toEqual(['pos-btc-long-2']);
    });

    expect(within(rowLong2).getByRole('button', { name: 'Bekleniyor' })).toBeDisabled();
    expect(within(rowLong1).getByRole('button', { name: 'Kapat' })).toBeEnabled();
    expect(within(rowShort1).getByRole('button', { name: 'Kapat' })).toBeEnabled();

    // SHORT BTCUSDT pozisyonunun SL/TP bracket'ını güncelle
    await user.click(within(rowShort1).getByRole('button', { name: 'SL/TP' }));
    const bracketDialog = await screen.findByRole('dialog');
    expect(within(bracketDialog).getByText(/SL \/ TP Bracket Düzenle · BTCUSDT \(SHORT\)/i)).toBeInTheDocument();

    const slField = within(bracketDialog).getByLabelText(/Yeni Stop-Loss/i);
    await user.clear(slField);
    await user.type(slField, '70100');
    await user.click(within(bracketDialog).getByRole('button', { name: /Bracket Güncelle/i }));

    await waitFor(() => {
      expect(bracketUpdates).toEqual([
        {
          positionId: 'pos-btc-short-1',
          stopLoss: '70100',
          takeProfit: '65500',
        },
      ]);
    });
  });
});

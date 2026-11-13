import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';

function renderHistory(sessionId: string) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [`/history/${sessionId}`],
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe('HistoryPage başlık temizliği ve boş işlem geçmişi rendering', () => {
  it('OTURUM rozetini, Hesap Özeti ve Emir Terminali butonlarını göstermez ve boş geçmişte 404 olmadan yüklenir', async () => {
    const sid = '874e6e63-da1e-4a62-bb35-511f4748f176';
    setAccessToken(TEST_TOKENS.user);

    server.use(
      http.get('/api/v1/account/me/trades', ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('sessionId')).toBe(sid);
        return HttpResponse.json({
          sessionId: sid,
          mode: 'LIVE_PAPER',
          currency: 'USDT',
          items: [],
          nextCursor: null,
          coverage: 'READY',
          projectionAsOf: '2026-11-13T01:00:00Z',
        });
      }),
    );

    renderHistory(sid);

    await waitFor(() => {
      expect(screen.getByText('Bu sayfada eşleşen işlem kaydı bulunamadı.')).toBeInTheDocument();
    });

    expect(screen.getByText('İşlem Geçmişi (Trade History)')).toBeInTheDocument();
    expect(screen.queryByText(/OTURUM:/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hesap Özeti' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Emir Terminali' })).not.toBeInTheDocument();
  });

  it('Tabloda Likidasyon sütununu gösterir; likit olan işlemde Likit Oldu, normal kapanışta — yazar', async () => {
    const sid = '874e6e63-da1e-4a62-bb35-511f4748f176';
    setAccessToken(TEST_TOKENS.user);

    server.use(
      http.get('/api/v1/account/me/trades', () =>
        HttpResponse.json({
          sessionId: sid,
          mode: 'LIVE_PAPER',
          currency: 'USDT',
          items: [
            {
              positionId: 'pos-liq-1',
              sessionId: sid,
              instrumentId: '11111111-1111-4111-8111-111111111111',
              symbol: 'BTCUSDT',
              side: 'LONG',
              status: 'LIQUIDATED',
              openedAt: '2026-11-13T01:00:00Z',
              closedAt: '2026-11-13T01:05:00Z',
              realizedPnl: '-150.00',
              totalFees: '4.20',
              signedFunding: '0.00',
              liquidationFees: '39.34',
              netRealizedPnl: '-193.54',
              insuranceAdjustment: '0.00',
            },
            {
              positionId: 'pos-cls-2',
              sessionId: sid,
              instrumentId: '22222222-2222-4222-8222-222222222222',
              symbol: 'ETHUSDT',
              side: 'SHORT',
              status: 'CLOSED',
              openedAt: '2026-11-13T00:30:00Z',
              closedAt: '2026-11-13T00:45:00Z',
              realizedPnl: '45.00',
              totalFees: '1.80',
              signedFunding: '0.00',
              liquidationFees: '0.00',
              netRealizedPnl: '43.20',
              insuranceAdjustment: '0.00',
            },
          ],
          nextCursor: null,
          coverage: 'READY',
          projectionAsOf: '2026-11-13T01:06:00Z',
        }),
      ),
    );

    renderHistory(sid);

    expect(await screen.findByRole('columnheader', { name: 'Likidasyon' })).toBeInTheDocument();

    const liqRow = screen.getByText('BTCUSDT').closest('tr')!;
    expect(within(liqRow).getByText('LIQUIDATED')).toBeInTheDocument();
    expect(within(liqRow).getByText('Likit Oldu')).toBeInTheDocument();

    const normalRow = screen.getByText('ETHUSDT').closest('tr')!;
    expect(within(normalRow).getByText('CLOSED')).toBeInTheDocument();
    expect(within(normalRow).queryByText('Likit Oldu')).not.toBeInTheDocument();
    expect(within(normalRow).getByText('—')).toBeInTheDocument();
  });
});

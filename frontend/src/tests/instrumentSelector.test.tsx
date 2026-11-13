import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { canonicalInstruments, TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';
import { InstrumentSelector } from '@/features/market/InstrumentSelector';

describe('InstrumentSelector kompakt (Emir Terminali) görünümü', () => {
  it('Emir Terminali sol panelinde (compact) varlıkların yanında 125x · PERP / 75x · PERP etiketlerini göstermez', async () => {
    setAccessToken(TEST_TOKENS.user);
    server.use(
      http.get('/api/v1/market/instruments', () =>
        HttpResponse.json({
          items: canonicalInstruments.map((item) =>
            item.symbol === 'BTCUSDT'
              ? { ...item, maxLeverage: '125' }
              : item.symbol === 'ETHUSDT'
                ? { ...item, maxLeverage: '75' }
                : item,
          ),
          nextCursor: null,
        }),
      ),
    );

    const queryClient = createTestQueryClient();
    const router = createMemoryRouter(appRoutes, {
      initialEntries: ['/trade/sess-1'],
    });
    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'Sözleşmeler' });

    const btcRowBtn = await screen.findByRole('button', { name: /BTCUSDT/i });
    const ethRowBtn = await screen.findByRole('button', { name: /ETHUSDT/i });

    expect(btcRowBtn).toHaveTextContent('BTCUSDT');
    expect(btcRowBtn).not.toHaveTextContent(/PERP/i);
    expect(btcRowBtn).not.toHaveTextContent(/125x/i);

    expect(ethRowBtn).toHaveTextContent('ETHUSDT');
    expect(ethRowBtn).not.toHaveTextContent(/PERP/i);
    expect(ethRowBtn).not.toHaveTextContent(/75x/i);
  });

  it('Kompakt olmayan katalog modunda maxLeverage · PERP bilgisini korur', async () => {
    server.use(
      http.get('/api/v1/market/instruments', () =>
        HttpResponse.json({
          items: canonicalInstruments.map((item) =>
            item.symbol === 'BTCUSDT' ? { ...item, maxLeverage: '125' } : item,
          ),
          nextCursor: null,
        }),
      ),
    );

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <InstrumentSelector
          selectedInstrumentId={null}
          onSelectInstrument={vi.fn()}
        />
      </QueryClientProvider>,
    );

    expect(await screen.findByText('BTCUSDT')).toBeInTheDocument();
    expect(screen.getByText('125x · PERP')).toBeInTheDocument();
  });
});

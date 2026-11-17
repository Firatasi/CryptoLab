import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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

  it('Giriş yapmış kullanıcıda varlıkların yanında yıldız görünür, favorilenenler mevcut sırayı bozmadan en üste taşınır ve kaldırılınca eski sırasına döner', async () => {
    window.localStorage.clear();
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    const solItem = {
      ...canonicalInstruments[1],
      instrumentId: '33333333-3333-4333-8333-333333333333',
      symbol: 'SOLUSDT',
      baseAsset: 'SOL',
    };

    server.use(
      http.get('/api/v1/market/instruments', () =>
        HttpResponse.json({
          items: [canonicalInstruments[0], canonicalInstruments[1], solItem],
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

    await screen.findByTestId('favorite-star-SOLUSDT');

    const getRowOrder = () =>
      screen.getAllByTestId(/^instrument-row-/).map((el) => el.getAttribute('data-testid'));

    expect(getRowOrder()).toEqual([
      'instrument-row-BTCUSDT',
      'instrument-row-ETHUSDT',
      'instrument-row-SOLUSDT',
    ]);

    // SOLUSDT yıldızlandığında mevcut sıra bozulmadan en üste gelir
    await user.click(screen.getByTestId('favorite-star-SOLUSDT'));
    expect(getRowOrder()).toEqual([
      'instrument-row-SOLUSDT',
      'instrument-row-BTCUSDT',
      'instrument-row-ETHUSDT',
    ]);

    // ETHUSDT de yıldızlandığında favoriler kendi orijinal görece sırasını (ETHUSDT -> SOLUSDT) koruyarak üstte kalır
    await user.click(screen.getByTestId('favorite-star-ETHUSDT'));
    expect(getRowOrder()).toEqual([
      'instrument-row-ETHUSDT',
      'instrument-row-SOLUSDT',
      'instrument-row-BTCUSDT',
    ]);

    // Yıldızlar kaldırıldığında tam olarak ilk sıraya döner
    await user.click(screen.getByTestId('favorite-star-ETHUSDT'));
    await user.click(screen.getByTestId('favorite-star-SOLUSDT'));
    expect(getRowOrder()).toEqual([
      'instrument-row-BTCUSDT',
      'instrument-row-ETHUSDT',
      'instrument-row-SOLUSDT',
    ]);
  });

  it('Giriş yapmamış kullanıcıda varlıkların yanında yıldız ikonu göstermez', async () => {
    window.localStorage.clear();
    setAccessToken(null);

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider skipInitialRefresh>
          <InstrumentSelector
            selectedInstrumentId={null}
            onSelectInstrument={vi.fn()}
          />
        </AuthProvider>
      </QueryClientProvider>,
    );

    await screen.findByText('BTCUSDT');
    expect(screen.queryByTestId(/^favorite-star-/)).not.toBeInTheDocument();
  });
});

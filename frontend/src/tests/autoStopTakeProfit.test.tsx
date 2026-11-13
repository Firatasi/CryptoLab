import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { server } from './mocks/server';
import { http, HttpResponse } from 'msw';
import { TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { toApiErrorFromPayload } from '@/shared/api/apiErrors';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';

const SESSION_ID = 'sess-1';

describe('Otomatik SL / TP Doğrulama ve Kapanış Bildirimleri', () => {
  it('Backend INVALID_STOP, INVALID_TARGET ve BRACKET_REQUIRED hata kodlarını Türkçe açıklar', () => {
    const stopErr = toApiErrorFromPayload(400, { code: 'INVALID_STOP', message: '' });
    const targetErr = toApiErrorFromPayload(400, { code: 'INVALID_TARGET', message: '' });
    const bracketErr = toApiErrorFromPayload(400, { code: 'BRACKET_REQUIRED', message: '' });

    expect(stopErr.message).toContain('Zarar Durdur (SL)');
    expect(targetErr.message).toContain('Kâr Al (TP)');
    expect(bracketErr.message).toContain('ikisi de zorunludur');
  });

  it('Emir Terminali açılışında boş SL/TP alanlarını zorunlu tutmaz ve varsayılanları null bırakır', async () => {
    setAccessToken(TEST_TOKENS.user);
    server.use(
      http.get(`/api/v1/sim/sessions/${SESSION_ID}/stream`, () =>
        new HttpResponse(
          'id: 1\nevent: position.closed\ndata: {"symbol":"BTCUSDT","reason":"TAKE_PROFIT","exitPrice":"91250"}\n\n',
          {
            status: 200,
            headers: { 'Content-Type': 'text/event-stream' },
          },
        ),
      ),
    );

    const queryClient = createTestQueryClient();
    const router = createMemoryRouter(appRoutes, {
      initialEntries: [`/trade/${SESSION_ID}`],
    });

    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>,
    );

    await waitFor(
      () => {
        expect(screen.getByText(/BTCUSDT pozisyonu Kâr Al \(TP/i)).toBeInTheDocument();
      },
      { timeout: 3000 },
    );

    const stopInput = screen.getByLabelText(/Zarar Durdur \(SL\)/i) as HTMLInputElement;
    const takeInput = screen.getByLabelText(/Kâr Al \(TP\)/i) as HTMLInputElement;
    expect(stopInput.value).toBe('');
    expect(takeInput.value).toBe('');
  });
});

import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { TEST_TOKENS } from './mocks/handlers';
import { setAccessToken } from '@/shared/api/apiClient';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';

function renderShell(route = '/sessions') {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [route],
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe('AppShell kullanıcı başlık çubuğu', () => {
  it('Kullanıcı adını, Ayarlar ve Çıkış kontrollerini gösterir; USER rol rozetini göstermez', async () => {
    setAccessToken(TEST_TOKENS.user);
    renderShell('/sessions');

    const banner = await waitFor(() => screen.getByRole('banner'));
    expect(await within(banner).findByText('Sanal Yatırımcı')).toBeInTheDocument();
    expect(within(banner).getByRole('link', { name: 'Ayarlar' })).toBeInTheDocument();
    expect(within(banner).getByTestId('header-logout-btn')).toBeInTheDocument();
    expect(within(banner).queryByText(/^USER$/i)).not.toBeInTheDocument();
  });
});

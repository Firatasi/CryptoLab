import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/features/auth/AuthContext';
import { LandingPage } from '@/features/landing/LandingPage';
import { createTestQueryClient } from '@/app/App';

describe('LandingPage kompakt footer', () => {
  it('Hesap sütununu, Giriş Yap / Kayıt Ol ve Tarihsel Tekrar bağlantılarını göstermeden kompakt footer render eder', async () => {
    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <MemoryRouter>
            <LandingPage />
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    const footer = await waitFor(() => screen.getByRole('contentinfo'));
    expect(within(footer).getByText('CryptoLab')).toBeInTheDocument();
    expect(within(footer).getByRole('heading', { name: 'Kurumsal' })).toBeInTheDocument();
    expect(within(footer).getByRole('heading', { name: 'Altyapı' })).toBeInTheDocument();
    expect(within(footer).getByRole('link', { name: 'Canlı Grafik' })).toBeInTheDocument();
    expect(within(footer).getByRole('link', { name: 'İletişim' })).toBeInTheDocument();

    expect(within(footer).queryByRole('heading', { name: 'Hesap' })).not.toBeInTheDocument();
    expect(within(footer).queryByText('Giriş Yap')).not.toBeInTheDocument();
    expect(within(footer).queryByText('Kayıt Ol')).not.toBeInTheDocument();
    expect(within(footer).queryByText('Sanal Oturumlar')).not.toBeInTheDocument();
    expect(within(footer).queryByText('Tarihsel Tekrar')).not.toBeInTheDocument();
  });
});

import { afterEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { createTestQueryClient } from '@/app/App';
import { appRoutes } from '@/app/router';
import { AuthProvider } from '@/features/auth/AuthContext';
import { setAccessToken } from '@/shared/api/apiClient';

afterEach(() => {
  setAccessToken(null);
});

function renderRoute(initialEntry: string) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

function forceUnauthenticatedBootstrap() {
  server.use(
    http.post('/api/v1/auth/refresh', () =>
      HttpResponse.json({ code: 'UNAUTHORIZED', message: 'Oturum yok.' }, { status: 401 }),
    ),
  );
}

describe('Kayıt Ekranı CAPTCHA, İletişim Formu ve Admin Paneli', () => {
  it('Kayıt ekranında sanal hesap/endpoint metinlerini gizler, CAPTCHA doğrular ve kayıt tamamlar', async () => {
    forceUnauthenticatedBootstrap();
    let captchaFetchCount = 0;
    const submittedPayloads: Array<Record<string, unknown>> = [];

    server.use(
      http.get('/api/v1/auth/captcha', () => {
        captchaFetchCount += 1;
        return HttpResponse.json({
          captchaId: `cap-${captchaFetchCount}`,
          imageDataUri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjwvc3ZnPg==',
          expiresInSeconds: 300,
        });
      }),
      http.post('/api/v1/auth/register', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        submittedPayloads.push(body);
        if (String(body.captchaAnswer ?? '').toUpperCase() === 'WRONG') {
          return HttpResponse.json(
            { code: 'CAPTCHA_INVALID', message: 'Güvenlik doğrulama kodu hatalı.' },
            { status: 400 },
          );
        }
        return HttpResponse.json(
          {
            id: 'usr-new-99',
            displayName: String(body.displayName ?? 'Yeni Kullanıcı'),
            roles: ['USER'],
          },
          { status: 201 },
        );
      }),
    );

    const user = userEvent.setup();
    renderRoute('/register');

    expect(await screen.findByText('Yeni Hesap Oluştur')).toBeInTheDocument();
    expect(screen.queryByText(/KAYIT · SANAL HESAP/i)).not.toBeInTheDocument();
    expect(screen.queryByText('/api/v1/auth/register')).not.toBeInTheDocument();
    expect(screen.queryByText(/Gerçek para veya borsa API anahtarı istenmez/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/otomatik USER rolü atanır/i)).not.toBeInTheDocument();

    const captchaImg = await screen.findByAltText('Güvenlik doğrulama kodu');
    expect(captchaImg).toHaveAttribute('src', expect.stringContaining('data:image/svg+xml;base64,'));
    expect(captchaFetchCount).toBe(1);

    await user.click(screen.getByRole('button', { name: 'Kodu Yenile' }));
    await waitFor(() => expect(captchaFetchCount).toBe(2));

    await user.type(screen.getByLabelText(/Görünen Ad/i), 'Deneme Kullanıcı');
    await user.type(screen.getByLabelText(/E-posta Adresi/i), 'deneme@cryptolab.local');
    await user.type(screen.getByLabelText(/^Parola/i), 'GucluParola123!');

    await user.type(screen.getByLabelText(/Güvenlik Kodu/i), 'WRONG');
    await user.click(screen.getByRole('button', { name: /Hesap Oluştur ve Başla/i }));

    expect(await screen.findByText(/Güvenlik doğrulama kodu \(CAPTCHA\) hatalı/i)).toBeInTheDocument();
    await waitFor(() => expect(captchaFetchCount).toBe(3));

    await user.type(screen.getByLabelText(/Güvenlik Kodu/i), 'A7K3M');
    await user.click(screen.getByRole('button', { name: /Hesap Oluştur ve Başla/i }));

    await waitFor(() => expect(submittedPayloads).toHaveLength(2));
    expect(submittedPayloads[1]?.captchaId).toBe('cap-3');
    expect(submittedPayloads[1]?.captchaAnswer).toBe('A7K3M');
  });

  it('Ana sayfa iletişim formu support@cryptolab.dev alıcısına mesaj kaydeder', async () => {
    forceUnauthenticatedBootstrap();
    let capturedBody: Record<string, unknown> | null = null;

    server.use(
      http.post('/api/v1/auth/contact', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            id: 'msg-1',
            recipientEmail: 'support@cryptolab.dev',
            senderName: capturedBody.senderName,
            senderEmail: capturedBody.senderEmail,
            subject: capturedBody.subject,
            message: capturedBody.message,
            status: 'NEW',
            createdAt: '2026-11-13T12:00:00Z',
            readAt: null,
          },
          { status: 201 },
        );
      }),
    );

    const user = userEvent.setup();
    renderRoute('/');

    await user.type(screen.getByLabelText(/Ad Soyad/i), 'Mehmet Demir');
    await user.type(screen.getByLabelText(/E-Posta Adresi/i), 'mehmet@ornek.com');
    await user.type(screen.getByLabelText(/^Konu/i), 'Hesap Desteği');
    await user.type(screen.getByLabelText(/Mesajınız/i), 'Merhaba, destek talebim var.');
    await user.click(screen.getByRole('button', { name: /Mesajı Gönder/i }));

    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody).toMatchObject({
      senderName: 'Mehmet Demir',
      senderEmail: 'mehmet@ornek.com',
      subject: 'Hesap Desteği',
      recipientEmail: 'support@cryptolab.dev',
    });
    expect(await screen.findByText(/destek kutusuna ve yönetici paneline/i)).toBeInTheDocument();
  });

  it('admin.com girişi /admin paneline yönlendirir, canlı kullanıcı ve formları yönetir', async () => {
    forceUnauthenticatedBootstrap();
    const user = userEvent.setup();
    renderRoute('/login');

    const emailInput = await screen.findByLabelText(/E-posta Adresi/i);
    expect(screen.queryByText(/SANAL İŞLEM TERMİNALİ/i)).not.toBeInTheDocument();
    expect(screen.queryByText('/api/v1/auth/login')).not.toBeInTheDocument();
    await user.type(emailInput, 'admin.com');
    await user.type(screen.getByLabelText(/Parola/i), 'cryptoadmin15');
    await user.click(screen.getByRole('button', { name: /Giriş Yap/i }));

    expect(await screen.findByText('Sistem ve İletişim Yönetimi')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Admin Paneli' }).length).toBeGreaterThanOrEqual(1);
    expect(await screen.findByTestId('live-user-count')).toHaveTextContent('3');
    expect(screen.getByTestId('new-contact-count')).toHaveTextContent('1');
    expect(screen.getByText('Teknik Destek Talebi')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Okundu İşaretle/i }));
    await waitFor(() => expect(screen.getByTestId('new-contact-count')).toHaveTextContent('0'));
    expect(screen.getAllByText('Okundu').length).toBeGreaterThanOrEqual(2);
  });

  it('Standart USER kullanıcısı /admin rotasına erişemez ve Admin Paneli linkini görmez', async () => {
    renderRoute('/admin');

    await waitFor(() => {
      expect(screen.queryByRole('main')).toBeInTheDocument();
      expect(screen.getByRole('main')).toHaveTextContent(/Yetkisiz Erişim \(403 FORBIDDEN\)/i);
    }, { timeout: 3000 });
    expect(screen.queryByRole('link', { name: 'Admin Paneli' })).not.toBeInTheDocument();
  });

  it('Ayarlar ekranı teknik JWT/authority metinlerini göstermez ve canlı saat önizlemesini günceller', async () => {
    const user = userEvent.setup();
    renderRoute('/settings');

    expect(await screen.findByText('Hesap ve Görünüm Ayarları')).toBeInTheDocument();
    expect(screen.queryByText(/bellek içi imzalı JWT claim/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Özel İzinler \(Authorities\)/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Oturum Güvenliği/i)).not.toBeInTheDocument();

    expect(screen.getByTestId('timezone-preview')).toHaveTextContent(/TSİ/);
    await user.selectOptions(screen.getByLabelText(/Zaman Dilimi/i), 'UTC');
    expect(screen.getByTestId('timezone-preview')).toHaveTextContent(/UTC/);
  });
});

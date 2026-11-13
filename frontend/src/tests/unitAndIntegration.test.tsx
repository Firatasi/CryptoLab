import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
import { TEST_TOKENS } from './mocks/handlers';
import {
  compareDecimalStrings,
  parsePositiveDecimalInput,
} from '@/shared/formatting/decimalParser';
import {
  formatMoney,
  formatPrice,
  formatSignedMoney,
} from '@/shared/formatting/formatters';
import {
  apiRequest,
  getAccessToken,
  setAccessToken,
} from '@/shared/api/apiClient';
import {
  createSseChunkParser,
  SseSequenceGuard,
} from '@/shared/stream/sseParser';
import type { ParsedSseEvent } from '@/shared/contracts/types';
import { AuthProvider } from '@/features/auth/AuthContext';
import { appRoutes } from '@/app/router';
import { createTestQueryClient } from '@/app/App';

function renderWithAppRouter(initialEntries: string[] = ['/']) {
  const queryClient = createTestQueryClient();
  const router = createMemoryRouter(appRoutes, { initialEntries });
  return {
    queryClient,
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

describe('1. Ondalık Ayrıştırıcı (Decimal Parser) ve Finansal Biçimlendirme', () => {
  it('Türkçe virgülü noktaya dönüştürür ve hassasiyeti string olarak korur', () => {
    const res = parsePositiveDecimalInput('1250,75', 8);
    expect(res).toEqual({ valid: true, normalized: '1250.75' });
  });

  it('Hem nokta hem virgül içeren belirsiz binlik ayırıcıları reddeder', () => {
    const res = parsePositiveDecimalInput('1.250,75', 8);
    expect(res.valid).toBe(false);
    if (!res.valid) {
      expect(res.code).toBe('AMBIGUOUS_SEPARATOR');
    }
  });

  it('Sıfır, negatif ve bilimsel gösterimleri (1e5) reddeder', () => {
    expect(parsePositiveDecimalInput('0').valid).toBe(false);
    expect(parsePositiveDecimalInput('-10').valid).toBe(false);
    expect(parsePositiveDecimalInput('1e5').valid).toBe(false);
  });

  it('IEEE-754 kaybı olmadan yüksek duyarlıklı string karşılaştırması ve Türkçe biçimlendirme yapar', () => {
    expect(compareDecimalStrings('64250.00000002', '64250.00000001')).toBe(1);
    expect(compareDecimalStrings('100.50', '100.5')).toBe(0);
    expect(formatPrice('64250.50')).toBe('64.250,50');
    expect(formatMoney('10000.00')).toBe('10.000,00 USDT');
    expect(formatSignedMoney('18.87')).toBe('+18,87 USDT');
  });
});

describe('2. Kimlik Doğrulama, Single-Flight Refresh ve Oturum İzolasyonu', () => {
  it('Access token yalnızca bellekte tutulur; localStorage veya sessionStorage içine asla yazılmaz', () => {
    setAccessToken(TEST_TOKENS.user);
    expect(getAccessToken()).toBe(TEST_TOKENS.user);
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(sessionStorage.getItem('accessToken')).toBeNull();
    expect(Object.keys(localStorage)).toHaveLength(0);
    setAccessToken(null);
  });

  it('Eşzamanlı 401 yanıtlarında yalnızca TEK bir /api/v1/auth/refresh çağrısı yapar (Single-Flight)', async () => {
    let refreshCallCount = 0;
    setAccessToken('expired-token');

    server.use(
      http.get('/api/v1/sim/sessions', ({ request }) => {
        const auth = request.headers.get('Authorization');
        if (auth === 'Bearer expired-token') {
          return HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 });
        }
        return HttpResponse.json({ items: [] });
      }),
      http.post('/api/v1/auth/refresh', ({ request }) => {
        refreshCallCount += 1;
        expect(request.headers.get('X-CSRF')).toBe('1');
        return HttpResponse.json({
          accessToken: TEST_TOKENS.user,
          expiresIn: 900,
        });
      }),
    );

    const [r1, r2, r3] = await Promise.all([
      apiRequest('/sim/sessions'),
      apiRequest('/sim/sessions'),
      apiRequest('/sim/sessions'),
    ]);

    expect(refreshCallCount).toBe(1);
    expect(r1).toEqual({ items: [] });
    expect(r2).toEqual({ items: [] });
    expect(r3).toEqual({ items: [] });
    setAccessToken(null);
  });

  it('Kullanıcı çıkış yaptığında bellek içi token ve kullanıcı durumu tamamen temizlenir', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    renderWithAppRouter(['/sessions']);

    await waitFor(() => {
      expect(screen.getByText('Sanal Yatırımcı')).toBeInTheDocument();
    });

    await user.click(screen.getByTestId('header-logout-btn'));

    await waitFor(() => {
      expect(getAccessToken()).toBeNull();
    });
  });
});

describe('3. Emir Bileti, Sunucu ExecutionPlan Onayı ve Strict Submit Sözleşmesi', () => {
  it('Önizleme sonrasında form alanı değişirse eski ExecutionPlan geçersiz kılınır ve yeniden önizleme zorunlu olur', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    renderWithAppRouter(['/trade/sess-1?instrumentId=BTCUSDT']);

    // Katalogdan BTCUSDT seç
    const btcItems = await screen.findAllByText('BTCUSDT');
    await user.click(btcItems[0]);

    const previewBtn = await screen.findByRole('button', {
      name: /Sunucu Emir Önizlemesi Al/i,
    });
    await user.click(previewBtn);

    const planCard = await screen.findByTestId('execution-plan-card');
    expect(planCard).toBeInTheDocument();

    // Kullanıcı teminat bütçesini değiştirir -> Önizleme anında düşmeli!
    const marginInput = screen.getByLabelText(/Teminat Bütçesi/i);
    await user.clear(marginInput);
    await user.type(marginInput, '1500');

    expect(screen.queryByTestId('execution-plan-card')).not.toBeInTheDocument();
  });

  it('POST /api/v1/sim/orders çağrısında YALNIZCA {planId, revision, planHash} gönderir ve 202 ACCEPTED durumunu FILLED sanmaz', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    let capturedOrderBody: Record<string, unknown> | null = null;
    let capturedIdempotencyKey: string | null = null;

    server.use(
      http.post('/api/v1/sim/orders', async ({ request }) => {
        capturedOrderBody = (await request.json()) as Record<string, unknown>;
        capturedIdempotencyKey = request.headers.get('Idempotency-Key');
        return HttpResponse.json(
          {
            commandId: 'cmd-202',
            status: 'ACCEPTED',
            statusUrl: '/api/v1/sim/commands/cmd-202',
            acceptedAt: '2026-11-06T14:30:05Z',
          },
          { status: 202 },
        );
      }),
    );

    renderWithAppRouter(['/trade/sess-1?instrumentId=BTCUSDT']);

    const btcItems = await screen.findAllByText('BTCUSDT');
    await user.click(btcItems[0]);

    await user.click(
      await screen.findByRole('button', { name: /Sunucu Emir Önizlemesi Al/i }),
    );
    const submitBtn = await screen.findByRole('button', {
      name: /Planı Onayla ve Emri Gönder/i,
    });
    await user.click(submitBtn);

    await waitFor(() => {
      expect(capturedOrderBody).toEqual({
        planId: 'plan-101',
        revision: 1,
        planHash: 'sha256:planhash101abcdef',
      });
    });
    expect(capturedIdempotencyKey).toBeTruthy();

    expect(
      await screen.findByText(/Emir komutu sunucu tarafından kabul edildi \(202 ACCEPTED\)/i),
    ).toBeInTheDocument();
  });

  it('409 REVALIDATION_REQUIRED döndüğünde eski planı düşürür ve kullanıcıyı uyarır', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);

    server.use(
      http.post('/api/v1/sim/orders', () => {
        return HttpResponse.json(
          {
            code: 'REVALIDATION_REQUIRED',
            message: 'Gösterge fiyatı değişti, yeni önizleme gerekli.',
          },
          { status: 409 },
        );
      }),
    );

    renderWithAppRouter(['/trade/sess-1?instrumentId=BTCUSDT']);

    const btcItems = await screen.findAllByText('BTCUSDT');
    await user.click(btcItems[0]);

    await user.click(
      await screen.findByRole('button', { name: /Sunucu Emir Önizlemesi Al/i }),
    );
    await user.click(
      await screen.findByRole('button', { name: /Planı Onayla ve Emri Gönder/i }),
    );

    expect(
      await screen.findByText(
        /Piyasa fiyatı veya risk parametreleri onay sınırlarının dışına çıktı/i,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('execution-plan-card')).not.toBeInTheDocument();
  });
});

describe('4. AI Analiz: WAIT vs FAILED Ayrımı ve Şablon (TEMPLATE) Dürüstlüğü', () => {
  it('WAIT kararında emir önerisi veya emir gönderme butonu ASLA göstermez ve TEMPLATE rozetini açıkça etiketler', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    renderWithAppRouter(['/analysis/sess-1']);

    const startBtn = await screen.findByRole('button', {
      name: /AI Analizi Başlat/i,
    });
    await user.click(startBtn);

    const waitCard = await screen.findByTestId('analysis-wait-card');
    expect(waitCard).toBeInTheDocument();
    expect(
      screen.getByText(/KARAR: WAIT \(EKONOMİK ÇEKİMSERLİK\)/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/ŞABLON AÇIKLAMA \(TEMPLATE/i),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('transfer-proposal-btn')).not.toBeInTheDocument();
  });

  it('FAILED / MODEL_UNAVAILABLE teknik hatasını ekonomik WAIT kararı olarak göstermez', async () => {
    setAccessToken(TEST_TOKENS.user);
    sessionStorage.setItem(
      'cryptolab.jobs.usr-trader-1',
      JSON.stringify({ analysisBySession: { 'sess-1': 'anl-failed' } }),
    );

    renderWithAppRouter(['/analysis/sess-1']);

    expect(
      await screen.findByText(
        /Teknik Analiz Hatası \(FAILED\) — Bu bir ekonomik WAIT kararı değildir/i,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('analysis-wait-card')).not.toBeInTheDocument();
  });
});

describe('5. Eğitici (Trainer) RBAC, 20 MiB Sınırı ve MODEL_PUBLISH Yetki Ayrımı', () => {
  it('Standart USER rolündeki kullanıcının /training/* ekranlarına erişimini 403 ile engeller', async () => {
    setAccessToken(TEST_TOKENS.user);
    renderWithAppRouter(['/training/sources']);

    expect(
      await screen.findByText(/Yetkisiz Erişim \(403 FORBIDDEN\)/i),
    ).toBeInTheDocument();
  });

  it('TRAINER rolü olup MODEL_PUBLISH yetkisi olmayan kullanıcıda Promote ve Rollback butonlarını devre dışı bırakır', async () => {
    setAccessToken(TEST_TOKENS.trainerNoPublish);
    renderWithAppRouter(['/training/models']);

    expect(
      await screen.findByText(/MODEL_PUBLISH Yetkisi Bulunmuyor/i),
    ).toBeInTheDocument();

    const promoteBtn = await screen.findByTestId('promote-model-btn');
    const rollbackBtn = await screen.findByTestId('rollback-model-btn');
    expect(promoteBtn).toBeDisabled();
    expect(rollbackBtn).toBeDisabled();
  });

  it('MODEL_PUBLISH yetkisine sahip kullanıcıda Promote ve Rollback butonlarını aktif eder', async () => {
    setAccessToken(TEST_TOKENS.trainerWithPublish);
    renderWithAppRouter(['/training/models']);

    const promoteBtn = await screen.findByTestId('promote-model-btn');
    const rollbackBtn = await screen.findByTestId('rollback-model-btn');
    expect(promoteBtn).not.toBeDisabled();
    expect(rollbackBtn).not.toBeDisabled();
  });
});

describe('6. SSE Akış Ayrıştırıcı ve Epoch:Sequence Koruması (SseRing uyumu)', () => {
  it('Parçalı (chunked) SSE çerçevelerini ayrıştırır, tekrar eden sequence olaylarını yoksayar ve boşlukta resync tetikler', () => {
    const frames: ParsedSseEvent[] = [];
    const parser = createSseChunkParser({
      onEvent: (f) => frames.push(f),
    });

    parser.push('id: epoch1:1\nevent: price.tick\nda');
    parser.push('ta: {"markPrice":"64245.10"}\n\n');
    parser.push('id: epoch1:1\nevent: price.tick\ndata: {"markPrice":"64245.10"}\n\n');
    parser.push('id: epoch1:5\nevent: price.tick\ndata: {"markPrice":"64300.00"}\n\n');

    expect(frames).toHaveLength(3);

    const guard = new SseSequenceGuard();
    expect(guard.evaluate(frames[0])).toEqual({ accept: true, resyncRequired: false });
    expect(guard.evaluate(frames[1])).toEqual({ accept: false, resyncRequired: false });
    expect(guard.evaluate(frames[2])).toEqual({ accept: true, resyncRequired: true });
    expect(guard.getLastEventId()).toBe('epoch1:5');
  });
});

describe('7. Raporlama (BUILDING Dürüstlüğü), Kısmi Tarama (PARTIAL), Replay ve PDF 20 MiB Sınırı', () => {
  it('AccountSummary coverage=BUILDING olduğunda hesabı boş göstermez, açık uyarı verir', async () => {
    setAccessToken(TEST_TOKENS.user);
    server.use(
      http.get('/api/v1/account/me/summary', () => {
        return HttpResponse.json({
          sessionId: 'sess-1',
          mode: 'LIVE_PAPER',
          currency: 'USDT',
          realizedPnl: null,
          totalFees: null,
          signedFunding: null,
          liquidationFees: null,
          netRealizedPnl: null,
          unrealizedPnl: null,
          totalNetPnl: null,
          insuranceAdjustment: null,
          closedTradeCount: null,
          coverage: 'BUILDING',
          projectionAsOf: null,
          lagMs: 1200,
        });
      }),
    );

    renderWithAppRouter(['/reports/sess-1']);

    expect(
      await screen.findByText(
        /Projeksiyon İnşa Ediliyor \(BUILDING\) — Bu Hesap Boş Değildir/i,
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Başlangıç Sanal Teminatı \(Kâr Değildir\)/i),
    ).toBeInTheDocument();
  });

  it('Piyasa taraması PARTIAL döndüğünde tüm piyasa taranmış gibi davranmaz, kısmi kapsam uyarısı gösterir', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    renderWithAppRouter(['/scan/sess-1']);

    await user.click(
      await screen.findByRole('button', { name: /Yeni Tarama Başlat/i }),
    );

    expect(
      await screen.findByText(
        /Kısmi Tarama Kapsamı \(PARTIAL\) — Tüm Piyasa Taranmadı/i,
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: /Sonraki Sayfayı Tara \(Cursor: scan-cursor-2\)/i,
      }),
    ).toBeInTheDocument();
  });

  it('Tarihsel Replay TRADE_ONLY / APPROXIMATE metodolojik uyarısını ve belirsiz bar sayısını (ambiguousBarCount) açıkça gösterir', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.user);
    renderWithAppRouter(['/replay']);

    await user.click(
      await screen.findByRole('button', { name: /Tarihsel Replay Başlat/i }),
    );

    expect(
      await screen.findByText(/Metodolojik Uyarı: TRADE_ONLY \/ Sadakat: APPROXIMATE/i),
    ).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByTestId('replay-download-csv-btn')).toBeInTheDocument();
  });

  it('Eğitici alanında 20 MiB üzeri PDF dosyasını istemci tarafında reddeder ve geçerli PDF yüklemesinde "Model Henüz Eğitilmedi" mesajı gösterir', async () => {
    const user = userEvent.setup();
    setAccessToken(TEST_TOKENS.trainerWithPublish);
    renderWithAppRouter(['/training/sources']);

    const fileInput = (await screen.findByTestId(
      'training-pdf-input',
    )) as HTMLInputElement;

    const oversizedFile = new File(['x'], 'oversized.pdf', {
      type: 'application/pdf',
    });
    Object.defineProperty(oversizedFile, 'size', {
      value: 21 * 1024 * 1024,
    });

    await user.upload(fileInput, oversizedFile);
    expect(
      await screen.findByText(/Seçilen PDF dosyası 20 MiB sınırını aşıyor/i),
    ).toBeInTheDocument();

    const validFile = new File(['%PDF-1.4 sample'], 'valid-notes.pdf', {
      type: 'application/pdf',
    });
    await user.upload(fileInput, validFile);

    const titleInput = screen.getByLabelText(/Kaynak Başlığı/i);
    await user.type(titleInput, 'Mikroyapı Makalesi');

    await user.click(
      screen.getByRole('button', { name: /PDF Kaynağını Yükle/i }),
    );

    expect(
      await screen.findByText(/Kaynak İşleme Alındı \(Model Henüz Eğitilmedi\)/i),
    ).toBeInTheDocument();
  });
});

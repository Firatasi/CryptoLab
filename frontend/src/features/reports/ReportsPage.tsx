import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { getAccountSummary } from './api';
import { sessionsApi } from '@/features/sessions/api';
import { useAuth } from '@/features/auth/AuthContext';
import { queryKeys } from '@/shared/api/queryKeys';
import {
  formatMoney,
  formatSignedMoney,
  formatTimestamp,
  pnlToneClass,
} from '@/shared/formatting/formatters';
import {
  Badge,
  Button,
  Card,
  CardHeader,
} from '@/shared/ui/Primitives';
import { ErrorBanner } from '@/shared/ui/Feedback';

export function ReportsPage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const { timezone } = useAuth();

  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: ({ signal }) => sessionsApi.getById(sessionId, signal),
    enabled: Boolean(sessionId),
  });

  const summaryQuery = useQuery({
    queryKey: queryKeys.account.summary(sessionId),
    queryFn: ({ signal }) => getAccountSummary(sessionId, signal),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => {
      return query.state.data?.coverage === 'BUILDING' ? 1500 : 5000;
    },
  });

  const session = sessionQuery.data;
  const summary = summaryQuery.data;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-none border border-[#e5e5e5] bg-white px-4 py-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-lg font-bold tracking-tight text-[#0a0a0a]">
              Hesap Özeti & Performans Raporu
            </h1>
            <Badge tone="accent">OTURUM: {sessionId}</Badge>
            {session && <Badge tone="paper">{session.status}</Badge>}
          </div>
          <p className="text-xs text-[#737373]">
            Brüt ve net gerçekleşmiş PnL, işlem ücretleri, işaretli fonlama, likidasyon
            ücretleri ve sigorta fonu kesintileri ayrı ayrı raporlanır.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Link to={`/history/${encodeURIComponent(sessionId)}`}>
            <Button size="sm" variant="primary">
              İşlem Geçmişi (Fills)
            </Button>
          </Link>
          <Link to={`/trade/${encodeURIComponent(sessionId)}`}>
            <Button size="sm" variant="secondary">
              Emir Terminali
            </Button>
          </Link>
        </div>
      </div>

      {summaryQuery.isLoading ? (
        <Card className="p-12 text-center text-sm text-[#737373]">
          Hesap projeksiyonu yükleniyor...
        </Card>
      ) : summaryQuery.isError ? (
        <ErrorBanner
          error={summaryQuery.error}
          onRetry={() => void summaryQuery.refetch()}
        />
      ) : summary ? (
        <div className="space-y-4">
          {/* BUILDING / STALE Dürüstlük Uyarısı (BUILDING hiçbir zaman boş hesap olarak gösterilmez!) */}
          {summary.coverage === 'BUILDING' && (
            <div
              role="status"
              className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 text-xs text-[#0a0a0a]"
            >
              <strong>
                Projeksiyon İnşa Ediliyor (BUILDING) — Bu Hesap Boş Değildir:
              </strong>{' '}
              Raporlama servisi bu oturumun defter (ledger) olaylarını henüz birleştiriyor (
              <code className="font-mono">coverage=BUILDING</code>). Aşağıdaki rakamlar projeksiyon tamamlandığında (
              <code className="font-mono">READY</code>) kesinleşecektir; sıfır bakiye veya boş hesap anlamına gelmez.
            </div>
          )}

          {summary.coverage === 'STALE' && (
            <div
              role="status"
              className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 text-xs text-[#0a0a0a]"
            >
              <strong>
                Projeksiyon Gecikmeli (STALE — Gecikme: {summary.lagMs ?? 0} ms):
              </strong>{' '}
              Raporlama projeksiyonu son defter olaylarının gerisinde kalmış olabilir. Son
              projeksiyon zamanı: {formatTimestamp(summary.projectionAsOf, timezone)}.
            </div>
          )}

          {/* Sermaye vs PnL Ayrımı (Başlangıç depozitosu kâr olarak gösterilmez!) */}
          <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
            <Card className="p-4">
              <div className="text-xs text-[#737373]">
                Başlangıç Sanal Teminatı (Kâr Değildir)
              </div>
              <div className="mt-1 font-mono text-lg font-bold text-[#0a0a0a]">
                {session ? formatMoney(session.initialDeposit, session.currency) : '—'}
              </div>
              <div className="mt-1 text-[11px] text-[#737373]">
                Oturum açılışında yatırılan anapara
              </div>
            </Card>

            <Card className="p-4">
              <div className="text-xs text-[#737373]">
                Brüt Gerçekleşmiş PnL (Maliyet Öncesi)
              </div>
              <div
                className={`mt-1 font-mono text-lg font-bold ${pnlToneClass(
                  summary.realizedPnl,
                )}`}
              >
                {summary.coverage === 'BUILDING'
                  ? 'Hesaplanıyor...'
                  : formatSignedMoney(summary.realizedPnl, summary.currency)}
              </div>
              <div className="mt-1 text-[11px] text-[#737373]">
                Kapanan {summary.closedTradeCount ?? 0} işlem brüt sonucu
              </div>
            </Card>

            <Card className="p-4">
              <div className="text-xs text-[#737373]">
                Net Gerçekleşmiş PnL (Maliyet Sonrası)
              </div>
              <div
                className={`mt-1 font-mono text-lg font-bold ${pnlToneClass(
                  summary.netRealizedPnl,
                )}`}
              >
                {summary.coverage === 'BUILDING'
                  ? 'Hesaplanıyor...'
                  : formatSignedMoney(summary.netRealizedPnl, summary.currency)}
              </div>
              <div className="mt-1 text-[11px] text-[#737373]">
                Ücret, fonlama ve likidasyon sonrası net
              </div>
            </Card>

            <Card className="p-4">
              <div className="text-xs text-[#737373]">
                Toplam Net PnL (Gerçekleşmiş + Açık)
              </div>
              <div
                className={`mt-1 font-mono text-lg font-bold ${pnlToneClass(
                  summary.totalNetPnl,
                )}`}
              >
                {summary.coverage === 'BUILDING'
                  ? 'Hesaplanıyor...'
                  : formatSignedMoney(summary.totalNetPnl, summary.currency)}
              </div>
              <div className="mt-1 text-[11px] text-[#737373]">
                Açık PnL: {formatSignedMoney(summary.unrealizedPnl, summary.currency)}
              </div>
            </Card>
          </div>

          <Card>
            <CardHeader
              title="Maliyet, Fonlama ve Likidasyon Muhasebesi"
              subtitle={`Projeksiyon Durumu: ${summary.coverage} · Zaman: ${formatTimestamp(
                summary.projectionAsOf,
                timezone,
              )} · Gecikme: ${summary.lagMs ?? 0} ms`}
              action={
                <Badge
                  tone={
                    summary.coverage === 'READY'
                      ? 'success'
                      : summary.coverage === 'BUILDING'
                        ? 'wait'
                        : 'danger'
                  }
                >
                  KAPSAM: {summary.coverage}
                </Badge>
              }
            />

            <div className="grid grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-5">
              <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                <div className="text-xs text-[#737373]">Toplam İşlem Ücretleri (Fees)</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a]">
                  {formatMoney(summary.totalFees, summary.currency)}
                </div>
                <div className="mt-1 text-[11px] text-[#737373]">
                  Maker / Taker komisyon toplamı
                </div>
              </div>

              <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                <div className="text-xs text-[#737373]">
                  İşaretli Fonlama (Signed Funding)
                </div>
                <div
                  className={`mt-1 font-mono text-sm font-semibold ${pnlToneClass(
                    summary.signedFunding,
                  )}`}
                >
                  {formatSignedMoney(summary.signedFunding, summary.currency)}
                </div>
                <div className="mt-1 text-[11px] text-[#737373]">
                  (+) Alınan / (−) Ödenen fonlama
                </div>
              </div>

              <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                <div className="text-xs text-[#737373]">Likidasyon Cezaları</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[#dc2626]">
                  {formatMoney(summary.liquidationFees, summary.currency)}
                </div>
                <div className="mt-1 text-[11px] text-[#737373]">
                  Zorunlu tasfiye kesintileri
                </div>
              </div>

              <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                <div className="text-xs text-[#737373]">
                  Sigorta Fonu Düzeltmesi (Insurance)
                </div>
                <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a]">
                  {formatSignedMoney(summary.insuranceAdjustment, summary.currency)}
                </div>
                <div className="mt-1 text-[11px] text-[#737373]">
                  İflas / sigorta tamamlaması
                </div>
              </div>

              <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                <div className="text-xs text-[#737373]">Kapanan İşlem Sayısı</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a]">
                  {summary.closedTradeCount ?? 0}
                </div>
                <div className="mt-1 text-[11px] text-[#737373]">
                  Tam veya kısmi kapanış adedi
                </div>
              </div>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  );
}

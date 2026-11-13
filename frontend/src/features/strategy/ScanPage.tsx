import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { cancelScan, createScan, getScan } from './api';
import { useAuth } from '@/features/auth/AuthContext';
import { queryKeys } from '@/shared/api/queryKeys';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import {
  loadPersistedJobs,
  savePersistedJobs,
} from '@/shared/storage/jobPersistence';
import {
  formatHorizon,
  formatReasonCode,
  formatTimestamp,
} from '@/shared/formatting/formatters';
import {
  Badge,
  Button,
  Card,
  CardHeader,
} from '@/shared/ui/Primitives';
import { FieldGroup, SelectInput } from '@/shared/ui/FormControls';
import { ErrorBanner } from '@/shared/ui/Feedback';
import type { HorizonSeconds, ScanStatus } from '@/shared/contracts/types';

const TERMINAL_SCAN_STATUSES: ScanStatus[] = [
  'SUCCEEDED',
  'COMPLETED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
];

export function ScanPage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const { user, timezone } = useAuth();

  const [horizonSeconds, setHorizonSeconds] = useState<HorizonSeconds>(3600);
  const [pageLimit, setPageLimit] = useState<number>(25);
  const [maxAnalysesBudget, setMaxAnalysesBudget] = useState<number>(10);
  const [activeScanId, setActiveScanId] = useState<string | null>(() => {
    const persisted = loadPersistedJobs(user?.id);
    return persisted.scanBySession?.[sessionId] ?? null;
  });

  useEffect(() => {
    if (!user?.id || !sessionId) return;
    savePersistedJobs(user.id, (prev) => ({
      ...prev,
      scanBySession: {
        ...(prev.scanBySession ?? {}),
        ...(activeScanId ? { [sessionId]: activeScanId } : {}),
      },
    }));
  }, [activeScanId, sessionId, user?.id]);

  const scanQuery = useQuery({
    queryKey: queryKeys.strategy.scan(activeScanId ?? 'none'),
    queryFn: ({ signal }) => getScan(activeScanId!, signal),
    enabled: Boolean(activeScanId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (!status || TERMINAL_SCAN_STATUSES.includes(status)) {
        return false;
      }
      return 1200;
    },
  });

  const startScanMutation = useMutation({
    mutationFn: (cursor?: string | null) =>
      createScan(
        {
          sessionId,
          horizonSeconds,
          pageLimit,
          maxAnalysesBudget,
          cursor: cursor ?? null,
        },
        createIdempotencyKey(),
      ),
    onSuccess: (res) => {
      setActiveScanId(res.scanId);
    },
  });

  const cancelScanMutation = useMutation({
    mutationFn: (scanId: string) => cancelScan(scanId),
    onSuccess: () => {
      void scanQuery.refetch();
    },
  });

  const scan = scanQuery.data;
  const isRunning = scan && (scan.status === 'QUEUED' || scan.status === 'RUNNING');

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-none border border-[#e5e5e5] bg-white px-4 py-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-lg font-bold tracking-tight text-[#0a0a0a]">
              AI Piyasa Taraması (Bounded Market Scan)
            </h1>
            <Badge tone="accent">OTURUM: {sessionId}</Badge>
          </div>
          <p className="text-xs text-[#737373]">
            Bütçe kontrollü çoklu enstrüman taraması. Kısmi (PARTIAL) taramalar hiçbir zaman
            &ldquo;tüm piyasa tarandı&rdquo; olarak sunulmaz.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link to={`/analysis/${encodeURIComponent(sessionId)}`}>
            <Button size="sm" variant="secondary">
              Tekil Analiz
            </Button>
          </Link>
          <Link to={`/trade/${encodeURIComponent(sessionId)}`}>
            <Button size="sm" variant="ghost">
              Emir Terminali
            </Button>
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-4 space-y-4">
          <Card>
            <CardHeader
              title="Tarama Bütçesi & Ufuk"
              subtitle="Sayfa sınırı ve maksimum analiz bütçesini belirleyin"
            />
            <form
              onSubmit={(e) => {
                e.preventDefault();
                startScanMutation.mutate(null);
              }}
              className="p-4 space-y-4"
            >
              <FieldGroup label="Tahmin Ufku (Horizon)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={String(horizonSeconds)}
                    onChange={(e) =>
                      setHorizonSeconds(Number(e.target.value) as HorizonSeconds)
                    }
                  >
                    <option value="60">60s — 1 Dakika</option>
                    <option value="900">900s — 15 Dakika</option>
                    <option value="3600">3600s — 1 Saat</option>
                    <option value="86400">86400s — 1 Gün</option>
                    <option value="604800">604800s — 1 Hafta</option>
                  </SelectInput>
                )}
              </FieldGroup>

              <FieldGroup label="Sayfa Enstrüman Limiti (pageLimit)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={String(pageLimit)}
                    onChange={(e) => setPageLimit(Number(e.target.value))}
                  >
                    <option value="10">10 Enstrüman</option>
                    <option value="25">25 Enstrüman</option>
                    <option value="50">50 Enstrüman</option>
                  </SelectInput>
                )}
              </FieldGroup>

              <FieldGroup label="Maksimum AI Analiz Bütçesi (maxAnalysesBudget)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={String(maxAnalysesBudget)}
                    onChange={(e) => setMaxAnalysesBudget(Number(e.target.value))}
                  >
                    <option value="5">5 Derin Analiz</option>
                    <option value="10">10 Derin Analiz</option>
                    <option value="25">25 Derin Analiz</option>
                  </SelectInput>
                )}
              </FieldGroup>

              {startScanMutation.isError && (
                <ErrorBanner
                  error={startScanMutation.error}
                  title="Tarama Başlatılamadı"
                />
              )}

              <div className="flex gap-2">
                <Button
                  type="submit"
                  variant="primary"
                  className="flex-1"
                  loading={startScanMutation.isPending}
                >
                  Yeni Tarama Başlat
                </Button>
                {isRunning && activeScanId && (
                  <Button
                    type="button"
                    variant="danger"
                    loading={cancelScanMutation.isPending}
                    onClick={() => cancelScanMutation.mutate(activeScanId)}
                  >
                    İptal Et
                  </Button>
                )}
              </div>
            </form>
          </Card>
        </div>

        <div className="lg:col-span-8 space-y-4">
          {!activeScanId ? (
            <Card className="p-12 text-center">
              <h2 className="text-base font-semibold text-[#0a0a0a]">
                Aktif Piyasa Taraması Yok
              </h2>
              <p className="mt-1 text-xs text-[#737373]">
                Soldaki panelden bütçe ve zaman ufkunu seçerek tarama başlatın.
              </p>
            </Card>
          ) : scanQuery.isLoading ? (
            <Card className="p-12 text-center text-sm text-[#737373]">
              Tarama durumu yükleniyor (<code className="font-mono text-[#0a0a0a]">{activeScanId}</code>)...
            </Card>
          ) : scanQuery.isError ? (
            <ErrorBanner
              error={scanQuery.error}
              onRetry={() => void scanQuery.refetch()}
            />
          ) : scan ? (
            <div className="space-y-4">
              {(scan.status === 'PARTIAL' || Boolean(scan.cursor)) && (
                <div
                  role="status"
                  className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 text-xs text-[#0a0a0a]"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div>
                      <strong>
                        Kısmi Tarama Kapsamı (PARTIAL) — Tüm Piyasa Taranmadı:
                      </strong>{' '}
                      Bütçe veya sayfa sınırı nedeniyle bilinen{' '}
                      <strong>{scan.totalKnown}</strong> enstrümanın yalnızca{' '}
                      <strong>{scan.evaluatedCount}</strong> tanesi değerlendirildi,{' '}
                      <strong>{scan.skippedCount}</strong> tanesi atlandı.
                    </div>
                    {scan.cursor && (
                      <Button
                        size="sm"
                        variant="secondary"
                        loading={startScanMutation.isPending}
                        onClick={() => startScanMutation.mutate(scan.cursor)}
                      >
                        Sonraki Sayfayı Tara (Cursor: {scan.cursor})
                      </Button>
                    )}
                  </div>
                </div>
              )}

              <Card>
                <CardHeader
                  title={`Tarama Raporu (${scan.id})`}
                  subtitle={`Güncellenme: ${formatTimestamp(
                    scan.updatedAt,
                    timezone,
                  )} · Ufuk: ${formatHorizon(scan.horizonSeconds)}`}
                  action={<Badge tone="accent">{scan.status}</Badge>}
                />

                <div className="p-4 space-y-4">
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Bilinen Evren</div>
                      <div className="mt-1 font-mono text-base font-bold text-[#0a0a0a]">
                        {scan.totalKnown}
                      </div>
                    </div>
                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Değerlendirilen</div>
                      <div className="mt-1 font-mono text-base font-bold text-[#0a0a0a]">
                        {scan.evaluatedCount}
                      </div>
                    </div>
                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Atlanan / Elenen</div>
                      <div className="mt-1 font-mono text-base font-bold text-[#0a0a0a]">
                        {scan.skippedCount}
                      </div>
                    </div>
                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Sonraki Cursor</div>
                      <div className="mt-1 font-mono text-xs text-[#0a0a0a]">
                        {scan.cursor ?? 'Tamamlandı (Son Sayfa)'}
                      </div>
                    </div>
                  </div>

                  {scan.reasonCounts && Object.keys(scan.reasonCounts).length > 0 && (
                    <div>
                      <div className="mb-1.5 text-xs font-semibold text-[#0a0a0a]">
                        Eleme / Çekimserlik Neden Dağılımı (Reason Counts)
                      </div>
                      <div className="flex flex-wrap gap-1.5">
                        {Object.entries(scan.reasonCounts).map(([code, count]) => (
                          <Badge key={code} tone="neutral">
                            {count}× {formatReasonCode(code)}
                          </Badge>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </Card>

              <Card>
                <CardHeader
                  title="Taranan Enstrümanlar & Aday Sıralaması"
                  subtitle="Uygun adayları tekil analizde inceleyebilir veya emir ekranına geçebilirsiniz"
                />
                <div className="p-4">
                  {!scan.candidates || scan.candidates.length === 0 ? (
                    <p className="py-6 text-center text-xs text-[#737373]">
                      Bu taramada listelenecek aday bulunamadı.
                    </p>
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-left text-xs">
                        <thead>
                          <tr className="border-b border-[#e5e5e5] text-[#737373]">
                            <th className="py-2 pr-3">#</th>
                            <th className="py-2 px-3">Enstrüman</th>
                            <th className="py-2 px-3">Uygunluk</th>
                            <th className="py-2 px-3">Durum / Neden Kodu</th>
                            <th className="py-2 pl-3 text-right">İşlem</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-[#e5e5e5]">
                          {scan.candidates.map((cand) => (
                            <tr key={cand.instrumentId} className="hover:bg-[#f5f5f5]">
                              <td className="py-2.5 pr-3 font-mono text-[#737373]">
                                {cand.positionIndex}
                              </td>
                              <td className="py-2.5 px-3 font-mono font-semibold text-[#0a0a0a]">
                                {cand.instrumentId}
                              </td>
                              <td className="py-2.5 px-3">
                                {cand.eligible ? (
                                  <Badge tone="success">● UYGUN ADAY</Badge>
                                ) : (
                                  <Badge tone="wait">◆ ATLANDI</Badge>
                                )}
                              </td>
                              <td className="py-2.5 px-3 text-[#525252]">
                                {cand.skippedReason
                                  ? formatReasonCode(cand.skippedReason)
                                  : cand.analysisId
                                    ? `Analiz: ${cand.analysisId}`
                                    : 'Analize Uygun'}
                              </td>
                              <td className="py-2.5 pl-3 text-right">
                                <div className="inline-flex gap-1.5">
                                  <Link
                                    to={`/analysis/${encodeURIComponent(
                                      sessionId,
                                    )}?instrumentId=${encodeURIComponent(cand.instrumentId)}`}
                                  >
                                    <Button size="xs" variant="secondary">
                                      Analiz Et
                                    </Button>
                                  </Link>
                                  {cand.eligible && (
                                    <Link
                                      to={`/trade/${encodeURIComponent(
                                        sessionId,
                                      )}?instrumentId=${encodeURIComponent(cand.instrumentId)}`}
                                    >
                                      <Button size="xs" variant="primary">
                                        Emir Ekranı
                                      </Button>
                                    </Link>
                                  )}
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </Card>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

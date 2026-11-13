import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  cancelReplay,
  createReplay,
  downloadReplayCsvText,
  getReplay,
} from '../reports/api';
import { useAuth } from '@/features/auth/AuthContext';
import { queryKeys } from '@/shared/api/queryKeys';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import { parsePositiveDecimalInput } from '@/shared/formatting/decimalParser';
import {
  formatMoney,
  formatSignedMoney,
  pnlToneClass,
} from '@/shared/formatting/formatters';
import {
  loadPersistedJobs,
  savePersistedJobs,
} from '@/shared/storage/jobPersistence';
import {
  Badge,
  Button,
  Card,
  CardHeader,
} from '@/shared/ui/Primitives';
import {
  DecimalInput,
  FieldGroup,
  SelectInput,
  TextInput,
} from '@/shared/ui/FormControls';
import { toUserFacingErrorMessage } from '@/shared/api/apiErrors';
import { ErrorBanner } from '@/shared/ui/Feedback';
import type { ReplayDataLayer, ReplayStatus } from '@/shared/contracts/types';

const TERMINAL_REPLAY_STATUSES: ReplayStatus[] = [
  'SUCCEEDED',
  'INDETERMINATE',
  'FAILED',
  'CANCELLED',
];

export function ReplayPage() {
  const { user } = useAuth();

  const [datasetId, setDatasetId] = useState('ds-btc-2026-q1');
  const [ruleProfileId, setRuleProfileId] = useState('binance-usdm-perp-v1');
  const [policyArtifactId, setPolicyArtifactId] = useState('policy-conservative-v1');
  const [seed, setSeed] = useState('42');
  const [datasetHash, setDatasetHash] = useState(
    'sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
  );
  const [ruleHash, setRuleHash] = useState(
    'sha256:4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a',
  );
  const [policyHash, setPolicyHash] = useState(
    'sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  );
  const [tiePolicy, setTiePolicy] = useState('WORST_CASE_SL_FIRST');
  const [dataLayer, setDataLayer] = useState<ReplayDataLayer>('PAIRED_OHLC');
  const [initialDeposit, setInitialDeposit] = useState('10000');
  const [formError, setFormError] = useState<string | null>(null);
  const [csvNotice, setCsvNotice] = useState<string | null>(null);

  const [activeReplayId, setActiveReplayId] = useState<string | null>(() => {
    const persisted = loadPersistedJobs(user?.id);
    return persisted.activeReplayId ?? null;
  });

  useEffect(() => {
    if (!user?.id) return;
    savePersistedJobs(user.id, (prev) => ({
      ...prev,
      activeReplayId,
    }));
  }, [activeReplayId, user?.id]);

  const replayQuery = useQuery({
    queryKey: queryKeys.replay.detail(activeReplayId ?? 'none'),
    queryFn: ({ signal }) => getReplay(activeReplayId!, signal),
    enabled: Boolean(activeReplayId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (!status || TERMINAL_REPLAY_STATUSES.includes(status)) {
        return false;
      }
      return 1200;
    },
  });

  const startReplayMutation = useMutation({
    mutationFn: () => {
      const parsedDeposit = parsePositiveDecimalInput(initialDeposit, 8);
      if (!parsedDeposit.valid) {
        throw new Error(parsedDeposit.message);
      }
      const parsedSeed = Number(seed);
      if (!Number.isInteger(parsedSeed)) {
        throw new Error('Deterministik tohum (seed) tam sayı olmalıdır.');
      }
      return createReplay(
        {
          datasetId: datasetId.trim(),
          ruleProfileId: ruleProfileId.trim(),
          policyArtifactId: policyArtifactId.trim(),
          seed: parsedSeed,
          datasetHash: datasetHash.trim(),
          ruleHash: ruleHash.trim(),
          policyHash: policyHash.trim(),
          tiePolicy: tiePolicy.trim(),
          dataLayer,
          initialDeposit: parsedDeposit.normalized,
        },
        createIdempotencyKey(),
      );
    },
    onSuccess: (res) => {
      setFormError(null);
      setActiveReplayId(res.replayId);
    },
    onError: (err) => {
      setFormError(toUserFacingErrorMessage(err, 'Tarihsel tekrar başlatılamadı. Lütfen bilgilerinizi kontrol edin.'));
    },
  });

  const cancelMutation = useMutation({
    mutationFn: (replayId: string) => cancelReplay(replayId),
    onSuccess: () => {
      void replayQuery.refetch();
    },
  });

  const downloadCsvMutation = useMutation({
    mutationFn: async (replayId: string) => {
      const csvText = await downloadReplayCsvText(replayId);
      const blob = new Blob([csvText], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `cryptolab-replay-${replayId}.csv`;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);
      return csvText;
    },
    onSuccess: () => {
      setCsvNotice('CSV raporu yetkili oturum üzerinden başarıyla indirildi.');
    },
  });

  const replay = replayQuery.data;

  return (
    <div className="space-y-4">
      <div className="rounded-none border border-[#e5e5e5] bg-white px-4 py-3">
        <h1 className="text-lg font-bold tracking-tight text-[#0a0a0a]">
          Deterministik Tarihsel Replay & Geriye Dönük Simülasyon
        </h1>
        <p className="text-xs text-[#737373]">
          Sabit veri kümesi özeti (datasetHash), kural profili (ruleProfileId) ve politika
          artefaktı ile tekrarlanabilir tarihsel yürütme. Veri katmanı (DataLayer), sadakat
          (Fidelity) ve belirsiz bar sayısı (ambiguousBarCount) açıkça raporlanır.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-5 space-y-4">
          <Card>
            <CardHeader
              title="Replay Yapılandırması"
              subtitle="CreateReplayRequest sözleşmesiyle birebir uyumlu parametreler"
            />
            <form
              onSubmit={(e) => {
                e.preventDefault();
                setFormError(null);
                startReplayMutation.mutate();
              }}
              className="p-4 space-y-3"
            >
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <FieldGroup label="Veri Kümesi ID (datasetId)" required>
                  {(id) => (
                    <TextInput
                      id={id}
                      value={datasetId}
                      onChange={(e) => setDatasetId(e.target.value)}
                      required
                    />
                  )}
                </FieldGroup>
                <FieldGroup label="Kural Profili (ruleProfileId)" required>
                  {(id) => (
                    <TextInput
                      id={id}
                      value={ruleProfileId}
                      onChange={(e) => setRuleProfileId(e.target.value)}
                      required
                    />
                  )}
                </FieldGroup>
              </div>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <FieldGroup label="Politika Artefaktı (policyArtifactId)" required>
                  {(id) => (
                    <TextInput
                      id={id}
                      value={policyArtifactId}
                      onChange={(e) => setPolicyArtifactId(e.target.value)}
                      required
                    />
                  )}
                </FieldGroup>
                <FieldGroup label="Deterministik Tohum (seed)" required>
                  {(id) => (
                    <TextInput
                      id={id}
                      value={seed}
                      onChange={(e) => setSeed(e.target.value)}
                      required
                    />
                  )}
                </FieldGroup>
              </div>

              <FieldGroup label="Veri Katmanı (DataLayer)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={dataLayer}
                    onChange={(e) => setDataLayer(e.target.value as ReplayDataLayer)}
                  >
                    <option value="RECORDED_TICKS">
                      RECORDED_TICKS — Kayıtlı Tick + Mark/Index (Tam Sadakat)
                    </option>
                    <option value="PAIRED_OHLC">
                      PAIRED_OHLC — Eşleşmiş Trade + Mark OHLC Barları
                    </option>
                    <option value="TRADE_ONLY">
                      TRADE_ONLY — Yalnız İşlem Barı (Yaklaşık Mark/Likidasyon)
                    </option>
                  </SelectInput>
                )}
              </FieldGroup>

              <FieldGroup label="Aynı Bar Çakışma Kuralı (tiePolicy)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={tiePolicy}
                    onChange={(e) => setTiePolicy(e.target.value)}
                  >
                    <option value="WORST_CASE_SL_FIRST">
                      WORST_CASE_SL_FIRST — Kötümser (Önce Zarar Durdur)
                    </option>
                    <option value="CONSERVATIVE_SKIP">
                      CONSERVATIVE_SKIP — Belirsiz Barı İşaretle
                    </option>
                  </SelectInput>
                )}
              </FieldGroup>

              <FieldGroup label="Başlangıç Sanal Teminatı (USDT)" required>
                {(id) => (
                  <DecimalInput
                    id={id}
                    value={initialDeposit}
                    onChange={setInitialDeposit}
                    suffix="USDT"
                  />
                )}
              </FieldGroup>

              <FieldGroup label="Dataset Hash" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={datasetHash}
                    onChange={(e) => setDatasetHash(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>

              <FieldGroup label="Rule Hash" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={ruleHash}
                    onChange={(e) => setRuleHash(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>

              <FieldGroup label="Policy Hash" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={policyHash}
                    onChange={(e) => setPolicyHash(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>

              {formError && (
                <div
                  role="alert"
                  className="rounded-none border border-[#dc2626] bg-red-50 px-3 py-2 text-xs text-[#dc2626]"
                >
                  {formError}
                </div>
              )}

              <Button
                type="submit"
                variant="primary"
                className="w-full"
                loading={startReplayMutation.isPending}
              >
                Tarihsel Replay Başlat
              </Button>
            </form>
          </Card>
        </div>

        <div className="lg:col-span-7 space-y-4">
          {!activeReplayId ? (
            <Card className="p-12 text-center">
              <h2 className="text-base font-semibold text-[#0a0a0a]">
                Henüz Tarihsel Replay Başlatılmadı
              </h2>
              <p className="mt-1 text-xs text-[#737373]">
                Soldaki formdan veri katmanı ve kural profilini seçerek yeni bir replay işi
                başlatabilirsiniz.
              </p>
            </Card>
          ) : replayQuery.isLoading ? (
            <Card className="p-12 text-center text-sm text-[#737373]">
              Replay durumu sorgulanıyor (<code className="font-mono text-[#0a0a0a]">{activeReplayId}</code>)...
            </Card>
          ) : replayQuery.isError ? (
            <ErrorBanner
              error={replayQuery.error}
              onRetry={() => void replayQuery.refetch()}
            />
          ) : replay ? (
            <div className="space-y-4">
              {(replay.dataLayer === 'TRADE_ONLY' ||
                replay.fidelity === 'APPROXIMATE' ||
                replay.fidelity === 'DEGRADED') && (
                <div
                  role="status"
                  className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 text-xs text-[#0a0a0a]"
                >
                  <strong>
                    Metodolojik Uyarı: {replay.dataLayer} / Sadakat: {replay.fidelity}
                  </strong>{' '}
                  — Bu replay koşumu <strong>{replay.dataLayer}</strong> katmanında çalıştı.
                  Ayrı tarihsel <code className="font-mono">markPrice</code> veya{' '}
                  <code className="font-mono">indexPrice</code> akışı bulunmadığında likidasyon ve
                  fonlama hesaplamaları yaklaşık (<code className="font-mono">{replay.fidelity}</code>)
                  niteliktedir; birebir borsa eşleşmesi olarak yorumlanmamalıdır.
                </div>
              )}

              <Card>
                <CardHeader
                  title={`Replay Sonucu (${replay.replayId})`}
                  subtitle={`Oturum: ${replay.sessionId} · Tohum: ${replay.seed}`}
                  action={
                    <div className="flex items-center gap-2">
                      <Badge
                        tone={
                          replay.status === 'SUCCEEDED'
                            ? 'success'
                            : replay.status === 'FAILED'
                              ? 'danger'
                              : 'accent'
                        }
                      >
                        {replay.status}
                      </Badge>
                      {(replay.status === 'QUEUED' || replay.status === 'RUNNING') && (
                        <Button
                          size="xs"
                          variant="danger"
                          loading={cancelMutation.isPending}
                          onClick={() => cancelMutation.mutate(replay.replayId)}
                        >
                          İptal Et
                        </Button>
                      )}
                      {replay.status === 'SUCCEEDED' && (
                        <Button
                          size="xs"
                          variant="secondary"
                          loading={downloadCsvMutation.isPending}
                          onClick={() => downloadCsvMutation.mutate(replay.replayId)}
                          data-testid="replay-download-csv-btn"
                        >
                          CSV İndir
                        </Button>
                      )}
                    </div>
                  }
                />

                <div className="p-4 space-y-4">
                  {csvNotice && (
                    <div className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]">
                      {csvNotice}
                    </div>
                  )}

                  {downloadCsvMutation.isError && (
                    <ErrorBanner
                      error={downloadCsvMutation.error}
                      title="CSV İndirilemedi"
                    />
                  )}

                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Sadakat (Fidelity)</div>
                      <div className="mt-1">
                        <Badge
                          tone={
                            replay.fidelity === 'EXACT_WITHIN_MODEL'
                              ? 'success'
                              : replay.fidelity === 'APPROXIMATE'
                                ? 'wait'
                                : 'danger'
                          }
                        >
                          {replay.fidelity}
                        </Badge>
                      </div>
                    </div>

                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Veri Katmanı (DataLayer)</div>
                      <div className="mt-1">
                        <Badge tone="accent">{replay.dataLayer}</Badge>
                      </div>
                    </div>

                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">
                        Belirsiz Bar Sayısı (ambiguousBarCount)
                      </div>
                      <div className="mt-1 font-mono text-base font-bold text-[#0a0a0a]">
                        {replay.ambiguousBarCount}
                      </div>
                      <div className="text-[11px] text-[#737373]">
                        Aynı barda SL ve TP çakışması
                      </div>
                    </div>

                    <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                      <div className="text-xs text-[#737373]">Ekonomik Sağlama Özeti</div>
                      <div className="mt-1 truncate font-mono text-xs text-[#0a0a0a]">
                        {replay.economicChecksum ?? 'Hesaplanıyor...'}
                      </div>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <div className="rounded-none border border-[#e5e5e5] bg-white p-3">
                      <div className="text-xs text-[#737373]">Son Kullanılabilir Bakiye</div>
                      <div className="mt-1 font-mono text-sm font-semibold text-[#0a0a0a]">
                        {formatMoney(replay.finalAvailable)}
                      </div>
                    </div>

                    <div className="rounded-none border border-[#e5e5e5] bg-white p-3">
                      <div className="text-xs text-[#737373]">Brüt PnL</div>
                      <div
                        className={`mt-1 font-mono text-sm font-semibold ${pnlToneClass(
                          replay.grossPnl,
                        )}`}
                      >
                        {formatSignedMoney(replay.grossPnl)}
                      </div>
                    </div>

                    <div className="rounded-none border border-[#e5e5e5] bg-white p-3">
                      <div className="text-xs text-[#737373]">Net PnL (Maliyet Sonrası)</div>
                      <div
                        className={`mt-1 font-mono text-sm font-semibold ${pnlToneClass(
                          replay.netPnl,
                        )}`}
                      >
                        {formatSignedMoney(replay.netPnl)}
                      </div>
                    </div>

                    <div className="rounded-none border border-[#e5e5e5] bg-white p-3">
                      <div className="text-xs text-[#737373]">Ücretler / Fonlama</div>
                      <div className="mt-1 font-mono text-xs text-[#0a0a0a]">
                        Fee: {formatMoney(replay.totalFees)} | Fund:{' '}
                        {formatSignedMoney(replay.signedFunding)}
                      </div>
                    </div>
                  </div>
                </div>
              </Card>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

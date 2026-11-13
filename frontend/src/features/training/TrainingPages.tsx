import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { NavLink, Outlet } from 'react-router-dom';
import {
  cancelTrainingRun,
  createExampleJob,
  createTrainingRun,
  createTrainingUrlSource,
  deleteTrainingSource,
  freezeTrainingDataset,
  getTrainingModelCard,
  getTrainingRun,
  getTrainingSourcePage,
  listTrainingExamples,
  listTrainingModels,
  listTrainingSources,
  MAX_PDF_UPLOAD_BYTES,
  promoteTrainingModel,
  reviewTrainingExample,
  rollbackTrainingModel,
  uploadTrainingPdfSource,
} from './api';
import { useAuth } from '@/features/auth/AuthContext';
import { queryKeys } from '@/shared/api/queryKeys';
import {
  loadPersistedJobs,
  savePersistedJobs,
} from '@/shared/storage/jobPersistence';
import { formatTimestamp } from '@/shared/formatting/formatters';
import {
  Badge,
  Button,
  Card,
  CardHeader,
} from '@/shared/ui/Primitives';
import { FieldGroup, SelectInput, TextInput } from '@/shared/ui/FormControls';
import { ConfirmDialog } from '@/shared/ui/Modal';
import { ErrorBanner } from '@/shared/ui/Feedback';
import { toUserFacingErrorMessage } from '@/shared/api/apiErrors';
import type { TrainingSourceItem, TrainingTaskType } from '@/shared/contracts/types';

export function TrainingLayout() {
  const { user, hasAuthority } = useAuth();
  const canPublish = hasAuthority('MODEL_PUBLISH');

  const tabs = [
    { to: '/training/sources', label: '1. Eğitim Kaynakları (PDF / URL)' },
    { to: '/training/datasets', label: '2. Örnek İnceleme & Veri Kümesi' },
    { to: '/training/runs', label: '3. Eğitim Koşumları (Runs)' },
    { to: '/training/models', label: '4. Model Kartları & Yayınlama' },
  ];

  return (
    <div className="space-y-4">
      <div className="rounded-none border border-[#0a0a0a] bg-white px-4 py-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-lg font-bold tracking-tight text-[#0a0a0a]">
                İzole AI Eğitim & Model Yaşam Döngüsü Alanı
              </h1>
              <Badge tone="paper">TRAINER / ADMIN WORKSPACE</Badge>
              {canPublish ? (
                <Badge tone="success">MODEL_PUBLISH YETKİSİ AKTİF</Badge>
              ) : (
                <Badge tone="wait">
                  MODEL_PUBLISH YOK (Yalnız Eğitim/İnceleme)
                </Badge>
              )}
            </div>
            <p className="mt-0.5 text-xs text-[#525252]">
              Bu çalışma alanı sanal alım-satım terminalinden tamamen ayrıdır. PDF/URL
              kaynaklarının yüklenmesi doğrudan aktif modeli değiştirmez; örnek onayı,
              veri kümesi dondurma, eğitim koşumu ve <code className="font-mono text-[#0a0a0a]">MODEL_PUBLISH</code> yetkili
              terfi adımlarından geçer.
            </p>
          </div>
          <div className="text-xs text-[#737373]">
            Aktif Kullanıcı: <strong className="text-[#0a0a0a]">{user?.displayName}</strong>
          </div>
        </div>

        <nav className="mt-3 flex flex-wrap gap-2 border-t border-[#e5e5e5] pt-3">
          {tabs.map((tab) => (
            <NavLink
              key={tab.to}
              to={tab.to}
              className={({ isActive }) =>
                `rounded-none border px-3 py-1.5 text-xs font-semibold transition-colors ${
                  isActive
                    ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                    : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#525252] hover:border-[#0a0a0a] hover:text-[#0a0a0a]'
                }`
              }
            >
              {tab.label}
            </NavLink>
          ))}
        </nav>
      </div>

      <Outlet />
    </div>
  );
}

export function TrainingSourcesPage() {
  const queryClient = useQueryClient();
  const [uploadMode, setUploadMode] = useState<'PDF' | 'URL'>('PDF');
  const [title, setTitle] = useState('');
  const [author, setAuthor] = useState('');
  const [rightsDeclaration, setRightsDeclaration] = useState(
    'Kurumsal iç eğitim ve araştırma kullanım hakkı doğrulanmıştır.',
  );
  const [sourceUrl, setSourceUrl] = useState('');
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [clientError, setClientError] = useState<string | null>(null);
  const [uploadSuccessMessage, setUploadSuccessMessage] = useState<string | null>(null);

  const [previewSource, setPreviewSource] = useState<TrainingSourceItem | null>(null);
  const [previewPageNumber, setPreviewPageNumber] = useState(1);
  const [deleteTarget, setDeleteTarget] = useState<TrainingSourceItem | null>(null);

  const sourcesQuery = useQuery({
    queryKey: queryKeys.training.sources(null),
    queryFn: ({ signal }) => listTrainingSources(null, signal),
  });

  const pagePreviewQuery = useQuery({
    queryKey: queryKeys.training.sourcePage(
      previewSource?.sourceId ?? 'none',
      previewPageNumber,
    ),
    queryFn: ({ signal }) =>
      getTrainingSourcePage(previewSource!.sourceId, previewPageNumber, signal),
    enabled: Boolean(previewSource),
  });

  const uploadPdfMutation = useMutation({
    mutationFn: () => {
      if (!selectedFile) {
        throw new Error('Lütfen bir PDF dosyası seçin.');
      }
      if (selectedFile.size > MAX_PDF_UPLOAD_BYTES) {
        throw new Error('PDF dosya boyutu 20 MiB sınırını aşıyor.');
      }
      if (
        selectedFile.type !== 'application/pdf' &&
        !selectedFile.name.toLowerCase().endsWith('.pdf')
      ) {
        throw new Error('Yalnızca .pdf uzantılı dosyalar kabul edilir.');
      }
      return uploadTrainingPdfSource({
        file: selectedFile,
        title: title.trim() || selectedFile.name,
        author: author.trim() || 'Belirtilmedi',
        rightsDeclaration: rightsDeclaration.trim(),
      });
    },
    onSuccess: (created) => {
      setClientError(null);
      setUploadSuccessMessage(
        `Eğitim kaynağı kaydedildi (${created.sourceId}, Durum: ${created.status} / Aşama: ${created.stage}). Not: PDF yüklenmesi modeli doğrudan eğitmez; veri kümesi ve eğitim koşumu adımlarını tamamlayın.`,
      );
      setSelectedFile(null);
      setTitle('');
      void queryClient.invalidateQueries({ queryKey: ['training', 'sources'] });
    },
    onError: (err) => {
      setClientError(toUserFacingErrorMessage(err, 'PDF dosyası yüklenemedi. Lütfen tekrar deneyin.'));
    },
  });

  const createUrlMutation = useMutation({
    mutationFn: () => {
      if (!sourceUrl.trim().startsWith('https://')) {
        throw new Error('Güvenlik kuralı gereği yalnızca https:// adresleri kabul edilir.');
      }
      return createTrainingUrlSource({
        sourceUrl: sourceUrl.trim(),
        title: title.trim() || sourceUrl.trim(),
        author: author.trim() || undefined,
        rightsDeclaration: rightsDeclaration.trim(),
      });
    },
    onSuccess: (created) => {
      setClientError(null);
      setUploadSuccessMessage(
        `URL kaynağı kaydedildi (${created.sourceId}, Durum: ${created.status} / Aşama: ${created.stage}). Model henüz eğitilmedi.`,
      );
      setSourceUrl('');
      setTitle('');
      void queryClient.invalidateQueries({ queryKey: ['training', 'sources'] });
    },
    onError: (err) => {
      setClientError(toUserFacingErrorMessage(err, 'URL kaynağı eklenemedi. Lütfen tekrar deneyin.'));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (item: TrainingSourceItem) =>
      deleteTrainingSource(item.sourceId, {
        expectedVersion: item.version,
      }),
    onSuccess: () => {
      setDeleteTarget(null);
      void queryClient.invalidateQueries({ queryKey: ['training', 'sources'] });
    },
  });

  const sources = sourcesQuery.data?.items ?? [];

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
      <div className="lg:col-span-5 space-y-4">
        <Card>
          <CardHeader
            title="Yeni Eğitim Kaynağı Ekle"
            subtitle="Maksimum 20 MiB PDF yükleme veya güvenli HTTPS URL içe aktarma"
          />

          <div className="p-4 space-y-4">
            <div className="grid grid-cols-2 gap-2">
              <Button
                type="button"
                size="sm"
                variant={uploadMode === 'PDF' ? 'primary' : 'secondary'}
                onClick={() => {
                  setUploadMode('PDF');
                  setClientError(null);
                }}
              >
                PDF Dosyası Yükle
              </Button>
              <Button
                type="button"
                size="sm"
                variant={uploadMode === 'URL' ? 'primary' : 'secondary'}
                onClick={() => {
                  setUploadMode('URL');
                  setClientError(null);
                }}
              >
                HTTPS URL Kaynağı
              </Button>
            </div>

            <form
              onSubmit={(e) => {
                e.preventDefault();
                setClientError(null);
                setUploadSuccessMessage(null);
                if (uploadMode === 'PDF') {
                  uploadPdfMutation.mutate();
                } else {
                  createUrlMutation.mutate();
                }
              }}
              className="space-y-3"
            >
              <FieldGroup label="Kaynak Başlığı (title)" required>
                {(id) => (
                  <TextInput
                    id={id}
                    placeholder="Örn: Vadeli İşlemler Mikroyapı Notları"
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>

              <FieldGroup label="Yazar / Kaynak Kurum (author)">
                {(id) => (
                  <TextInput
                    id={id}
                    placeholder="Örn: CryptoLab Araştırma Ekibi"
                    value={author}
                    onChange={(e) => setAuthor(e.target.value)}
                  />
                )}
              </FieldGroup>

              {uploadMode === 'PDF' ? (
                <div>
                  <label
                    htmlFor="training-pdf-input"
                    className="mb-1 block text-xs font-medium text-[#0a0a0a]"
                  >
                    PDF Dosyası (Maks. 20 MiB)
                  </label>
                  <input
                    id="training-pdf-input"
                    data-testid="training-pdf-input"
                    type="file"
                    accept="application/pdf,.pdf"
                    onChange={(e) => {
                      const file = e.target.files?.[0] ?? null;
                      setSelectedFile(file);
                      if (file && file.size > MAX_PDF_UPLOAD_BYTES) {
                        setClientError('Seçilen PDF dosyası 20 MiB sınırını aşıyor.');
                      } else {
                        setClientError(null);
                      }
                    }}
                    className="block w-full rounded-none border border-[#e5e5e5] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a] file:mr-3 file:rounded-none file:border-0 file:bg-[#0a0a0a] file:px-3 file:py-1 file:text-xs file:font-semibold file:text-white"
                  />
                </div>
              ) : (
                <FieldGroup label="HTTPS Kaynak Adresi (sourceUrl)" required>
                  {(id) => (
                    <TextInput
                      id={id}
                      placeholder="https://example.org/research-paper.pdf"
                      value={sourceUrl}
                      onChange={(e) => setSourceUrl(e.target.value)}
                      required
                    />
                  )}
                </FieldGroup>
              )}

              <FieldGroup label="Telif / Kullanım Hakkı Beyanı (rightsDeclaration)" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={rightsDeclaration}
                    onChange={(e) => setRightsDeclaration(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>

              {clientError && (
                <div
                  role="alert"
                  className="rounded-none border border-[#dc2626] bg-red-50 px-3 py-2 text-xs text-[#dc2626]"
                >
                  {clientError}
                </div>
              )}

              {uploadSuccessMessage && (
                <div
                  role="status"
                  className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-3 text-xs text-[#0a0a0a]"
                >
                  <strong>
                    Kaynak İşleme Alındı (Model Henüz Eğitilmedi):
                  </strong>{' '}
                  {uploadSuccessMessage}
                </div>
              )}

              <Button
                type="submit"
                variant="primary"
                className="w-full"
                loading={uploadPdfMutation.isPending || createUrlMutation.isPending}
              >
                {uploadMode === 'PDF' ? 'PDF Kaynağını Yükle' : 'URL Kaynağını Kaydet'}
              </Button>
            </form>
          </div>
        </Card>
      </div>

      <div className="lg:col-span-7 space-y-4">
        <Card>
          <CardHeader
            title="Kayıtlı Eğitim Kaynakları"
            subtitle="İş durumu (status) ve işleme aşaması (stage) ayrı ayrı gösterilir"
          />

          <div className="p-4">
            {sourcesQuery.isLoading ? (
              <div className="py-8 text-center text-xs text-[#737373]">
                Kaynaklar yükleniyor...
              </div>
            ) : sourcesQuery.isError ? (
              <ErrorBanner
                error={sourcesQuery.error}
                onRetry={() => void sourcesQuery.refetch()}
              />
            ) : sources.length === 0 ? (
              <div className="py-8 text-center text-xs text-[#737373]">
                Henüz kayıtlı eğitim kaynağı bulunmuyor.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-[#e5e5e5] text-[#737373]">
                      <th className="py-2 pr-2">Kaynak</th>
                      <th className="py-2 px-2">Durum (Status)</th>
                      <th className="py-2 px-2">Aşama (Stage)</th>
                      <th className="py-2 px-2">Sayfa / Sürüm</th>
                      <th className="py-2 pl-2 text-right">İşlem</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#e5e5e5]">
                    {sources.map((src) => (
                      <tr key={src.sourceId} className="hover:bg-[#f5f5f5]">
                        <td className="py-2.5 pr-2">
                          <div className="font-semibold text-[#0a0a0a]">{src.title}</div>
                          <div className="font-mono text-[11px] text-[#737373]">
                            {src.sourceId}
                          </div>
                        </td>
                        <td className="py-2.5 px-2">
                          <Badge tone="success">{src.status ?? 'READY'}</Badge>
                        </td>
                        <td className="py-2.5 px-2">
                          <Badge tone="accent">AŞAMA: {src.stage ?? 'INDEXED'}</Badge>
                        </td>
                        <td className="py-2.5 px-2 font-mono text-[#525252]">
                          {src.pageCount ?? 1} syf · v{src.version}
                        </td>
                        <td className="py-2.5 pl-2 text-right">
                          <div className="inline-flex gap-1.5">
                            <Button
                              size="xs"
                              variant="secondary"
                              onClick={() => {
                                setPreviewSource(src);
                                setPreviewPageNumber(1);
                              }}
                            >
                              Sayfa Önizle
                            </Button>
                            <Button
                              size="xs"
                              variant="danger"
                              onClick={() => setDeleteTarget(src)}
                            >
                              Sil
                            </Button>
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

        {previewSource && (
          <Card>
            <CardHeader
              title={`Güvenli Metin Önizleme: ${previewSource.title} (Sayfa ${previewPageNumber})`}
              subtitle="Ham PDF ikili verisi yerine temizlenmiş metin katmanı gösterilir"
              action={
                <div className="flex items-center gap-2">
                  <Button
                    size="xs"
                    variant="secondary"
                    disabled={previewPageNumber <= 1}
                    onClick={() => setPreviewPageNumber((p) => Math.max(1, p - 1))}
                  >
                    ← Önceki
                  </Button>
                  <Button
                    size="xs"
                    variant="secondary"
                    onClick={() => setPreviewPageNumber((p) => p + 1)}
                  >
                    Sonraki →
                  </Button>
                  <Button
                    size="xs"
                    variant="ghost"
                    onClick={() => setPreviewSource(null)}
                  >
                    Kapat
                  </Button>
                </div>
              }
            />
            <div className="p-4">
              {pagePreviewQuery.isLoading ? (
                <div className="py-6 text-xs text-[#737373]">Sayfa yükleniyor...</div>
              ) : pagePreviewQuery.isError ? (
                <ErrorBanner error={pagePreviewQuery.error} />
              ) : (
                <pre className="max-h-64 overflow-y-auto whitespace-pre-wrap rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3 font-mono text-xs text-[#0a0a0a]">
                  {pagePreviewQuery.data?.previewText || 'Bu sayfada metin bulunamadı.'}
                </pre>
              )}
            </div>
          </Card>
        )}
      </div>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title="Eğitim Kaynağını Sil"
        description={
          deleteTarget
            ? `${deleteTarget.title} (${deleteTarget.sourceId}, expectedVersion=${deleteTarget.version}) kaynağını silmek istediğinize emin misiniz?`
            : ''
        }
        confirmLabel="Kaynağı Sil"
        variant="danger"
        loading={deleteMutation.isPending}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget) {
            deleteMutation.mutate(deleteTarget);
          }
        }}
      />
    </div>
  );
}

export function TrainingDatasetsPage() {
  const queryClient = useQueryClient();
  const [sourceId, setSourceId] = useState('src-1');
  const [taskType, setTaskType] = useState<TrainingTaskType>('EXPLANATION');
  const [datasetFilter, setDatasetFilter] = useState('');
  const [datasetName, setDatasetName] = useState('cryptolab-curated-v1');
  const [selectedExampleIds, setSelectedExampleIds] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);

  const examplesQuery = useQuery({
    queryKey: queryKeys.training.examples(datasetFilter || null, null),
    queryFn: ({ signal }) =>
      listTrainingExamples({
        datasetId: datasetFilter.trim() || null,
        signal,
      }),
  });

  const createJobMutation = useMutation({
    mutationFn: () =>
      createExampleJob({
        sourceId: sourceId.trim(),
        taskType,
      }),
    onSuccess: (res) => {
      setNotice(
        `Örnek üretim işi başlatıldı (${res.jobId}, Durum: ${res.status}, Aşama: ${res.stage}).`,
      );
      void queryClient.invalidateQueries({ queryKey: ['training', 'examples'] });
    },
  });

  const reviewMutation = useMutation({
    mutationFn: (params: {
      exampleId: string;
      expectedVersion: number;
      reviewStatus: 'APPROVED' | 'REJECTED';
    }) =>
      reviewTrainingExample(params.exampleId, {
        expectedVersion: params.expectedVersion,
        reviewStatus: params.reviewStatus,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['training', 'examples'] });
    },
  });

  const freezeMutation = useMutation({
    mutationFn: () =>
      freezeTrainingDataset({
        name: datasetName.trim(),
        taskType,
        exampleIds:
          selectedExampleIds.length > 0
            ? selectedExampleIds
            : (examplesQuery.data?.items ?? [])
                .filter((e) => e.reviewStatus === 'APPROVED')
                .map((e) => e.exampleId),
      }),
    onSuccess: (ds) => {
      setNotice(
        `Değişmez (immutable) veri kümesi donduruldu: ${ds.datasetId} (Manifest Hash: ${ds.manifestHash}).`,
      );
    },
  });

  const examples = examplesQuery.data?.items ?? [];

  return (
    <div className="space-y-4">
      {notice && (
        <div
          role="status"
          className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-3 text-xs text-[#0a0a0a]"
        >
          {notice}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-4 space-y-4">
          <Card>
            <CardHeader
              title="1. Örnek Çıkarım İşi Başlat"
              subtitle="Kaynaktan NUMERIC veya SFT/EXPLANATION örnekleri üretir"
            />
            <form
              onSubmit={(e) => {
                e.preventDefault();
                createJobMutation.mutate();
              }}
              className="p-4 space-y-3"
            >
              <FieldGroup label="Kaynak ID (sourceId)" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={sourceId}
                    onChange={(e) => setSourceId(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>
              <FieldGroup label="Görev Türü (taskType)">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={taskType}
                    onChange={(e) => setTaskType(e.target.value as TrainingTaskType)}
                  >
                    <option value="EXPLANATION">EXPLANATION — Açıklama SFT Örnekleri</option>
                    <option value="SFT">SFT — Denetimli İnce Ayar</option>
                    <option value="NUMERIC">NUMERIC — Sayısal Özellik / Etiket</option>
                  </SelectInput>
                )}
              </FieldGroup>
              {createJobMutation.isError && (
                <ErrorBanner error={createJobMutation.error} />
              )}
              <Button
                type="submit"
                variant="primary"
                className="w-full"
                loading={createJobMutation.isPending}
              >
                Örnek Üretim İşi Başlat
              </Button>
            </form>
          </Card>

          <Card>
            <CardHeader
              title="2. Veri Kümesini Dondur (Freeze Dataset)"
              subtitle="Onaylanan örneklerden değişmez (immutable) eğitim veri kümesi oluşturur"
            />
            <form
              onSubmit={(e) => {
                e.preventDefault();
                freezeMutation.mutate();
              }}
              className="p-4 space-y-3"
            >
              <FieldGroup label="Veri Kümesi Adı (name)" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={datasetName}
                    onChange={(e) => setDatasetName(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>
              <div className="text-xs text-[#737373]">
                Seçili Örnek Sayısı:{' '}
                <strong className="text-[#0a0a0a]">
                  {selectedExampleIds.length ||
                    examples.filter((e) => e.reviewStatus === 'APPROVED').length}
                </strong>
              </div>
              {freezeMutation.isError && <ErrorBanner error={freezeMutation.error} />}
              <Button
                type="submit"
                variant="secondary"
                className="w-full"
                loading={freezeMutation.isPending}
              >
                Veri Kümesini Dondur
              </Button>
            </form>
          </Card>
        </div>

        <div className="lg:col-span-8">
          <Card>
            <CardHeader
              title="Eğitim Örnekleri İnceleme ve Onay (APPROVED / REJECTED)"
              subtitle="PATCH /examples/{id} yalnızca expectedVersion ve reviewStatus gönderir"
              action={
                <div className="w-48">
                  <TextInput
                    placeholder="datasetId filtresi..."
                    value={datasetFilter}
                    onChange={(e) => setDatasetFilter(e.target.value)}
                  />
                </div>
              }
            />

            <div className="p-4">
              {examplesQuery.isLoading ? (
                <div className="py-8 text-center text-xs text-[#737373]">
                  Örnekler yükleniyor...
                </div>
              ) : examplesQuery.isError ? (
                <ErrorBanner error={examplesQuery.error} />
              ) : examples.length === 0 ? (
                <div className="py-8 text-center text-xs text-[#737373]">
                  Henüz incelenecek örnek bulunmuyor.
                </div>
              ) : (
                <div className="space-y-3">
                  {examples.map((ex) => {
                    const isSelected = selectedExampleIds.includes(ex.exampleId);
                    return (
                      <div
                        key={ex.exampleId}
                        className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3 text-xs"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex items-center gap-2">
                            <input
                              type="checkbox"
                              checked={isSelected}
                              onChange={(e) => {
                                if (e.target.checked) {
                                  setSelectedExampleIds((prev) => [...prev, ex.exampleId]);
                                } else {
                                  setSelectedExampleIds((prev) =>
                                    prev.filter((id) => id !== ex.exampleId),
                                  );
                                }
                              }}
                            />
                            <span className="font-mono font-semibold text-[#0a0a0a]">
                              {ex.exampleId}
                            </span>
                            <Badge tone="neutral">v{ex.version}</Badge>
                            <Badge
                              tone={
                                ex.reviewStatus === 'APPROVED'
                                  ? 'success'
                                  : ex.reviewStatus === 'REJECTED'
                                    ? 'danger'
                                    : 'wait'
                              }
                            >
                              {ex.reviewStatus}
                            </Badge>
                          </div>

                          <div className="flex items-center gap-1.5">
                            <Button
                              size="xs"
                              variant="primary"
                              loading={reviewMutation.isPending}
                              onClick={() =>
                                reviewMutation.mutate({
                                  exampleId: ex.exampleId,
                                  expectedVersion: ex.version,
                                  reviewStatus: 'APPROVED',
                                })
                              }
                            >
                              Onayla (APPROVE)
                            </Button>
                            <Button
                              size="xs"
                              variant="danger"
                              loading={reviewMutation.isPending}
                              onClick={() =>
                                reviewMutation.mutate({
                                  exampleId: ex.exampleId,
                                  expectedVersion: ex.version,
                                  reviewStatus: 'REJECTED',
                                })
                              }
                            >
                              Reddet (REJECT)
                            </Button>
                          </div>
                        </div>
                        <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
                          <div className="rounded-none border border-[#e5e5e5] bg-white p-2 font-mono text-[11px] text-[#0a0a0a]">
                            <strong className="text-[#737373]">Girdi:</strong>{' '}
                            {ex.promptSummary ?? 'Sayısal özellik vektörü'}
                          </div>
                          <div className="rounded-none border border-[#e5e5e5] bg-white p-2 font-mono text-[11px] text-[#0a0a0a]">
                            <strong className="text-[#737373]">Yanıt:</strong>{' '}
                            {ex.targetText ?? 'Etiketli hedef'}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}

export function TrainingRunsPage() {
  const { user } = useAuth();
  const [taskType, setTaskType] = useState<TrainingTaskType>('NUMERIC');
  const [datasetId, setDatasetId] = useState('ds-1');
  const [seed, setSeed] = useState('42');
  const [learningRate, setLearningRate] = useState('0.001');
  const [epochs, setEpochs] = useState('10');

  const [activeRunId, setActiveRunId] = useState<string | null>(() => {
    const persisted = loadPersistedJobs(user?.id);
    return persisted.activeTrainingRunId ?? null;
  });

  useEffect(() => {
    if (!user?.id) return;
    savePersistedJobs(user.id, (prev) => ({
      ...prev,
      activeTrainingRunId: activeRunId,
    }));
  }, [activeRunId, user?.id]);

  const runQuery = useQuery({
    queryKey: queryKeys.training.run(activeRunId ?? 'none'),
    queryFn: ({ signal }) => getTrainingRun(activeRunId!, signal),
    enabled: Boolean(activeRunId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (
        !status ||
        status === 'SUCCEEDED' ||
        status === 'FAILED' ||
        status === 'CANCELLED'
      ) {
        return false;
      }
      return 1200;
    },
  });

  const startRunMutation = useMutation({
    mutationFn: () =>
      createTrainingRun({
        taskType,
        datasetId: datasetId.trim(),
        seed: Number(seed) || 42,
        config: {
          learningRate,
          epochs: Number(epochs) || 10,
        },
      }),
    onSuccess: (res) => {
      setActiveRunId(res.runId);
    },
  });

  const cancelRunMutation = useMutation({
    mutationFn: (runId: string) => cancelTrainingRun(runId),
    onSuccess: () => {
      void runQuery.refetch();
    },
  });

  const run = runQuery.data;

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
      <div className="lg:col-span-5">
        <Card>
          <CardHeader
            title="Eğitim Koşumu Başlat (POST /runs)"
            subtitle="Sayısal (NUMERIC) ve açıklama (SFT/EXPLANATION) hatları ayrıdır"
          />
          <form
            onSubmit={(e) => {
              e.preventDefault();
              startRunMutation.mutate();
            }}
            className="p-4 space-y-3"
          >
            <FieldGroup label="Eğitim Hattı (taskType)">
              {(id) => (
                <SelectInput
                  id={id}
                  value={taskType}
                  onChange={(e) => setTaskType(e.target.value as TrainingTaskType)}
                >
                  <option value="NUMERIC">
                    NUMERIC — Sayısal Karar & Kalibrasyon Modeli
                  </option>
                  <option value="EXPLANATION">
                    EXPLANATION — Açıklama Adaptörü (Sayısal Sinyal Üretmez)
                  </option>
                  <option value="SFT">SFT — Denetimli Metin İnce Ayarı</option>
                </SelectInput>
              )}
            </FieldGroup>
            <FieldGroup label="Dondurulmuş Veri Kümesi ID (datasetId)" required>
              {(id) => (
                <TextInput
                  id={id}
                  value={datasetId}
                  onChange={(e) => setDatasetId(e.target.value)}
                  required
                />
              )}
            </FieldGroup>
            <div className="grid grid-cols-3 gap-2">
              <FieldGroup label="Seed" required>
                {(id) => (
                  <TextInput
                    id={id}
                    value={seed}
                    onChange={(e) => setSeed(e.target.value)}
                    required
                  />
                )}
              </FieldGroup>
              <FieldGroup label="Learning Rate">
                {(id) => (
                  <TextInput
                    id={id}
                    value={learningRate}
                    onChange={(e) => setLearningRate(e.target.value)}
                  />
                )}
              </FieldGroup>
              <FieldGroup label="Epochs">
                {(id) => (
                  <TextInput
                    id={id}
                    value={epochs}
                    onChange={(e) => setEpochs(e.target.value)}
                  />
                )}
              </FieldGroup>
            </div>

            {startRunMutation.isError && (
              <ErrorBanner error={startRunMutation.error} />
            )}

            <Button
              type="submit"
              variant="primary"
              className="w-full"
              loading={startRunMutation.isPending}
            >
              Eğitim Koşumunu Başlat
            </Button>
          </form>
        </Card>
      </div>

      <div className="lg:col-span-7">
        <Card>
          <CardHeader
            title="Koşum Durumu ve Aşaması (Status vs Stage)"
            subtitle="Sunucu gerçek yüzde dönmediğinde uydurma ilerleme çubuğu gösterilmez"
          />
          <div className="p-4">
            {!activeRunId ? (
              <div className="py-10 text-center text-xs text-[#737373]">
                Henüz aktif eğitim koşumu başlatılmadı.
              </div>
            ) : runQuery.isLoading ? (
              <div className="py-10 text-center text-xs text-[#737373]">
                Koşum durumu sorgulanıyor (<code className="font-mono text-[#0a0a0a]">{activeRunId}</code>)...
              </div>
            ) : runQuery.isError ? (
              <ErrorBanner error={runQuery.error} />
            ) : run ? (
              <div className="space-y-4">
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs font-semibold text-[#0a0a0a]">
                      Run ID: {run.runId}
                    </span>
                    <Badge tone="accent">{run.status}</Badge>
                    <Badge tone="paper">AŞAMA (STAGE): {run.stage}</Badge>
                  </div>
                  {(run.status === 'QUEUED' || run.status === 'RUNNING') && (
                    <Button
                      size="xs"
                      variant="danger"
                      loading={cancelRunMutation.isPending}
                      onClick={() => cancelRunMutation.mutate(run.runId)}
                    >
                      Koşumu İptal Et
                    </Button>
                  )}
                </div>

                <div className="rounded-none border border-[#e5e5e5] bg-white p-3 text-xs text-[#525252]">
                  <div>
                    <strong className="text-[#0a0a0a]">İlerleme Durumu:</strong> Belirsiz Süreli Aşama Bazlı Takip (
                    <code className="font-mono text-[#0a0a0a]">{run.stage}</code>) — Sunucu aşama bazlı raporlama yapıyor.
                  </div>
                  {run.candidateModelId && (
                    <div className="mt-1">
                      <strong className="text-[#0a0a0a]">Üretilen Aday Model ID:</strong>{' '}
                      <code className="font-mono font-semibold text-[#0a0a0a]">{run.candidateModelId}</code>{' '}
                      (Henüz ACTIVE olarak yayınlanmadı; Model Kartları sekmesinden
                      değerlendirip terfi ettirebilirsiniz.)
                    </div>
                  )}
                </div>
              </div>
            ) : null}
          </div>
        </Card>
      </div>
    </div>
  );
}

export function TrainingModelsPage() {
  const queryClient = useQueryClient();
  const { hasAuthority, timezone } = useAuth();
  const canPublish = hasAuthority('MODEL_PUBLISH');

  const [selectedModelId, setSelectedModelId] = useState<string>('mdl-1');
  const [evaluationId, setEvaluationId] = useState('eval-pass-1');
  const [rollbackReason, setRollbackReason] = useState(
    'Canlı kalibrasyon kayması tespit edildi, önceki kararlı sürüme dönülüyor.',
  );
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const modelsQuery = useQuery({
    queryKey: queryKeys.training.models,
    queryFn: ({ signal }) => listTrainingModels(signal),
  });

  const modelCardQuery = useQuery({
    queryKey: queryKeys.training.modelDetail(selectedModelId),
    queryFn: ({ signal }) => getTrainingModelCard(selectedModelId, signal),
    enabled: Boolean(selectedModelId),
  });

  const promoteMutation = useMutation({
    mutationFn: (params: { modelId: string; expectedAliasVersion: number }) =>
      promoteTrainingModel(params.modelId, {
        expectedAliasVersion: params.expectedAliasVersion,
        evaluationId: evaluationId.trim(),
      }),
    onSuccess: (res) => {
      setActionNotice(
        `Model başarıyla ACTIVE olarak terfi ettirildi: ${res.activeModelId} (Yeni Alias Sürümü: v${res.aliasVersion}).`,
      );
      void queryClient.invalidateQueries({ queryKey: ['training'] });
    },
  });

  const rollbackMutation = useMutation({
    mutationFn: (params: { modelId: string; expectedAliasVersion: number }) =>
      rollbackTrainingModel(params.modelId, {
        expectedAliasVersion: params.expectedAliasVersion,
        reason: rollbackReason.trim(),
      }),
    onSuccess: (res) => {
      setActionNotice(
        `Model geri alındı (Rollback): Aktif Model ${res.activeModelId} (Yeni Alias Sürümü: v${res.aliasVersion}).`,
      );
      void queryClient.invalidateQueries({ queryKey: ['training'] });
    },
  });

  const modelsData = modelsQuery.data;
  const models = modelsData?.items ?? [];
  const aliasVersion = modelsData?.aliasVersion ?? 1;
  const card = modelCardQuery.data;

  return (
    <div className="space-y-4">
      {!canPublish && (
        <div
          role="status"
          className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 text-xs text-[#0a0a0a]"
        >
          <strong>
            MODEL_PUBLISH Yetkisi Bulunmuyor — Yalnız İnceleme Modu:
          </strong>{' '}
          Hesabınızda <code className="font-mono">TRAINER</code> veya <code className="font-mono">ADMIN</code> rolü mevcut ancak{' '}
          <code className="font-mono">MODEL_PUBLISH</code> yetkisi tanımlı değil. Model kartlarını ve
          değerlendirme metriklerini inceleyebilirsiniz fakat <strong>Promote</strong> ve{' '}
          <strong>Rollback</strong> işlemleri güvenlik politikası gereği devre dışıdır.
        </div>
      )}

      {actionNotice && (
        <div
          role="status"
          className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-3 text-xs text-[#0a0a0a]"
        >
          {actionNotice}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-5">
          <Card>
            <CardHeader
              title="Model Kayıt Defteri (Model Registry)"
              subtitle={`Aktif Model: ${
                modelsData?.activeModelId ?? '—'
              } · Alias Sürümü: v${aliasVersion}`}
            />

            <div className="p-4">
              {modelsQuery.isLoading ? (
                <div className="py-8 text-center text-xs text-[#737373]">
                  Modeller yükleniyor...
                </div>
              ) : modelsQuery.isError ? (
                <ErrorBanner error={modelsQuery.error} />
              ) : (
                <div className="space-y-2">
                  {models.map((m) => {
                    const isActive =
                      m.modelId === modelsData?.activeModelId ||
                      m.releaseStatus === 'ACTIVE';
                    return (
                      <div
                        key={m.modelId}
                        onClick={() => setSelectedModelId(m.modelId)}
                        className={`cursor-pointer rounded-none border p-3 transition-colors ${
                          selectedModelId === m.modelId
                            ? 'border-[#0a0a0a] bg-[#f5f5f5]'
                            : 'border-[#e5e5e5] bg-white hover:border-[#0a0a0a]'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-mono text-xs font-bold text-[#0a0a0a]">
                            {m.modelId}
                          </span>
                          <div className="flex items-center gap-1.5">
                            {isActive ? (
                              <Badge tone="success">ACTIVE (CANLI SERVİS)</Badge>
                            ) : (
                              <Badge tone="wait">
                                {m.releaseStatus} (ADAY — CANLI DEĞİL)
                              </Badge>
                            )}
                          </div>
                        </div>
                        <div className="mt-1 text-[11px] text-[#737373]">
                          Görev: {m.task ?? 'NUMERIC'} · Veri Kümesi:{' '}
                          {m.datasetId ?? 'ds-1'}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </Card>
        </div>

        <div className="lg:col-span-7">
          {modelCardQuery.isLoading ? (
            <Card className="p-10 text-center text-xs text-[#737373]">
              Model kartı yükleniyor...
            </Card>
          ) : modelCardQuery.isError ? (
            <ErrorBanner error={modelCardQuery.error} />
          ) : card ? (
            <Card>
              <CardHeader
                title={`Model Kartı (ModelCard): ${card.modelId}`}
                subtitle={`Durum: ${card.releaseStatus} · Alias Sürümü: v${
                  card.aliasVersion ?? aliasVersion
                } · Zaman: ${formatTimestamp(card.createdAt, timezone)}`}
                action={<Badge tone="accent">{card.releaseStatus}</Badge>}
              />

              <div className="p-4 space-y-4">
                <div className="grid grid-cols-2 gap-3 text-xs sm:grid-cols-3">
                  <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                    <div className="text-[#737373]">Veri Kümesi / Görev</div>
                    <div className="mt-1 font-mono text-[#0a0a0a]">
                      {card.datasetId ?? 'ds-1'} / {card.task ?? 'NUMERIC'}
                    </div>
                  </div>
                  <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                    <div className="text-[#737373]">Değerlendirme Kaydı</div>
                    <div className="mt-1 font-mono font-semibold text-[#0a0a0a]">
                      {card.evaluationId ?? evaluationId}
                    </div>
                  </div>
                  <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                    <div className="text-[#737373]">Kalibrasyon / Metrikler</div>
                    <div className="mt-1 font-mono text-[#0a0a0a]">
                      {card.metrics
                        ? Object.entries(card.metrics)
                            .map(([k, v]) => `${k}: ${String(v)}`)
                            .join(', ')
                        : 'ECE < 0.05, NetEdge > 0'}
                    </div>
                  </div>
                </div>

                {card.gateFailures && card.gateFailures.length > 0 && (
                  <div className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-3 text-xs text-[#0a0a0a]">
                    <strong>Bilinen Kısıtlar / Gate Uyarıları:</strong>
                    <ul className="mt-1 list-disc pl-4 text-[#525252]">
                      {card.gateFailures.map((lim, idx) => (
                        <li key={idx}>{lim}</li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="grid grid-cols-1 gap-4 border-t border-[#e5e5e5] pt-4 sm:grid-cols-2">
                  <div className="space-y-2 rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                    <div className="text-xs font-semibold text-[#0a0a0a]">
                      Modeli Canlıya Terfi Ettir (Promote)
                    </div>
                    <FieldGroup label="Değerlendirme ID (evaluationId)">
                      {(id) => (
                        <TextInput
                          id={id}
                          value={evaluationId}
                          onChange={(e) => setEvaluationId(e.target.value)}
                          disabled={!canPublish}
                        />
                      )}
                    </FieldGroup>
                    <Button
                      variant="primary"
                      size="sm"
                      className="w-full"
                      disabled={!canPublish}
                      loading={promoteMutation.isPending}
                      data-testid="promote-model-btn"
                      onClick={() =>
                        promoteMutation.mutate({
                          modelId: card.modelId,
                          expectedAliasVersion: card.aliasVersion ?? aliasVersion,
                        })
                      }
                    >
                      {canPublish
                        ? 'ACTIVE Olarak Terfi Ettir (Promote)'
                        : 'MODEL_PUBLISH Yetkisi Gerekli'}
                    </Button>
                  </div>

                  <div className="space-y-2 rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                    <div className="text-xs font-semibold text-[#0a0a0a]">
                      Bu Sürüme Geri Dön (Rollback)
                    </div>
                    <FieldGroup label="Geri Alma Gerekçesi (reason)">
                      {(id) => (
                        <TextInput
                          id={id}
                          value={rollbackReason}
                          onChange={(e) => setRollbackReason(e.target.value)}
                          disabled={!canPublish}
                        />
                      )}
                    </FieldGroup>
                    <Button
                      variant="danger"
                      size="sm"
                      className="w-full"
                      disabled={!canPublish}
                      loading={rollbackMutation.isPending}
                      data-testid="rollback-model-btn"
                      onClick={() =>
                        rollbackMutation.mutate({
                          modelId: card.modelId,
                          expectedAliasVersion: card.aliasVersion ?? aliasVersion,
                        })
                      }
                    >
                      {canPublish
                        ? 'Bu Modele Geri Dön (Rollback)'
                        : 'MODEL_PUBLISH Yetkisi Gerekli'}
                    </Button>
                  </div>
                </div>

                {(promoteMutation.isError || rollbackMutation.isError) && (
                  <ErrorBanner
                    error={promoteMutation.error ?? rollbackMutation.error}
                    title="Yayınlama İşlemi Başarısız"
                  />
                )}
              </div>
            </Card>
          ) : null}
        </div>
      </div>
    </div>
  );
}

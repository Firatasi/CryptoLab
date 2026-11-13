import React, { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  BrainCircuit,
  FileBarChart,
  History,
  PauseCircle,
  PlayCircle,
  Plus,
  RotateCcw,
  Trash2,
  Wallet,
  XOctagon,
} from 'lucide-react';
import type { SimulationSession } from '@/shared/contracts/types';
import { queryKeys } from '@/shared/api/queryKeys';
import { createIdempotencyKey, LogicalActionKeyTracker } from '@/shared/api/idempotency';
import { decimalSign, parsePositiveDecimalInput } from '@/shared/formatting/decimalParser';
import {
  formatDecimal,
  formatMoney,
  formatSignedMoney,
  formatTimestamp,
} from '@/shared/formatting/formatters';
import { savePersistedJobs } from '@/shared/storage/jobPersistence';
import { useAuth } from '@/features/auth/AuthContext';
import { Badge, Button, Card, Skeleton } from '@/shared/ui/Primitives';
import { DecimalInput, FieldGroup, TextInput } from '@/shared/ui/FormControls';
import { ConfirmDialog, Modal } from '@/shared/ui/Modal';
import { EmptyState, ErrorBanner } from '@/shared/ui/Feedback';
import { sessionsApi } from './api';
import {
  DEFAULT_RISK_POLICY_ID,
  DEFAULT_RULE_PROFILE_ID,
  getRiskPolicy,
  getRuleProfile,
  RISK_POLICIES,
  RULE_PROFILES,
} from './sessionProfiles';

const DEPOSIT_PRESETS = ['1000', '5000', '10000', '25000', '50000', '100000'] as const;

export function walletPnlToneClass(value: string | undefined): string {
  const sign = decimalSign(value);
  if (sign === 1) return 'text-[#15803d]';
  if (sign === -1) return 'text-[#dc2626]';
  return 'text-[#525252]';
}

export function SessionsPage() {
  const { user, timezone } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const createKeyRef = useRef(new LogicalActionKeyTracker());

  const [createOpen, setCreateOpen] = useState(false);
  const [walletName, setWalletName] = useState('');
  const [rawDeposit, setRawDeposit] = useState('10000');
  const [ruleProfileId, setRuleProfileId] = useState(DEFAULT_RULE_PROFILE_ID);
  const [riskPolicyId, setRiskPolicyId] = useState(DEFAULT_RISK_POLICY_ID);
  const [formError, setFormError] = useState<string | null>(null);
  const selectedRuleProfile = getRuleProfile(ruleProfileId);
  const selectedRiskPolicy = getRiskPolicy(riskPolicyId);

  const [confirmState, setConfirmState] = useState<{
    session: SimulationSession;
    action: 'HALT' | 'RESUME' | 'CLOSE_ALL' | 'DELETE';
  } | null>(null);

  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.all,
    queryFn: ({ signal }) => sessionsApi.list(signal),
    refetchInterval: (query) =>
      query.state.data?.items?.some((item) => item.status === 'RUNNING') ? 4_000 : false,
  });

  const createMutation = useMutation({
    mutationFn: (params: {
      name?: string;
      initialDeposit: string;
      ruleProfileId: string;
      riskPolicyId: string;
      idemKey: string;
    }) =>
      sessionsApi.create(
        {
          name: params.name,
          initialDeposit: params.initialDeposit,
          ruleProfileId: params.ruleProfileId,
          riskPolicyId: params.riskPolicyId,
        },
        params.idemKey
      ),
    onSuccess: (created) => {
      createKeyRef.current.reset();
      setCreateOpen(false);
      savePersistedJobs(user?.id, (prev) => ({ ...prev, activeSessionId: created.id }));
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.all });
      navigate(`/trade/${created.id}`);
    },
  });

  const lifecycleMutation = useMutation({
    mutationFn: async (target: {
      session: SimulationSession;
      action: 'HALT' | 'RESUME' | 'CLOSE_ALL' | 'DELETE';
    }) => {
      const key = createIdempotencyKey();
      if (target.action === 'HALT') {
        return sessionsApi.halt(target.session.id, key);
      }
      if (target.action === 'RESUME') {
        return sessionsApi.resume(target.session.id, key);
      }
      if (target.action === 'DELETE') {
        return sessionsApi.deleteSession(target.session.id, key);
      }
      return sessionsApi.closeAllPositions(target.session.id, key);
    },
    onSuccess: (_, variables) => {
      setConfirmState(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.all });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sessions.detail(variables.session.id),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.trading.positions(variables.session.id),
      });
    },
  });

  const sessions = sessionsQuery.data?.items ?? [];

  const handleOpenCreate = (presetDeposit = '10000') => {
    setWalletName(`Sanal Cüzdan #${sessions.length + 1}`);
    setRawDeposit(presetDeposit);
    setFormError(null);
    createMutation.reset();
    setCreateOpen(true);
  };

  const handleCreateSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const parsed = parsePositiveDecimalInput(rawDeposit, 2);
    if (!parsed.valid) {
      setFormError(parsed.message);
      return;
    }
    setFormError(null);
    const trimmedName = walletName.trim() || `Sanal Cüzdan #${sessions.length + 1}`;
    const sig = `${trimmedName}:${parsed.normalized}:${ruleProfileId}:${riskPolicyId}`;
    const idemKey = createKeyRef.current.getOrCreate(sig);
    createMutation.mutate({
      name: trimmedName,
      initialDeposit: parsed.normalized,
      ruleProfileId,
      riskPolicyId,
      idemKey,
    });
  };

  const handleSelectActiveSession = (sessionId: string, destination: string) => {
    savePersistedJobs(user?.id, (prev) => ({ ...prev, activeSessionId: sessionId }));
    navigate(destination);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-[#0a0a0a]">
            Sanal İşlem Oturumları
          </h1>
          <p className="mt-0.5 text-xs text-[#737373]">
            Her oturum bağımsız bir sanal USDT cüzdanına sahiptir. Cüzdanınıza kendi isminizi
            verebilir, yeni bakiye tanımlayabilir veya dilediğiniz zaman silebilirsiniz.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => handleOpenCreate('10000')}
            leftIcon={<RotateCcw className="h-4 w-4" aria-hidden="true" />}
          >
            Yeni Cüzdanla Sıfırla
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => handleOpenCreate('10000')}
            leftIcon={<Plus className="h-4 w-4" aria-hidden="true" />}
          >
            Yeni Sanal Oturum Aç
          </Button>
        </div>
      </div>

      {lifecycleMutation.isError && (
        <ErrorBanner
          error={lifecycleMutation.error}
          title="Oturum durumu güncellenemedi"
        />
      )}

      {sessionsQuery.isLoading ? (
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : sessionsQuery.isError ? (
        <ErrorBanner
          error={sessionsQuery.error}
          title="Oturum listesi alınamadı"
          onRetry={() => void sessionsQuery.refetch()}
        />
      ) : sessions.length === 0 ? (
        <EmptyState
          title="Henüz sanal işlem oturumunuz yok"
          description="Cüzdanınıza bir isim verip sanal başlangıç sermayesi (USDT) seçerek ilk sanal cüzdanınızı saniyeler içinde oluşturun."
          action={
            <Button
              variant="primary"
              onClick={() => handleOpenCreate('10000')}
              leftIcon={<Plus className="h-4 w-4" aria-hidden="true" />}
            >
              İlk Sanal Oturumu Oluştur
            </Button>
          }
        />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {sessions.map((session, idx) => {
            const displayTitle =
              session.name && session.name.trim().length > 0
                ? session.name
                : `Sanal Cüzdan #${sessions.length - idx}`;

            return (
              <Card key={session.id} className="space-y-4 p-5">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="inline-flex items-center gap-1.5 text-sm font-semibold text-[#0a0a0a]">
                        <Wallet className="h-4 w-4 text-[#0a0a0a]" aria-hidden="true" />
                        {displayTitle}
                      </span>
                      <SessionStatusBadge status={session.status} />
                    </div>
                    <div className="mt-1.5 flex flex-wrap items-center gap-2 text-[11px] text-[#525252]">
                      <span className="border border-[#e5e5e5] bg-[#f5f5f5] px-2 py-0.5 font-medium">
                        {getRuleProfile(session.ruleProfileId).shortLabel}
                      </span>
                      <span className="border border-[#e5e5e5] bg-[#f5f5f5] px-2 py-0.5 font-medium">
                        {getRiskPolicy(session.riskPolicyId).shortLabel}
                      </span>
                    </div>
                    <div className="mt-1 text-[11px] text-[#737373]">
                      Açılış: {formatTimestamp(session.createdAt, timezone)}
                    </div>
                  </div>

                  <Button
                    variant="primary"
                    size="sm"
                    onClick={() => handleSelectActiveSession(session.id, `/trade/${session.id}`)}
                    leftIcon={<ArrowRight className="h-4 w-4" aria-hidden="true" />}
                  >
                    İşlem Terminaline Gir
                  </Button>
                </div>

                <div className="space-y-2.5 rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3">
                  <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                    <div>
                      <div className="text-[11px] text-[#737373]">Başlangıç Sermayesi</div>
                      <div className="mt-0.5 font-mono text-sm font-semibold tabular-nums text-[#0a0a0a]">
                        {formatMoney(session.initialDeposit, session.currency)}
                      </div>
                    </div>
                    <div>
                      <div className="text-[11px] text-[#737373]">Kullanılabilir Bakiye</div>
                      <div className="mt-0.5 font-mono text-sm font-semibold tabular-nums text-[#0a0a0a]">
                        {formatMoney(
                          session.availableBalance ?? session.initialDeposit,
                          session.currency
                        )}
                      </div>
                    </div>
                    <div>
                      <div className="text-[11px] text-[#737373]">İzole Marjin</div>
                      <div className="mt-0.5 font-mono text-sm font-semibold tabular-nums text-[#0a0a0a]">
                        {formatMoney(session.isolatedMargin ?? '0', session.currency)}
                      </div>
                    </div>
                    <div>
                      <div className="text-[11px] text-[#737373]">Güncel Varlık</div>
                      <div className="mt-0.5 font-mono text-sm font-semibold tabular-nums text-[#0a0a0a]">
                        {formatMoney(
                          session.equity ?? session.availableBalance ?? session.initialDeposit,
                          session.currency
                        )}
                      </div>
                    </div>
                  </div>

                  <div
                    className="flex flex-wrap items-center justify-between gap-2 border-t border-[#e5e5e5] pt-2.5"
                    data-testid={`wallet-pnl-${session.id}`}
                  >
                    <div className="flex flex-wrap items-baseline gap-2">
                      <span className="text-[11px] font-medium text-[#525252]">
                        Mevcut Kâr / Zarar:
                      </span>
                      <span
                        className={`font-mono text-sm font-bold tabular-nums ${walletPnlToneClass(
                          session.netPnl
                        )}`}
                      >
                        {session.netPnl !== undefined
                          ? formatSignedMoney(session.netPnl, session.currency)
                          : '—'}
                      </span>
                      {session.netPnlPercent !== undefined && (
                        <span
                          className={`font-mono text-xs font-semibold tabular-nums ${walletPnlToneClass(
                            session.netPnlPercent
                          )}`}
                        >
                          ({formatDecimal(session.netPnlPercent, {
                            minFractionDigits: 2,
                            maxFractionDigits: 2,
                            signed: true,
                          })}%)
                        </span>
                      )}
                    </div>

                    <div className="flex flex-wrap items-center gap-3 font-mono text-[11px] tabular-nums text-[#737373]">
                      <span>
                        Gerçekleşen:{' '}
                        <strong className={walletPnlToneClass(session.realizedPnl)}>
                          {session.realizedPnl !== undefined
                            ? formatSignedMoney(session.realizedPnl, session.currency)
                            : '—'}
                        </strong>
                      </span>
                      <span>
                        Açık PnL
                        {(session.openPositionCount ?? 0) > 0
                          ? ` (${session.openPositionCount})`
                          : ''}
                        :{' '}
                        <strong className={walletPnlToneClass(session.unrealizedPnl)}>
                          {session.unrealizedPnl !== undefined
                            ? formatSignedMoney(session.unrealizedPnl, session.currency)
                            : '—'}
                        </strong>
                      </span>
                    </div>
                  </div>
                </div>

                {session.haltReason && (
                  <div className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]">
                    Duraklatma Nedeni:{' '}
                    <span className="font-mono font-semibold">{session.haltReason}</span> (Açık
                    pozisyonlar otomatik kapanmaz; yalnız yeni emir girişi durdurulmuştur.)
                  </div>
                )}

                <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[#e5e5e5] pt-3">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Link
                      to={`/analysis/${session.id}`}
                      onClick={() =>
                        savePersistedJobs(user?.id, (p) => ({ ...p, activeSessionId: session.id }))
                      }
                    >
                      <Button
                        variant="secondary"
                        size="xs"
                        leftIcon={<BrainCircuit className="h-3.5 w-3.5" aria-hidden="true" />}
                      >
                        AI Analiz
                      </Button>
                    </Link>
                    <Link
                      to={`/reports/${session.id}`}
                      onClick={() =>
                        savePersistedJobs(user?.id, (p) => ({ ...p, activeSessionId: session.id }))
                      }
                    >
                      <Button
                        variant="secondary"
                        size="xs"
                        leftIcon={<FileBarChart className="h-3.5 w-3.5" aria-hidden="true" />}
                      >
                        Hesap Özeti
                      </Button>
                    </Link>
                    <Link
                      to={`/history/${session.id}`}
                      onClick={() =>
                        savePersistedJobs(user?.id, (p) => ({ ...p, activeSessionId: session.id }))
                      }
                    >
                      <Button
                        variant="secondary"
                        size="xs"
                        leftIcon={<History className="h-3.5 w-3.5" aria-hidden="true" />}
                      >
                        İşlem Geçmişi
                      </Button>
                    </Link>
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5">
                    {session.status === 'RUNNING' && (
                      <Button
                        variant="warning"
                        size="xs"
                        onClick={() => setConfirmState({ session, action: 'HALT' })}
                        leftIcon={<PauseCircle className="h-3.5 w-3.5" aria-hidden="true" />}
                      >
                        Duraklat
                      </Button>
                    )}
                    {session.status === 'HALTED' && (
                      <Button
                        variant="secondary"
                        size="xs"
                        onClick={() => setConfirmState({ session, action: 'RESUME' })}
                        leftIcon={<PlayCircle className="h-3.5 w-3.5" aria-hidden="true" />}
                      >
                        Oturumu Sürdür
                      </Button>
                    )}
                    {session.status !== 'COMPLETED' && (
                      <Button
                        variant="danger"
                        size="xs"
                        onClick={() => setConfirmState({ session, action: 'CLOSE_ALL' })}
                        leftIcon={<XOctagon className="h-3.5 w-3.5" aria-hidden="true" />}
                      >
                        Tüm Pozisyonları Kapat
                      </Button>
                    )}
                    <Button
                      variant="danger"
                      size="xs"
                      onClick={() => setConfirmState({ session, action: 'DELETE' })}
                      leftIcon={<Trash2 className="h-3.5 w-3.5" aria-hidden="true" />}
                    >
                      Cüzdanı Sil
                    </Button>
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Yeni Sanal İşlem Cüzdanı"
        description="Cüzdanınıza özel bir isim verin, sanal USDT başlangıç sermayenizi ve risk ayarlarınızı seçerek işlemlere başlayın."
        maxWidthClass="max-w-2xl"
        footer={
          <>
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>
              İptal
            </Button>
            <Button
              variant="primary"
              onClick={handleCreateSubmit}
              loading={createMutation.isPending}
            >
              Oturumu Oluştur ve Terminale Geç
            </Button>
          </>
        }
      >
        <form onSubmit={handleCreateSubmit} className="space-y-4">
          {createMutation.isError && (
            <ErrorBanner error={createMutation.error} title="Oturum oluşturulamadı" />
          )}

          <FieldGroup
            label="Sanal Cüzdan Adı"
            hint="Örn: Ana Vadeli Cüzdanım, BTC Scalping, AI Strateji Testi"
            required
          >
            {(id) => (
              <TextInput
                id={id}
                value={walletName}
                onChange={(e) => setWalletName(e.target.value)}
                placeholder="Örn: Ana Vadeli Cüzdanım"
                maxLength={60}
              />
            )}
          </FieldGroup>

          <div className="space-y-2">
            <span className="block text-xs font-medium text-[#0a0a0a]">
              Hızlı Başlangıç Bakiyesi Seçimi
            </span>
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
              {DEPOSIT_PRESETS.map((preset) => {
                const isSelected = rawDeposit === preset;
                return (
                  <button
                    key={preset}
                    type="button"
                    onClick={() => {
                      setRawDeposit(preset);
                      if (formError) setFormError(null);
                    }}
                    className={`border px-2.5 py-2 font-mono text-xs font-medium transition-colors ${
                      isSelected
                        ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                        : 'border-[#e5e5e5] bg-[#f5f5f5] text-[#0a0a0a] hover:border-[#0a0a0a]'
                    }`}
                  >
                    ${Number(preset).toLocaleString('tr-TR')}
                  </button>
                );
              })}
            </div>
          </div>

          <FieldGroup
            label="Sanal Başlangıç Sermayesi (USDT)"
            hint="İstediğiniz tutarı doğrudan yazabilir veya yukarıdaki hazır paketlerden seçebilirsiniz."
            error={formError}
            required
          >
            {(id) => (
              <DecimalInput
                id={id}
                value={rawDeposit}
                onChange={(val) => {
                  setRawDeposit(val);
                  if (formError) setFormError(null);
                }}
                suffix="USDT"
                hasError={Boolean(formError)}
              />
            )}
          </FieldGroup>

          <FieldGroup
            label="Piyasa & Komisyon Profili"
            hint="Seçilen oranlar sunucu emir önizlemesinde ve gerçekleşen işlemlerde birebir uygulanır."
            required
          >
            {(id) => (
              <div
                id={id}
                role="radiogroup"
                aria-label="Piyasa & Komisyon Profili"
                className="grid grid-cols-1 gap-2 sm:grid-cols-3"
              >
                {RULE_PROFILES.map((profile) => {
                  const selected = ruleProfileId === profile.id;
                  return (
                    <button
                      key={profile.id}
                      type="button"
                      role="radio"
                      aria-checked={selected}
                      onClick={() => setRuleProfileId(profile.id)}
                      className={`flex flex-col items-start justify-between border p-2.5 text-left transition-colors ${
                        selected
                          ? 'border-[#0a0a0a] bg-[#fafafa] ring-1 ring-[#0a0a0a]'
                          : 'border-[#e5e5e5] bg-white hover:border-[#a3a3a3] hover:bg-[#fafafa]'
                      }`}
                    >
                      <div className="flex w-full items-center justify-between gap-1.5">
                        <span className="text-xs font-semibold text-[#0a0a0a]">
                          {profile.title}
                        </span>
                        {profile.badge && (
                          <span className="border border-[#d4d4d4] bg-[#f5f5f5] px-1.5 py-0.5 text-[10px] font-semibold text-[#0a0a0a]">
                            {profile.badge}
                          </span>
                        )}
                      </div>
                      <div className="mt-2 flex w-full flex-col gap-0.5 border-y border-[#e5e5e5] py-1.5 font-mono text-[11px] text-[#0a0a0a]">
                        <span>{profile.makerFee}</span>
                        <span>{profile.takerFee}</span>
                      </div>
                      <p className="mt-1.5 text-[11px] leading-snug text-[#525252]">
                        {profile.summary}
                      </p>
                    </button>
                  );
                })}
              </div>
            )}
          </FieldGroup>

          <FieldGroup
            label="Risk & Kaldıraç Politikası"
            hint="Sözleşme tavanı izin verdiği sürece seçtiğiniz politikanın maksimum kaldıracı uygulanır."
            required
          >
            {(id) => (
              <div
                id={id}
                role="radiogroup"
                aria-label="Risk & Kaldıraç Politikası"
                className="grid grid-cols-1 gap-2 sm:grid-cols-3"
              >
                {RISK_POLICIES.map((policy) => {
                  const selected = riskPolicyId === policy.id;
                  return (
                    <button
                      key={policy.id}
                      type="button"
                      role="radio"
                      aria-checked={selected}
                      onClick={() => setRiskPolicyId(policy.id)}
                      className={`flex flex-col items-start justify-between border p-2.5 text-left transition-colors ${
                        selected
                          ? 'border-[#0a0a0a] bg-[#fafafa] ring-1 ring-[#0a0a0a]'
                          : 'border-[#e5e5e5] bg-white hover:border-[#a3a3a3] hover:bg-[#fafafa]'
                      }`}
                    >
                      <div className="flex w-full items-center justify-between gap-1.5">
                        <span className="text-xs font-semibold text-[#0a0a0a]">
                          {policy.title}
                        </span>
                        {policy.badge && (
                          <span className="border border-[#d4d4d4] bg-[#f5f5f5] px-1.5 py-0.5 text-[10px] font-semibold text-[#0a0a0a]">
                            {policy.badge}
                          </span>
                        )}
                      </div>
                      <div className="mt-2 flex w-full flex-col gap-0.5 border-y border-[#e5e5e5] py-1.5 font-mono text-[11px] text-[#0a0a0a]">
                        <span className="font-semibold">Maks. {policy.maxLeverage}x Kaldıraç</span>
                        <span className="text-[#525252]">{policy.dailyLossBrake}</span>
                      </div>
                      <p className="mt-1.5 text-[11px] leading-snug text-[#525252]">
                        {policy.summary}
                      </p>
                    </button>
                  );
                })}
              </div>
            )}
          </FieldGroup>

          <div className="space-y-1.5 border border-[#e5e5e5] bg-[#f5f5f5] p-3 text-xs text-[#525252]">
            <div className="flex flex-wrap items-center justify-between gap-2 font-medium text-[#0a0a0a]">
              <span>Cüzdan &amp; Başlangıç Bakiyesi:</span>
              <span className="font-mono font-semibold">
                {walletName.trim() || 'Sanal Cüzdan'} · {rawDeposit ? `${rawDeposit} USDT` : '—'}
              </span>
            </div>
            <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-[#0a0a0a]">
              <span className="text-[#525252]">Seçili Profil &amp; Limitler:</span>
              <span className="font-mono font-medium">
                {selectedRuleProfile.title} ({selectedRuleProfile.takerFee}) · Maks.{' '}
                {selectedRiskPolicy.maxLeverage}x ({selectedRiskPolicy.dailyLossBrake})
              </span>
            </div>
            <div className="text-[11px] text-[#737373]">
              Oturum oluşturulduğunda bu kurallar aktif olur ve doğrudan canlı emir terminaline yönlendirilirsiniz.
            </div>
          </div>
        </form>
      </Modal>

      <ConfirmDialog
        open={Boolean(confirmState)}
        onClose={() => setConfirmState(null)}
        onConfirm={() => {
          if (confirmState) {
            lifecycleMutation.mutate(confirmState);
          }
        }}
        loading={lifecycleMutation.isPending}
        variant={
          confirmState?.action === 'CLOSE_ALL' || confirmState?.action === 'DELETE'
            ? 'danger'
            : 'warning'
        }
        title={
          confirmState?.action === 'HALT'
            ? 'Oturumu Duraklat'
            : confirmState?.action === 'RESUME'
              ? 'Oturumu Yeniden Etkinleştir'
              : confirmState?.action === 'DELETE'
                ? 'Sanal Cüzdanı Sil'
                : 'Tüm Açık Pozisyonlar İçin Kapanış İsteği Gönder'
        }
        description={
          confirmState?.action === 'HALT'
            ? 'Bu işlem yalnız YENİ pozisyon açılışlarını durdurur; mevcut açık pozisyonlarınızı otomatik kapatmaz.'
            : confirmState?.action === 'RESUME'
              ? 'Oturum aktif durumuna dönecek ve yeni emir önizlemelerine izin verilecektir.'
              : confirmState?.action === 'DELETE'
                ? `"${confirmState.session.name || 'Sanal Cüzdan'}" içindeki açık pozisyonlar kapatılacak ve cüzdan oturum listenizden kaldırılacaktır.`
                : 'Oturumdaki tüm açık pozisyonlar için sunucuya piyasa fiyatından kapanış komutu gönderilir.'
        }
        confirmLabel={
          confirmState?.action === 'HALT'
            ? 'Evet, Yeni Girişleri Duraklat'
            : confirmState?.action === 'RESUME'
              ? 'Evet, Oturumu Sürdür'
              : confirmState?.action === 'DELETE'
                ? 'Evet, Cüzdanı Sil'
                : 'Evet, Tüm Pozisyonları Kapat'
        }
      />
    </div>
  );
}

export function SessionStatusBadge({ status }: { status: string }) {
  if (status === 'RUNNING') {
    return (
      <Badge tone="success">
        <span aria-hidden="true">●</span> Aktif
      </Badge>
    );
  }
  if (status === 'HALTED') {
    return (
      <Badge tone="wait">
        <span aria-hidden="true">⏸</span> Duraklatıldı
      </Badge>
    );
  }
  return <Badge tone="neutral">{status}</Badge>;
}

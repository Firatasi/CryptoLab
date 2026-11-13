import { useCallback, useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Mail, RefreshCw, ShieldAlert, Users } from 'lucide-react';
import type {
  AdminMessageStatus,
  AdminOverviewResponse,
  ContactMessageResponse,
} from '@/shared/contracts/types';
import { formatTimestamp } from '@/shared/formatting/formatters';
import { Badge, Button, Card } from '@/shared/ui/Primitives';
import { ErrorBanner } from '@/shared/ui/Feedback';
import { useAuth } from '@/features/auth/AuthContext';
import { authApi } from '@/features/auth/api';

type StatusFilter = 'ALL' | AdminMessageStatus;

const STATUS_LABEL: Record<AdminMessageStatus, string> = {
  NEW: 'Yeni',
  READ: 'Okundu',
  RESOLVED: 'Çözüldü',
};

const STATUS_TONE: Record<AdminMessageStatus, 'accent' | 'paper' | 'neutral'> = {
  NEW: 'accent',
  READ: 'paper',
  RESOLVED: 'neutral',
};

function recomputeNewCount(messages: ContactMessageResponse[]): number {
  return messages.filter((item) => item.status === 'NEW').length;
}

export function AdminPage() {
  const { timezone } = useAuth();
  const [overview, setOverview] = useState<AdminOverviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  const loadOverview = useCallback(async (background = false) => {
    if (background) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const data = await authApi.adminOverview();
      setOverview(data);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void loadOverview(false);
    const timer = window.setInterval(() => {
      void loadOverview(true);
    }, 10_000);
    return () => window.clearInterval(timer);
  }, [loadOverview]);

  const handleStatusChange = async (id: string, nextStatus: AdminMessageStatus) => {
    setUpdatingId(id);
    setError(null);
    try {
      const updated = await authApi.updateAdminMessageStatus(id, nextStatus);
      setOverview((prev) => {
        if (!prev) return prev;
        const nextMessages = prev.messages.map((item) =>
          item.id === id ? updated : item
        );
        return {
          ...prev,
          messages: nextMessages,
          newContactCount: recomputeNewCount(nextMessages),
        };
      });
    } catch (err) {
      setError(err);
    } finally {
      setUpdatingId(null);
    }
  };

  const filteredMessages = useMemo(() => {
    if (!overview) return [];
    if (statusFilter === 'ALL') return overview.messages;
    return overview.messages.filter((item) => item.status === statusFilter);
  }, [overview, statusFilter]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-[#e5e5e5] pb-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <Badge tone="accent">YÖNETİCİ PANELİ</Badge>
            <span className="font-mono text-[11px] text-[#737373]">
              {overview?.supportInbox ?? 'support@cryptolab.dev'}
            </span>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-[#0a0a0a]">
            Sistem ve İletişim Yönetimi
          </h1>
          <p className="text-xs text-[#737373]">
            Canlı kullanıcı aktivitesini ve iletişim formundan gelen destek taleplerini tek noktadan takip edin.
          </p>
        </div>

        <Button
          type="button"
          variant="secondary"
          size="md"
          onClick={() => void loadOverview(true)}
          disabled={refreshing || loading}
        >
          <RefreshCw className={`h-4 w-4 ${refreshing || loading ? 'animate-spin' : ''}`} />
          <span>Yenile</span>
        </Button>
      </div>

      {error ? <ErrorBanner error={error} /> : null}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-[#737373]">
            <span className="font-mono uppercase tracking-wider">Canlı Kullanıcı</span>
            <Users className="h-4 w-4 text-[#0a0a0a]" />
          </div>
          <div className="mt-2 font-mono text-3xl font-semibold text-[#0a0a0a]" data-testid="live-user-count">
            {overview ? overview.liveUserCount : '—'}
          </div>
          <p className="mt-1 text-[11px] text-[#737373]">Son etkinlik penceresindeki aktif hesaplar</p>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-[#737373]">
            <span className="font-mono uppercase tracking-wider">Aktif Oturum</span>
            <ShieldAlert className="h-4 w-4 text-[#0a0a0a]" />
          </div>
          <div className="mt-2 font-mono text-3xl font-semibold text-[#0a0a0a]">
            {overview ? overview.activeSessionUsersCount : '—'}
          </div>
          <p className="mt-1 text-[11px] text-[#737373]">Geçerli yenileme oturumuna sahip kullanıcı</p>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-[#737373]">
            <span className="font-mono uppercase tracking-wider">Toplam Hesap</span>
            <Users className="h-4 w-4 text-[#737373]" />
          </div>
          <div className="mt-2 font-mono text-3xl font-semibold text-[#0a0a0a]">
            {overview ? overview.totalUserCount : '—'}
          </div>
          <p className="mt-1 text-[11px] text-[#737373]">Sistemde kayıtlı toplam kullanıcı</p>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-[#737373]">
            <span className="font-mono uppercase tracking-wider">Gelen Mesajlar</span>
            <Mail className="h-4 w-4 text-[#0a0a0a]" />
          </div>
          <div className="mt-2 flex items-baseline gap-2 font-mono">
            <span className="text-3xl font-semibold text-[#0a0a0a]" data-testid="new-contact-count">
              {overview ? overview.newContactCount : '—'}
            </span>
            <span className="text-xs text-[#737373]">
              yeni / {overview ? overview.totalContactCount : '—'} toplam
            </span>
          </div>
          <p className="mt-1 text-[11px] text-[#737373]">
            Alıcı: {overview?.supportInbox ?? 'support@cryptolab.dev'}
          </p>
        </Card>
      </div>

      <Card className="p-5">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[#e5e5e5] pb-4">
          <div>
            <h2 className="text-sm font-semibold text-[#0a0a0a]">İletişim Formu Kayıtları</h2>
            <p className="text-xs text-[#737373]">
              Ana sayfa iletişim formundan veya destek kanalından gelen mesajlar
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            {(['ALL', 'NEW', 'READ', 'RESOLVED'] as const).map((filterValue) => {
              const active = statusFilter === filterValue;
              const label = filterValue === 'ALL' ? 'Tümü' : STATUS_LABEL[filterValue];
              return (
                <button
                  key={filterValue}
                  type="button"
                  onClick={() => setStatusFilter(filterValue)}
                  className={`border px-2.5 py-1 font-mono text-[11px] transition-colors ${
                    active
                      ? 'border-[#0a0a0a] bg-[#0a0a0a] text-white'
                      : 'border-[#e5e5e5] bg-white text-[#737373] hover:text-[#0a0a0a]'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="mt-4 space-y-3">
          {loading && !overview ? (
            <div className="py-10 text-center font-mono text-xs text-[#737373]">
              Yönetici verileri yükleniyor...
            </div>
          ) : filteredMessages.length === 0 ? (
            <div className="border border-dashed border-[#d4d4d4] bg-[#fafafa] py-10 text-center text-xs text-[#737373]">
              Seçili filtreye uygun iletişim formu kaydı bulunmuyor.
            </div>
          ) : (
            filteredMessages.map((msg) => (
              <div
                key={msg.id}
                className="space-y-2.5 border border-[#e5e5e5] bg-[#fafafa] p-4"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="space-y-0.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-semibold text-[#0a0a0a]">{msg.subject}</span>
                      <Badge tone={STATUS_TONE[msg.status]}>{STATUS_LABEL[msg.status]}</Badge>
                    </div>
                    <div className="flex flex-wrap items-center gap-3 text-xs text-[#737373]">
                      <span>
                        Gönderen:{' '}
                        <strong className="font-medium text-[#0a0a0a]">{msg.senderName}</strong> (
                        {/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(msg.senderEmail) ? (
                          <a
                            href={`mailto:${msg.senderEmail}`}
                            className="underline hover:text-[#0a0a0a]"
                          >
                            {msg.senderEmail}
                          </a>
                        ) : (
                          <span>{msg.senderEmail}</span>
                        )}
                        )
                      </span>
                      <span className="font-mono text-[11px]">Alıcı: {msg.recipientEmail}</span>
                    </div>
                  </div>

                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-[11px] text-[#737373]">
                      {formatTimestamp(msg.createdAt, timezone)}
                    </span>
                    {msg.status !== 'READ' ? (
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        loading={updatingId === msg.id}
                        onClick={() => void handleStatusChange(msg.id, 'READ')}
                      >
                        Okundu İşaretle
                      </Button>
                    ) : null}
                    {msg.status !== 'RESOLVED' ? (
                      <Button
                        type="button"
                        variant="primary"
                        size="sm"
                        loading={updatingId === msg.id}
                        onClick={() => void handleStatusChange(msg.id, 'RESOLVED')}
                      >
                        <CheckCircle2 className="h-3.5 w-3.5" />
                        <span>Çözüldü İşaretle</span>
                      </Button>
                    ) : null}
                  </div>
                </div>

                <p className="border-t border-[#e5e5e5] pt-2.5 text-xs leading-relaxed text-[#262626] whitespace-pre-wrap">
                  {msg.message}
                </p>
              </div>
            ))
          )}
        </div>
      </Card>
    </div>
  );
}

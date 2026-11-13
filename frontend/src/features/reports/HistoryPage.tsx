import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { getTradeHistory } from './api';
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
  SideBadge,
} from '@/shared/ui/Primitives';
import { FieldGroup, SelectInput, TextInput } from '@/shared/ui/FormControls';
import { ErrorBanner } from '@/shared/ui/Feedback';

export function HistoryPage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const { timezone } = useAuth();

  const [cursorStack, setCursorStack] = useState<string[]>([]);
  const currentCursor = cursorStack[cursorStack.length - 1] ?? null;

  const [symbolFilter, setSymbolFilter] = useState('');
  const [sideFilter, setSideFilter] = useState<'ALL' | 'LONG' | 'SHORT'>('ALL');

  const historyQuery = useQuery({
    queryKey: queryKeys.account.trades(sessionId, currentCursor, 25),
    queryFn: ({ signal }) =>
      getTradeHistory({
        sessionId,
        cursor: currentCursor,
        limit: 25,
        signal,
      }),
    enabled: Boolean(sessionId),
    refetchOnMount: 'always',
  });

  const data = historyQuery.data;
  const filteredItems = (data?.items ?? []).filter((item) => {
    if (
      symbolFilter.trim() &&
      !item.symbol.toLowerCase().includes(symbolFilter.trim().toLowerCase()) &&
      !item.instrumentId.toLowerCase().includes(symbolFilter.trim().toLowerCase())
    ) {
      return false;
    }
    if (sideFilter !== 'ALL' && item.side !== sideFilter) {
      return false;
    }
    return true;
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-none border border-[#e5e5e5] bg-white px-4 py-3">
        <div>
          <h1 className="text-lg font-bold tracking-tight text-[#0a0a0a]">
            İşlem Geçmişi (Trade History)
          </h1>
          <p className="text-xs text-[#737373]">
            İmzalı HMAC cursor ile sayfalanan gerçek işlem kapanışları, ücretler, fonlama ve
            net gerçekleşmiş PnL kayıtları.
          </p>
        </div>
      </div>

      {data?.coverage === 'BUILDING' && (
        <div
          role="status"
          className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] p-4 text-xs text-[#0a0a0a]"
        >
          <strong>İşlem Geçmişi Projeksiyonu İnşa Ediliyor (BUILDING):</strong> Raporlama
          servisi henüz olayları indeksliyor. Liste geçici olarak eksik olabilir.
        </div>
      )}

      <Card>
        <CardHeader
          title="Filtreleme ve Cursor Sayfalama"
          subtitle={`Sayfa: ${cursorStack.length + 1} · Kapsam: ${
            data?.coverage ?? 'READY'
          } · Projeksiyon Zamanı: ${formatTimestamp(data?.projectionAsOf, timezone)}`}
          action={
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={cursorStack.length === 0 || historyQuery.isFetching}
                onClick={() => setCursorStack((prev) => prev.slice(0, -1))}
              >
                ← Önceki Sayfa
              </Button>
              <Button
                size="sm"
                variant="primary"
                disabled={!data?.nextCursor || historyQuery.isFetching}
                onClick={() => {
                  if (data?.nextCursor) {
                    setCursorStack((prev) => [...prev, data.nextCursor!]);
                  }
                }}
              >
                Sonraki Sayfa →
              </Button>
            </div>
          }
        />

        <div className="p-4 space-y-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <FieldGroup label="Enstrüman Filtresi">
              {(id) => (
                <TextInput
                  id={id}
                  placeholder="Örn: BTCUSDT"
                  value={symbolFilter}
                  onChange={(e) => setSymbolFilter(e.target.value)}
                />
              )}
            </FieldGroup>
            <FieldGroup label="Yön Filtresi">
              {(id) => (
                <SelectInput
                  id={id}
                  value={sideFilter}
                  onChange={(e) => setSideFilter(e.target.value as 'ALL' | 'LONG' | 'SHORT')}
                >
                  <option value="ALL">Tümü (LONG + SHORT)</option>
                  <option value="LONG">▲ LONG</option>
                  <option value="SHORT">▼ SHORT</option>
                </SelectInput>
              )}
            </FieldGroup>
          </div>

          {historyQuery.isLoading ? (
            <div className="py-12 text-center text-sm text-[#737373]">
              İşlem geçmişi yükleniyor...
            </div>
          ) : historyQuery.isError ? (
            <ErrorBanner
              error={historyQuery.error}
              onRetry={() => void historyQuery.refetch()}
            />
          ) : filteredItems.length === 0 ? (
            <div className="py-10 text-center text-xs text-[#737373]">
              Bu sayfada eşleşen işlem kaydı bulunamadı.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs tabular-nums">
                <thead>
                  <tr className="border-b border-[#e5e5e5] text-[#737373]">
                    <th className="py-2 pr-3">Kapanış Zamanı</th>
                    <th className="py-2 px-2">Enstrüman</th>
                    <th className="py-2 px-2">Yön</th>
                    <th className="py-2 px-2">Durum</th>
                    <th className="py-2 px-2">Likidasyon</th>
                    <th className="py-2 px-2 text-right">Brüt PnL</th>
                    <th className="py-2 px-2 text-right">Ücret (Fees)</th>
                    <th className="py-2 px-2 text-right">Fonlama</th>
                    <th className="py-2 px-2 text-right">Lik. Ücreti</th>
                    <th className="py-2 pl-2 text-right">Net PnL</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#e5e5e5]">
                  {filteredItems.map((trade) => {
                    const isLiquidated = trade.status === 'LIQUIDATED';

                    return (
                    <tr key={trade.positionId} className="hover:bg-[#f5f5f5]">
                      <td className="py-2.5 pr-3 font-mono text-[#525252]">
                        {formatTimestamp(trade.closedAt ?? trade.openedAt, timezone)}
                      </td>
                      <td className="py-2.5 px-2 font-mono font-semibold text-[#0a0a0a]">
                        {trade.symbol || trade.instrumentId}
                      </td>
                      <td className="py-2.5 px-2">
                        <SideBadge side={trade.side} />
                      </td>
                      <td className="py-2.5 px-2">
                        <Badge
                          tone={
                            isLiquidated
                              ? 'danger'
                              : trade.status === 'OPEN'
                                ? 'accent'
                                : 'neutral'
                          }
                        >
                          {trade.status}
                        </Badge>
                      </td>
                      <td className="py-2.5 px-2">
                        {isLiquidated ? (
                          <Badge tone="danger">
                            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
                            Likit Oldu
                          </Badge>
                        ) : (
                          <span className="text-[#737373]">—</span>
                        )}
                      </td>
                      <td
                        className={`py-2.5 px-2 text-right font-mono ${pnlToneClass(
                          trade.realizedPnl,
                        )}`}
                      >
                        {formatSignedMoney(trade.realizedPnl, data?.currency)}
                      </td>
                      <td className="py-2.5 px-2 text-right font-mono text-[#525252]">
                        −{formatMoney(trade.totalFees, data?.currency)}
                      </td>
                      <td
                        className={`py-2.5 px-2 text-right font-mono ${pnlToneClass(
                          trade.signedFunding,
                        )}`}
                      >
                        {formatSignedMoney(trade.signedFunding, data?.currency)}
                      </td>
                      <td className="py-2.5 px-2 text-right font-mono text-[#dc2626]">
                        {formatMoney(trade.liquidationFees, data?.currency)}
                      </td>
                      <td
                        className={`py-2.5 pl-2 text-right font-mono font-semibold ${pnlToneClass(
                          trade.netRealizedPnl,
                        )}`}
                      >
                        {formatSignedMoney(trade.netRealizedPnl, data?.currency)}
                      </td>
                    </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

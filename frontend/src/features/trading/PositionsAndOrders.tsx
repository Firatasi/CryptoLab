import React, { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Edit3, XCircle } from 'lucide-react';
import type { OpenOrderView, PositionView } from '@/shared/contracts/types';
import { isApiError } from '@/shared/api/apiErrors';
import { createIdempotencyKey } from '@/shared/api/idempotency';
import { queryKeys } from '@/shared/api/queryKeys';
import {
  parseOptionalPositiveDecimalInput,
  parsePositiveDecimalInput,
} from '@/shared/formatting/decimalParser';
import {
  formatMoney,
  formatPrice,
  formatQuantity,
  formatSignedMoney,
  formatTimestamp,
  pnlToneClass,
  type TimezonePreference,
} from '@/shared/formatting/formatters';
import { Badge, Button, QualityBadge, SideBadge } from '@/shared/ui/Primitives';
import { DecimalInput, FieldGroup } from '@/shared/ui/FormControls';
import { Modal } from '@/shared/ui/Modal';
import { EmptyState, ErrorBanner } from '@/shared/ui/Feedback';
import { cn } from '@/shared/ui/cn';
import { tradingApi } from './api';
import {
  type BracketInputMode,
  estimateSizingFromOpenPosition,
  formatFixedDecimal,
  parseFiniteDecimal,
  pnlUsdFromTriggerPrice,
  stepDecimals,
  stopLossPriceFromUsd,
  takeProfitPriceFromUsd,
  validateStopLossPriceAgainstLiquidation,
} from './orderBracketMath';

/**
 * F05.1 / F05.2 / F05.3 / F05.4:
 * - Pozisyon alanları doğrudan sunucudan (PositionView) gösterilir; tarayıcıda PnL/marjin hesabı yapılmaz.
 * - 202 kapanış isteği sonrasında pozisyon iyimser (optimistic) olarak gizlenmez; closeRequested
 *   veya EXIT_PENDING_DATA / LIQUIDATING durumu açıkça gösterilir.
 */
export function PositionsTable({
  sessionId,
  positions,
  timezone = 'Europe/Istanbul',
}: {
  sessionId: string;
  positions: PositionView[];
  timezone?: TimezonePreference;
}) {
  const queryClient = useQueryClient();
  const [closingTarget, setClosingTarget] = useState<PositionView | null>(null);
  const [bracketTarget, setBracketTarget] = useState<PositionView | null>(null);
  const [closeMode, setCloseMode] = useState<'FULL' | 'PARTIAL'>('FULL');
  const [partialQty, setPartialQty] = useState('');
  const [slInput, setSlInput] = useState('');
  const [tpInput, setTpInput] = useState('');
  const [modalSlMode, setModalSlMode] = useState<BracketInputMode>('PRICE');
  const [modalTpMode, setModalTpMode] = useState<BracketInputMode>('PRICE');
  const [modalValidation, setModalValidation] = useState<string | null>(null);
  const [requestedIds, setRequestedIds] = useState<Record<string, boolean>>({});

  const invalidateSessionViews = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.trading.positions(sessionId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.trading.openOrders(sessionId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.account.summary(sessionId) });
  };

  const closeMutation = useMutation({
    mutationFn: (params: {
      positionId: string;
      expectedVersion: number;
      closeQuantity: string | null;
    }) =>
      tradingApi.closePosition(
        params.positionId,
        {
          expectedVersion: params.expectedVersion,
          closeQuantity: params.closeQuantity,
        },
        createIdempotencyKey()
      ),
    onSuccess: (_, vars) => {
      // 202 ACCEPTED: Pozisyonu listeden silmiyoruz; closeRequested olarak işaretleyip sunucu teyidini bekliyoruz!
      setRequestedIds((prev) => ({ ...prev, [vars.positionId]: true }));
      setClosingTarget(null);
      invalidateSessionViews();
    },
    onError: (err) => {
      if (isApiError(err) && err.code === 'VERSION_CONFLICT') {
        invalidateSessionViews();
      }
    },
  });

  const bracketMutation = useMutation({
    mutationFn: (params: {
      positionId: string;
      expectedVersion: number;
      stopLoss: string | null;
      takeProfit: string | null;
    }) =>
      tradingApi.updateBrackets(
        params.positionId,
        {
          expectedVersion: params.expectedVersion,
          stopLoss: params.stopLoss,
          takeProfit: params.takeProfit,
        },
        createIdempotencyKey()
      ),
    onSuccess: () => {
      setBracketTarget(null);
      invalidateSessionViews();
    },
    onError: (err) => {
      if (isApiError(err) && err.code === 'VERSION_CONFLICT') {
        invalidateSessionViews();
      }
    },
  });

  const handleOpenCloseModal = (pos: PositionView) => {
    setClosingTarget(pos);
    setCloseMode('FULL');
    setPartialQty('');
    setModalValidation(null);
    closeMutation.reset();
  };

  const handleOpenBracketModal = (pos: PositionView) => {
    setBracketTarget(pos);
    setSlInput(pos.stopLoss ?? '');
    setTpInput(pos.takeProfit ?? '');
    setModalSlMode('PRICE');
    setModalTpMode('PRICE');
    setModalValidation(null);
    bracketMutation.reset();
  };

  const bracketSizing = useMemo(() => {
    if (!bracketTarget) return null;
    return estimateSizingFromOpenPosition({
      side: bracketTarget.side,
      quantity: parseFiniteDecimal(bracketTarget.quantity),
      entryPrice: parseFiniteDecimal(bracketTarget.entryPrice),
      margin: parseFiniteDecimal(bracketTarget.isolatedMargin),
    });
  }, [bracketTarget]);

  const resolvedModalSl = useMemo(() => {
    if (!bracketTarget || !slInput.trim()) {
      return { priceStr: null as string | null, usdEstimate: null as number | null, error: null as string | null };
    }
    if (modalSlMode === 'USDT') {
      const parsedUsd = parsePositiveDecimalInput(slInput, 2);
      if (!parsedUsd.valid) {
        return { priceStr: null, usdEstimate: null, error: `Zarar tutarı: ${parsedUsd.message}` };
      }
      if (!bracketSizing) {
        return { priceStr: null, usdEstimate: null, error: 'Pozisyon büyüklüğü hesaplanamadı.' };
      }
      const conv = stopLossPriceFromUsd(bracketTarget.side, bracketSizing, Number(parsedUsd.normalized));
      if (conv.error || conv.price === null) {
        return { priceStr: null, usdEstimate: Number(parsedUsd.normalized), error: conv.error };
      }
      return {
        priceStr: formatFixedDecimal(conv.price, stepDecimals(bracketSizing.tickSize)),
        usdEstimate: Number(parsedUsd.normalized),
        error: null,
      };
    }
    const parsedPrice = parsePositiveDecimalInput(slInput, 8);
    if (!parsedPrice.valid) {
      return { priceStr: null, usdEstimate: null, error: `Stop-Loss: ${parsedPrice.message}` };
    }
    const numPrice = Number(parsedPrice.normalized);
    return {
      priceStr: parsedPrice.normalized,
      usdEstimate: bracketSizing ? pnlUsdFromTriggerPrice(bracketTarget.side, bracketSizing, numPrice, 'SL') : null,
      error: validateStopLossPriceAgainstLiquidation(bracketTarget.side, bracketSizing, numPrice),
    };
  }, [bracketSizing, bracketTarget, modalSlMode, slInput]);

  const resolvedModalTp = useMemo(() => {
    if (!bracketTarget || !tpInput.trim()) {
      return { priceStr: null as string | null, usdEstimate: null as number | null, error: null as string | null };
    }
    if (modalTpMode === 'USDT') {
      const parsedUsd = parsePositiveDecimalInput(tpInput, 2);
      if (!parsedUsd.valid) {
        return { priceStr: null, usdEstimate: null, error: `Kâr hedefi: ${parsedUsd.message}` };
      }
      if (!bracketSizing) {
        return { priceStr: null, usdEstimate: null, error: 'Pozisyon büyüklüğü hesaplanamadı.' };
      }
      const conv = takeProfitPriceFromUsd(bracketTarget.side, bracketSizing, Number(parsedUsd.normalized));
      if (conv.error || conv.price === null) {
        return { priceStr: null, usdEstimate: Number(parsedUsd.normalized), error: conv.error };
      }
      return {
        priceStr: formatFixedDecimal(conv.price, stepDecimals(bracketSizing.tickSize)),
        usdEstimate: Number(parsedUsd.normalized),
        error: null,
      };
    }
    const parsedPrice = parsePositiveDecimalInput(tpInput, 8);
    if (!parsedPrice.valid) {
      return { priceStr: null, usdEstimate: null, error: `Take-Profit: ${parsedPrice.message}` };
    }
    return {
      priceStr: parsedPrice.normalized,
      usdEstimate: bracketSizing
        ? pnlUsdFromTriggerPrice(bracketTarget.side, bracketSizing, Number(parsedPrice.normalized), 'TP')
        : null,
      error: null,
    };
  }, [bracketSizing, bracketTarget, modalTpMode, tpInput]);

  const switchModalSlMode = (nextMode: BracketInputMode) => {
    if (nextMode === modalSlMode) return;
    if (bracketTarget && slInput.trim() && bracketSizing) {
      if (nextMode === 'USDT' && resolvedModalSl.priceStr) {
        const usd = pnlUsdFromTriggerPrice(bracketTarget.side, bracketSizing, Number(resolvedModalSl.priceStr), 'SL');
        if (usd !== null) setSlInput(formatFixedDecimal(usd, 2));
      } else if (nextMode === 'PRICE' && resolvedModalSl.priceStr) {
        setSlInput(resolvedModalSl.priceStr);
      }
    }
    setModalSlMode(nextMode);
    setModalValidation(null);
  };

  const switchModalTpMode = (nextMode: BracketInputMode) => {
    if (nextMode === modalTpMode) return;
    if (bracketTarget && tpInput.trim() && bracketSizing) {
      if (nextMode === 'USDT' && resolvedModalTp.priceStr) {
        const usd = pnlUsdFromTriggerPrice(bracketTarget.side, bracketSizing, Number(resolvedModalTp.priceStr), 'TP');
        if (usd !== null) setTpInput(formatFixedDecimal(usd, 2));
      } else if (nextMode === 'PRICE' && resolvedModalTp.priceStr) {
        setTpInput(resolvedModalTp.priceStr);
      }
    }
    setModalTpMode(nextMode);
    setModalValidation(null);
  };

  const submitClose = (e: React.FormEvent) => {
    e.preventDefault();
    if (!closingTarget) return;

    let closeQuantity: string | null = null;
    if (closeMode === 'PARTIAL') {
      const parsed = parsePositiveDecimalInput(partialQty, 8);
      if (!parsed.valid) {
        setModalValidation(`Kapatılacak miktar: ${parsed.message}`);
        return;
      }
      closeQuantity = parsed.normalized;
    }

    setModalValidation(null);
    closeMutation.mutate({
      positionId: closingTarget.positionId,
      expectedVersion: closingTarget.version,
      closeQuantity,
    });
  };

  const submitBrackets = (e: React.FormEvent) => {
    e.preventDefault();
    if (!bracketTarget) return;

    if (resolvedModalSl.error) {
      setModalValidation(resolvedModalSl.error);
      return;
    }
    if (resolvedModalTp.error) {
      setModalValidation(resolvedModalTp.error);
      return;
    }

    const parsedSl = parseOptionalPositiveDecimalInput(resolvedModalSl.priceStr ?? '', 8);
    if (!parsedSl.valid) {
      setModalValidation(`Stop-Loss: ${parsedSl.message}`);
      return;
    }
    const parsedTp = parseOptionalPositiveDecimalInput(resolvedModalTp.priceStr ?? '', 8);
    if (!parsedTp.valid) {
      setModalValidation(`Take-Profit: ${parsedTp.message}`);
      return;
    }

    setModalValidation(null);
    bracketMutation.mutate({
      positionId: bracketTarget.positionId,
      expectedVersion: bracketTarget.version,
      stopLoss: parsedSl.normalized,
      takeProfit: parsedTp.normalized,
    });
  };

  if (positions.length === 0) {
    return (
      <EmptyState
        title="Açık pozisyon bulunmuyor"
        description="Emir formundan sunucu önizlemesi alıp onayladığınızda açılan pozisyonlar burada gerçek zamanlı izlenir."
      />
    );
  }

  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-[#e5e5e5] text-[11px] font-medium text-[#737373]">
              <th className="px-2.5 py-2">Sözleşme</th>
              <th className="px-2.5 py-2 text-right">Miktar</th>
              <th className="px-2.5 py-2 text-right">Giriş / Mark</th>
              <th className="px-2.5 py-2 text-right">Marjin</th>
              <th className="px-2.5 py-2 text-right">Anlık PnL</th>
              <th className="px-2.5 py-2 text-right">Tasfiye / SL-TP</th>
              <th className="px-2.5 py-2 text-right">İşlem</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#e5e5e5] font-mono tabular-nums">
            {positions.map((pos) => {
              const isClosePending =
                pos.closeRequested ||
                Boolean(requestedIds[pos.positionId]) ||
                pos.status === 'CLOSE_REQUESTED';

              return (
                <tr
                  key={pos.positionId}
                  data-testid={`position-row-${pos.positionId}`}
                  className="hover:bg-[#f5f5f5]"
                >
                  <td className="px-2.5 py-2.5">
                    <div className="flex items-center gap-1.5">
                      <span className="font-bold text-[#0a0a0a]">{pos.symbol}</span>
                      <SideBadge side={pos.side} />
                      <span className="text-[10px] text-[#737373]">
                        {pos.leverage}x · v{pos.version}
                      </span>
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-1 font-sans">
                      <PositionLifecycleBadge status={pos.status} closeRequested={isClosePending} />
                      {pos.dataQuality !== 'FRESH' && <QualityBadge quality={pos.dataQuality} />}
                    </div>
                  </td>

                  <td className="px-2.5 py-2.5 text-right font-semibold text-[#0a0a0a]">
                    {formatQuantity(pos.quantity)}
                  </td>

                  <td className="px-2.5 py-2.5 text-right">
                    <div className="text-[#0a0a0a]">{formatPrice(pos.entryPrice)}</div>
                    <div className="text-[11px] font-semibold text-[#525252]">
                      {formatPrice(pos.markPrice)}
                    </div>
                  </td>

                  <td className="px-2.5 py-2.5 text-right">
                    <div className="text-[#0a0a0a]">{formatMoney(pos.isolatedMargin)}</div>
                    <div className="text-[10px] text-[#737373]">
                      Bakım: {formatMoney(pos.maintenanceMargin)}
                    </div>
                  </td>

                  <td
                    className={`px-2.5 py-2.5 text-right font-semibold ${pnlToneClass(
                      pos.unrealizedPnl
                    )}`}
                  >
                    {pos.dataQuality === 'UNAVAILABLE'
                      ? '—'
                      : formatSignedMoney(pos.unrealizedPnl)}
                  </td>

                  <td className="px-2.5 py-2.5 text-right text-[11px]">
                    <div className="font-semibold text-[#dc2626]">
                      Liq: {formatPrice(pos.estimatedLiquidationPrice)}
                    </div>
                    <div className="text-[#525252]">
                      {formatPrice(pos.stopLoss)} / {formatPrice(pos.takeProfit)}
                    </div>
                    {pos.deadlineAt && (
                      <div className="text-[10px] text-[#737373]">
                        {formatTimestamp(pos.deadlineAt, timezone)}
                      </div>
                    )}
                  </td>

                  <td className="px-2.5 py-2.5 text-right font-sans">
                    <div className="inline-flex items-center gap-1">
                      <Button
                        variant="secondary"
                        size="xs"
                        disabled={pos.status === 'LIQUIDATING' || pos.status === 'CLOSED'}
                        onClick={() => handleOpenBracketModal(pos)}
                        leftIcon={<Edit3 className="h-3 w-3" aria-hidden="true" />}
                      >
                        SL/TP
                      </Button>
                      <Button
                        variant="danger"
                        size="xs"
                        disabled={
                          isClosePending || pos.status === 'LIQUIDATING' || pos.status === 'CLOSED'
                        }
                        onClick={() => handleOpenCloseModal(pos)}
                      >
                        {isClosePending ? 'Bekleniyor' : 'Kapat'}
                      </Button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Tam / Kısmi Pozisyon Kapatma Modalı */}
      <Modal
        open={Boolean(closingTarget)}
        onClose={() => setClosingTarget(null)}
        title={`Pozisyon Kapat · ${closingTarget?.symbol ?? ''}`}
        description="Güncel pozisyon sürümü doğrulanarak kapatılır. İşlem onaylanana kadar pozisyon tabloda görüntülenmeye devam eder."
        footer={
          <>
            <Button variant="secondary" onClick={() => setClosingTarget(null)}>
              Vazgeç
            </Button>
            <Button
              variant="danger"
              onClick={submitClose}
              loading={closeMutation.isPending}
            >
              Kapanış Komutu Gönder
            </Button>
          </>
        }
      >
        {closingTarget && (
          <form onSubmit={submitClose} className="space-y-4">
            {closeMutation.isError && (
              <ErrorBanner error={closeMutation.error} title="Kapanış komutu reddedildi" />
            )}
            {modalValidation && (
              <div role="alert" className="text-xs text-[#dc2626]">
                {modalValidation}
              </div>
            )}

            <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3 font-mono text-xs text-[#0a0a0a] space-y-1">
              <div>Mevcut Miktar: {closingTarget.quantity}</div>
              <div>Giriş Fiyatı: {formatPrice(closingTarget.entryPrice)}</div>
            </div>

            <div className="grid grid-cols-2 gap-2" role="group" aria-label="Kapanış kapsamı">
              <Button
                type="button"
                variant={closeMode === 'FULL' ? 'primary' : 'secondary'}
                size="sm"
                onClick={() => setCloseMode('FULL')}
              >
                Tam Kapanış (%100)
              </Button>
              <Button
                type="button"
                variant={closeMode === 'PARTIAL' ? 'primary' : 'secondary'}
                size="sm"
                onClick={() => setCloseMode('PARTIAL')}
              >
                Kısmi Miktar Kapat
              </Button>
            </div>

            {closeMode === 'PARTIAL' && (
              <FieldGroup
                label="Kapatılacak Miktar (Azaltıcı Emir Miktarı)"
                hint={`Mevcut miktar: ${closingTarget.quantity} — kalan miktar sunucu tarafından hesaplanır.`}
                required
              >
                {(id) => (
                  <DecimalInput
                    id={id}
                    value={partialQty}
                    onChange={setPartialQty}
                    placeholder="Örn: 0,05"
                  />
                )}
              </FieldGroup>
            )}
          </form>
        )}
      </Modal>

      {/* SL / TP Bracket Düzenleme Modalı */}
      <Modal
        open={Boolean(bracketTarget)}
        onClose={() => setBracketTarget(null)}
        title={`SL / TP Bracket Düzenle · ${bracketTarget?.symbol ?? ''}`}
        description="Güncel pozisyon sürümü doğrulanarak zarar durdur ve kâr al seviyeleri güncellenir."
        footer={
          <>
            <Button variant="secondary" onClick={() => setBracketTarget(null)}>
              Vazgeç
            </Button>
            <Button
              variant="primary"
              onClick={submitBrackets}
              loading={bracketMutation.isPending}
            >
              Bracket Güncelle
            </Button>
          </>
        }
      >
        {bracketTarget && (
          <form onSubmit={submitBrackets} className="space-y-4">
            {bracketMutation.isError && (
              <ErrorBanner error={bracketMutation.error} title="Bracket güncellenemedi" />
            )}
            {modalValidation && (
              <div role="alert" className="text-xs text-[#dc2626]">
                {modalValidation}
              </div>
            )}

            <FieldGroup
              label="Yeni Stop-Loss (Boş bırakılırsa kaldırılır)"
              error={resolvedModalSl.error}
              hint={
                !resolvedModalSl.error
                  ? modalSlMode === 'USDT'
                    ? resolvedModalSl.priceStr
                      ? `Tetik: ~${formatPrice(resolvedModalSl.priceStr)} USDT${
                          bracketSizing?.maxSafeStopLossUsd !== null && bracketSizing?.maxSafeStopLossUsd !== undefined
                            ? ` · Maks. ${bracketSizing.maxSafeStopLossUsd.toLocaleString('tr-TR', { maximumFractionDigits: 2 })} USDT`
                            : ''
                        }`
                      : bracketSizing?.maxSafeStopLossUsd !== null && bracketSizing?.maxSafeStopLossUsd !== undefined
                        ? `Örn: 50 → 50 USDT zararda dur · Maks. ${bracketSizing.maxSafeStopLossUsd.toLocaleString('tr-TR', { maximumFractionDigits: 2 })} USDT`
                        : 'Zarar durdur tutarı (USDT)'
                    : resolvedModalSl.usdEstimate !== null
                      ? `Tahmini zarar: ~-${resolvedModalSl.usdEstimate.toLocaleString('tr-TR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USDT`
                      : undefined
                  : undefined
              }
              rightAddon={
                <div className="inline-flex border border-[#e5e5e5]" role="group" aria-label="Modal Stop-Loss modu">
                  <button
                    type="button"
                    onClick={() => switchModalSlMode('PRICE')}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold',
                      modalSlMode === 'PRICE' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252]'
                    )}
                  >
                    Fiyat
                  </button>
                  <button
                    type="button"
                    onClick={() => switchModalSlMode('USDT')}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold',
                      modalSlMode === 'USDT' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252]'
                    )}
                  >
                    USDT ($)
                  </button>
                </div>
              }
            >
              {(id) => (
                <DecimalInput
                  id={id}
                  value={slInput}
                  hasError={Boolean(resolvedModalSl.error)}
                  onChange={setSlInput}
                  suffix={modalSlMode === 'USDT' ? 'USDT' : undefined}
                  placeholder={modalSlMode === 'USDT' ? 'Örn: 50' : 'Örn: 48500'}
                />
              )}
            </FieldGroup>

            <FieldGroup
              label="Yeni Take-Profit (Boş bırakılırsa kaldırılır)"
              error={resolvedModalTp.error}
              hint={
                !resolvedModalTp.error
                  ? modalTpMode === 'USDT'
                    ? resolvedModalTp.priceStr
                      ? `Tetik: ~${formatPrice(resolvedModalTp.priceStr)} USDT kârda kapat`
                      : 'Örn: 10 → 10 USDT kârda kapat'
                    : resolvedModalTp.usdEstimate !== null
                      ? `Tahmini kâr: ~+${resolvedModalTp.usdEstimate.toLocaleString('tr-TR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USDT`
                      : undefined
                  : undefined
              }
              rightAddon={
                <div className="inline-flex border border-[#e5e5e5]" role="group" aria-label="Modal Take-Profit modu">
                  <button
                    type="button"
                    onClick={() => switchModalTpMode('PRICE')}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold',
                      modalTpMode === 'PRICE' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252]'
                    )}
                  >
                    Fiyat
                  </button>
                  <button
                    type="button"
                    onClick={() => switchModalTpMode('USDT')}
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-semibold',
                      modalTpMode === 'USDT' ? 'bg-[#0a0a0a] text-white' : 'bg-[#f5f5f5] text-[#525252]'
                    )}
                  >
                    USDT ($)
                  </button>
                </div>
              }
            >
              {(id) => (
                <DecimalInput
                  id={id}
                  value={tpInput}
                  hasError={Boolean(resolvedModalTp.error)}
                  onChange={setTpInput}
                  suffix={modalTpMode === 'USDT' ? 'USDT' : undefined}
                  placeholder={modalTpMode === 'USDT' ? 'Örn: 10' : 'Örn: 53000'}
                />
              )}
            </FieldGroup>
          </form>
        )}
      </Modal>
    </>
  );
}

function PositionLifecycleBadge({
  status,
  closeRequested,
}: {
  status: string;
  closeRequested: boolean;
}) {
  if (status === 'LIQUIDATING') {
    return (
      <Badge tone="danger">
        <AlertTriangle className="h-3 w-3" aria-hidden="true" /> LIQUIDATING (Tasfiye Sürecinde)
      </Badge>
    );
  }
  if (status === 'EXIT_PENDING_DATA') {
    return (
      <Badge tone="wait">
        <AlertTriangle className="h-3 w-3" aria-hidden="true" /> EXIT_PENDING_DATA (Taze Mark Bekleniyor)
      </Badge>
    );
  }
  if (closeRequested) {
    return <Badge tone="accent">◌ Kapanış İsteği İletildi (202)</Badge>;
  }
  return <Badge tone="success">● AÇIK (OPEN)</Badge>;
}

/**
 * F05.5: Açık LIMIT emirlerinin iptali pozisyon kapatmadan ayrı bir tablodur.
 */
export function OpenOrdersTable({
  sessionId,
  orders,
  timezone = 'Europe/Istanbul',
}: {
  sessionId: string;
  orders: OpenOrderView[];
  timezone?: TimezonePreference;
}) {
  const queryClient = useQueryClient();

  const cancelMutation = useMutation({
    mutationFn: (order: OpenOrderView) =>
      tradingApi.cancelOrder(order.orderId, order.version, createIdempotencyKey()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.trading.openOrders(sessionId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
    },
  });

  if (orders.length === 0) {
    return (
      <EmptyState
        title="Bekleyen açık limit emir yok"
        description="Henüz eşleşmemiş LIMIT emirleriniz burada listelenir ve pozisyon kapatmadan bağımsız olarak iptal edilebilir."
      />
    );
  }

  return (
    <div className="space-y-2">
      {cancelMutation.isError && (
        <ErrorBanner error={cancelMutation.error} title="Limit emir iptal edilemedi" />
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-[#e5e5e5] text-[11px] font-medium text-[#737373]">
              <th className="px-3 py-2.5">Sözleşme / Yön</th>
              <th className="px-3 py-2.5 text-right">Miktar</th>
              <th className="px-3 py-2.5 text-right">Limit Fiyatı</th>
              <th className="px-3 py-2.5 text-right">Ayrılan Marjin</th>
              <th className="px-3 py-2.5 text-right">Oluşturulma</th>
              <th className="px-3 py-2.5 text-right">İptal</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#e5e5e5] font-mono tabular-nums">
            {orders.map((ord) => (
              <tr key={ord.orderId} className="hover:bg-[#f5f5f5]">
                <td className="px-3 py-2.5">
                  <div className="flex items-center gap-1.5">
                    <span className="font-bold text-[#0a0a0a]">{ord.symbol}</span>
                    <SideBadge side={ord.side} />
                    <Badge tone="neutral">{ord.orderType}</Badge>
                  </div>
                </td>
                <td className="px-3 py-2.5 text-right text-[#0a0a0a]">
                  {formatQuantity(ord.quantity)}
                </td>
                <td className="px-3 py-2.5 text-right font-semibold text-[#0a0a0a]">
                  {formatPrice(ord.limitPrice)}
                </td>
                <td className="px-3 py-2.5 text-right text-[#0a0a0a]">
                  {formatMoney(ord.marginReserved)} ({ord.leverage}x)
                </td>
                <td className="px-3 py-2.5 text-right text-[#737373]">
                  {formatTimestamp(ord.createdAt, timezone)}
                </td>
                <td className="px-3 py-2.5 text-right font-sans">
                  <Button
                    variant="secondary"
                    size="xs"
                    loading={cancelMutation.isPending}
                    onClick={() => cancelMutation.mutate(ord)}
                    leftIcon={<XCircle className="h-3.5 w-3.5 text-[#dc2626]" aria-hidden="true" />}
                  >
                    Limit Emri İptal Et
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

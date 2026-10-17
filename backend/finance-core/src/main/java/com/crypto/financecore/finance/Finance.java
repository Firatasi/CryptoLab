package com.crypto.financecore.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import static com.crypto.financecore.finance.Decimal.*;
import java.util.List;
import java.util.Optional;

/**
 * Yalnızca verilen girdilerle finans hesabı yapar.
 * Bakiye değiştirmez, veritabanına yazmaz, Spring başlatmaz.
 */
public final class Finance {

    private Finance() {
    }

    /**
     * Pozisyon açılışının hesap sonucudur.
     * quantity: İşlem miktarı.
     * notional: Pozisyonun parasal büyüklüğü.
     * margin: Ayrılacak başlangıç teminatı.
     * fee: Açılış komisyonu.
     */
    public record Opening(
            BigDecimal quantity,
            BigDecimal notional,
            BigDecimal margin,
            BigDecimal fee
    ) {
        public BigDecimal requiredCash() {
            // Açılışta hem teminat hem komisyon karşılanmalıdır.
            return margin.add(fee);
        }
    }

    /**
     * Funding hesabının sonucu.
     * signedFunding: Pozitifse alınan, negatifse ödenen tutar.
     * availableAfter: Funding sonrası kullanılabilir bakiye.
     * isolatedAfter: Funding sonrası pozisyona ayrılmış teminat.
     */
    public record Settlement(
            BigDecimal signedFunding,
            BigDecimal availableAfter,
            BigDecimal isolatedAfter
    ) {
    }

    /**
     * Kapanış hesabının sonucu.
     * released: Kapanan miktara düşen teminat.
     * realized: Gerçekleşen brüt kâr/zarar.
     * fee: Bu kapanışa uygulanacak komisyon.
     * rawResidual: Teminat + kâr/zarar - komisyon; negatif olabilir.
     * returnedCash: Kullanılabilir bakiyeye dönecek tutar.
     * insurance: Tam kapanışta oluşan açığı karşılayacak katkı.
     * forceFullClose: Kısmi kapanış yerine tam kapanış hesabı gerekli mi?
     */
    public record Closing(
            BigDecimal released,
            BigDecimal realized,
            BigDecimal fee,
            BigDecimal rawResidual,
            BigDecimal returnedCash,
            BigDecimal insurance,
            boolean forceFullClose
    ) {
    }

    public static BigDecimal pnl(
            PositionSide side,
            BigDecimal q,
            BigDecimal entry,
            BigDecimal exit
    ) {
        positive(q);
        positive(entry);
        positive(exit);

        // LONG: q × (exit - entry)
        // SHORT: -q × (exit - entry)
        return side.sign()
                .multiply(q, MC)
                .multiply(exit.subtract(entry, MC), MC);
    }

    public static BigDecimal fee(
            BigDecimal q,
            BigDecimal price,
            BigDecimal rate
    ) {
        positive(q);
        positive(price);
        nonNegative(rate);

        // Komisyon teminat üzerinden değil, işlem büyüklüğü üzerinden alınır.
        // Açılışta giriş fiyatı, kapanışta çıkış fiyatı gönderilir.
        return q.multiply(price, MC)
                .multiply(rate, MC);
    }

    public static BigDecimal floorStep(
            BigDecimal q,
            BigDecimal step
    ) {
        nonNegative(q);
        positive(step);

        // Miktarın kaç tam adıma sığdığını bulup tekrar adımla çarpar.
        // Örneğin q=0.209, step=0.01 -> 20 × 0.01 = 0.20.
        return q.divide(step, 0, RoundingMode.FLOOR)
                .multiply(step);
    }

    public static Opening open(
            BigDecimal marginBudget,
            BigDecimal leverage,
            BigDecimal entry,
            BigDecimal step,
            BigDecimal rate,
            BigDecimal available
    ) {
        positive(marginBudget);
        positive(entry);
        nonNegative(rate);
        nonNegative(available);

        if (leverage == null || leverage.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("INVALID_LEVERAGE");
        }

        // Hedef miktar = teminat bütçesi × kaldıraç / giriş fiyatı.
        // Miktarı yukarı yuvarlayıp bütçeyi aşmamak için floor uygularız.
        BigDecimal q = floorStep(
                marginBudget.multiply(leverage, MC).divide(entry, MC),
                step
        );

        // Bütçe bir işlem adımına bile yetmiyorsa miktar sıfır olur.
        positive(q);

        // Gerçek miktardan notional ve teminatı yeniden hesaplarız.
        BigDecimal n = q.multiply(entry, MC);

        Opening result = new Opening(
                q,
                n,
                n.divide(leverage, MC),
                fee(q, entry, rate)
        );

        // Sadece teminatı karşılamak yeterli değildir; komisyon da gerekir.
        if (result.requiredCash().compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    "INSUFFICIENT_AVAILABLE_BALANCE"
            );
        }

        return result;
    }

    public static BigDecimal allInQuantity(
            BigDecimal available,
            BigDecimal entry,
            BigDecimal leverage,
            BigDecimal rate,
            BigDecimal step
    ) {
        nonNegative(available);
        positive(entry);
        positive(leverage);
        nonNegative(rate);

        // Bir birim miktar için gereken nakit:
        // giriş fiyatı / kaldıraç + giriş fiyatı × komisyon oranı.
        BigDecimal perUnit = entry.multiply(
                BigDecimal.ONE.divide(leverage, MC).add(rate),
                MC
        );

        // Kullanılabilir nakde komisyon dahil sığan miktarı hesaplar.
        return floorStep(available.divide(perUnit, MC), step);
    }

    public static BigDecimal marketPrice(
            boolean buy,
            BigDecimal bid,
            BigDecimal ask,
            BigDecimal tick,
            BigDecimal slippageBps
    ) {
        positive(bid);
        positive(ask);
        positive(tick);
        nonNegative(slippageBps);

        // Alış teklifi satış teklifinden büyük olamaz.
        // Slippage %100 veya üzerindeyse satış fiyatı sıfır/negatif olabilir.
        if (bid.compareTo(ask) > 0
                || slippageBps.compareTo(new BigDecimal("10000")) >= 0) {
            throw new IllegalArgumentException("INVALID_QUOTE_OR_SLIPPAGE");
        }

        // 1 bps = 0.0001; 10 bps = %0.1.
        BigDecimal slip = slippageBps.divide(new BigDecimal("10000"), MC);

        // BUY, ask üzerinden; SELL, bid üzerinden hesaplanır.
        BigDecimal raw = buy
                ? ask.multiply(BigDecimal.ONE.add(slip), MC)
                : bid.multiply(BigDecimal.ONE.subtract(slip), MC);

        // BUY yukarı, SELL aşağı: sonuç tick'in tam katı olur.
        return raw.divide(
                        tick,
                        0,
                        buy ? RoundingMode.CEILING : RoundingMode.FLOOR
                )
                .multiply(tick);
    }

    public static BigDecimal maintenance(
            BigDecimal q,
            BigDecimal mark,
            List<MaintenanceTier> tiers
    ) {
        positive(q);
        positive(mark);

        // Kademe, girişteki değil güncel mark fiyatındaki büyüklükle seçilir.
        BigDecimal n = q.multiply(mark, MC);

        MaintenanceTier tier = tiers.stream()
                .filter(candidate -> candidate.contains(n))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MISSING_TIER"));

        // MM = max(0, notional × oran - düzeltme tutarı).
        return n.multiply(tier.rate(), MC)
                .subtract(tier.amount(), MC)
                .max(BigDecimal.ZERO);
    }

    public static boolean liquidates(
            PositionSide side,
            BigDecimal q,
            BigDecimal entry,
            BigDecimal mark,
            BigDecimal isolated,
            List<MaintenanceTier> tiers
    ) {
        // Pozisyon özkaynağı = isolated teminat + gerçekleşmemiş PnL.
        // Funding sonrasında isolated negatif olabilir; kaybı gizlemiyoruz.
        BigDecimal equity = isolated.add(
                pnl(side, q, entry, mark),
                MC
        );

        // Eşitlik de likidasyon koşuluna dahildir.
        return equity.compareTo(maintenance(q, mark, tiers)) <= 0;
    }

    public static Optional<BigDecimal> liquidation(
            PositionSide side,
            BigDecimal q,
            BigDecimal entry,
            BigDecimal isolated,
            List<MaintenanceTier> tiers
    ) {
        positive(q);
        positive(entry);

        for (MaintenanceTier tier : tiers) {
            BigDecimal n = q.multiply(entry, MC);

            // B + yön × q × (P - E) = q × P × rate - amount
            // eşitliğini P için çözüyoruz.
            BigDecimal numerator = side == PositionSide.LONG
                    ? n.subtract(isolated).subtract(tier.amount())
                    : n.add(isolated).add(tier.amount());

            BigDecimal denominator = q.multiply(
                    side == PositionSide.LONG
                            ? BigDecimal.ONE.subtract(tier.rate())
                            : BigDecimal.ONE.add(tier.rate()),
                    MC
            );

            BigDecimal price = numerator.divide(denominator, MC);

            // Aday pozitif olmalı ve kendi kademe aralığında kalmalı.
            if (price.signum() > 0
                    && tier.contains(q.multiply(price, MC))) {

                BigDecimal unboundedMaintenance = q.multiply(price, MC)
                        .multiply(tier.rate(), MC)
                        .subtract(tier.amount());

                // Bu formül, MM'nin sıfıra kırpılmadığı bölgede geçerlidir.
                if (unboundedMaintenance.signum() >= 0) {
                    return Optional.of(price);
                }
            }
        }

        // MM'nin sıfıra kırpıldığı bölge için ayrı çözüm:
        // B + yön × q × (P - E) = 0.
        BigDecimal zeroMm = entry.subtract(
                isolated.divide(q.multiply(side.sign()), MC)
        );

        if (zeroMm.signum() > 0
                && maintenance(q, zeroMm, tiers).signum() == 0) {
            return Optional.of(zeroMm);
        }

        // Geçerli pozitif eşik bulunamadıysa sahte bir 0 fiyatı döndürmeyiz.
        return Optional.empty();
    }

    public static Settlement funding(
            PositionSide side,
            BigDecimal boundaryQ,
            BigDecimal mark,
            BigDecimal rate,
            BigDecimal available,
            BigDecimal isolated
    ) {
        positive(boundaryQ);
        positive(mark);
        nonNegative(available);

        // rate negatif de olabilir; nonNegative kontrolü uygulanmaz.
        // Pozitif rate: LONG öder, SHORT alır.
        // Negatif rate: ödeme yönü tersine döner.
        BigDecimal signed = side.sign()
                .negate()
                .multiply(boundaryQ, MC)
                .multiply(mark, MC)
                .multiply(rate, MC);

        if (signed.signum() >= 0) {
            // Alınan funding kullanılabilir bakiyeye eklenir.
            return new Settlement(
                    signed,
                    available.add(signed),
                    isolated
            );
        }

        BigDecimal debit = signed.negate();

        // AVAILABLE_FIRST: Önce kullanılabilir bakiyeden karşıla.
        BigDecimal fromAvailable = available.min(debit);
        BigDecimal remainingDebit = debit.subtract(fromAvailable);

        // Yetmeyen kısmı isolated teminattan düş.
        // Negatif sonucu sıfıra çekmeyiz; gerçek açık korunmalıdır.
        return new Settlement(
                signed,
                available.subtract(fromAvailable),
                isolated.subtract(remainingDebit)
        );
    }

    public static Closing close(
            PositionSide side,
            BigDecimal qBefore,
            BigDecimal qClose,
            BigDecimal entry,
            BigDecimal exit,
            BigDecimal isolated,
            BigDecimal feeRate
    ) {
        positive(qBefore);
        positive(qClose);

        if (qClose.compareTo(qBefore) > 0) {
            throw new IllegalArgumentException("OVER_CLOSE");
        }

        boolean full = qClose.compareTo(qBefore) == 0;

        // Kısmi kapanışta kapanan miktarın oranı kadar teminat serbest kalır.
        // Son kapanışta bölme yapmadan teminatın tamamını kullanırız.
        // Böylece yuvarlamadan kalan küçük artıklar hesapta tutulmaz.
        BigDecimal released = full
                ? isolated
                : isolated.multiply(qClose, MC).divide(qBefore, MC);

        BigDecimal realized = pnl(side, qClose, entry, exit);
        BigDecimal closeFee = fee(qClose, exit, feeRate);

        // Negatif sonuç gerçek açığı gösterir; bu bilgiyi kaybetmiyoruz.
        BigDecimal raw = released.add(realized).subtract(closeFee);

        // Kısmi kapanış kendi payına düşen teminatla karşılanamıyorsa
        // çağıran taraf bu sonucu uygulamamalı; tam kapanışı yeniden hesaplamalı.
        boolean force = !full && raw.signum() < 0;

        return new Closing(
                released,
                realized,
                closeFee,
                raw,
                raw.max(BigDecimal.ZERO),
                full ? raw.negate().max(BigDecimal.ZERO) : BigDecimal.ZERO,
                force
        );
    }

}

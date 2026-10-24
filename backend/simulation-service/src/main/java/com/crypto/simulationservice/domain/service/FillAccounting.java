package com.crypto.simulationservice.domain.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.crypto.financecore.finance.Decimal;
import com.crypto.financecore.finance.Finance;
import com.crypto.simulationservice.domain.model.Journal;

/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir. Okuma ipucu: Aynı girdinin
 * aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class FillAccounting {

    private FillAccounting() {
    }

    public static List<Journal.Posting> opening(UUID available, UUID isolated, UUID fees, Finance.Opening open) {
        BigDecimal margin = Decimal.cash(open.margin()), fee = Decimal.cash(open.fee());
        return List.of(new Journal.Posting(available, margin.add(fee).negate()), new Journal.Posting(isolated, margin), new Journal.Posting(fees, fee));
    }

    public static List<Journal.Posting> closing(UUID available, UUID isolated, UUID pnlPool, UUID fees, UUID insurance, Finance.Closing close) {
        if (close.forceFullClose()) {
            throw new IllegalStateException("RECOMPUTE_FULL_CLOSE");
        }
        BigDecimal released = Decimal.cash(close.released()), realized = Decimal.cash(close.realized()), fee = Decimal.cash(close.fee());
        BigDecimal raw = released.add(realized).subtract(fee), support = raw.negate().max(BigDecimal.ZERO);
        // Ham kaybı saklayıp explicit sigorta karşı hesabı açıyoruz; farkı yok etmiyoruz.
        return List.of(new Journal.Posting(isolated, released.negate()), new Journal.Posting(available, raw.add(support)),
                new Journal.Posting(pnlPool, realized.negate()), new Journal.Posting(fees, fee), new Journal.Posting(insurance, support.negate()));
    }

    public static List<Journal.Posting> transfer(UUID from, UUID to, BigDecimal value) {
        BigDecimal amount = Decimal.cash(value);
        Decimal.nonNegative(amount);
        return List.of(new Journal.Posting(from, amount.negate()), new Journal.Posting(to, amount));
    }
}

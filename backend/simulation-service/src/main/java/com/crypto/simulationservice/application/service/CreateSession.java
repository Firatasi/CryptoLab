package com.crypto.simulationservice.application.service;

import com.crypto.financecore.finance.Decimal;
import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.application.repository.SessionRepository;
import com.crypto.simulationservice.domain.model.Journal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class CreateSession {

    private final SessionRepository sessions;
    private final LedgerRepository ledger;
    private final Clock clock;

    public CreateSession(
            SessionRepository sessions,
            LedgerRepository ledger,
            Clock clock
    ) {
        this.sessions = sessions;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public UUID execute(
            UUID owner,
            BigDecimal deposit,
            UUID rule,
            UUID risk
    ) {
        // Başlangıç sermayesi pozitif olmalı.
        Decimal.positive(deposit);

        BigDecimal cash = Decimal.cash(deposit);

        // Sekiz haneye yuvarlandığında sıfıra düşen tutarı reddet.
        if (cash.signum() <= 0) {
            throw new IllegalArgumentException("DEPOSIT_TOO_SMALL");
        }

        UUID sessionId = UUID.randomUUID();
        UUID availableAccountId = UUID.randomUUID();
        UUID capitalAccountId = UUID.randomUUID();

        var now = clock.instant();

        // Session kaydını oluştur.
        sessions.create(
                sessionId,
                owner,
                cash,
                rule,
                risk,
                now
        );

        // Hesapları sıfır bakiyeyle oluştur.
        sessions.createInitialAccounts(
                sessionId,
                availableAccountId,
                capitalAccountId
        );

        // Başlangıç sermayesinin dengeli finans kaydını hazırla.
        Journal initialCapital = new Journal(
                UUID.randomUUID(),
                sessionId,
                "initial:" + sessionId,
                "deposit:" + cash.toPlainString(),
                "INITIAL_CAPITAL",
                List.of(
                        new Journal.Posting(availableAccountId, cash),
                        new Journal.Posting(capitalAccountId, cash.negate())
                )
        );

        // Journal, posting ve hesap bakiyelerini birlikte kaydet.
        ledger.post(initialCapital, now, now);

        return sessionId;
    }
}
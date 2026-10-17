package com.crypto.simulationservice.infrastructure.repository;

import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.domain.model.Journal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcLedgerStore implements LedgerRepository {

    private final JdbcTemplate jdbc;

    public JdbcLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(
            Journal journal,
            Instant effectiveAt,
            Instant recordedAt
    ) {
        // Aynı iş anahtarıyla daha önce kayıt yapılmış mı?
        var existing = jdbc.query("""
                SELECT id, request_hash
                FROM journal_transactions
                WHERE business_key = ?
                """,
                (rs, rowNum) -> new String[]{
                        rs.getString("id"),
                        rs.getString("request_hash")
                },
                journal.businessKey()
        );

        if (!existing.isEmpty()) {
            String[] previous = existing.get(0);

            // Aynı anahtar farklı bir içerik için kullanılamaz.
            if (!previous[1].equals(journal.requestHash())) {
                throw new IllegalStateException("BUSINESS_KEY_CONFLICT");
            }

            // Aynı hareket zaten kaydedilmiş: bakiyeye yeniden dokunma.
            return UUID.fromString(previous[0]);
        }

        // Finans hareketinin üst kaydını oluştur.
        jdbc.update("""
                INSERT INTO journal_transactions (
                    id,
                    session_id,
                    business_key,
                    request_hash,
                    effective_at,
                    recorded_at,
                    reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                journal.id(),
                journal.sessionId(),
                journal.businessKey(),
                journal.requestHash(),
                Timestamp.from(effectiveAt),
                Timestamp.from(recordedAt),
                journal.reason()
        );

        for (Journal.Posting posting : journal.postings()) {

            // Yalnızca bu session'a ait hesabın bakiyesini değiştir.
            int changed = jdbc.update("""
                    UPDATE wallet_accounts
                    SET balance = balance + ?
                    WHERE id = ? AND session_id = ?
                    """,
                    posting.amount(),
                    posting.accountId(),
                    journal.sessionId()
            );

            if (changed != 1) {
                throw new IllegalStateException("ACCOUNT_SCOPE_MISMATCH");
            }

            // Bakiye değişikliğinin finans geçmişini kaydet.
            jdbc.update("""
                    INSERT INTO postings (
                        transaction_id,
                        account_id,
                        amount,
                        currency
                    )
                    VALUES (?, ?, ?, 'USDT')
                    """,
                    journal.id(),
                    posting.accountId(),
                    posting.amount()
            );
        }

        return journal.id();
    }
}
package com.crypto.simulationservice;

import com.crypto.simulationservice.application.repository.LedgerRepository;
import com.crypto.simulationservice.domain.model.Journal;
import com.crypto.simulationservice.application.repository.SessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import com.crypto.simulationservice.application.service.CreateSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LedgerIT {

    @Autowired
    private CreateSession createSession;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LedgerRepository ledger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SessionRepository sessions;

    @Test
    void initialCapitalBalances() {
        UUID ownerId = UUID.randomUUID();

        UUID sessionId = createSession.execute(
                ownerId,
                new BigDecimal("10000"),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        // Session doğru kullanıcıya ait mi?
        UUID storedOwner = jdbc.queryForObject("""
                SELECT owner_id
                FROM simulation_sessions
                WHERE id = ?
                """,
                UUID.class,
                sessionId
        );

        assertEquals(ownerId, storedOwner);

        // Kullanılabilir bakiye başlangıç sermayesine eşit mi?
        BigDecimal available = jdbc.queryForObject("""
                SELECT balance
                FROM wallet_accounts
                WHERE session_id = ? AND account_key = 'AVAILABLE'
                """,
                BigDecimal.class,
                sessionId
        );

        assertNotNull(available);
        assertEquals(0, new BigDecimal("10000").compareTo(available));

        // Başlangıç sermayesinin karşı kaydı oluşmuş mu?
        BigDecimal capital = jdbc.queryForObject("""
                SELECT balance
                FROM wallet_accounts
                WHERE session_id = ? AND account_key = 'INITIAL_CAPITAL'
                """,
                BigDecimal.class,
                sessionId
        );

        assertNotNull(capital);
        assertEquals(0, new BigDecimal("-10000").compareTo(capital));

        // Bir journal oluşmuş mu?
        Long journalCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM journal_transactions
                WHERE session_id = ?
                """,
                Long.class,
                sessionId
        );

        assertEquals(Long.valueOf(1), journalCount);

        // İki posting oluşmuş mu?
        Long postingCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM postings p
                JOIN journal_transactions j ON j.id = p.transaction_id
                WHERE j.session_id = ?
                """,
                Long.class,
                sessionId
        );

        assertEquals(Long.valueOf(2), postingCount);

        // Posting toplamı sıfır mı?
        BigDecimal total = jdbc.queryForObject("""
                SELECT sum(p.amount)
                FROM postings p
                JOIN journal_transactions j ON j.id = p.transaction_id
                WHERE j.session_id = ?
                """,
                BigDecimal.class,
                sessionId
        );

        assertNotNull(total);
        assertEquals(0, total.signum());

        // Ledger'den hesaplanan bakiyeler, kayıtlı bakiyelerle aynı mı?
        var mismatches = jdbc.queryForList("""
                SELECT
                    a.id,
                    a.balance,
                    coalesce(sum(p.amount), 0) AS rebuilt
                FROM wallet_accounts a
                LEFT JOIN postings p ON p.account_id = a.id
                WHERE a.session_id = ?
                GROUP BY a.id, a.balance
                HAVING a.balance <> coalesce(sum(p.amount), 0)
                """,
                sessionId
        );

        assertTrue(mismatches.isEmpty(), "Ledger ve hesap bakiyeleri uyuşmuyor");
    }

    @Test
    void duplicateJournalIsAppliedOnlyOnce() {
        UUID sessionId = createSession.execute(
                UUID.randomUUID(),
                new BigDecimal("10000"),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        UUID availableId = jdbc.queryForObject("""
            SELECT id FROM wallet_accounts
            WHERE session_id = ? AND account_key = 'AVAILABLE'
            """,
                UUID.class,
                sessionId
        );

        UUID capitalId = jdbc.queryForObject("""
            SELECT id FROM wallet_accounts
            WHERE session_id = ? AND account_key = 'INITIAL_CAPITAL'
            """,
                UUID.class,
                sessionId
        );

        String businessKey = "test-transfer:" + sessionId;
        String requestHash = "amount:100";

        Journal first = new Journal(
                UUID.randomUUID(),
                sessionId,
                businessKey,
                requestHash,
                "TEST_TRANSFER",
                List.of(
                        new Journal.Posting(availableId, new BigDecimal("-100")),
                        new Journal.Posting(capitalId, new BigDecimal("100"))
                )
        );

        // Yeni journal kimliği olsa da aynı businessKey aynı hareketi temsil eder.
        Journal repeated = new Journal(
                UUID.randomUUID(),
                sessionId,
                businessKey,
                requestHash,
                "TEST_TRANSFER",
                first.postings()
        );

        var transaction = new TransactionTemplate(transactionManager);
        var now = java.time.Instant.parse("2026-10-17T12:00:00Z");

        UUID firstResult = transaction.execute(
                status -> ledger.post(first, now, now)
        );

        UUID repeatedResult = transaction.execute(
                status -> ledger.post(repeated, now, now)
        );

        // Tekrar gönderimde ilk kaydın kimliği dönmeli.
        assertEquals(first.id(), firstResult);
        assertEquals(firstResult, repeatedResult);

        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM journal_transactions
            WHERE business_key = ?
            """,
                Long.class,
                businessKey
        );

        assertEquals(Long.valueOf(1), count);

        BigDecimal balance = jdbc.queryForObject("""
            SELECT balance FROM wallet_accounts WHERE id = ?
            """,
                BigDecimal.class,
                availableId
        );

        assertNotNull(balance);
        assertEquals(0, new BigDecimal("9900").compareTo(balance));
    }

    @Test
    void failureRollsBackSessionAccountsAndJournal() {
        UUID ownerId = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> transaction.executeWithoutResult(status -> {
                    UUID sessionId = createSession.execute(
                            ownerId,
                            new BigDecimal("10000"),
                            UUID.randomUUID(),
                            UUID.randomUUID()
                    );

                    // Hata üretmeden önce kayıtların transaction içinde oluştuğunu gör.
                    Long postingCount = jdbc.queryForObject("""
                        SELECT count(*)
                        FROM postings p
                        JOIN journal_transactions j
                            ON j.id = p.transaction_id
                        WHERE j.session_id = ?
                        """,
                            Long.class,
                            sessionId
                    );

                    assertEquals(Long.valueOf(2), postingCount);

                    throw new RuntimeException("TEST_ROLLBACK");
                })
        );

        assertEquals("TEST_ROLLBACK", failure.getMessage());

        // Transaction bittikten sonra session kaydı kalmamalı.
        Long sessionCount = jdbc.queryForObject("""
            SELECT count(*) FROM simulation_sessions WHERE owner_id = ?
            """,
                Long.class,
                ownerId
        );

        assertEquals(Long.valueOf(0), sessionCount);
    }

    @Test
    void sameBusinessKeyWithDifferentContentIsRejected() {
        UUID sessionId = createSession.execute(
                UUID.randomUUID(),
                new BigDecimal("10000"),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        UUID availableId = jdbc.queryForObject("""
            SELECT id FROM wallet_accounts
            WHERE session_id = ? AND account_key = 'AVAILABLE'
            """,
                UUID.class,
                sessionId
        );

        UUID capitalId = jdbc.queryForObject("""
            SELECT id FROM wallet_accounts
            WHERE session_id = ? AND account_key = 'INITIAL_CAPITAL'
            """,
                UUID.class,
                sessionId
        );

        String businessKey = "test-conflict:" + sessionId;

        Journal first = new Journal(
                UUID.randomUUID(),
                sessionId,
                businessKey,
                "amount:100",
                "TEST_TRANSFER",
                List.of(
                        new Journal.Posting(availableId, new BigDecimal("-100")),
                        new Journal.Posting(capitalId, new BigDecimal("100"))
                )
        );

        // Aynı anahtarla bu kez farklı bir tutar gönderiyoruz.
        Journal conflicting = new Journal(
                UUID.randomUUID(),
                sessionId,
                businessKey,
                "amount:200",
                "TEST_TRANSFER",
                List.of(
                        new Journal.Posting(availableId, new BigDecimal("-200")),
                        new Journal.Posting(capitalId, new BigDecimal("200"))
                )
        );

        var transaction = new TransactionTemplate(transactionManager);
        var now = java.time.Instant.parse("2026-10-17T12:00:00Z");

        transaction.executeWithoutResult(
                status -> ledger.post(first, now, now)
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> transaction.executeWithoutResult(
                        status -> ledger.post(conflicting, now, now)
                )
        );

        assertEquals("BUSINESS_KEY_CONFLICT", failure.getMessage());

        // Reddedilen hareket bakiyeyi değiştirmemeli.
        BigDecimal balance = jdbc.queryForObject("""
            SELECT balance FROM wallet_accounts WHERE id = ?
            """,
                BigDecimal.class,
                availableId
        );

        assertNotNull(balance);
        assertEquals(0, new BigDecimal("9900").compareTo(balance));

        // Aynı anahtar için yalnızca ilk journal bulunmalı.
        Long journalCount = jdbc.queryForObject("""
            SELECT count(*) FROM journal_transactions
            WHERE business_key = ?
            """,
                Long.class,
                businessKey
        );

        assertEquals(Long.valueOf(1), journalCount);

        // Reddedilen journal'a ait posting oluşmamalı.
        Long rejectedPostingCount = jdbc.queryForObject("""
            SELECT count(*) FROM postings
            WHERE transaction_id = ?
            """,
                Long.class,
                conflicting.id()
        );

        assertEquals(Long.valueOf(0), rejectedPostingCount);
    }

    @Test
    void concurrentSpendingCannotConsumeSameCashTwice() throws Exception {
        UUID ownerId = UUID.randomUUID();

        UUID sessionId = createSession.execute(
                ownerId,
                new BigDecimal("10000"),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        UUID availableId = jdbc.queryForObject("""
            SELECT id FROM wallet_accounts
            WHERE session_id = ? AND account_key = 'AVAILABLE'
            """,
                UUID.class,
                sessionId
        );

        UUID capitalId = jdbc.queryForObject("""
            SELECT id FROM wallet_accounts
            WHERE session_id = ? AND account_key = 'INITIAL_CAPITAL'
            """,
                UUID.class,
                sessionId
        );

        Long initialVersion = jdbc.queryForObject("""
            SELECT version FROM simulation_sessions WHERE id = ?
            """,
                Long.class,
                sessionId
        );
        assertNotNull(initialVersion);

        BigDecimal amount = new BigDecimal("7000");
        var now = java.time.Instant.parse("2026-10-17T12:00:00Z");

        // İki işlem hazır olduğunda birlikte başlatılacak.
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        Callable<Boolean> spend = () -> {
            ready.countDown();

            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("TEST_START_TIMEOUT");
            }

            // Her thread kendi transaction'ını açar.
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(10);

            return transaction.execute(status -> {
                // Hatalı kilit kullanımında test sınırsız beklemesin.
                jdbc.execute("SET LOCAL lock_timeout = '5s'");

                long version = sessions.lock(sessionId, ownerId);

                // Bakiye mutlaka session kilidi alındıktan sonra okunur.
                BigDecimal balance = jdbc.queryForObject("""
                    SELECT balance FROM wallet_accounts WHERE id = ?
                    """,
                        BigDecimal.class,
                        availableId
                );
                assertNotNull(balance);

                if (balance.compareTo(amount) < 0) {
                    return false;
                }

                Journal journal = new Journal(
                        UUID.randomUUID(),
                        sessionId,
                        "test-spend:" + UUID.randomUUID(),
                        "amount:7000",
                        "TEST_SPEND",
                        List.of(
                                new Journal.Posting(availableId, amount.negate()),
                                new Journal.Posting(capitalId, amount)
                        )
                );

                ledger.post(journal, now, now);
                sessions.incrementVersion(sessionId, ownerId, version);

                return true;
            });
        };

        try {
            var first = executor.submit(spend);
            var second = executor.submit(spend);

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "İki işlem de başlangıca hazır olmalı"
            );

            start.countDown();

            boolean firstSucceeded = first.get(15, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(15, TimeUnit.SECONDS);

            int successCount =
                    (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0);

            assertEquals(1, successCount, "Yalnızca bir harcama başarılı olmalı");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(15, TimeUnit.SECONDS),
                    "Test thread'leri kapanmalı"
            );
        }

        BigDecimal finalBalance = jdbc.queryForObject("""
            SELECT balance FROM wallet_accounts WHERE id = ?
            """,
                BigDecimal.class,
                availableId
        );

        assertNotNull(finalBalance);
        assertEquals(0, new BigDecimal("3000").compareTo(finalBalance));

        Long finalVersion = jdbc.queryForObject("""
            SELECT version FROM simulation_sessions WHERE id = ?
            """,
                Long.class,
                sessionId
        );

        assertEquals(Long.valueOf(initialVersion + 1), finalVersion);

        // Başlangıç sermayesi + başarılı tek harcama = iki journal.
        Long journalCount = jdbc.queryForObject("""
            SELECT count(*) FROM journal_transactions WHERE session_id = ?
            """,
                Long.class,
                sessionId
        );

        assertEquals(Long.valueOf(2), journalCount);

        // Bakiye snapshot'ları ledger kayıtlarıyla hâlâ aynı olmalı.
        var mismatches = jdbc.queryForList("""
            SELECT a.id
            FROM wallet_accounts a
            LEFT JOIN postings p ON p.account_id = a.id
            WHERE a.session_id = ?
            GROUP BY a.id, a.balance
            HAVING a.balance <> coalesce(sum(p.amount), 0)
            """,
                sessionId
        );

        assertTrue(mismatches.isEmpty(), "Ledger ve bakiyeler uyuşmalı");
    }
}
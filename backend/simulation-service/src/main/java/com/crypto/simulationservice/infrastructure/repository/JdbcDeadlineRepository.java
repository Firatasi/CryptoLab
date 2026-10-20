package com.crypto.simulationservice.infrastructure.repository;

import com.crypto.simulationservice.application.repository.DeadlineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular. Okuma ipucu:
 * Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcDeadlineRepository implements DeadlineRepository {

    private final JdbcTemplate db;

    public JdbcDeadlineRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void schedule(UUID position, UUID session, long generation, int horizon, String kind) {
        db.update("""
            INSERT INTO due_actions(position_id,generation,deadline_at,kind,status)
            SELECT id,?,opened_at+(? * interval '1 second'),?,'PENDING' FROM positions
            WHERE id=? AND session_id=? ON CONFLICT DO NOTHING
            """, generation, horizon, kind, position, session);
    }

    public boolean claim(UUID position, long generation, String kind, Instant now) {
        return db.update("""
            UPDATE due_actions SET status='RUNNING'
            WHERE position_id=? AND generation=? AND kind=? AND status='PENDING' AND deadline_at<=?
            """, position, generation, kind, Timestamp.from(now)) == 1;
    }
}


package com.crypto.simulationservice.infrastructure.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.crypto.simulationservice.application.repository.OrderSubmissionRepository;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOrderSubmissionRepository implements OrderSubmissionRepository {

    private final JdbcTemplate db;

    public JdbcOrderSubmissionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<UUID> findSession(UUID plan, int revision) {
        return db.queryForList("SELECT session_id FROM execution_plans WHERE plan_id=? AND revision=?",
                UUID.class, plan, revision).stream().findFirst();
    }

    public boolean lockOwnedSession(UUID session, UUID owner) {
        return !db.queryForList("SELECT id FROM simulation_sessions WHERE id=? AND owner_id=? FOR UPDATE",
                UUID.class, session, owner).isEmpty();
    }

    public Optional<Previous> previous(UUID owner, String route, UUID session, String key) {
        return db.query("""
            SELECT request_hash,result_ref FROM idempotency_records
            WHERE subject=? AND route=? AND scope=? AND key=?
            """, (rs, n) -> new Previous(rs.getString(1), UUID.fromString(rs.getString(2))),
                owner, route, session.toString(), key).stream().findFirst();
    }

    public Plan lockPlan(UUID plan, int revision) {
        return db.queryForObject("""
            SELECT hash,state,valid_until,instrument_id,position_generation
            FROM execution_plans WHERE plan_id=? AND revision=? FOR UPDATE
            """, (rs, n) -> new Plan(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getObject(4, UUID.class), rs.getLong(5)), plan, revision);
    }

    public long generation(UUID session, UUID instrument) {
        return db.queryForObject("SELECT generation FROM instrument_generations WHERE session_id=? AND instrument_id=?",
                Long.class, session, instrument);
    }

    public String sessionStatus(UUID session) {
        return db.queryForObject("SELECT status FROM simulation_sessions WHERE id=?", String.class, session);
    }

    public void accept(UUID command, UUID session, UUID owner, UUID plan, int revision,
            String hash, String route, String key, String requestHash, Instant now) {
        // PostgreSQL JSON oluşturur; string birleştirme/kaçış hatası yapılmaz.
        db.update("""
            INSERT INTO commands(id,session_id,type,payload,status,reason,created_at,updated_at)
            VALUES (?,?,'SUBMIT',jsonb_build_object('planId',?::text,'revision',?::integer,
            'planHash',?::text),'ACCEPTED',NULL,?,?)
            """, command, session, plan.toString(), revision, hash, Timestamp.from(now), Timestamp.from(now));
        db.update("INSERT INTO approvals VALUES (?,?,?,?,?,?)", UUID.randomUUID(), owner, plan, revision, hash, command);
        db.update("UPDATE execution_plans SET state='CONSUMED' WHERE plan_id=? AND revision=?", plan, revision);
        db.update("INSERT INTO idempotency_records(subject,route,scope,key,request_hash,result_ref) VALUES (?,?,?,?,?,?)",
                owner, route, session.toString(), key, requestHash, command);
        // Dört yazım ayrı commit yapmaz; dış service transaction'ının parçalarıdır.
    }

    public Instant acceptedAt(UUID command) {
        return db.queryForObject("SELECT created_at FROM commands WHERE id=?", Timestamp.class, command).toInstant();
    }
}

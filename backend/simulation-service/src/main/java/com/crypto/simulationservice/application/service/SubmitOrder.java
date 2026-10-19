package com.crypto.simulationservice.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crypto.simulationservice.application.command.SubmitOrderCommand;
import com.crypto.simulationservice.application.repository.OrderSubmissionRepository;
import com.crypto.simulationservice.domain.exception.BusinessException;

@Service
public class SubmitOrder {

    public record Accepted(UUID commandId, String status, String statusUrl, Instant acceptedAt) {}
    private final OrderSubmissionRepository orders;
    private final Clock clock;

    public SubmitOrder(OrderSubmissionRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional
    public Accepted execute(UUID owner, String key, SubmitOrderCommand request) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("INVALID_KEY");
        }
        // Bu ilk okuma henüz kilit değildir. Yetki kontrolü kilit sorgusunda tekrar yapılır.
        UUID session = orders.findSession(request.planId(), request.revision())
                .orElseThrow(() -> new BusinessException(BusinessException.Code.NOT_FOUND));
        if (!orders.lockOwnedSession(session, owner)) {
            throw new BusinessException(BusinessException.Code.NOT_FOUND);
        }
        String normalized = request.planId() + ":" + request.revision() + ":" + request.planHash();
        String route = "POST /api/v1/sim/orders";
        var previous = orders.previous(owner, route, session, key);
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(normalized)) {
                throw new BusinessException(BusinessException.Code.IDEMPOTENCY_CONFLICT);
            }
            return accepted(previous.get().commandId());
        }
        var plan = orders.lockPlan(request.planId(), request.revision());
        if (!request.planHash().equals(plan.hash())) {
            throw new BusinessException(BusinessException.Code.REVALIDATION_REQUIRED);
        }
        if (!"PREVIEWED".equals(plan.state())) {
            throw new BusinessException(BusinessException.Code.PLAN_REVOKED);
        }
        if (!clock.instant().isBefore(plan.validUntil())) {
            throw new BusinessException(BusinessException.Code.PLAN_EXPIRED);
        }
        if (orders.generation(session, plan.instrumentId()) != plan.generation()) {
            throw new BusinessException(BusinessException.Code.PLAN_REVOKED);
        }
        if (!"RUNNING".equals(orders.sessionStatus(session))) {
            throw new BusinessException(BusinessException.Code.SESSION_HALTED);
        }
        // Kalan entegrasyon: güncel yerel snapshot/risk preflight burada bağlanır.
        // Bu bölüm tamamlanmadan tam emir kabul hattı DONE değildir; HTTP/AI çağrısı lock içinde yapılmaz.
        UUID command = UUID.randomUUID();
        orders.accept(command, session, owner, request.planId(), request.revision(), request.planHash(),
                route, key, normalized, clock.instant());
        return accepted(command);
    }

    private Accepted accepted(UUID command) {
        return new Accepted(command, "ACCEPTED", "/api/v1/sim/commands/" + command, orders.acceptedAt(command));
    }
}

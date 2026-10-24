package com.crypto.simulationservice;

import com.crypto.simulationservice.application.repository.SessionRepository;
import com.crypto.simulationservice.application.service.CreateSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class OwnerIsolationIT {

    @Autowired
    private CreateSession createSession;

    @Autowired
    private SessionRepository sessions;

    @Test
    void shouldAllowOwnerToLockAndUpdateSession() {
        UUID owner = UUID.randomUUID();
        UUID session = createSessionFor(owner);

        long version = sessions.lock(session, owner);

        sessions.incrementVersion(session, owner, version);

        assertEquals(
                version + 1,
                sessions.lock(session, owner)
        );
    }

    @Test
    void shouldRejectAnotherUserWhenLockingSession() {
        UUID owner = UUID.randomUUID();
        UUID anotherUser = UUID.randomUUID();
        UUID session = createSessionFor(owner);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> sessions.lock(session, anotherUser)
        );

        assertEquals("SESSION_NOT_FOUND", exception.getMessage());

        // Gerçek sahibin erişimi devam etmeli.
        long version = sessions.lock(session, owner);

        sessions.incrementVersion(session, owner, version);

        assertEquals(
                version + 1,
                sessions.lock(session, owner)
        );
    }

    @Test
    void shouldRejectAnotherUserWithoutChangingVersion() {
        UUID owner = UUID.randomUUID();
        UUID anotherUser = UUID.randomUUID();
        UUID session = createSessionFor(owner);

        long versionBefore = sessions.lock(session, owner);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> sessions.incrementVersion(
                        session,
                        anotherUser,
                        versionBefore
                )
        );

        assertEquals(
                "SESSION_VERSION_CONFLICT",
                exception.getMessage()
        );

        assertEquals(
                versionBefore,
                sessions.lock(session, owner)
        );
    }

    private UUID createSessionFor(UUID owner) {
        return createSession.execute(
                owner,
                new BigDecimal("10000.00000000"),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }
}
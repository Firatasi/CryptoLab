package com.crypto.simulationservice.infrastructure.client;

import com.crypto.simulationservice.application.port.ProposalReader;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FixtureProposalReader implements ProposalReader {

    private record Key(UUID proposalId, int revision) {
    }

    private final Map<Key, ProposalSnapshot> proposals = new ConcurrentHashMap<>();

    public void register(ProposalSnapshot snapshot) {
        proposals.put(new Key(snapshot.proposalId(), snapshot.revision()), snapshot);
    }

    public void clear() {
        proposals.clear();
    }

    @Override
    public Optional<ProposalSnapshot> findProposal(
            UUID ownerId,
            UUID sessionId,
            UUID proposalId,
            int revision
    ) {
        ProposalSnapshot found = proposals.get(new Key(proposalId, revision));
        if (found == null) {
            return Optional.empty();
        }
        if (!found.ownerId().equals(ownerId) || !found.sessionId().equals(sessionId)) {
            return Optional.empty();
        }
        return Optional.of(found);
    }
}


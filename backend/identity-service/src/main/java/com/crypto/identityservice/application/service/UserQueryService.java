package com.crypto.identityservice.application.service;

import com.crypto.identityservice.application.repository.IdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class UserQueryService {

    private final IdentityRepository users;
    private final Clock clock;

    public UserQueryService(IdentityRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public IdentityRepository.User find(UUID subject) {
        return users.user(subject);
    }

    @Transactional
    public IdentityRepository.User findAndTouch(UUID subject) {
        users.touchLastSeen(subject, clock.instant());
        return users.user(subject);
    }
}
package com.crypto.identityservice.application.service;

import com.crypto.identityservice.application.repository.IdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserQueryService {

    private final IdentityRepository users;

    public UserQueryService(IdentityRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public IdentityRepository.User find(UUID subject) {
        return users.user(subject);
    }
}
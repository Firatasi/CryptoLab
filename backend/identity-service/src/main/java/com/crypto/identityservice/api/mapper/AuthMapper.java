package com.crypto.identityservice.api.mapper;

import com.crypto.identityservice.api.dto.request.RegisterRequest;
import com.crypto.identityservice.api.dto.response.UserResponse;
import com.crypto.identityservice.application.command.RegisterCommand;
import com.crypto.identityservice.application.repository.IdentityRepository;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    public RegisterCommand toCommand(RegisterRequest request) {
        return new RegisterCommand(
                request.email(),
                request.displayName(),
                request.password()
        );
    }

    public UserResponse toResponse(IdentityRepository.User user) {
        return new UserResponse(
                user.id(),
                user.displayName(),
                user.roles()
        );
    }
}
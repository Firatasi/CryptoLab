package com.crypto.identityservice.application.port;

import java.util.UUID;

public interface TokenIssuer {

    String issue(UUID user);
}
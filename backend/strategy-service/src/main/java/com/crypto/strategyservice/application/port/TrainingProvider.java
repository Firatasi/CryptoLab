package com.crypto.strategyservice.application.port;

import java.io.InputStream;
import java.util.UUID;

/**
 * Sorumluluk: Dış teknik yeteneği soyutlar.
 * Okuma ipucu: Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.
 */
public interface TrainingProvider {

    record Actor(UUID id, String roles, String authorities) {
    }

    record Reply(int status, String contentType, byte[] body) {
    }

    Reply json(Actor actor, String method, String relativePath, String idempotencyKey, byte[] body);

    Reply upload(
            Actor actor,
            String idempotencyKey,
            String title,
            String author,
            String rights,
            String filename,
            long length,
            InputStream content
    );
    // Internal sağlayıcı actor scope'u yeniden doğrular. Gateway'den gelen actor header kullanılmaz.
}


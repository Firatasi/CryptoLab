package com.crypto.simulationservice.infrastructure.artifact;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class ArtifactReader {

    private final Path root;

    public ArtifactReader(Path root) throws Exception {
        this.root = root.toRealPath();
    }

    public Path verified(String relative, String expectedHash, long maxBytes) throws Exception {
        // Artifact ID -> relative path eşlemesi yetkili DB'den gelir; kullanıcı path gönderemez.
        Path file = root.resolve(relative).normalize().toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > maxBytes) {
            throw new IllegalArgumentException("INVALID_ARTIFACT");
        }
        var digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("ARTIFACT_TOO_LARGE");
                }
                digest.update(buf, 0, n);
            }
        }
        String actual = "sha256:" + HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedHash)) {
            throw new IllegalArgumentException("ARTIFACT_HASH_MISMATCH");
        }
        return file;
    }
}


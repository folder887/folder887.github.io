package ru.paycore.gateway.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes PAN using SHA-256 with a server-side pepper (stored in Vault).
 *
 * INVARIANT: PAN is hashed immediately at entry point and NEVER persisted.
 * Pepper must be rotated periodically — requires re-hashing all stored hashes.
 */
@Slf4j
@Component
public class PanHasher {

    private final byte[] pepper;

    public PanHasher(@Value("${paycore.gateway.pan-pepper}") String pepperHex) {
        this.pepper = HexFormat.of().parseHex(pepperHex);
        if (pepper.length < 16) {
            throw new IllegalStateException("PAN pepper must be at least 16 bytes");
        }
    }

    /**
     * Returns lowercase hex SHA-256(pan_bytes || pepper_bytes).
     */
    public String hash(String pan) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(pan.getBytes(StandardCharsets.UTF_8));
            md.update(pepper);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in any JVM — this cannot happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

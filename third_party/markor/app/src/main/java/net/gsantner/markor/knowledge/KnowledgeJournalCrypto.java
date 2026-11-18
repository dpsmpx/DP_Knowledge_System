/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Portable AES-256-GCM journal envelope with password-derived key.
 * Uses JCA standard primitives, never stores a password or encryption key.
 *
 * Format: 8-byte DPKSJ001 header, 16-byte salt, 12-byte nonce, GCM ciphertext
 * with 128-bit tag; header is authenticated additional data.
 *
 * PBKDF2-HMAC-SHA1 is supported at Android API 24. The 600k work factor is
 * fixed by format version; future format versions may use stronger KDFs.
 */
public final class KnowledgeJournalCrypto {
    private static final byte[] HEADER = new byte[]{
            'D', 'P', 'K', 'S', 'J', '0', '0', '1'
    };
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int ITERATIONS = 600_000;
    public static final int MAX_JOURNAL_BYTES = 8_000_000;
    private static final int MAX_CIPHERTEXT_BYTES = MAX_JOURNAL_BYTES + 128;

    private KnowledgeJournalCrypto() {
    }

    public static byte[] encrypt(final byte[] plaintext, final char[] password)
            throws GeneralSecurityException {
        if (plaintext == null || plaintext.length < 1 || plaintext.length > MAX_JOURNAL_BYTES) {
            throw new GeneralSecurityException("Invalid journal size");
        }
        if (password == null || password.length < 12) {
            throw new GeneralSecurityException("Passphrase is too short");
        }
        final SecureRandom random = new SecureRandom();
        final byte[] salt = new byte[SALT_BYTES];
        final byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        final SecretKeySpec key = derive(password, salt);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(HEADER);
        final byte[] encrypted = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(HEADER.length + SALT_BYTES + NONCE_BYTES + encrypted.length)
                .put(HEADER).put(salt).put(nonce).put(encrypted).array();
    }

    public static byte[] decrypt(final byte[] envelope, final char[] password)
            throws GeneralSecurityException {
        if (envelope == null || envelope.length < HEADER.length + SALT_BYTES
                + NONCE_BYTES + TAG_BITS / 8 || envelope.length > MAX_CIPHERTEXT_BYTES) {
            throw new GeneralSecurityException("Invalid encrypted backup size");
        }
        final ByteBuffer source = ByteBuffer.wrap(envelope);
        final byte[] header = new byte[HEADER.length];
        source.get(header);
        if (!Arrays.equals(header, HEADER)) {
            throw new GeneralSecurityException("Unsupported encrypted backup format");
        }
        final byte[] salt = new byte[SALT_BYTES];
        final byte[] nonce = new byte[NONCE_BYTES];
        source.get(salt);
        source.get(nonce);
        final byte[] ciphertext = new byte[source.remaining()];
        source.get(ciphertext);

        final SecretKeySpec key = derive(password, salt);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(HEADER);
        final byte[] plain = cipher.doFinal(ciphertext);
        if (plain.length > MAX_JOURNAL_BYTES) {
            throw new GeneralSecurityException("Decrypted journal is too large");
        }
        return plain;
    }

    private static SecretKeySpec derive(final char[] password, final byte[] salt)
            throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new GeneralSecurityException("Missing passphrase");
        }
        final PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            final byte[] bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(bits, "AES");
            } finally {
                Arrays.fill(bits, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }
}

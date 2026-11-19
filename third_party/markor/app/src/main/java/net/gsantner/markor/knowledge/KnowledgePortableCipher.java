/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * DPKSB002: password-derived, chunked authenticated encryption of portable ZIPs.
 * Header: 8-byte magic, 4-byte PBKDF2 iterations, 16-byte salt, 12-byte base
 * nonce. Records: 4-byte plaintext length, ciphertext, 16-byte GCM tag.
 * A zero-length authenticated final record is mandatory. Every record's nonce
 * and AAD bind its monotonic sequence, size and the complete header. This keeps
 * encryption AND decryption memory-bounded for large vaults; a ZIP can never be
 * returned to a restore caller until the final record verifies.
 */
final class KnowledgePortableCipher {
    private static final byte[] MAGIC = {'D', 'P', 'K', 'S', 'B', '0', '0', '2'};
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int HEADER_BYTES = MAGIC.length + 4 + SALT_BYTES + NONCE_BYTES;
    private static final int ITERATIONS = 900_000;
    private static final int KEY_BITS = 256;
    private static final int TAG_BYTES = 16;
    private static final int CHUNK_BYTES = 1024 * 1024;

    private KnowledgePortableCipher() { }

    static OutputStream encrypt(final OutputStream output, final char[] password)
            throws Exception {
        validatePassword(password);
        final byte[] salt = new byte[SALT_BYTES];
        final byte[] nonce = new byte[NONCE_BYTES];
        final SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        final byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC)
                .putInt(ITERATIONS).put(salt).put(nonce).array();
        final SecretKeySpec key = key(password, salt, ITERATIONS);
        output.write(header);
        return new OutputStream() {
            private final byte[] buf = new byte[CHUNK_BYTES];
            private int filled;
            private long sequence;
            private boolean closed;

            @Override public void write(final int b) throws IOException {
                if (closed) throw new IOException("Portable backup already closed");
                buf[filled++] = (byte) b;
                if (filled == buf.length) flushChunk();
            }

            @Override public void write(final byte[] bytes, int offset, int length)
                    throws IOException {
                if (closed) throw new IOException("Portable backup already closed");
                if (bytes == null || offset < 0 || length < 0
                        || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                while (length > 0) {
                    final int n = Math.min(length, buf.length - filled);
                    System.arraycopy(bytes, offset, buf, filled, n);
                    filled += n;
                    offset += n;
                    length -= n;
                    if (filled == buf.length) flushChunk();
                }
            }

            private void flushChunk() throws IOException {
                if (filled == 0) return;
                writeRecord(output, key, header, nonce, sequence++, buf, filled);
                filled = 0;
            }

            @Override public void flush() throws IOException { output.flush(); }

            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    flushChunk();
                    writeRecord(output, key, header, nonce, sequence,
                            new byte[0], 0);
                } finally {
                    Arrays.fill(buf, (byte) 0);
                    output.close();
                }
            }
        };
    }

    static File decryptToVerifiedStaging(final InputStream encrypted, final File privateDir,
                                         final char[] password,
                                         final KnowledgePortableArchive.Cancellation cancelled)
            throws Exception {
        validatePassword(password);
        final byte[] header = new byte[HEADER_BYTES];
        readFully(encrypted, header);
        final ByteBuffer encoded = ByteBuffer.wrap(header);
        final byte[] magic = new byte[MAGIC.length];
        encoded.get(magic);
        final int iterations = encoded.getInt();
        if (!Arrays.equals(magic, MAGIC) || iterations != ITERATIONS)
            throw new GeneralSecurityException("Unsupported backup version or parameters");
        final byte[] salt = new byte[SALT_BYTES];
        final byte[] nonce = new byte[NONCE_BYTES];
        encoded.get(salt);
        encoded.get(nonce);
        final SecretKeySpec key = key(password, salt, iterations);
        if (privateDir == null || !privateDir.isDirectory())
            throw new IOException("Private staging directory unavailable");
        final File staging = File.createTempFile(".dpks-verified-", ".zip", privateDir);
        boolean verified = false;
        try (FileOutputStream output = new FileOutputStream(staging)) {
            long sequence = 0;
            long total = 0;
            final byte[] sizeField = new byte[4];
            while (true) {
                if (cancelled.cancelled()) throw new IOException("Backup read cancelled");
                readFully(encrypted, sizeField);
                final int length = ByteBuffer.wrap(sizeField).getInt();
                if (length < 0 || length > CHUNK_BYTES || sequence > 20_000)
                    throw new IOException("Invalid encrypted block size/count");
                if (total > KnowledgePortableArchive.MAX_ARCHIVE_BYTES - length)
                    throw new IOException("Portable backup exceeds capacity");
                final byte[] payload = new byte[length + TAG_BYTES];
                readFully(encrypted, payload);
                final Cipher cipher = initialized(Cipher.DECRYPT_MODE, key, header,
                        nonce, sequence++, length);
                final byte[] plain = cipher.doFinal(payload);
                if (plain.length != length) throw new GeneralSecurityException("Block size changed");
                if (length == 0) {
                    if (encrypted.read() != -1) throw new IOException("Trailing backup data");
                    output.getFD().sync();
                    verified = true;
                    break;
                }
                output.write(plain);
                total += length;
            }
        } finally {
            if (!verified && staging.exists() && !staging.delete()) staging.deleteOnExit();
        }
        return staging;
    }

    private static void writeRecord(final OutputStream out, final SecretKeySpec key,
                                    final byte[] header, final byte[] nonce, final long sequence,
                                    final byte[] bytes, final int length) throws IOException {
        try {
            final Cipher cipher = initialized(Cipher.ENCRYPT_MODE, key, header,
                    nonce, sequence, length);
            out.write(ByteBuffer.allocate(4).putInt(length).array());
            out.write(cipher.doFinal(bytes, 0, length));
        } catch (GeneralSecurityException failed) {
            throw new IOException("Cannot encrypt portable backup", failed);
        }
    }

    private static Cipher initialized(final int mode, final SecretKeySpec key,
                                      final byte[] header, final byte[] baseNonce,
                                      final long sequence, final int length)
            throws GeneralSecurityException {
        if (sequence < 0 || sequence > 0xffff_ffffL)
            throw new GeneralSecurityException("Backup sequence exhausted");
        final byte[] nonce = baseNonce.clone();
        for (int i = 0; i < 4; i++) nonce[11 - i] ^= (byte) (sequence >>> (i * 8));
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(ByteBuffer.allocate(12).putLong(sequence).putInt(length).array());
        return cipher;
    }

    private static void readFully(final InputStream source, final byte[] target)
            throws IOException {
        int offset = 0;
        while (offset < target.length) {
            final int n = source.read(target, offset, target.length - offset);
            if (n < 0) throw new IOException("Truncated encrypted backup");
            if (n == 0) continue;
            offset += n;
        }
    }

    private static void validatePassword(final char[] password) throws GeneralSecurityException {
        if (password == null || password.length < 12 || password.length > 512)
            throw new GeneralSecurityException("Portable backup passphrase length invalid");
    }

    private static SecretKeySpec key(final char[] password, final byte[] salt,
                                     final int iterations) throws GeneralSecurityException {
        // PBKDF2/HMAC-SHA1 is supported on Android API 24. The format is
        // versioned so an Argon2id KDF can be introduced without ambiguity.
        final PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            final byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).getEncoded();
            try { return new SecretKeySpec(raw, "AES"); }
            finally { Arrays.fill(raw, (byte) 0); }
        } finally { spec.clearPassword(); }
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Exact-length, SHA-256-checked FD transfer. Never accepts a model file path. */
final class KnowledgeOnnxSessionPipe {
    private KnowledgeOnnxSessionPipe() { }

    static byte[] readVerified(final InputStream stream, final int length,
                               final String expectedSha256) throws IOException {
        if (stream == null || length < 16 || length > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES
                || !validSha256(expectedSha256)) {
            throw new IOException("Invalid isolated ONNX pipe metadata");
        }
        final byte[] received = new byte[length];
        boolean accepted = false;
        try {
            final MessageDigest sha = digest();
            int position = 0;
            while (position < length) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Isolated ONNX stream cancelled");
                }
                final int read = stream.read(received, position, length - position);
                if (read < 0) throw new IOException("Isolated ONNX stream truncated");
                if (read == 0) continue;
                sha.update(received, position, read);
                position += read;
            }
            if (stream.read() != -1 || !MessageDigest.isEqual(sha.digest(), unhex(expectedSha256))) {
                throw new IOException("Isolated ONNX stream integrity mismatch");
            }
            accepted = true;
            return received;
        } finally {
            if (!accepted) Arrays.fill(received, (byte) 0);
        }
    }

    static boolean validSha256(final String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] unhex(final String value) {
        final byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((Character.digit(value.charAt(2 * i), 16) << 4)
                    | Character.digit(value.charAt(2 * i + 1), 16));
        }
        return bytes;
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Bounded-memory transport preparation for large ONNX file descriptors.
 *
 * This is NOT an ONNX loader or an authorization to run a model. In particular,
 * copyToQuarantine streams bytes before their final hash is known: the receiver
 * must quarantine/discard the partial output on failure, then independently call
 * verifyExactly on its own FD before any subsequent use. No path is accepted.
 */
final class KnowledgeE5LargeFdTransfer {
    static final int CHUNK_BYTES = 64 * 1024;
    // First-slice diagnostic envelope, not a model import/download limit.
    static final long MAX_TRANSFER_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_EMPTY_READS = 8;

    private KnowledgeE5LargeFdTransfer() { }

    /** Check the exact byte count and SHA-256 without retaining the ONNX bytes. */
    static void verifyExactly(final InputStream source, final long length,
                              final String expectedSha256) throws IOException {
        transfer(source, null, length, expectedSha256);
    }

    /**
     * Send a bounded-memory copy to an UNTRUSTED destination. The caller owns
     * both streams and must discard/close the destination if this method fails.
     * A successful send does not replace the receiver's own verification.
     */
    static void copyToQuarantine(final InputStream source, final OutputStream destination,
                                 final long length, final String expectedSha256)
            throws IOException {
        if (destination == null) throw new IOException("Missing quarantine destination");
        transfer(source, destination, length, expectedSha256);
    }

    private static void transfer(final InputStream source, final OutputStream destination,
                                 final long length, final String expectedSha256)
            throws IOException {
        if (source == null || length < 16 || length > MAX_TRANSFER_BYTES
                || !KnowledgeOnnxSessionPipe.validSha256(expectedSha256)) {
            throw new IOException("Invalid large ONNX FD envelope");
        }
        final byte[] chunk = new byte[CHUNK_BYTES];
        final byte[] expected = decodeSha256(expectedSha256);
        try {
            final MessageDigest sha = sha256();
            long remaining = length;
            int emptyReads = 0;
            while (remaining != 0L) {
                checkCancelled();
                final int count = source.read(chunk, 0, (int) Math.min(remaining, chunk.length));
                checkCancelled();
                if (count < 0) throw new IOException("Large ONNX FD truncated");
                if (count == 0) {
                    if (++emptyReads > MAX_EMPTY_READS) {
                        throw new IOException("Large ONNX FD stalled");
                    }
                    continue;
                }
                emptyReads = 0;
                sha.update(chunk, 0, count);
                if (destination != null) destination.write(chunk, 0, count);
                remaining -= count;
            }
            checkCancelled();
            if (source.read() != -1) throw new IOException("Large ONNX FD has extra bytes");
            checkCancelled();
            if (!MessageDigest.isEqual(sha.digest(), expected)) {
                throw new IOException("Large ONNX FD SHA-256 mismatch");
            }
            if (destination != null) destination.flush();
        } finally {
            Arrays.fill(chunk, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Large ONNX FD transfer cancelled");
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] decodeSha256(final String hex) {
        final byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return result;
    }
}

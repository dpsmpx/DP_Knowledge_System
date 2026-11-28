/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Quarantined direct-memory copy of a length/SHA-pinned ONNX FD stream.
 * It does NOT open an ORT session. The caller must close any native session
 * using the view BEFORE closing the Verified buffer to zero its backing memory.
 * No model path, temporary file, note data or heap-sized model array is used.
 */
final class KnowledgeE5DirectModelBuffer {
    // Explicit native-memory diagnostic ceiling, NOT a model-import limit.
    // Both known E5 Small variants fit; larger models need another strategy.
    static final long MAX_DIRECT_BYTES = 512L * 1024L * 1024L;

    static final class Verified implements AutoCloseable {
        private ByteBuffer direct;

        private Verified(final ByteBuffer content) { direct = content; }

        /** A read-only direct view, with independent position and limit. */
        ByteBuffer view() {
            if (direct == null) throw new IllegalStateException("E5 buffer already released");
            return direct.asReadOnlyBuffer();
        }

        @Override public void close() {
            final ByteBuffer buffer = direct;
            direct = null;
            if (buffer != null) wipe(buffer);
        }
    }

    private KnowledgeE5DirectModelBuffer() { }

    /**
     * Strict exact length/EOF/SHA before returning a direct buffer. An error,
     * cancellation or partial stream wipes its allocation before propagation.
     * The owning isolated worker must separately deny unsafe RAM conditions
     * BEFORE calling this method. The announced SHA alone is not provenance.
     */
    static Verified readVerified(final InputStream source, final long bytes,
                                 final String expectedSha256) throws IOException {
        if (source == null || bytes < 16 || bytes > MAX_DIRECT_BYTES
                || !KnowledgeOnnxSessionPipe.validSha256(expectedSha256)) {
            throw new IOException("Invalid E5 direct-buffer FD envelope");
        }
        final ByteBuffer direct = ByteBuffer.allocateDirect((int) bytes);
        boolean verified = false;
        try {
            KnowledgeE5LargeFdTransfer.copyToQuarantine(source, new OutputStream() {
                @Override public void write(final int value) {
                    direct.put((byte) value);
                }

                @Override public void write(final byte[] data, final int offset,
                                            final int length) {
                    direct.put(data, offset, length);
                }
            }, bytes, expectedSha256);
            direct.flip();
            if (!direct.isDirect() || direct.remaining() != bytes) {
                throw new IOException("E5 direct-buffer copy incomplete");
            }
            final Verified result = new Verified(direct);
            verified = true;
            return result;
        } finally {
            if (!verified) wipe(direct);
        }
    }

    /** Zero every byte of the direct allocation, even if a view has moved. */
    private static void wipe(final ByteBuffer allocation) {
        final ByteBuffer writable = allocation.duplicate();
        writable.clear();
        final byte[] zeroes = new byte[64 * 1024];
        while (writable.hasRemaining()) {
            writable.put(zeroes, 0, Math.min(writable.remaining(), zeroes.length));
        }
    }
}

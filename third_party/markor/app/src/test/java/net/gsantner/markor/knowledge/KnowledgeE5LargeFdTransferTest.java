/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Pure-JVM checks: no files, native ORT, models, network or user notes. */
public final class KnowledgeE5LargeFdTransferTest {
    private static final long LARGE_BYTES = 17L * 1024L * 1024L + 73L;

    private interface CheckedCall {
        void run() throws IOException;
    }

    private static void rejects(final CheckedCall call) throws IOException {
        try {
            call.run();
            fail("Expected rejected FD transfer");
        } catch (IOException expected) { }
    }

    private static byte valueAt(final long offset) {
        return (byte) (offset * 31L + 17L);
    }

    /** Generates a deterministic model-sized stream without a model-sized array. */
    private static final class PatternStream extends InputStream {
        private final long length;
        private long position;

        PatternStream(final long length) {
            this.length = length;
        }

        @Override public int read() {
            if (position == length) return -1;
            return valueAt(position++) & 0xff;
        }

        @Override public int read(final byte[] destination, final int offset, final int count) {
            if (position == length) return -1;
            final int copied = (int) Math.min(count, length - position);
            for (int i = 0; i < copied; i++) destination[offset + i] = valueAt(position + i);
            position += copied;
            return copied;
        }
    }

    private static String hash(final InputStream input) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] chunk = new byte[KnowledgeE5LargeFdTransfer.CHUNK_BYTES];
        int count;
        while ((count = input.read(chunk)) != -1) digest.update(chunk, 0, count);
        final StringBuilder hex = new StringBuilder(64);
        for (final byte b : digest.digest()) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    @Test public void streamsMoreThanLegacyLimitWithoutModelSizedArray() throws Exception {
        assertTrue(LARGE_BYTES > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES);
        assertEquals(64 * 1024, KnowledgeE5LargeFdTransfer.CHUNK_BYTES);
        final String expected = hash(new PatternStream(LARGE_BYTES));
        KnowledgeE5LargeFdTransfer.verifyExactly(new PatternStream(LARGE_BYTES),
                LARGE_BYTES, expected);

        final long[] copied = {0};
        final MessageDigest sinkHash = MessageDigest.getInstance("SHA-256");
        final OutputStream quarantine = new OutputStream() {
            @Override public void write(final int b) {
                copied[0]++;
                sinkHash.update((byte) b);
            }

            @Override public void write(final byte[] b, final int off, final int len) {
                copied[0] += len;
                sinkHash.update(b, off, len);
            }
        };
        KnowledgeE5LargeFdTransfer.copyToQuarantine(new PatternStream(LARGE_BYTES),
                quarantine, LARGE_BYTES, expected);
        assertEquals(LARGE_BYTES, copied[0]);
        final StringBuilder actual = new StringBuilder(64);
        for (final byte b : sinkHash.digest()) actual.append(String.format("%02x", b & 0xff));
        assertEquals(expected, actual.toString());
    }

    @Test public void smallQuarantinedCopyCanBeIndependentlyVerified() throws Exception {
        final byte[] source = new byte[129];
        for (int i = 0; i < source.length; i++) source[i] = valueAt(i);
        final String expected = hash(new ByteArrayInputStream(source));
        final ByteArrayOutputStream quarantine = new ByteArrayOutputStream();
        KnowledgeE5LargeFdTransfer.copyToQuarantine(new ByteArrayInputStream(source),
                quarantine, source.length, expected);
        assertArrayEquals(source, quarantine.toByteArray());
        KnowledgeE5LargeFdTransfer.verifyExactly(
                new ByteArrayInputStream(quarantine.toByteArray()), source.length, expected);
    }

    @Test public void rejectsTruncationExtensionAndChangedBytes() throws Exception {
        final byte[] source = new byte[64];
        Arrays.fill(source, (byte) 9);
        final String expected = hash(new ByteArrayInputStream(source));
        for (final byte[] incorrect : new byte[][]{
                Arrays.copyOf(source, source.length - 1),
                Arrays.copyOf(source, source.length + 1),
                new byte[source.length]}) {
            rejects(() -> KnowledgeE5LargeFdTransfer.verifyExactly(
                    new ByteArrayInputStream(incorrect), source.length, expected));
        }
        final ByteArrayOutputStream quarantine = new ByteArrayOutputStream();
        rejects(() -> KnowledgeE5LargeFdTransfer.copyToQuarantine(
                new ByteArrayInputStream(new byte[source.length]), quarantine,
                source.length, expected));
        assertEquals(source.length, quarantine.size()); // Must be discarded by caller.
    }

    @Test public void rejectsUntrustedEnvelopeBeforeReading() throws Exception {
        final String expected = hash(new PatternStream(16));
        for (final long length : new long[]{0, 15,
                KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES + 1}) {
            rejects(() -> KnowledgeE5LargeFdTransfer.verifyExactly(
                    new PatternStream(16), length, expected));
        }
        rejects(() -> KnowledgeE5LargeFdTransfer.verifyExactly(
                new PatternStream(16), 16, expected.toUpperCase()));
        rejects(() -> KnowledgeE5LargeFdTransfer.verifyExactly(null, 16, expected));
        rejects(() -> KnowledgeE5LargeFdTransfer.copyToQuarantine(
                new PatternStream(16), null, 16, expected));
    }

    @Test public void rejectsPermanentlyEmptyReads() throws Exception {
        final InputStream stalled = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(final byte[] b, final int off, final int len) { return 0; }
        };
        final String expected = hash(new PatternStream(16));
        rejects(() -> KnowledgeE5LargeFdTransfer.verifyExactly(stalled, 16, expected));
    }

    @Test public void interruptedTransferStopsBeforeReading() throws Exception {
        final String expected = hash(new PatternStream(16));
        Thread.currentThread().interrupt();
        try {
            try {
                KnowledgeE5LargeFdTransfer.verifyExactly(new PatternStream(16), 16, expected);
                fail("Interrupted transfer must be rejected");
            } catch (InterruptedIOException expectedCancellation) {
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted(); // Do not leave JUnit's thread interrupted.
        }
    }
}

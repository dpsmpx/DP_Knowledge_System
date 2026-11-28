/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class KnowledgeE5DirectModelBufferTest {
    private static byte[] fixture() {
        final byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 17 + 3);
        return bytes;
    }

    @Test public void verifiedBufferIsDirectReadOnlyAndWipedOnClose() throws Exception {
        final byte[] bytes = fixture();
        final KnowledgeE5DirectModelBuffer.Verified result =
                KnowledgeE5DirectModelBuffer.readVerified(new ByteArrayInputStream(bytes),
                        bytes.length, KnowledgeOnnxSessionPipe.sha256Hex(bytes));
        final ByteBuffer view = result.view();
        assertTrue(view.isDirect());
        assertTrue(view.isReadOnly());
        assertEquals(bytes.length, view.remaining());
        for (int i = 0; i < bytes.length; i++) assertEquals(bytes[i], view.get(i));
        view.position(100);
        assertEquals(bytes.length, result.view().remaining());
        result.close();
        result.close(); // Idempotent, still zeroized.
        for (int i = 0; i < bytes.length; i++) assertEquals(0, view.get(i));
        try {
            result.view();
            fail("Wiped native buffer was reused");
        } catch (IllegalStateException expected) { }
    }

    @Test public void shaMismatchTruncationAndTrailingBytesCannotEscape() throws Exception {
        final byte[] bytes = fixture();
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(bytes);
        try {
            KnowledgeE5DirectModelBuffer.readVerified(new ByteArrayInputStream(bytes),
                    bytes.length, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            fail("Different hash accepted");
        } catch (IOException expected) { }
        try {
            KnowledgeE5DirectModelBuffer.readVerified(
                    new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 1)),
                    bytes.length, sha);
            fail("Truncated stream accepted");
        } catch (IOException expected) { }
        try {
            KnowledgeE5DirectModelBuffer.readVerified(
                    new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length + 1)),
                    bytes.length, sha);
            fail("Trailing bytes accepted");
        } catch (IOException expected) { }
    }

    @Test public void oversizedEnvelopeIsRejectedBeforeReading() throws Exception {
        final InputStream neverRead = new InputStream() {
            @Override public int read() { throw new AssertionError("Model stream was opened"); }
        };
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(fixture());
        for (final long size : new long[]{0, 15, KnowledgeE5DirectModelBuffer.MAX_DIRECT_BYTES + 1}) {
            try {
                KnowledgeE5DirectModelBuffer.readVerified(neverRead, size, sha);
                fail("Invalid native-memory envelope accepted");
            } catch (IOException expected) { }
        }
    }

    @Test public void cancellationPreventsVerifiedResult() throws Exception {
        final byte[] bytes = fixture();
        Thread.currentThread().interrupt();
        try {
            KnowledgeE5DirectModelBuffer.readVerified(new ByteArrayInputStream(bytes),
                    bytes.length, KnowledgeOnnxSessionPipe.sha256Hex(bytes));
            fail("Cancelled transfer accepted");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void pinnedOver17MiBFixtureStaysDirectAndNeverTouchesOrt() throws Exception {
        assertTrue(KnowledgeE5LargeFdFixture.LENGTH_BYTES
                > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES);
        try (InputStream source = KnowledgeE5LargeFdFixture.open();
             KnowledgeE5DirectModelBuffer.Verified result =
                     KnowledgeE5DirectModelBuffer.readVerified(source,
                             KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                             KnowledgeE5LargeFdFixture.SHA256)) {
            final ByteBuffer view = result.view();
            assertTrue(view.isDirect());
            assertTrue(view.isReadOnly());
            assertEquals(KnowledgeE5LargeFdFixture.LENGTH_BYTES, view.remaining());
            assertEquals((byte) 17, view.get(0));
            assertEquals((byte) (16L * 31L + 17L), view.get(16));
            final int last = (int) KnowledgeE5LargeFdFixture.LENGTH_BYTES - 1;
            assertEquals((byte) (last * 31L + 17L), view.get(last));
        }
    }
}

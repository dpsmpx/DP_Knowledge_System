/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Pure JVM transfer checks; physical Binder/FD/native behavior still needs QA. */
public final class KnowledgeOnnxSessionPipeTest {
    private static String hash(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder text = new StringBuilder(64);
        for (final byte value : digest) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }

    @Test public void exactBytesAreAccepted() throws Exception {
        final byte[] bytes = new byte[257];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 13);
        assertArrayEquals(bytes, KnowledgeOnnxSessionPipe.readVerified(
                new ByteArrayInputStream(bytes), bytes.length, hash(bytes)));
    }

    @Test public void generatedIdentityUsesTheSameFdChecksumContract() throws Exception {
        final byte[] fixture = KnowledgeOnnxSmoke.identityModel();
        assertTrue(fixture.length >= 16);
        assertEquals(hash(fixture), KnowledgeOnnxSessionPipe.sha256Hex(fixture));
        assertArrayEquals(fixture, KnowledgeOnnxSessionPipe.readVerified(
                new ByteArrayInputStream(fixture), fixture.length,
                KnowledgeOnnxSessionPipe.sha256Hex(fixture)));
    }

    @Test public void rejectsTruncatedExtendedOrChangedModel() throws Exception {
        final byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte) 7);
        final String expected = hash(bytes);
        for (final byte[] invalid : new byte[][]{
                Arrays.copyOf(bytes, bytes.length - 1),
                Arrays.copyOf(bytes, bytes.length + 1),
                new byte[bytes.length]}) {
            try {
                KnowledgeOnnxSessionPipe.readVerified(new ByteArrayInputStream(invalid),
                        bytes.length, expected);
                fail("Expected rejected FD payload");
            } catch (IOException expectedError) { /* closed on the caller side */ }
        }
    }

    @Test public void rejectsUntrustedOrOversizedEnvelope() throws Exception {
        final byte[] bytes = new byte[16];
        final String digest = hash(bytes);
        assertFalse(KnowledgeOnnxSessionPipe.validSha256(digest.toUpperCase()));
        for (final int length : new int[]{0, 15,
                (int) KnowledgeOnnxSessionGate.MAX_MODEL_BYTES + 1}) {
            try {
                KnowledgeOnnxSessionPipe.readVerified(new ByteArrayInputStream(bytes),
                        length, digest);
                fail("Expected rejected size");
            } catch (IOException expectedError) { }
        }
    }
}

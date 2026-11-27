/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.*;

/** Pure JVM sanity checks for the independent isolated-worker QA fixture. */
public final class KnowledgeE5LargeFdFixtureTest {
    @Test public void pinnedFixtureIsLargerThanLegacyModelSession() throws Exception {
        assertTrue(KnowledgeE5LargeFdFixture.LENGTH_BYTES
                > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES);
        assertTrue(KnowledgeE5LargeFdFixture.isPinned(KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                KnowledgeE5LargeFdFixture.SHA256));
        assertFalse(KnowledgeE5LargeFdFixture.isPinned(
                KnowledgeE5LargeFdFixture.LENGTH_BYTES + 1, KnowledgeE5LargeFdFixture.SHA256));
        assertFalse(KnowledgeE5LargeFdFixture.isPinned(
                KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test public void generatedFixtureMatchesIndependentFixedSha() throws Exception {
        try (InputStream stream = KnowledgeE5LargeFdFixture.open()) {
            KnowledgeE5LargeFdTransfer.verifyExactly(stream,
                    KnowledgeE5LargeFdFixture.LENGTH_BYTES, KnowledgeE5LargeFdFixture.SHA256);
        }
    }

    @Test public void fixtureHonorsZeroLengthReadAtEof() throws Exception {
        try (InputStream stream = KnowledgeE5LargeFdFixture.open()) {
            final byte[] array = new byte[8];
            assertEquals(0, stream.read(array, 4, 0));
            assertEquals(17, stream.read());
            assertEquals(48, stream.read());
            assertEquals(79, stream.read());
        }
    }
}

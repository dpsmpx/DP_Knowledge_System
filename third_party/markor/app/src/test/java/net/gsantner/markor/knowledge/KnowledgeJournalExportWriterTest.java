/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class KnowledgeJournalExportWriterTest {
    private static final byte[] ENVELOPE = "synthetic encrypted envelope"
            .getBytes(StandardCharsets.UTF_8);

    private static final class FakeSaf implements KnowledgeJournalExportWriter.Destination {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] readOverride;
        boolean opened;
        boolean closed;

        @Override public OutputStream openWrite() {
            opened = true;
            return new OutputStream() {
                @Override public void write(int b) { output.write(b); }
                @Override public void write(byte[] b, int off, int len) {
                    output.write(b, off, len);
                }
                @Override public void close() { closed = true; }
            };
        }

        @Override public InputStream openRead() {
            assertTrue("Read-back must happen after closing the writer", closed);
            return new ByteArrayInputStream(readOverride != null ? readOverride : output.toByteArray());
        }
    }

    @Test public void writesAndVerifiesEntireClosedEnvelope() throws Exception {
        final FakeSaf saf = new FakeSaf();
        KnowledgeJournalExportWriter.writeAndVerify(saf, ENVELOPE);
        assertTrue(saf.closed);
        assertArrayEquals(ENVELOPE, saf.output.toByteArray());
    }

    @Test public void refusesEmptyPayloadBeforeOpeningDestination() throws Exception {
        final FakeSaf saf = new FakeSaf();
        try {
            KnowledgeJournalExportWriter.writeAndVerify(saf, new byte[0]);
            fail("Empty archive should be refused before creating a document");
        } catch (IOException expected) {
            assertFalse(saf.opened);
        }
    }

    @Test public void refusesProviderThatPersistsZeroBytes() throws Exception {
        final FakeSaf saf = new FakeSaf();
        saf.readOverride = new byte[0];
        try {
            KnowledgeJournalExportWriter.writeAndVerify(saf, ENVELOPE);
            fail("Provider dropped the bytes");
        } catch (IOException expected) {
            assertTrue(saf.closed);
        }
    }

    @Test public void refusesProviderThatReturnsDifferentBytes() throws Exception {
        final FakeSaf saf = new FakeSaf();
        saf.readOverride = ENVELOPE.clone();
        saf.readOverride[3] ^= 1;
        try {
            KnowledgeJournalExportWriter.writeAndVerify(saf, ENVELOPE);
            fail("Provider corrupted the encrypted archive");
        } catch (IOException expected) {
            assertTrue(saf.closed);
        }
    }
}

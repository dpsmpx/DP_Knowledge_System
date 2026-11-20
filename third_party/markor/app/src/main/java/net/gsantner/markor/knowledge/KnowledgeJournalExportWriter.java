/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SAF destination writer: report success only when the closed output can be
 * read back byte-for-byte. Callers prepare the encrypted envelope *before*
 * creating a user-visible document, avoiding empty placeholders on preflight
 * or encryption failures. No note text, filename or secret is logged.
 */
final class KnowledgeJournalExportWriter {
    interface Destination {
        OutputStream openWrite() throws IOException;
        InputStream openRead() throws IOException;
    }

    private KnowledgeJournalExportWriter() { }

    static void writeAndVerify(final Destination destination, final byte[] envelope)
            throws IOException {
        if (destination == null || envelope == null || envelope.length == 0) {
            throw new IOException("Cannot export an empty backup");
        }
        try (OutputStream output = destination.openWrite()) {
            if (output == null) throw new IOException("Destination is not writable");
            output.write(envelope);
            output.flush();
        }

        final MessageDigest expected;
        final MessageDigest actual;
        try {
            expected = MessageDigest.getInstance("SHA-256");
            actual = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        expected.update(envelope);
        int total = 0;
        try (InputStream input = destination.openRead()) {
            if (input == null) throw new IOException("Destination cannot be verified");
            final byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (n == 0) continue;
                if (n > envelope.length - total) {
                    throw new IOException("Destination is larger than the prepared backup");
                }
                total += n;
                actual.update(buffer, 0, n);
            }
        }
        if (total != envelope.length
                || !MessageDigest.isEqual(expected.digest(), actual.digest())) {
            throw new IOException("Destination does not match the encrypted backup");
        }
    }
}

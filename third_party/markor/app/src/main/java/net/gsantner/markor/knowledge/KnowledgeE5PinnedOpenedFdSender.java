/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Source-side preparation for a future MANUAL large-E5 FD transfer.
 *
 * The caller first audits and authorizes a private package, then opens its
 * weight file read-only and passes that SAME open descriptor here. No path is
 * accepted and nothing invokes ORT. Every sink is quarantine-only: the caller
 * must discard partial output on any failure and independently verify the
 * receiver's bytes before using them. This method does not close either owner.
 */
final class KnowledgeE5PinnedOpenedFdSender {
    private KnowledgeE5PinnedOpenedFdSender() { }

    static void copyVerified(final RandomAccessFile opened, final OutputStream quarantine,
                             final long expectedBytes, final String expectedSha256)
            throws IOException {
        if (opened == null || quarantine == null || expectedBytes < 16
                || expectedBytes > KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES
                || !KnowledgeOnnxSessionPipe.validSha256(expectedSha256)) {
            throw new IOException("Invalid pinned E5 FD transfer envelope");
        }
        // A borrowed stream follows this file descriptor's seek pointer.
        // Closing it would close the caller-owned RandomAccessFile, so only the
        // owning caller may close the descriptor after the transport finishes.
        final InputStream borrowed = new InputStream() {
            @Override public int read() throws IOException { return opened.read(); }

            @Override public int read(final byte[] bytes, final int offset, final int count)
                    throws IOException {
                return opened.read(bytes, offset, count);
            }
        };
        final long previousPosition = opened.getFilePointer();
        try {
            requireLength(opened, expectedBytes);
            opened.seek(0L);
            KnowledgeE5LargeFdTransfer.verifyExactly(borrowed, expectedBytes, expectedSha256);

            requireLength(opened, expectedBytes);
            opened.seek(0L);
            KnowledgeE5LargeFdTransfer.copyToQuarantine(
                    borrowed, quarantine, expectedBytes, expectedSha256);

            // The second check rejects a file modified after the part already
            // copied. Source integrity and the receiver's own SHA are distinct.
            requireLength(opened, expectedBytes);
            opened.seek(0L);
            KnowledgeE5LargeFdTransfer.verifyExactly(borrowed, expectedBytes, expectedSha256);
            requireLength(opened, expectedBytes);
        } finally {
            // Preserve caller ownership and offset even on failure/cancellation.
            opened.seek(previousPosition);
        }
    }

    private static void requireLength(final RandomAccessFile opened, final long expectedBytes)
            throws IOException {
        if (opened.length() != expectedBytes) {
            throw new IOException("Pinned opened E5 FD length changed");
        }
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

/** File-descriptor tests use only generated, throwaway bytes. No user models. */
public final class KnowledgeE5PinnedOpenedFdSenderTest {
    private static String hash(final byte[] bytes) throws IOException {
        return KnowledgeOnnxSessionPipe.sha256Hex(bytes);
    }

    private static File fixture(final byte[] bytes) throws IOException {
        final File file = File.createTempFile("dpks-e5-opened-fd-", ".fixture");
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }

    private static void reject(final RunnableWithIo call) throws IOException {
        try {
            call.run();
            fail("Expected source FD verification failure");
        } catch (IOException expected) { }
    }

    private interface RunnableWithIo { void run() throws IOException; }

    @Test public void largeOpenedFdStreamsWithFixedMemoryAndRestoresOwnerOffset()
            throws Exception {
        final File file = File.createTempFile("dpks-e5-large-opened-", ".fixture");
        try {
            try (InputStream source = KnowledgeE5LargeFdFixture.open();
                 OutputStream output = new FileOutputStream(file)) {
                final byte[] block = new byte[KnowledgeE5LargeFdTransfer.CHUNK_BYTES];
                int count;
                while ((count = source.read(block)) != -1) output.write(block, 0, count);
            }
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final long[] sent = {0};
            final OutputStream quarantine = new OutputStream() {
                @Override public void write(final int value) {
                    digest.update((byte) value);
                    sent[0]++;
                }

                @Override public void write(final byte[] data, final int offset, final int length) {
                    digest.update(data, offset, length);
                    sent[0] += length;
                }
            };
            try (RandomAccessFile opened = new RandomAccessFile(file, "r")) {
                opened.seek(7L);
                KnowledgeE5PinnedOpenedFdSender.copyVerified(opened, quarantine,
                        KnowledgeE5LargeFdFixture.LENGTH_BYTES, KnowledgeE5LargeFdFixture.SHA256);
                assertEquals(7L, opened.getFilePointer());
            }
            assertEquals(KnowledgeE5LargeFdFixture.LENGTH_BYTES, sent[0]);
            final StringBuilder actual = new StringBuilder(64);
            for (final byte b : digest.digest()) actual.append(String.format("%02x", b & 255));
            assertEquals(KnowledgeE5LargeFdFixture.SHA256, actual.toString());
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void modifiedSourceIsRejectedBeforeFirstOutputByte() throws Exception {
        final byte[] bytes = new byte[128];
        Arrays.fill(bytes, (byte) 11);
        final File file = fixture(bytes);
        try (RandomAccessFile opened = new RandomAccessFile(file, "r")) {
            final String pinned = hash(bytes);
            try (RandomAccessFile changed = new RandomAccessFile(file, "rw")) {
                changed.seek(0);
                changed.write(99);
            }
            final ByteArrayOutputStream quarantine = new ByteArrayOutputStream();
            reject(() -> KnowledgeE5PinnedOpenedFdSender.copyVerified(
                    opened, quarantine, bytes.length, pinned));
            assertEquals(0, quarantine.size());
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void mutationAfterAlreadySentByteFailsFinalOpenedFdAudit()
            throws Exception {
        final byte[] bytes = new byte[128];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        final File file = fixture(bytes);
        try (RandomAccessFile opened = new RandomAccessFile(file, "r")) {
            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            final OutputStream mutatingSink = new OutputStream() {
                private boolean modified;

                @Override public void write(final int value) throws IOException {
                    write(new byte[]{(byte) value}, 0, 1);
                }

                @Override public void write(final byte[] data, final int offset, final int length)
                        throws IOException {
                    captured.write(data, offset, length);
                    if (!modified) {
                        modified = true;
                        try (RandomAccessFile changed = new RandomAccessFile(file, "rw")) {
                            changed.seek(0);
                            changed.write(255);
                        }
                    }
                }
            };
            reject(() -> KnowledgeE5PinnedOpenedFdSender.copyVerified(
                    opened, mutatingSink, bytes.length, hash(bytes)));
            // The receiver has partial/untrusted output, even if these bytes
            // happened to match the original digest. Caller must discard it.
            assertArrayEquals(bytes, captured.toByteArray());
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void pathReplacementCannotSwapOpenedInode() throws Exception {
        final byte[] expected = new byte[128];
        Arrays.fill(expected, (byte) 17);
        final File path = fixture(expected);
        final File moved = new File(path.getParentFile(), path.getName() + ".old");
        try (RandomAccessFile opened = new RandomAccessFile(path, "r")) {
            Files.move(path.toPath(), moved.toPath(), StandardCopyOption.ATOMIC_MOVE);
            try (FileOutputStream replacement = new FileOutputStream(path)) {
                final byte[] unrelated = new byte[expected.length];
                Arrays.fill(unrelated, (byte) 99);
                replacement.write(unrelated);
            }
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            KnowledgeE5PinnedOpenedFdSender.copyVerified(
                    opened, output, expected.length, hash(expected));
            assertArrayEquals(expected, output.toByteArray());
        } finally {
            Files.deleteIfExists(path.toPath());
            Files.deleteIfExists(moved.toPath());
        }
    }

    @Test public void rejectsInvalidEnvelopeAndCancellationWithoutClosingOwner()
            throws Exception {
        final byte[] bytes = new byte[64];
        final File file = fixture(bytes);
        try (RandomAccessFile opened = new RandomAccessFile(file, "r")) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            reject(() -> KnowledgeE5PinnedOpenedFdSender.copyVerified(
                    opened, output, KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES + 1, hash(bytes)));
            reject(() -> KnowledgeE5PinnedOpenedFdSender.copyVerified(
                    opened, output, bytes.length, hash(bytes).toUpperCase()));
            assertEquals(0, output.size());
            Thread.currentThread().interrupt();
            try {
                try {
                    KnowledgeE5PinnedOpenedFdSender.copyVerified(
                            opened, output, bytes.length, hash(bytes));
                    fail("Interrupted opened FD was accepted");
                } catch (InterruptedIOException expectedCancellation) {
                    assertTrue(Thread.currentThread().isInterrupted());
                }
            } finally {
                Thread.interrupted();
            }
            assertEquals(0, output.size());
            assertEquals(bytes.length, opened.length());
        } finally {
            assertTrue(file.delete());
        }
    }
}

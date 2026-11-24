/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class KnowledgeModelFilesTest {
    private static byte[] gguf() {
        final byte[] bytes = new byte[64];
        bytes[0] = 'G'; bytes[1] = 'G'; bytes[2] = 'U'; bytes[3] = 'F';
        bytes[4] = 3;
        for (int i = 8; i < bytes.length; i++) bytes[i] = (byte) i;
        return bytes;
    }

    @Test public void importIsPrivateImmutableAndMultipleVariantsRemainIndependent()
            throws Exception {
        final File root = Files.createTempDirectory("dpks-model-test").toFile();
        try {
            final KnowledgeModelFiles store = new KnowledgeModelFiles(root);
            final KnowledgeModelFiles.Entry one = store.importFile(
                    new ByteArrayInputStream(gguf()), "../../Model\u202E.gguf",
                    KnowledgeModelPolicy.Role.GENERATION);
            assertEquals(64, one.bytes);
            assertEquals(KnowledgeModelFiles.Format.GGUF, one.format);
            assertFalse(one.name.contains("/"));
            assertFalse(one.name.contains("\u202E"));
            assertEquals(64, one.sha256.length());
            assertEquals(1, store.installed().size());
            final File orphan = new File(root, "model-previous-crash.part");
            assertTrue(orphan.createNewFile());
            assertEquals(1, store.installed().size());
            assertFalse(orphan.exists());

            // Different class can reference the same private immutable blob.
            final KnowledgeModelFiles.Entry same = store.importFile(
                    new ByteArrayInputStream(gguf()), "same.gguf",
                    KnowledgeModelPolicy.Role.RERANKING);
            assertEquals(one.sha256, same.sha256);
            assertEquals(2, store.installed().size());
            assertTrue(store.delete(one));
            assertEquals(1, store.installed().size());
            assertTrue(store.delete(same));
            assertEquals(0, store.installed().size());
        } finally { cleanup(root); }
    }

    @Test public void corruptAndUnknownFilesNeverAppearAsInstalled() throws Exception {
        final File root = Files.createTempDirectory("dpks-model-reject").toFile();
        try {
            final KnowledgeModelFiles store = new KnowledgeModelFiles(root);
            for (final String name : Arrays.asList("model.bin", "bad.apk", "data.zip")) {
                try {
                    store.importFile(new ByteArrayInputStream(gguf()), name,
                            KnowledgeModelPolicy.Role.EMBEDDING);
                    fail("Accepted unsupported extension: " + name);
                } catch (IOException expected) { }
            }
            final byte[] fake = gguf(); fake[0] = 'X';
            try {
                store.importFile(new ByteArrayInputStream(fake), "wrong.gguf",
                        KnowledgeModelPolicy.Role.EMBEDDING);
                fail("Accepted invalid GGUF magic");
            } catch (IOException expected) { }
            assertTrue(store.installed().isEmpty());
            assertEquals(0, countMetadata(root));
            final KnowledgeModelFiles.Entry onnx = store.importFile(
                    new ByteArrayInputStream(new byte[32]), "unverified.onnx",
                    KnowledgeModelPolicy.Role.EMBEDDING);
            assertEquals(KnowledgeModelFiles.Format.ONNX_UNVERIFIED, onnx.format);
            assertEquals(1, store.installed().size());
        } finally { cleanup(root); }
    }

    @Test public void cancellationLeavesNoVisibleModel() throws Exception {
        final File root = Files.createTempDirectory("dpks-model-cancel").toFile();
        try {
            final KnowledgeModelFiles store = new KnowledgeModelFiles(root);
            Thread.currentThread().interrupt();
            try {
                store.importFile(new ByteArrayInputStream(gguf()), "stop.gguf",
                        KnowledgeModelPolicy.Role.GENERATION);
                fail("Interrupted model import completed");
            } catch (InterruptedIOException expected) { }
            finally { Thread.interrupted(); }
            assertTrue(store.installed().isEmpty());
            assertEquals(0, countMetadata(root));
        } finally { cleanup(root); }
    }

    @Test public void expectedDigestIsCheckedBeforeModelBecomesVisible() throws Exception {
        final File root = Files.createTempDirectory("dpks-model-checksum").toFile();
        try {
            final KnowledgeModelFiles store = new KnowledgeModelFiles(root);
            final long[] observed = {0};
            try {
                store.importFile(new ByteArrayInputStream(gguf()), "weights.gguf",
                        KnowledgeModelPolicy.Role.GENERATION, repeat('0', 64),
                        size -> observed[0] = size);
                fail("Incorrect expected SHA-256 accepted");
            } catch (IOException expected) { }
            assertEquals(64, observed[0]);
            assertTrue(store.installed().isEmpty());
            assertEquals(0, countMetadata(root));

            final String digest = sha256(gguf());
            final KnowledgeModelFiles.Entry verified = store.importFile(
                    new ByteArrayInputStream(gguf()), "weights.gguf",
                    KnowledgeModelPolicy.Role.GENERATION, digest, null);
            assertEquals(digest, verified.sha256);
            assertEquals(1, store.installed().size());
        } finally { cleanup(root); }
    }

    private static String repeat(final char ch, final int length) {
        final char[] chars = new char[length];
        Arrays.fill(chars, ch);
        return new String(chars);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder result = new StringBuilder();
        for (final byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private static int countMetadata(final File root) {
        int n = 0;
        final File[] files = root.listFiles();
        if (files != null) for (final File file : files) {
            if (file.getName().endsWith(".meta")) n++;
        }
        return n;
    }

    private static void cleanup(final File root) {
        final File[] children = root.listFiles();
        if (children != null) for (final File child : children) child.delete();
        root.delete();
    }
}

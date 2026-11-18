/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class KnowledgeVaultScannerTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void scansMarkdownRecursivelyAndSortsDeterministically() throws Exception {
        final File root = temporaryFolder.newFolder("vault");
        Files.write(new File(root, "Z.md").toPath(), Arrays.asList("# Z"));
        Files.write(new File(root, "a.MARKDOWN").toPath(), Arrays.asList("# A"));
        final File nested = new File(root, "nested");
        assertTrue(nested.mkdir());
        Files.write(new File(nested, "b.md").toPath(), Arrays.asList("# B"));
        Files.write(new File(nested, "skip.txt").toPath(), Arrays.asList("text"));

        final KnowledgeVaultScanner.Snapshot result =
                KnowledgeVaultScanner.scan(root, 100, () -> false);
        assertEquals(Arrays.asList("a.MARKDOWN", "nested/b.md", "Z.md"),
                result.notes.stream().map(note -> note.relativePath).collect(Collectors.toList()));
        assertFalse(result.truncated);
        assertEquals(0, result.unreadableDirectories);
    }

    @Test
    public void skipsHiddenVaultMetadata() throws Exception {
        final File root = temporaryFolder.newFolder("vault");
        final File hidden = new File(root, ".obsidian");
        assertTrue(hidden.mkdir());
        Files.write(new File(hidden, "private.md").toPath(), Arrays.asList("not indexed"));
        Files.write(new File(root, ".hidden.md").toPath(), Arrays.asList("not indexed"));
        assertTrue(KnowledgeVaultScanner.scan(root, 100, () -> false).notes.isEmpty());
    }

    @Test
    public void neverFollowsSymlinksOutsideVault() throws Exception {
        final File root = temporaryFolder.newFolder("vault");
        final File outside = temporaryFolder.newFolder("outside");
        final File confidential = new File(outside, "outside.md");
        Files.write(confidential.toPath(), Arrays.asList("private fixture"));
        Files.createSymbolicLink(new File(root, "linked.md").toPath(), confidential.toPath());
        Files.createSymbolicLink(new File(root, "linked-dir").toPath(), outside.toPath());
        assertTrue(KnowledgeVaultScanner.scan(root, 100, () -> false).notes.isEmpty());
    }

    @Test
    public void reportsBoundedScanWithoutWritingNotes() throws Exception {
        final File root = temporaryFolder.newFolder("vault");
        final File first = new File(root, "one.md");
        final File second = new File(root, "two.md");
        Files.write(first.toPath(), Arrays.asList("# One"));
        Files.write(second.toPath(), Arrays.asList("# Two"));
        final long firstModified = first.lastModified();
        final KnowledgeVaultScanner.Snapshot result = KnowledgeVaultScanner.scan(root, 1, () -> false);
        assertEquals(1, result.notes.size());
        assertTrue(result.truncated);
        assertEquals(firstModified, first.lastModified());
        assertTrue(second.isFile());
    }

    @Test(expected = InterruptedIOException.class)
    public void canCancelLongScans() throws Exception {
        KnowledgeVaultScanner.scan(temporaryFolder.newFolder("vault"), 100, () -> true);
    }

    @Test
    public void stressScansTwelveHundredSyntheticNotesWithBoundedCountOnlyProgress()
            throws Exception {
        final File root = temporaryFolder.newFolder("synthetic-vault");
        final int expected = 1200;
        for (int i = 0; i < expected; i++) {
            final File directory = new File(root, "group-" + i / 100);
            assertTrue(directory.isDirectory() || directory.mkdir());
            Files.write(new File(directory, String.format("synthetic-%04d.md", i)).toPath(),
                    Arrays.asList("# Synthetic fixture " + i));
        }
        final List<Integer> progress = new ArrayList<>();
        final KnowledgeVaultScanner.Snapshot result = KnowledgeVaultScanner.scan(
                root, 2000, () -> false, (notes, directories) -> {
                    assertTrue(directories >= 1);
                    progress.add(notes);
                });
        assertEquals(expected, result.notes.size());
        assertFalse(result.truncated);
        assertEquals(0, result.unreadableDirectories);
        assertTrue(progress.contains(512));
        assertTrue(progress.contains(1024));
        assertEquals(Integer.valueOf(expected), progress.get(progress.size() - 1));
        assertEquals("group-0/synthetic-0000.md", result.notes.get(0).relativePath);
        assertTrue(new File(root, "group-11/synthetic-1199.md").isFile());
    }

    @Test
    public void cancelFromProgressDoesNotChangeSyntheticSourceNotes() throws Exception {
        final File root = temporaryFolder.newFolder("cancel-vault");
        for (int i = 0; i < 700; i++) {
            Files.write(new File(root, String.format("item-%04d.md", i)).toPath(),
                    Arrays.asList("# Fake content"));
        }
        final File sentinel = new File(root, "item-0000.md");
        final byte[] before = Files.readAllBytes(sentinel.toPath());
        final AtomicBoolean cancelled = new AtomicBoolean();
        try {
            KnowledgeVaultScanner.scan(root, 1000, cancelled::get,
                    (notes, directories) -> {
                        if (notes >= 512) {
                            cancelled.set(true);
                        }
                    });
            fail("Scan should honor cancellation");
        } catch (InterruptedIOException expected) {
            assertTrue(cancelled.get());
        }
        assertArrayEquals(before, Files.readAllBytes(sentinel.toPath()));
    }
}

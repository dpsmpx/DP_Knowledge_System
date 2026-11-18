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
}

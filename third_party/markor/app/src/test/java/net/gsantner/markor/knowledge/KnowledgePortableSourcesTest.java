/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class KnowledgePortableSourcesTest {
    private static final byte[] LOCAL = "# Local edit\nThis version stays.\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMOTE = "# Remote edit\nThis version is a separate copy.\n"
            .getBytes(StandardCharsets.UTF_8);

    private static File archive(final File parent) throws Exception {
        final File source = new File(parent, "source");
        assertTrue(source.mkdir());
        final File nested = new File(source, "nested");
        assertTrue(nested.mkdir());
        Files.write(new File(nested, "note.md").toPath(), REMOTE);
        Files.write(new File(source, "other.markdown").toPath(), "new"
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(source, "ignored.txt").toPath(), "ignore"
                .getBytes(StandardCharsets.UTF_8));
        final File zip = new File(parent, "originals.zip");
        try (FileOutputStream output = new FileOutputStream(zip)) {
            KnowledgePortableArchive.write(source, null, null, output, () -> false);
        }
        assertEquals(3, KnowledgePortableArchive.verify(zip, () -> false).sources);
        return zip;
    }

    @Test public void inspectAndKeepBothNeverOverwriteExistingOriginal() throws Exception {
        final File parent = Files.createTempDirectory("stage21-md-conflict-").toFile();
        final File zip = archive(parent);
        final File local = new File(parent, "local");
        assertTrue(local.mkdir());
        final File nested = new File(local, "nested");
        assertTrue(nested.mkdir());
        final File original = new File(nested, "note.md");
        Files.write(original.toPath(), LOCAL);
        final KnowledgePortableSources.Page page = KnowledgePortableSources.page(zip, 0);
        assertEquals(2, page.total);
        assertEquals("nested/note.md", page.paths.get(0));
        final KnowledgePortableSources.Item diff = KnowledgePortableSources.inspect(zip,
                local, "nested/note.md");
        assertEquals("different", diff.status);
        assertTrue(diff.localExcerpt.contains("Local edit"));
        assertTrue(diff.remoteExcerpt.contains("Remote edit"));
        final File copy = KnowledgePortableSources.saveCopy(zip, local, diff);
        assertTrue(copy.getName().startsWith("note.peer-"));
        assertArrayEquals(REMOTE, Files.readAllBytes(copy.toPath()));
        assertArrayEquals(LOCAL, Files.readAllBytes(original.toPath()));
        final File second = KnowledgePortableSources.saveCopy(zip, local, diff);
        assertNotEquals(copy.getName(), second.getName());
        assertArrayEquals(LOCAL, Files.readAllBytes(original.toPath()));
    }

    @Test public void missingFileCanBeCopiedOnlyAfterReview() throws Exception {
        final File parent = Files.createTempDirectory("stage21-md-missing-").toFile();
        final File zip = archive(parent);
        final File local = new File(parent, "local");
        assertTrue(local.mkdir());
        final KnowledgePortableSources.Item preview = KnowledgePortableSources.inspect(zip,
                local, "other.markdown");
        assertEquals("missing", preview.status);
        final File saved = KnowledgePortableSources.saveCopy(zip, local, preview);
        assertEquals("other.markdown", saved.getName());
        assertEquals("identical", KnowledgePortableSources.inspect(zip, local,
                "other.markdown").status);
        try { KnowledgePortableSources.saveCopy(zip, local, preview);
            fail("Unreviewed duplicate saved");
        } catch (java.io.IOException expected) { /* Repreview mandatory. */ }
    }

    @Test public void stalePreviewAndTraversalAreRejected() throws Exception {
        final File parent = Files.createTempDirectory("stage21-md-stale-").toFile();
        final File zip = archive(parent);
        final File local = new File(parent, "local");
        assertTrue(local.mkdir());
        final File original = new File(local, "other.markdown");
        final KnowledgePortableSources.Item previouslyMissing = KnowledgePortableSources.inspect(
                zip, local, "other.markdown");
        Files.write(original.toPath(), LOCAL);
        try { KnowledgePortableSources.saveCopy(zip, local, previouslyMissing);
            fail("Stale preview used");
        } catch (java.io.IOException expected) { /* No writes. */ }
        assertArrayEquals(LOCAL, Files.readAllBytes(original.toPath()));
        try { KnowledgePortableSources.inspect(zip, local, "../outside.md");
            fail("Traversal path accepted");
        } catch (java.io.IOException expected) { /* No writes. */ }
    }
}

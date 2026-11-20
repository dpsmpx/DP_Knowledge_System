/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class KnowledgePortableArchiveTest {
    @Test public void roundTripEncryptedZipPreservesUnicodeAndJournal() throws Exception {
        final File temp = Files.createTempDirectory("stage21-archive-").toFile();
        final File vault = new File(temp, "vault");
        final File dir = new File(vault, "Вложенные");
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "заметка.md").toPath(), "# 🎯 Тест\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(vault, "photo.png").toPath(), new byte[]{0, 1, 2, 3});
        final File journal = new File(temp, "journal.jsonl");
        Files.write(journal.toPath(), "synthetic journal fixture\n".getBytes(StandardCharsets.UTF_8));
        final byte[] settings = "{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        final char[] password = "synthetic-long-fixture-password".toCharArray();
        final KnowledgePortableArchive.Summary created = KnowledgePortableArchive.write(
                vault, journal, settings, KnowledgePortableCipher.encrypt(encrypted, password),
                () -> false);
        assertEquals(2, created.sources);
        assertTrue(created.journal);
        assertTrue(created.settings);
        final File restored = KnowledgePortableCipher.decryptToVerifiedStaging(
                new java.io.ByteArrayInputStream(encrypted.toByteArray()), temp, password,
                () -> false);
        try {
            final KnowledgePortableArchive.Summary verified =
                    KnowledgePortableArchive.verify(restored, () -> false);
            assertEquals(created.sources, verified.sources);
            assertEquals(created.bytes, verified.bytes);
            assertTrue(verified.journal && verified.settings);
        } finally {
            assertTrue(restored.delete());
            Arrays.fill(password, '\0');
        }
    }

    @Test public void optionalSignedPeerProofIsManifestBoundAndSurvivesRoundTrip()
            throws Exception {
        final File temp = Files.createTempDirectory("stage21-peer-").toFile();
        final File vault = new File(temp, "vault");
        assertTrue(vault.mkdir());
        Files.write(new File(vault, "n.md").toPath(), "fixture".getBytes(StandardCharsets.UTF_8));
        final File journal = new File(temp, "journal.jsonl");
        Files.write(journal.toPath(), "synthetic chain fixture".getBytes(StandardCharsets.UTF_8));
        final byte[] proof = "{\"schema\":\"synthetic-test-only\"}"
                .getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        KnowledgePortableArchive.write(vault, journal, null, proof, zipped, () -> false);
        final File zip = new File(temp, "archive.zip");
        Files.write(zip.toPath(), zipped.toByteArray());
        assertTrue(KnowledgePortableArchive.verify(zip, () -> false).journal);
        try (java.util.zip.ZipFile contents = new java.util.zip.ZipFile(zip)) {
            assertNotNull(contents.getEntry(KnowledgePortableArchive.PEER));
            try (java.io.InputStream input = contents.getInputStream(
                    contents.getEntry(KnowledgePortableArchive.PEER))) {
                final byte[] actual = new byte[proof.length];
                assertEquals(proof.length, input.read(actual));
                assertArrayEquals(proof, actual);
                assertEquals(-1, input.read());
            }
        }
        try {
            KnowledgePortableArchive.write(vault, null, null, proof,
                    new ByteArrayOutputStream(), () -> false);
            fail("Detached peer proof accepted without journal");
        } catch (java.io.IOException expected) { /* No detached signing metadata. */ }
    }

    @Test public void wrongPasswordAndSingleByteTamperNeverYieldStaging() throws Exception {
        final File temp = Files.createTempDirectory("stage21-tamper-").toFile();
        final File vault = new File(temp, "vault");
        assertTrue(vault.mkdir());
        Files.write(new File(vault, "n.md").toPath(), "safe fixture".getBytes(StandardCharsets.UTF_8));
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        KnowledgePortableArchive.write(vault, null, null,
                KnowledgePortableCipher.encrypt(output, "correct-fixture-password".toCharArray()),
                () -> false);
        final byte[] original = output.toByteArray();
        try {
            KnowledgePortableCipher.decryptToVerifiedStaging(
                    new java.io.ByteArrayInputStream(original), temp,
                    "different-fixture-pass".toCharArray(), () -> false);
            fail("Wrong password accepted");
        } catch (Exception expected) { /* Authentication must fail. */ }
        original[original.length - 1] ^= 1;
        try {
            KnowledgePortableCipher.decryptToVerifiedStaging(
                    new java.io.ByteArrayInputStream(original), temp,
                    "correct-fixture-password".toCharArray(), () -> false);
            fail("Tampered tag accepted");
        } catch (Exception expected) { /* Authentication must fail. */ }
        final File[] staging = temp.listFiles((dir, name) -> name.startsWith(".dpks-verified-"));
        assertNotNull(staging);
        assertEquals(0, staging.length);
    }

    @Test public void cancellationStopsBeforeArchiveIsAccepted() throws Exception {
        final File temp = Files.createTempDirectory("stage21-cancel-").toFile();
        final File vault = new File(temp, "vault");
        assertTrue(vault.mkdir());
        Files.write(new File(vault, "n.md").toPath(), "fixture".getBytes(StandardCharsets.UTF_8));
        try {
            KnowledgePortableArchive.write(vault, null, null, new ByteArrayOutputStream(),
                    () -> true);
            fail("Cancellation ignored");
        } catch (java.io.IOException expected) { /* No authoritative data touched. */ }
    }

    @Test public void multiBlockAndTruncatedArchiveAreRejected() throws Exception {
        final File temp = Files.createTempDirectory("stage21-blocks-").toFile();
        final File vault = new File(temp, "vault");
        assertTrue(vault.mkdir());
        final byte[] large = new byte[2_400_000];
        new java.util.Random(17).nextBytes(large);
        Files.write(new File(vault, "random.bin").toPath(), large);
        final char[] pass = "multi-block-fixture-passphrase".toCharArray();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        KnowledgePortableArchive.write(vault, null, null,
                KnowledgePortableCipher.encrypt(output, pass), () -> false);
        final byte[] data = output.toByteArray();
        final File stage = KnowledgePortableCipher.decryptToVerifiedStaging(
                new java.io.ByteArrayInputStream(data), temp, pass, () -> false);
        assertEquals(1, KnowledgePortableArchive.verify(stage, () -> false).sources);
        assertTrue(stage.delete());
        for (final int truncate : new int[]{1, 17, data.length / 2}) {
            try {
                KnowledgePortableCipher.decryptToVerifiedStaging(
                        new java.io.ByteArrayInputStream(Arrays.copyOf(data,
                                data.length - truncate)), temp, pass, () -> false);
                fail("Truncated block or terminal record accepted");
            } catch (Exception expected) { /* Must not return ZIP. */ }
        }
        final byte[] appended = Arrays.copyOf(data, data.length + 1);
        try {
            KnowledgePortableCipher.decryptToVerifiedStaging(
                    new java.io.ByteArrayInputStream(appended), temp, pass, () -> false);
            fail("Extra ciphertext accepted");
        } catch (Exception expected) { /* No trailing data accepted. */ }
        Arrays.fill(pass, '\0');
    }

    @Test public void traversalEntryIsRejectedBeforeRestore() throws Exception {
        final File temp = Files.createTempDirectory("stage21-traversal-").toFile();
        final File zipFile = new File(temp, "malicious.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zipFile))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("notes/../../outside.md"));
            zip.write("synthetic".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            KnowledgePortableArchive.verify(zipFile, () -> false);
            fail("Traversal path accepted");
        } catch (java.io.IOException expected) { /* No writes. */ }
    }

    @Test public void cleanRestoreCreatesNewLibraryAndPreservesOldSources() throws Exception {
        final File temp = Files.createTempDirectory("stage21-restore-").toFile();
        final File vault = new File(temp, "current");
        assertTrue(vault.mkdir());
        Files.write(new File(vault, "existing.md").toPath(),
                "User's original file".getBytes(StandardCharsets.UTF_8));
        final File source = new File(temp, "fixture");
        assertTrue(source.mkdir());
        Files.write(new File(source, "new.md").toPath(),
                "Only in encrypted snapshot".getBytes(StandardCharsets.UTF_8));
        final File output = new File(temp, "payload.zip");
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(output)) {
            KnowledgePortableArchive.write(source, null, null, file, () -> false);
        }
        final File imported = KnowledgePortableRestore.restoreNewLibrary(
                output, vault, text -> fail("Missing journal must not be imported"), () -> false);
        assertNotEquals(vault.getCanonicalPath(), imported.getCanonicalPath());
        assertEquals("User's original file", new String(Files.readAllBytes(
                new File(vault, "existing.md").toPath()), StandardCharsets.UTF_8));
        assertEquals("Only in encrypted snapshot", new String(Files.readAllBytes(
                new File(imported, "new.md").toPath()), StandardCharsets.UTF_8));
    }
}

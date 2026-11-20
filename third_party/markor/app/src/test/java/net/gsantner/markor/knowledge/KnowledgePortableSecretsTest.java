/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Only synthetic .invalid endpoints; no real credentials in test fixtures. */
public class KnowledgePortableSecretsTest {
    private static final String SAFE = "{\"schema\":\"dpks-safe-settings-v3\","
            + "\"secrets_exported\":true,\"derived_indexes_exported\":false}";
    private static final String BUNDLE = "{\"schema\":\"dpks-portable-secrets-v1\","
            + "\"fields\":{\"knowledge_llm_endpoint\":\"https://fixture.invalid/api?token=FAKE\"}}";

    private static File zip(final String settings, final String secrets) throws Exception {
        final File file = Files.createTempFile("stage21-confidential-", ".zip").toFile();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            if (settings != null) {
                output.putNextEntry(new ZipEntry(KnowledgePortableArchive.SETTINGS));
                output.write(settings.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            if (secrets != null) {
                output.putNextEntry(new ZipEntry(KnowledgePortableArchive.SECRETS));
                output.write(secrets.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }

    @Test public void v3RequiresBoundSecretEntryAndRevealsOnlyApprovedNames() throws Exception {
        final File file = zip(SAFE, BUNDLE);
        try {
            assertTrue(KnowledgePortableSettings.read(file).isEmpty());
            assertEquals(1, KnowledgePortableSecrets.fieldNames(file).size());
            assertEquals("knowledge_llm_endpoint", KnowledgePortableSecrets.fieldNames(file).get(0));
            final Map<String, String> values = KnowledgePortableSecrets.read(file);
            assertEquals("https://fixture.invalid/api?token=FAKE",
                    values.get("knowledge_llm_endpoint"));
        } finally { assertTrue(file.delete()); }
        for (final String missing : new String[]{SAFE, "{\"schema\":\"dpks-safe-settings-v2\","
                + "\"secrets_exported\":false,\"derived_indexes_exported\":false}"}) {
            final File inconsistent = zip(missing, null);
            try {
                if (missing.equals(SAFE)) {
                    try { KnowledgePortableSettings.read(inconsistent); fail("Missing secrets accepted"); }
                    catch (SecurityException expected) { /* Consistency required. */ }
                } else assertTrue(KnowledgePortableSettings.read(inconsistent).isEmpty());
            } finally { assertTrue(inconsistent.delete()); }
        }
    }

    @Test public void v2CannotSmuggleSecretEntry() throws Exception {
        final File file = zip("{\"schema\":\"dpks-safe-settings-v2\","
                + "\"secrets_exported\":false,\"derived_indexes_exported\":false}", BUNDLE);
        try {
            try { KnowledgePortableSettings.read(file); fail("Unannounced secrets accepted"); }
            catch (SecurityException expected) { /* Fail closed. */ }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void unknownOrMalformedSecretFieldsAreRejected() throws Exception {
        for (final String field : new String[]{
                "{\"knowledge_unknown_password\":\"https://fixture.invalid\"}",
                "{\"knowledge_llm_endpoint\":\"file:///etc/passwd\"}",
                "{\"knowledge_llm_endpoint\":true}",
                "{\"knowledge_llm_endpoint\":\"https://fixture.invalid\\nline\"}"
        }) {
            final File file = zip(SAFE,
                    "{\"schema\":\"dpks-portable-secrets-v1\",\"fields\":" + field + "}");
            try {
                try { KnowledgePortableSecrets.read(file); fail("Unsafe secrets accepted"); }
                catch (Exception expected) { /* Invalid field/type/scheme. */ }
            } finally { assertTrue(file.delete()); }
        }
    }

    @Test public void encryptedArchiveManifestBindsOptionalSecrets() throws Exception {
        final File directory = Files.createTempDirectory("stage21-secrets-bound-").toFile();
        final File vault = new File(directory, "vault");
        assertTrue(vault.mkdir());
        Files.write(new File(vault, "note.md").toPath(), "fixture".getBytes(StandardCharsets.UTF_8));
        final ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        final char[] password = "synthetic-long-fixture-password".toCharArray();
        final KnowledgePortableArchive.Summary created = KnowledgePortableArchive.write(
                vault, null, SAFE.getBytes(StandardCharsets.UTF_8), null,
                BUNDLE.getBytes(StandardCharsets.UTF_8),
                KnowledgePortableCipher.encrypt(encrypted, password), () -> false);
        assertTrue(created.secrets);
        final File staged = KnowledgePortableCipher.decryptToVerifiedStaging(
                new java.io.ByteArrayInputStream(encrypted.toByteArray()), directory,
                password, () -> false);
        try {
            assertTrue(KnowledgePortableArchive.verify(staged, () -> false).secrets);
            KnowledgePortableSettings.read(staged);
            assertEquals(1, KnowledgePortableSecrets.fieldNames(staged).size());
        } finally { assertTrue(staged.delete()); }
    }

    @Test public void automaticSnapshotAndDefaultArchiveContainNoSecrets() throws Exception {
        final String safe = new String(KnowledgePortableSettings.snapshot(null),
                StandardCharsets.UTF_8);
        assertTrue(safe.contains("dpks-safe-settings-v2"));
        assertTrue(safe.contains("\"secrets_exported\":false"));
        assertFalse(safe.contains("knowledge_llm_endpoint"));
        final File vault = Files.createTempDirectory("stage21-default-no-secrets-").toFile();
        final ByteArrayOutputStream zip = new ByteArrayOutputStream();
        final KnowledgePortableArchive.Summary summary = KnowledgePortableArchive.write(
                vault, null, KnowledgePortableSettings.snapshot(null), zip, () -> false);
        assertFalse(summary.secrets);
        assertFalse(new String(zip.toByteArray(), StandardCharsets.ISO_8859_1)
                .contains(KnowledgePortableArchive.SECRETS));
    }
}

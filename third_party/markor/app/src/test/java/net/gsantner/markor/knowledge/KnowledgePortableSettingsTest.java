/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Synthetic settings fixtures; never include live tokens/endpoints. */
public class KnowledgePortableSettingsTest {
    private static File archive(final String json) throws Exception {
        final File file = Files.createTempFile("stage21-safe-settings-", ".zip").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(KnowledgePortableArchive.SETTINGS));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test public void v2CarriesOnlyReviewedTypes() throws Exception {
        final File zip = archive("{\"schema\":\"dpks-safe-settings-v2\","
                + "\"secrets_exported\":false,\"derived_indexes_exported\":false,"
                + "\"knowledge_embedding_model\":\"bge-m3\","
                + "\"knowledge_llm_model\":\"local/generic-v1\","
                + "\"semantic_analysis_on_refresh\":true,"
                + "\"knowledge_embedding_endpoint\":\"https://example.invalid/token\","
                + "\"secret_token\":\"synthetic-not-a-real-token\"}");
        try {
            final Map<String, String> safe = KnowledgePortableSettings.read(zip);
            assertEquals(3, safe.size());
            assertEquals("bge-m3", safe.get("knowledge_embedding_model"));
            assertEquals("true", safe.get("semantic_analysis_on_refresh"));
            assertFalse(safe.containsKey("secret_token"));
            assertFalse(safe.containsKey("knowledge_embedding_endpoint"));
        } finally { assertTrue(zip.delete()); }
    }

    @Test public void legacyV1MigratesOnlyModelLabels() throws Exception {
        final File zip = archive("{\"schema\":\"dpks-safe-settings-v1\","
                + "\"secrets_exported\":false,\"derived_indexes_exported\":false,"
                + "\"knowledge_embedding_model\":\"legacy-m3\","
                + "\"semantic_analysis_on_refresh\":true}");
        try {
            final Map<String, String> safe = KnowledgePortableSettings.read(zip);
            assertEquals("legacy-m3", safe.get("knowledge_embedding_model"));
            assertFalse(safe.containsKey("semantic_analysis_on_refresh"));
        } finally { assertTrue(zip.delete()); }
    }

    @Test public void unexpectedPreferenceTypeAndSecretFlagFailClosed() throws Exception {
        for (final String fragment : new String[]{
                "\"semantic_analysis_on_refresh\":\"true\"",
                "\"semantic_analysis_on_refresh\":1"
        }) {
            final File zip = archive("{\"schema\":\"dpks-safe-settings-v2\","
                    + "\"secrets_exported\":false,\"derived_indexes_exported\":false,"
                    + fragment + "}");
            try {
                try {
                    KnowledgePortableSettings.read(zip);
                    fail("Invalid optional setting was trusted");
                } catch (SecurityException expected) { /* fail closed */ }
            } finally { assertTrue(zip.delete()); }
        }
        final File secrets = archive("{\"schema\":\"dpks-safe-settings-v2\","
                + "\"secrets_exported\":true,\"derived_indexes_exported\":false}");
        try {
            try {
                KnowledgePortableSettings.read(secrets);
                fail("Portable secret transfer flag was trusted");
            } catch (SecurityException expected) { /* never import secrets */ }
        } finally { assertTrue(secrets.delete()); }
    }

    @Test public void defaultSnapshotContainsNoSecrets() throws Exception {
        final String json = new String(KnowledgePortableSettings.snapshot(null),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("dpks-safe-settings-v2"));
        assertTrue(json.contains("\"secrets_exported\":false"));
        assertFalse(json.contains("endpoint"));
    }
}

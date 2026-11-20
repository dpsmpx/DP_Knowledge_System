/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Versioned allowlist of non-secret preferences; optional secret data has a separate entry. */
final class KnowledgePortableSettings {
    private static final String SCHEMA_V1 = "dpks-safe-settings-v1";
    private static final String SCHEMA_V2 = "dpks-safe-settings-v2";
    private static final String SCHEMA_V3 = "dpks-safe-settings-v3";
    private static final String[] MODELS = {"knowledge_embedding_model", "knowledge_llm_model"};
    private static final String ANALYZE_ON_REFRESH = "semantic_analysis_on_refresh";

    private KnowledgePortableSettings() { }

    private static boolean safeModel(final String model) {
        return model != null && model.length() <= 128
                && model.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                && !model.contains("..") && !model.contains("//");
    }

    static byte[] snapshot(final SharedPreferences preferences) throws Exception {
        return snapshot(preferences, false);
    }

    /** Only explicit manual exports can request v3 with a separate secret entry. */
    static byte[] snapshot(final SharedPreferences preferences,
                           final boolean includeSecrets) throws Exception {
        final JSONObject safe = new JSONObject();
        safe.put("schema", includeSecrets ? SCHEMA_V3 : SCHEMA_V2);
        safe.put("secrets_exported", includeSecrets);
        safe.put("derived_indexes_exported", false);
        if (preferences != null) {
            for (final String key : MODELS) {
                final String value = preferences.getString(key, "");
                if (safeModel(value)) safe.put(key, value);
            }
            try {
                safe.put(ANALYZE_ON_REFRESH, preferences.getBoolean(ANALYZE_ON_REFRESH, false));
            } catch (ClassCastException malformedLocalPreference) {
                // Do not abort a verified snapshot for an invalid optional
                // preference; neither guess nor serialize an arbitrary type.
            }
        }
        return safe.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An older archive's endpoint fields are deliberately ignored. */
    static Map<String, String> read(final File verifiedZip) throws Exception {
        final Map<String, String> models = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry entry = zip.getEntry(KnowledgePortableArchive.SETTINGS);
            if (entry == null) return models;
            if (entry.getSize() < 1 || entry.getSize() > 256_000)
                throw new IOException("Invalid portable settings size");
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(entry)) {
                final byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > 256_000)
                        throw new IOException("Oversized portable settings");
                    bytes.write(buffer, 0, n);
                }
            }
            final JSONObject json = new JSONObject(new String(bytes.toByteArray(),
                    StandardCharsets.UTF_8));
            final String schema = json.optString("schema");
            final boolean secrets = SCHEMA_V3.equals(schema);
            if (!(SCHEMA_V1.equals(schema) || SCHEMA_V2.equals(schema) || secrets)
                    || !(json.opt("secrets_exported") instanceof Boolean)
                    || json.optBoolean("secrets_exported") != secrets
                    || (zip.getEntry(KnowledgePortableArchive.SECRETS) != null) != secrets
                    || !Boolean.FALSE.equals(json.opt("derived_indexes_exported")))
                throw new SecurityException("Unsupported or unsafe portable settings");
            for (final String key : MODELS) {
                final String value = json.optString(key, "");
                if (safeModel(value)) models.put(key, value);
            }
            if ((SCHEMA_V2.equals(schema) || secrets) && json.has(ANALYZE_ON_REFRESH)) {
                final Object value = json.get(ANALYZE_ON_REFRESH);
                if (!(value instanceof Boolean))
                    throw new SecurityException("Invalid portable analysis preference");
                models.put(ANALYZE_ON_REFRESH, value.toString());
            }
            return models;
        }
    }

    static boolean apply(final SharedPreferences preferences, final Map<String, String> models) {
        if (preferences == null || models == null || models.isEmpty()) return true;
        final SharedPreferences.Editor update = preferences.edit();
        for (final String key : MODELS) {
            final String value = models.get(key);
            if (safeModel(value)) update.putString(key, value);
        }
        final String analyze = models.get(ANALYZE_ON_REFRESH);
        if ("true".equals(analyze) || "false".equals(analyze))
            update.putBoolean(ANALYZE_ON_REFRESH, Boolean.parseBoolean(analyze));
        return update.commit();
    }
}

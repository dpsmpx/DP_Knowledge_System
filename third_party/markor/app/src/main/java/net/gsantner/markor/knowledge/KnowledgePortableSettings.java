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

/** Explicitly safe, allowlisted model labels. URLs, tokens and raw app prefs stay local. */
final class KnowledgePortableSettings {
    private static final String SCHEMA = "dpks-safe-settings-v1";
    private static final String[] MODELS = {"knowledge_embedding_model", "knowledge_llm_model"};

    private KnowledgePortableSettings() { }

    private static boolean safeModel(final String model) {
        return model != null && model.length() <= 128
                && model.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                && !model.contains("..") && !model.contains("//");
    }

    static byte[] snapshot(final SharedPreferences preferences) throws Exception {
        final JSONObject safe = new JSONObject();
        safe.put("schema", SCHEMA);
        safe.put("secrets_exported", false);
        safe.put("derived_indexes_exported", false);
        if (preferences != null) {
            for (final String key : MODELS) {
                final String value = preferences.getString(key, "");
                if (safeModel(value)) safe.put(key, value);
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
            if (!SCHEMA.equals(json.optString("schema"))
                    || json.optBoolean("secrets_exported", true)
                    || json.optBoolean("derived_indexes_exported", true))
                throw new SecurityException("Unsupported or unsafe portable settings");
            for (final String key : MODELS) {
                final String value = json.optString(key, "");
                if (safeModel(value)) models.put(key, value);
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
        return update.commit();
    }
}

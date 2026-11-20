/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict, versioned, opt-in portable credentials. Never use in background backups. */
final class KnowledgePortableSecrets {
    private static final String SCHEMA = "dpks-portable-secrets-v1";
    private static final int MAX_BYTES = 16_384;
    // The app currently persists only these endpoints, which can themselves
    // contain tokens or user:password URLs. LLM API keys remain session-only.
    private static final String[] FIELDS = {
            "knowledge_embedding_endpoint", "knowledge_llm_endpoint"
    };

    private KnowledgePortableSecrets() { }

    private static boolean recognized(final String field) {
        for (final String allowed : FIELDS) if (allowed.equals(field)) return true;
        return false;
    }

    private static void validateEndpoint(final String value) {
        if (value == null || value.isEmpty() || value.length() > 2048
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new SecurityException("Invalid portable endpoint");
        }
        try {
            final URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new SecurityException("Invalid endpoint scheme");
        } catch (java.net.URISyntaxException malformed) {
            throw new SecurityException("Invalid portable endpoint");
        }
    }

    /** Called only from the manually confirmed foreground SAF export. */
    static byte[] snapshot(final SharedPreferences prefs) throws Exception {
        final JSONObject fields = new JSONObject();
        if (prefs != null) for (final String field : FIELDS) {
            final String value = prefs.getString(field, "");
            if (value != null && !value.isEmpty()) {
                validateEndpoint(value);
                fields.put(field, value);
            }
        }
        final JSONObject bundle = new JSONObject();
        bundle.put("schema", SCHEMA);
        bundle.put("fields", fields);
        final byte[] result = bundle.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) throw new IOException("Secret bundle too large");
        return result;
    }

    /** Read only after the entire decrypted ZIP has passed manifest verification. */
    static Map<String, String> read(final File verifiedZip) throws Exception {
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry entry = zip.getEntry(KnowledgePortableArchive.SECRETS);
            if (entry == null) return Collections.emptyMap();
            if (entry.getSize() < 1 || entry.getSize() > MAX_BYTES)
                throw new IOException("Invalid secret bundle size");
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(entry)) {
                final byte[] block = new byte[4096];
                int n;
                while ((n = input.read(block)) != -1) {
                    if (buffer.size() + n > MAX_BYTES)
                        throw new IOException("Secret bundle too large");
                    buffer.write(block, 0, n);
                }
            }
            final JSONObject root = new JSONObject(new String(buffer.toByteArray(),
                    StandardCharsets.UTF_8));
            if (!SCHEMA.equals(root.optString("schema")) || root.length() != 2
                    || !(root.opt("fields") instanceof JSONObject))
                throw new SecurityException("Unsupported secret bundle");
            final JSONObject fields = root.getJSONObject("fields");
            final Map<String, String> approved = new LinkedHashMap<>();
            for (final Iterator<String> it = fields.keys(); it.hasNext(); ) {
                final String field = it.next();
                if (!recognized(field) || !(fields.opt(field) instanceof String))
                    throw new SecurityException("Unexpected secret field");
                final String value = fields.getString(field);
                validateEndpoint(value);
                approved.put(field, value);
            }
            return approved;
        }
    }

    /** A field-name-only preview: never render values into dialogs, logs or analytics. */
    static List<String> fieldNames(final File verifiedZip) throws Exception {
        final List<String> result = new ArrayList<>(read(verifiedZip).keySet());
        Collections.sort(result);
        return result;
    }

    /** Never called without a separate, unchecked-by-default restore choice. */
    static boolean apply(final SharedPreferences prefs, final Map<String, String> values) {
        if (prefs == null || values == null || values.isEmpty()) return true;
        final SharedPreferences.Editor edit = prefs.edit();
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            if (!recognized(entry.getKey())) throw new SecurityException("Unexpected secret field");
            validateEndpoint(entry.getValue());
            edit.putString(entry.getKey(), entry.getValue());
        }
        return edit.commit();
    }
}

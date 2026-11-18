/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** OpenAI-compatible embeddings; always called after explicit per-operation permission. */
public final class KnowledgeEmbeddingClient {
    public static final int MAX_BATCH = 6;
    private static final int MAX_REQUEST_BYTES = 24_000;
    private static final int MAX_RESPONSE_BYTES = 650_000;

    private KnowledgeEmbeddingClient() {
    }

    public static URL endpoint(final String supplied) {
        return KnowledgeLlmClient.serviceEndpoint(supplied, "/v1/embeddings");
    }

    public static String modelKey(final URL endpoint, final String model) {
        if (endpoint == null || model == null || model.trim().isEmpty()
                || model.length() > 128 || model.indexOf('\n') >= 0
                || model.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid embedding model");
        }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (endpoint.toString() + "\n" + model.trim()).getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder(64);
            for (final byte b : digest) {
                result.append(Character.forDigit((b >> 4) & 15, 16))
                        .append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException unexpected) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static float[][] embed(final URL endpoint, final String model, final String apiKey,
                                  final List<String> inputs) throws IOException {
        modelKey(endpoint, model);
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Embedding input count exceeds bounds");
        }
        final JSONArray payload = new JSONArray();
        for (final String text : inputs) {
            if (text == null || text.trim().isEmpty() || text.length() > 1200) {
                throw new IllegalArgumentException("Embedding content is missing or oversized");
            }
            payload.put(text);
        }
        if (apiKey != null && (apiKey.length() > 1024
                || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Invalid API key");
        }
        final byte[] body;
        try {
            body = new JSONObject().put("model", model.trim()).put("input", payload)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException unexpected) {
            throw new IOException("Could not serialize embedding request");
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IOException("Embedding request exceeds size limit");
        }
        final HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(90_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            final int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Embedding service returned HTTP " + status);
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                final byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()
                            || bytes.size() + n > MAX_RESPONSE_BYTES) {
                        throw new IOException("Embedding response cancelled or oversized");
                    }
                    bytes.write(buffer, 0, n);
                }
            }
            return parseResponse(new String(bytes.toByteArray(), StandardCharsets.UTF_8),
                    inputs.size());
        } finally {
            connection.disconnect();
        }
    }

    private static float[][] parseResponse(final String json, final int count) throws IOException {
        try {
            final JSONArray entries = new JSONObject(json).getJSONArray("data");
            if (entries.length() != count) {
                throw new IOException("Embedding service returned an incomplete batch");
            }
            final float[][] vectors = new float[count][];
            int expectedDimensions = -1;
            for (int i = 0; i < count; i++) {
                final JSONObject entry = entries.getJSONObject(i);
                final int index = entry.getInt("index");
                if (index < 0 || index >= count || vectors[index] != null) {
                    throw new IOException("Duplicate or out-of-range embedding index");
                }
                final JSONArray raw = entry.getJSONArray("embedding");
                if (raw.length() < KnowledgeEmbeddingVectors.MIN_DIMENSIONS
                        || raw.length() > KnowledgeEmbeddingVectors.MAX_DIMENSIONS
                        || (expectedDimensions != -1 && raw.length() != expectedDimensions)) {
                    throw new IOException("Embedding dimensionality mismatch");
                }
                expectedDimensions = raw.length();
                final float[] v = new float[raw.length()];
                for (int j = 0; j < v.length; j++) {
                    v[j] = (float) raw.getDouble(j);
                }
                vectors[index] = KnowledgeEmbeddingVectors.normalize(v);
            }
            return vectors;
        } catch (JSONException | IllegalArgumentException malformed) {
            throw new IOException("Invalid embedding response", malformed);
        }
    }
}

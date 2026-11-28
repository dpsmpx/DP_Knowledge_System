/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * One-shot comparison of an audited local E5 ONNX file and a manually pasted
 * independent host ORT metadata-only result. Never stores trust or readiness.
 */
final class KnowledgeE5OnnxReferenceComparison {
    enum Verdict { MATCH, DIFFERENT_WEIGHTS, DIFFERENT_DESCRIPTOR, DIFFERENT_COUNTS }

    static final int MAX_HOST_JSON_CHARS = 2048;
    private static final String HOST_SCHEMA = "dpks-e5-io-reference-v1";

    /** Strict, short, data-only output of the opt-in Python --metadata-only tool. */
    static final class HostMetadata {
        final String manifestSha256;
        final String weightsSha256;
        final String descriptorSha256;
        final int inputs;
        final int outputs;
        final String runtimeVersion;

        private HostMetadata(final String manifest, final String weights,
                             final String descriptor, final int inputCount,
                             final int outputCount, final String version) {
            manifestSha256 = manifest;
            weightsSha256 = weights;
            descriptorSha256 = descriptor;
            inputs = inputCount;
            outputs = outputCount;
            runtimeVersion = version;
        }
    }

    private KnowledgeE5OnnxReferenceComparison() { }

    static HostMetadata parseHostMetadata(final String json) throws IOException {
        if (json == null || json.length() < 2 || json.length() > MAX_HOST_JSON_CHARS) {
            throw new IOException("E5 host metadata report outside size limit");
        }
        requireUniqueLiteralKeys(json);
        try {
            final JSONTokener tokener = new JSONTokener(json);
            final Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject)) {
                throw new IOException("E5 host metadata root must be a JSON object");
            }
            for (char trailing = tokener.next(); trailing != 0; trailing = tokener.next()) {
                if (!Character.isWhitespace(trailing)) {
                    throw new IOException("Trailing E5 host metadata content");
                }
            }
            final JSONObject object = (JSONObject) parsed;
            final Set<String> expected = new HashSet<>();
            for (final String key : new String[]{"schema", "manifest_sha256",
                    "model_sha256", "e5_io_descriptor_sha256", "inputs", "outputs",
                    "onnxruntime_version"}) expected.add(key);
            final Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                if (!expected.remove(keys.next())) {
                    throw new IOException("Unexpected E5 host metadata field");
                }
            }
            if (!expected.isEmpty() || !HOST_SCHEMA.equals(object.get("schema"))) {
                throw new IOException("Incomplete E5 host metadata-only report");
            }
            final String manifest = requireHash(object, "manifest_sha256");
            final String weights = requireHash(object, "model_sha256");
            final String descriptor = requireHash(object, "e5_io_descriptor_sha256");
            final int inputs = requireCount(object, "inputs", 2, 3);
            final int outputs = requireCount(object, "outputs", 1, 2);
            final Object version = object.get("onnxruntime_version");
            if (!(version instanceof String)
                    || !((String) version).matches("[0-9][A-Za-z0-9.+_-]{0,63}")) {
                throw new IOException("Invalid E5 host ONNX Runtime version");
            }
            return new HostMetadata(manifest, weights, descriptor,
                    inputs, outputs, (String) version);
        } catch (JSONException invalid) {
            throw new IOException("Malformed E5 host metadata JSON", invalid);
        }
    }

    /** Do not depend on platform org.json's varying duplicate-key behavior. */
    private static void requireUniqueLiteralKeys(final String json) throws IOException {
        final Set<String> keys = new HashSet<>();
        int depth = 0;
        for (int index = 0; index < json.length(); index++) {
            final char current = json.charAt(index);
            if (current == '"') {
                final int start = index + 1;
                boolean escaped = false;
                int end = start;
                for (; end < json.length(); end++) {
                    final char next = json.charAt(end);
                    if (escaped) escaped = false;
                    else if (next == '\\') escaped = true;
                    else if (next == '"') break;
                }
                if (end == json.length()) throw new IOException("Unterminated E5 metadata string");
                int following = end + 1;
                while (following < json.length()
                        && Character.isWhitespace(json.charAt(following))) following++;
                if (depth == 1 && following < json.length()
                        && json.charAt(following) == ':') {
                    final String key = json.substring(start, end);
                    if (key.indexOf('\\') >= 0 || !keys.add(key)) {
                        throw new IOException("Duplicate or escaped E5 host metadata key");
                    }
                }
                index = end;
            } else if (current == '{' || current == '[') depth++;
            else if (current == '}' || current == ']') depth--;
        }
    }

    private static String requireHash(final JSONObject source, final String name)
            throws JSONException, IOException {
        final Object value = source.get(name);
        if (!(value instanceof String) || !validSha256((String) value)) {
            throw new IOException("Invalid E5 host metadata digest");
        }
        return (String) value;
    }

    private static int requireCount(final JSONObject source, final String name,
                                    final int min, final int max)
            throws JSONException, IOException {
        final Object value = source.get(name);
        if (!(value instanceof Number) || !value.toString().matches("[0-9]{1,2}")) {
            throw new IOException("Invalid E5 host metadata tensor count");
        }
        final int count = ((Number) value).intValue();
        if (count < min || count > max) {
            throw new IOException("Unsupported E5 host metadata tensor count");
        }
        return count;
    }

    /** Host evidence is user-supplied; match does not certify provenance or readiness. */
    static Verdict compareHostMetadata(final String localWeightsSha,
                                       final String localDescriptorSha,
                                       final int localInputs, final int localOutputs,
                                       final HostMetadata host) {
        if (host == null || localInputs < 2 || localInputs > 3
                || localOutputs < 1 || localOutputs > 2) {
            throw new IllegalArgumentException("Missing local E5 graph report");
        }
        final Verdict hashes = compare(localWeightsSha, localDescriptorSha,
                host.weightsSha256, host.descriptorSha256);
        if (hashes != Verdict.MATCH) return hashes;
        return host.inputs == localInputs && host.outputs == localOutputs
                ? Verdict.MATCH : Verdict.DIFFERENT_COUNTS;
    }

    static Verdict compare(final String localWeightsSha, final String localDescriptorSha,
                           final String referenceWeightsSha, final String referenceDescriptorSha) {
        if (!validSha256(localWeightsSha) || !validSha256(localDescriptorSha)
                || !validSha256(referenceWeightsSha) || !validSha256(referenceDescriptorSha)) {
            throw new IllegalArgumentException("Invalid E5 ONNX reference digest");
        }
        if (!localWeightsSha.equals(referenceWeightsSha)) return Verdict.DIFFERENT_WEIGHTS;
        return localDescriptorSha.equals(referenceDescriptorSha)
                ? Verdict.MATCH : Verdict.DIFFERENT_DESCRIPTOR;
    }

    static boolean validSha256(final String sha) {
        if (sha == null || sha.length() != 64) return false;
        for (int i = 0; i < sha.length(); i++) {
            final char value = sha.charAt(i);
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) return false;
        }
        return true;
    }
}

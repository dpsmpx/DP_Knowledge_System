/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public final class KnowledgeE5OnnxReferenceComparisonTest {
    private static final String WEIGHTS = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DESCRIPTOR = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String OTHER = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    private static String hostJson() {
        return "{\"schema\":\"dpks-e5-io-reference-v1\","
                + "\"manifest_sha256\":\"" + OTHER + "\","
                + "\"model_sha256\":\"" + WEIGHTS + "\","
                + "\"e5_io_descriptor_sha256\":\"" + DESCRIPTOR + "\","
                + "\"inputs\":2,\"outputs\":1,\"onnxruntime_version\":\"1.31.1\"}";
    }

    private static void rejectHost(final String json) {
        try {
            KnowledgeE5OnnxReferenceComparison.parseHostMetadata(json);
            fail("Untrusted E5 host report accepted");
        } catch (IOException expected) { }
    }

    @Test public void sameVerifiedWeightsAndIoMatch() {
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.MATCH,
                KnowledgeE5OnnxReferenceComparison.compare(WEIGHTS, DESCRIPTOR,
                        WEIGHTS, DESCRIPTOR));
    }

    @Test public void differentWeightsTakePrecedenceOverDescriptor() {
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_WEIGHTS,
                KnowledgeE5OnnxReferenceComparison.compare(WEIGHTS, DESCRIPTOR,
                        OTHER, OTHER));
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_DESCRIPTOR,
                KnowledgeE5OnnxReferenceComparison.compare(WEIGHTS, DESCRIPTOR,
                        WEIGHTS, OTHER));
    }

    @Test public void malformedLocalOrPastedDigestIsNeverAComparison() {
        for (final String invalid : new String[]{null, "", "a", WEIGHTS.toUpperCase(),
                "g" + WEIGHTS.substring(1), WEIGHTS + "0", " " + WEIGHTS,
                WEIGHTS + "\n"}) {
            assertFalse(KnowledgeE5OnnxReferenceComparison.validSha256(invalid));
            for (int position = 0; position < 4; position++) {
                final String[] values = {WEIGHTS, DESCRIPTOR, WEIGHTS, DESCRIPTOR};
                values[position] = invalid;
                try {
                    KnowledgeE5OnnxReferenceComparison.compare(
                            values[0], values[1], values[2], values[3]);
                    fail("Malformed hash accepted in position " + position);
                } catch (IllegalArgumentException expected) { }
            }
        }
        assertTrue(KnowledgeE5OnnxReferenceComparison.validSha256(WEIGHTS));
    }

    @Test public void strictMetadataOnlyJsonMatchesWeightsDescriptorAndCounts()
            throws Exception {
        final KnowledgeE5OnnxReferenceComparison.HostMetadata report =
                KnowledgeE5OnnxReferenceComparison.parseHostMetadata(hostJson());
        assertEquals(OTHER, report.manifestSha256);
        assertEquals("1.31.1", report.runtimeVersion);
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.MATCH,
                KnowledgeE5OnnxReferenceComparison.compareHostMetadata(
                        WEIGHTS, DESCRIPTOR, 2, 1, report));
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_WEIGHTS,
                KnowledgeE5OnnxReferenceComparison.compareHostMetadata(
                        OTHER, DESCRIPTOR, 2, 1, report));
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_DESCRIPTOR,
                KnowledgeE5OnnxReferenceComparison.compareHostMetadata(
                        WEIGHTS, OTHER, 2, 1, report));
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_COUNTS,
                KnowledgeE5OnnxReferenceComparison.compareHostMetadata(
                        WEIGHTS, DESCRIPTOR, 3, 1, report));
        try {
            KnowledgeE5OnnxReferenceComparison.compareHostMetadata(
                    WEIGHTS, DESCRIPTOR, 2, 1, null);
            fail("Missing host report accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void hostJsonRejectsMalformedAndAmbiguousReports() {
        final String valid = hostJson();
        rejectHost(null);
        rejectHost("");
        rejectHost("x".repeat(KnowledgeE5OnnxReferenceComparison.MAX_HOST_JSON_CHARS + 1));
        rejectHost(valid.replace("dpks-e5-io-reference-v1", "unknown-schema"));
        rejectHost(valid.replace("\"outputs\":1", "\"outputs\":1,\"samples\":{}"));
        rejectHost(valid.replace("\"inputs\":2", "\"inputs\":2,\"inputs\":3"));
        rejectHost(valid.replace("\"inputs\":2", "\"\\u0069nputs\":2"));
        rejectHost(valid.replace("\"inputs\":2", "\"inputs\":2.0"));
        rejectHost(valid.replace("\"inputs\":2", "\"inputs\":4"));
        rejectHost(valid.replace("\"outputs\":1", "\"outputs\":3"));
        rejectHost(valid.replace("\"onnxruntime_version\":\"1.31.1\"",
                "\"onnxruntime_version\":\"release/1.31\""));
        rejectHost(valid.replace("\"manifest_sha256\":\"" + OTHER + "\"",
                "\"manifest_sha256\":\"" + OTHER.toUpperCase() + "\""));
        rejectHost(valid.replace("\"model_sha256\":\"" + WEIGHTS + "\"",
                "\"model_sha256\":42"));
        rejectHost(valid.replace("\"model_sha256\":\"" + WEIGHTS + "\"", ""));
        rejectHost(valid.substring(0, valid.length() - 1));
        rejectHost(valid + "true");
    }
}

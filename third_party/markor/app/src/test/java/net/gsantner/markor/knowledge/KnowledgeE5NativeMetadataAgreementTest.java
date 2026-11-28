/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Offline, pinned generated graphs only. No native ORT, user model, or notes. */
public final class KnowledgeE5NativeMetadataAgreementTest {
    private static KnowledgeE5OnnxMetadataProbe.Report declared(
            final int sequence, final boolean tokenTypes, final boolean pooler) throws Exception {
        final byte[] graph = KnowledgeE5TokenEmbeddingSmoke.graph(
                sequence, tokenTypes, pooler);
        final File file = Files.createTempFile("dpks-e5-agreement-", ".onnx").toFile();
        try {
            Files.write(file.toPath(), graph);
            return KnowledgeE5OnnxMetadataProbe.inspect(file, graph.length,
                    KnowledgeE5ConfigProfile.parse(KnowledgeE5ConfigProfileTest.model(), null),
                    KnowledgeOnnxSessionPipe.sha256Hex(graph));
        } finally {
            assertTrue(file.delete());
        }
    }

    private static KnowledgeOnnxIoSummary nativeReport(
            final KnowledgeE5OnnxMetadataProbe.Report report, final String digest) {
        return new KnowledgeOnnxIoSummary(report.inputs, report.outputs,
                0, report.outputs, report.inputs, 0, 0, 0, 2, 3, digest);
    }

    private static void reject(final KnowledgeE5OnnxMetadataProbe.Report declared,
                               final long inputs, final long outputs,
                               final KnowledgeOnnxIoSummary summary) throws Exception {
        try {
            KnowledgeE5NativeMetadataAgreement.requireMatching(
                    declared, inputs, outputs, summary);
            fail("Changed E5 metadata was accepted");
        } catch (IOException expected) { }
    }

    @Test public void bothPinnedE5VariantsPassExactIndependentMetadataAgreement()
            throws Exception {
        for (final boolean tokenTypes : new boolean[]{false, true}) {
            final KnowledgeE5OnnxMetadataProbe.Report report = declared(
                    6, tokenTypes, !tokenTypes);
            assertEquals(tokenTypes ? 3 : 2, report.inputs);
            assertEquals(tokenTypes ? 1 : 2, report.outputs);
            assertEquals(0, report.dynamicAxes);
            KnowledgeE5NativeMetadataAgreement.requireMatching(report,
                    report.inputs, report.outputs, nativeReport(report, report.descriptorSha256));
        }
    }

    @Test public void alteredNamesShapesTypesCountsAndRanksAllFailClosed()
            throws Exception {
        final KnowledgeE5OnnxMetadataProbe.Report report = declared(6, false, true);
        final KnowledgeOnnxIoSummary valid = nativeReport(report, report.descriptorSha256);
        final String changedSequence = declared(5, false, true).descriptorSha256;
        final String changedNamesAndOutputs = declared(6, true, false).descriptorSha256;
        assertNotEquals(report.descriptorSha256, changedSequence);
        assertNotEquals(report.descriptorSha256, changedNamesAndOutputs);
        reject(report, report.inputs, report.outputs, nativeReport(report, changedSequence));
        reject(report, report.inputs, report.outputs, nativeReport(report, changedNamesAndOutputs));
        reject(report, report.inputs, report.outputs, nativeReport(report, null));
        reject(report, report.inputs, report.outputs, nativeReport(report, "0".repeat(64)));
        reject(report, report.inputs + 1, report.outputs, valid);
        reject(report, report.inputs, report.outputs - 1, valid);
        reject(report, report.inputs, report.outputs,
                new KnowledgeOnnxIoSummary(1, 2, 0, 2, 1, 0, 0, 0, 2, 3,
                        report.descriptorSha256));
        reject(report, report.inputs, report.outputs,
                new KnowledgeOnnxIoSummary(2, 2, 1, 2, 1, 0, 0, 0, 2, 3,
                        report.descriptorSha256));
        reject(report, report.inputs, report.outputs,
                new KnowledgeOnnxIoSummary(2, 2, 0, 1, 2, 1, 0, 0, 2, 3,
                        report.descriptorSha256));
        reject(report, report.inputs, report.outputs,
                new KnowledgeOnnxIoSummary(2, 2, 0, 2, 2, 0, 1, 0, 2, 3,
                        report.descriptorSha256));
        reject(report, report.inputs, report.outputs,
                new KnowledgeOnnxIoSummary(2, 2, 0, 2, 2, 0, 0, 0, 3, 3,
                        report.descriptorSha256));
        reject(report, report.inputs, report.outputs,
                new KnowledgeOnnxIoSummary(2, 2, 0, 2, 2, 0, 0, 0, 2, 2,
                        report.descriptorSha256));
        reject(null, report.inputs, report.outputs, valid);
        reject(report, report.inputs, report.outputs, null);
    }

    @Test public void dynamicAxesMustMatchSeparatelyForInputsAndOutputs()
            throws Exception {
        final byte[] graph = KnowledgeE5OnnxMetadataProbeTest.model(384, false, false);
        final File file = Files.createTempFile("dpks-e5-dynamic-agreement-", ".onnx").toFile();
        final KnowledgeE5OnnxMetadataProbe.Report declared;
        try {
            Files.write(file.toPath(), graph);
            declared = KnowledgeE5OnnxMetadataProbe.inspect(file, graph.length,
                    KnowledgeE5ConfigProfile.parse(KnowledgeE5ConfigProfileTest.model(), null),
                    KnowledgeOnnxSessionPipe.sha256Hex(graph));
        } finally {
            assertTrue(file.delete());
        }
        assertEquals(6, declared.dynamicAxes);
        final KnowledgeOnnxIoSummary matching = new KnowledgeOnnxIoSummary(
                2, 1, 0, 1, 2, 0, 4, 2, 2, 3, declared.descriptorSha256);
        KnowledgeE5NativeMetadataAgreement.requireMatching(declared, 2, 1, matching);
        reject(declared, 2, 1, new KnowledgeOnnxIoSummary(
                2, 1, 0, 1, 2, 0, 3, 3, 2, 3, declared.descriptorSha256));
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Pure Java contract tests. Native execution is deliberately Android-only. */
public final class KnowledgeOnnxSmokeTest {
    @Test public void generatedIdentityModelHasFixedBoundedOnnxStructure() throws Exception {
        final byte[] first = KnowledgeOnnxSmoke.identityModel();
        assertArrayEquals(first, KnowledgeOnnxSmoke.identityModel());
        assertTrue("Fixture is tiny and generated, never a bundled model", first.length < 512);
        assertTrue(first.length > 16);
        final File file = File.createTempFile("dpks-synthetic-identity-", ".onnx");
        try {
            Files.write(file.toPath(), first);
            final KnowledgeModelContainerProbe.Result parsed = KnowledgeModelContainerProbe.inspect(
                    file, KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
            assertEquals(KnowledgeModelContainerProbe.State.METADATA_HINTS, parsed.state);
            assertEquals(KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ,
                    parsed.detailState);
            assertEquals(9, parsed.version);
            assertEquals(1, parsed.opsetDeclarations);
            assertEquals(1, parsed.graphNodes);
            assertEquals(1, parsed.graphInputs);
            assertEquals(1, parsed.graphOutputs);
            assertEquals(1, parsed.typedInputs);
            assertEquals(1, parsed.typedOutputs);
            assertEquals(0, parsed.externalDataTensorsDeclared);
            assertTrue(parsed.operatorInventoryHints.contains("Identity"));
            assertEquals(0, parsed.invalidExternalDataRanges);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test public void inputOutputContractNeverDerivesFromUserData() {
        assertEquals(1.25f, KnowledgeOnnxSmoke.INPUT, 0.0f);
        assertEquals(KnowledgeOnnxSmoke.INPUT, KnowledgeOnnxSmoke.EXPECTED_OUTPUT, 0.0f);
    }
}

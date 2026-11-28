/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeE5ResourceEstimateTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void exactTensorBytesAndNormalRamAreReportedWithoutModelLoading() {
        final KnowledgeE5ResourceEstimate.Plan two = KnowledgeE5ResourceEstimate.assess(
                480 * MIB, 0, 512, false, 4096 * MIB, false);
        assertEquals(512 * 2 * Long.BYTES, two.inputTensorBytes);
        assertEquals(512 * 384 * Float.BYTES, two.hiddenTensorBytes);
        assertFalse(two.memoryDenied);
        assertFalse(two.memoryWarning);

        final KnowledgeE5ResourceEstimate.Plan three = KnowledgeE5ResourceEstimate.assess(
                480 * MIB, 0, 512, true, 4096 * MIB, false);
        assertEquals(512 * 3 * Long.BYTES, three.inputTensorBytes);
        assertEquals(two.hiddenTensorBytes, three.hiddenTensorBytes);
        assertTrue(three.estimatedWorkingSetBytes > two.estimatedWorkingSetBytes);
    }

    @Test public void LowRamDenialIsIndependentOfWeightSize() {
        assertTrue(KnowledgeE5ResourceEstimate.assess(64, 0, 3, false,
                511 * MIB, false).memoryDenied);
        assertTrue(KnowledgeE5ResourceEstimate.assess(64, 0, 3, false,
                4 * 1024 * MIB, true).memoryDenied);
        assertFalse(KnowledgeE5ResourceEstimate.assess(64, 0, 3, false,
                512 * MIB, false).memoryDenied);
    }

    @Test public void LargeUserChosenModelGetsWarningNotArbitrarySizeBlock() {
        final KnowledgeE5ResourceEstimate.Plan plan = KnowledgeE5ResourceEstimate.assess(
                6L * 1024 * MIB, 2L * 1024 * MIB, 512, true,
                4L * 1024 * MIB, false);
        assertFalse(plan.memoryDenied);
        assertTrue(plan.memoryWarning);
        assertTrue(plan.estimatedWorkingSetBytes > plan.availableBytes);
    }

    @Test public void curatedEstimateOnlyAppliesToKnownE5VariantsBeforeDownload() {
        final KnowledgeModelCatalog.Artifact o4 = KnowledgeModelCatalog.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING).get(0);
        final KnowledgeModelCatalog.Artifact fp32 = KnowledgeModelCatalog.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING).get(1);
        final KnowledgeE5ResourceEstimate.Plan smaller = KnowledgeE5ResourceEstimate.assessCurated(
                o4, 4 * 1024 * MIB, false);
        final KnowledgeE5ResourceEstimate.Plan bigger = KnowledgeE5ResourceEstimate.assessCurated(
                fp32, 4 * 1024 * MIB, false);
        assertNotNull(smaller);
        assertNotNull(bigger);
        assertTrue(smaller.estimatedWorkingSetBytes < bigger.estimatedWorkingSetBytes);
        assertEquals(512 * 3 * Long.BYTES, smaller.inputTensorBytes);
        assertFalse(smaller.memoryWarning);
        assertTrue(KnowledgeE5ResourceEstimate.assessCurated(
                fp32, 512 * MIB, false).memoryWarning);
        assertTrue(KnowledgeE5ResourceEstimate.assessCurated(
                o4, 4 * 1024 * MIB, true).memoryDenied);
        assertNull(KnowledgeE5ResourceEstimate.assessCurated(null, 4 * 1024 * MIB, false));
        assertNull(KnowledgeE5ResourceEstimate.assessCurated(
                KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.GENERATION).get(0),
                4 * 1024 * MIB, false));
    }

    @Test public void RejectsInvalidAndOverflowingCalculations() {
        for (final int seq : new int[]{0, 2, 513}) {
            try {
                KnowledgeE5ResourceEstimate.assess(16, 0, seq, false, 1024 * MIB, false);
                fail("Invalid E5 sequence accepted");
            } catch (IllegalArgumentException expected) { }
        }
        for (final long model : new long[]{0, 15, Long.MAX_VALUE}) {
            try {
                KnowledgeE5ResourceEstimate.assess(model, 0, 3, false,
                        1024 * MIB, false);
                fail("Invalid/overflowing model size accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            KnowledgeE5ResourceEstimate.assess(16, -1, 3, false, 1024 * MIB, false);
            fail("Negative external tensor size accepted");
        } catch (IllegalArgumentException expected) { }
    }
}

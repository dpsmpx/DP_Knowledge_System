/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Read-only comparisons; no inference engine, Android device or network needed. */
public final class KnowledgeModelComparisonTest {
    @Test public void comparesMultipleVariantsWithoutCallingAnyModel() {
        final List<KnowledgeModelCatalog.Artifact> catalog =
                KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.EMBEDDING);
        final KnowledgeModelCatalog.Artifact small = catalog.get(0);
        final KnowledgeModelFiles.Entry installed = new KnowledgeModelFiles.Entry(
                KnowledgeModelPolicy.Role.EMBEDDING,
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                small.expectedSha256, "existing.onnx", 240_000_000L);
        final List<KnowledgeModelComparison.Row> compared = KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING, Collections.singletonList(installed),
                small.expectedSha256, 300_000_000L, 300_000_000L);
        assertEquals(2, compared.size());
        assertSame(small, compared.get(0).artifact);
        assertTrue(compared.get(0).installed);
        assertTrue(compared.get(0).preferred);
        assertEquals(240_000_000L, compared.get(0).weightBytes);
        assertEquals(KnowledgeModelComparison.Storage.INSTALLED, compared.get(0).storage);
        assertEquals(KnowledgeModelComparison.Ram.WEIGHT_WITHIN_SNAPSHOT,
                compared.get(0).ram);
        assertEquals(80, compared.get(0).weightPercentOfAvailableRam);

        assertFalse(compared.get(1).installed);
        assertFalse(compared.get(1).preferred);
        assertEquals(470_000_000L, compared.get(1).weightBytes);
        assertEquals(KnowledgeModelComparison.Storage.MAY_NOT_FIT,
                compared.get(1).storage);
        assertEquals(KnowledgeModelComparison.Ram.WEIGHT_ABOVE_SNAPSHOT,
                compared.get(1).ram);
        assertEquals(157, compared.get(1).weightPercentOfAvailableRam);
        try {
            compared.clear();
            fail("Comparison snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void storageReserveAndUnknownSnapshotsAreConservative() {
        final KnowledgeModelCatalog.Artifact generation = KnowledgeModelCatalog.forRole(
                KnowledgeModelPolicy.Role.GENERATION).get(0);
        final long estimatedBytes = generation.approximateMegabytes * 1_000_000L;
        final KnowledgeModelComparison.Row enough = KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.GENERATION, Collections.emptyList(),
                generation.expectedSha256,
                estimatedBytes + KnowledgeModelComparison.STORAGE_RESERVE_BYTES,
                estimatedBytes).get(0);
        assertEquals(KnowledgeModelComparison.Storage.ESTIMATED_FITS, enough.storage);
        assertEquals(KnowledgeModelComparison.Ram.WEIGHT_WITHIN_SNAPSHOT, enough.ram);
        assertEquals(100, enough.weightPercentOfAvailableRam);
        assertFalse("A saved preference cannot select a missing file", enough.preferred);

        final KnowledgeModelComparison.Row low = KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.GENERATION, Collections.emptyList(), "",
                estimatedBytes + KnowledgeModelComparison.STORAGE_RESERVE_BYTES - 1,
                estimatedBytes - 1).get(0);
        assertEquals(KnowledgeModelComparison.Storage.MAY_NOT_FIT, low.storage);
        assertEquals(KnowledgeModelComparison.Ram.WEIGHT_ABOVE_SNAPSHOT, low.ram);

        final KnowledgeModelComparison.Row unavailable = KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.GENERATION, Collections.emptyList(), "", 0, -1).get(0);
        assertEquals(KnowledgeModelComparison.Storage.UNKNOWN, unavailable.storage);
        assertEquals(KnowledgeModelComparison.Ram.UNKNOWN, unavailable.ram);
        assertEquals(-1, unavailable.weightPercentOfAvailableRam);
    }

    @Test public void installedMatchesRoleAndDigestAndAllFourRolesRemainOptional() {
        final KnowledgeModelCatalog.Artifact embedding = KnowledgeModelCatalog.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING).get(0);
        final KnowledgeModelFiles.Entry differentRole = new KnowledgeModelFiles.Entry(
                KnowledgeModelPolicy.Role.RERANKING,
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                embedding.expectedSha256, "other.onnx", 235_000_000L);
        final List<KnowledgeModelFiles.Entry> shelf = Arrays.asList(differentRole);
        assertFalse(KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING, shelf, embedding.expectedSha256,
                500_000_000L, 500_000_000L).get(0).installed);
        final List<KnowledgeModelComparison.Row> reranking = KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.RERANKING, shelf, differentRole.sha256, 1, 1);
        assertEquals(3, reranking.size());
        assertNull("Custom file is not falsely treated as curated", reranking.get(2).artifact);
        assertSame(differentRole, reranking.get(2).installedEntry);
        assertEquals("other.onnx", reranking.get(2).title);
        assertTrue(reranking.get(2).preferred);
        assertEquals(KnowledgeModelComparison.Storage.INSTALLED, reranking.get(2).storage);
        assertEquals(3, KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.GENERATION, shelf, "", 1, 1).size());
        assertTrue(KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.PERSONALIZATION, shelf, "", 1, 1).isEmpty());
        final KnowledgeModelFiles.Entry personal = new KnowledgeModelFiles.Entry(
                KnowledgeModelPolicy.Role.PERSONALIZATION,
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                embedding.expectedSha256, "my_classifier.onnx", 100_000L);
        final List<KnowledgeModelComparison.Row> personalRows = KnowledgeModelComparison.forRole(
                KnowledgeModelPolicy.Role.PERSONALIZATION,
                Collections.singletonList(personal), personal.sha256, 1, 1);
        assertEquals(1, personalRows.size());
        assertSame(personal, personalRows.get(0).installedEntry);
        assertTrue(personalRows.get(0).preferred);
    }
}

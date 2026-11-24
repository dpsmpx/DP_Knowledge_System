/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class KnowledgeModelPackageContractTest {
    @Test public void knownEmbeddingWeightsStillRequireTokenizerAndBackend() {
        final KnowledgeModelCatalog.Artifact artifact =
                KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.EMBEDDING).get(0);
        final KnowledgeModelFiles.Entry file = new KnowledgeModelFiles.Entry(
                artifact.role, KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                artifact.expectedSha256, artifact.filename, 235_000_000);
        final KnowledgeModelPackageContract.Report report =
                KnowledgeModelPackageContract.inspect(file);
        assertEquals(1, report.schemaVersion);
        assertTrue(report.curatedWeight);
        assertEquals(artifact.id, report.sourceId);
        assertEquals("MIT", report.license);
        assertEquals(artifact.expectedSha256, report.weightSha256);
        assertFalse(report.runnable());
        contains(report, KnowledgeModelPackageContract.Requirement.PINNED_REVISION);
        contains(report, KnowledgeModelPackageContract.Requirement.TOKENIZER);
        contains(report, KnowledgeModelPackageContract.Requirement.MODEL_CONFIG);
        contains(report, KnowledgeModelPackageContract.Requirement.EMBEDDING_POOLING);
        contains(report, KnowledgeModelPackageContract.Requirement.VALIDATED_ANDROID_BACKEND);
        assertFalse(report.missing.contains(
                KnowledgeModelPackageContract.Requirement.LICENSE_REVIEW));
    }

    @Test public void knownGgufGenerationStillRequiresTemplateAndBackend() {
        final KnowledgeModelCatalog.Artifact artifact =
                KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.GENERATION).get(0);
        final KnowledgeModelPackageContract.Report report =
                KnowledgeModelPackageContract.inspect(new KnowledgeModelFiles.Entry(
                        artifact.role, KnowledgeModelFiles.Format.GGUF, artifact.expectedSha256,
                        artifact.filename, 397_000_000));
        assertTrue(report.curatedWeight);
        assertFalse(report.runnable());
        contains(report, KnowledgeModelPackageContract.Requirement.CHAT_TEMPLATE);
        contains(report, KnowledgeModelPackageContract.Requirement.TOKENIZER);
        assertFalse(report.missing.contains(
                KnowledgeModelPackageContract.Requirement.MODEL_CONFIG));
        try {
            report.missing.clear();
            fail("Cannot mutate package completeness evidence");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void userWeightsAreUnverifiedAndWrongRoleFormatIsExplicit() {
        final String randomSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final KnowledgeModelPackageContract.Report personal =
                KnowledgeModelPackageContract.inspect(new KnowledgeModelFiles.Entry(
                        KnowledgeModelPolicy.Role.PERSONALIZATION,
                        KnowledgeModelFiles.Format.GGUF, randomSha, "custom.gguf", 1234));
        assertFalse(personal.curatedWeight);
        assertEquals("unverified", personal.license);
        assertFalse(personal.runnable());
        contains(personal, KnowledgeModelPackageContract.Requirement.LICENSE_REVIEW);
        contains(personal, KnowledgeModelPackageContract.Requirement.FEATURE_LABEL_SCHEMA);
        contains(personal, KnowledgeModelPackageContract.Requirement.UNSUPPORTED_ROLE_FORMAT);

        final KnowledgeModelCatalog.Artifact curated =
                KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.GENERATION).get(0);
        final KnowledgeModelPackageContract.Report disguised =
                KnowledgeModelPackageContract.inspect(new KnowledgeModelFiles.Entry(
                        curated.role, KnowledgeModelFiles.Format.ONNX_UNVERIFIED,
                        curated.expectedSha256, "disguised.onnx", 256));
        assertFalse("A digest alone cannot prove the format", disguised.curatedWeight);
        contains(disguised, KnowledgeModelPackageContract.Requirement.LICENSE_REVIEW);
    }

    @Test public void invalidIdentitiesNeverGetACompletenessReport() {
        try {
            KnowledgeModelPackageContract.inspect(new KnowledgeModelFiles.Entry(
                    KnowledgeModelPolicy.Role.EMBEDDING,
                    KnowledgeModelFiles.Format.GGUF, "not-a-sha", "fake.gguf", 16));
            fail("Invalid content identity accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            KnowledgeModelPackageContract.inspect(null);
            fail("Null package accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void contains(final KnowledgeModelPackageContract.Report report,
                                 final KnowledgeModelPackageContract.Requirement expected) {
        final List<KnowledgeModelPackageContract.Requirement> missing = report.missing;
        assertTrue("Required package part was not recorded: " + expected,
                missing.contains(expected));
    }
}

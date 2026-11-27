/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** No actual E5 weights/tokenizer, external files, inference, or network. */
public final class KnowledgeE5OfficialPackageTest {
    private static KnowledgeModelFiles.Entry weight(final int variant) {
        final KnowledgeModelCatalog.Artifact artifact = KnowledgeModelCatalog.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING).get(variant);
        return new KnowledgeModelFiles.Entry(KnowledgeModelPolicy.Role.EMBEDDING,
                KnowledgeModelFiles.Format.ONNX_UNVERIFIED, artifact.expectedSha256,
                artifact.filename, artifact.approximateMegabytes * 1024L * 1024L);
    }

    private static Map<String, Long> lengths() {
        final Map<String, Long> result = new HashMap<>();
        result.put("sentencepiece.bpe.model", 5_070_000L); // Synthetic declared size only.
        result.put("config.json", 653L);
        result.put("tokenizer_config.json", 443L);
        return result;
    }

    private static String sha(final byte[] bytes) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final char[] digits = "0123456789abcdef".toCharArray();
        final StringBuilder result = new StringBuilder();
        for (final byte b : digest) {
            result.append(digits[(b >>> 4) & 15]).append(digits[b & 15]);
        }
        return result.toString();
    }

    @Test public void localManifestIsDeterministicAndroidCompatibleAndNeverRunnable()
            throws Exception {
        for (int i = 0; i < 2; i++) {
            final KnowledgeModelFiles.Entry entry = weight(i);
            final byte[] first = KnowledgeE5OfficialPackage.manifestForVerifiedSizes(
                    entry, lengths());
            final byte[] second = KnowledgeE5OfficialPackage.manifestForVerifiedSizes(
                    entry, lengths());
            assertArrayEquals(first, second);
            final KnowledgeModelPackageManifest parsed = KnowledgeModelPackageManifest.parse(first);
            parsed.requireWeight(entry);
            assertEquals(1, parsed.schemaVersion);
            assertEquals(4, parsed.components.size());
            assertEquals("intfloat/multilingual-e5-small", parsed.sourceId);
            assertEquals("MIT", parsed.assertedLicense);
            assertTrue(parsed.assertedRevision.matches("sha256:[0-9a-f]{64}"));
            assertEquals(entry.sha256, parsed.weightSha256);
            assertEquals("config.json", parsed.components.get(2).name);
            assertEquals(KnowledgeModelPackageManifest.Kind.MODEL_CONFIG,
                    parsed.components.get(2).kind);
            assertEquals(KnowledgeModelPackageManifest.Kind.TOKENIZER,
                    parsed.components.get(3).kind);
            assertFalse(KnowledgeModelPackageContract.inspect(entry, parsed).runnable());
        }
        final KnowledgeModelPackageManifest left = KnowledgeModelPackageManifest.parse(
                KnowledgeE5OfficialPackage.manifestForVerifiedSizes(weight(0), lengths()));
        final KnowledgeModelPackageManifest right = KnowledgeModelPackageManifest.parse(
                KnowledgeE5OfficialPackage.manifestForVerifiedSizes(weight(1), lengths()));
        assertNotEquals(left.assertedRevision, right.assertedRevision);
    }

    @Test public void companionSizeAndWeightIdentityMustBeExact() throws Exception {
        final KnowledgeModelFiles.Entry original = weight(0);
        Map<String, Long> sizes = lengths();
        for (final String name : new String[]{"config.json", "tokenizer_config.json",
                "sentencepiece.bpe.model"}) {
            final Long value = sizes.remove(name);
            try {
                KnowledgeE5OfficialPackage.manifestForVerifiedSizes(original, sizes);
                fail("Missing companion accepted");
            } catch (IOException expected) { }
            sizes.put(name, value);
        }
        sizes.put("config.json", 652L);
        try {
            KnowledgeE5OfficialPackage.manifestForVerifiedSizes(original, sizes);
            fail("Wrong configuration size accepted");
        } catch (IOException expected) { }
        sizes = lengths();
        sizes.put("unexpected", 100L);
        try {
            KnowledgeE5OfficialPackage.manifestForVerifiedSizes(original, sizes);
            fail("Extra companion accepted");
        } catch (IOException expected) { }
        final KnowledgeModelFiles.Entry wrongRole = new KnowledgeModelFiles.Entry(
                KnowledgeModelPolicy.Role.GENERATION, original.format, original.sha256,
                original.name, original.bytes);
        try {
            KnowledgeE5OfficialPackage.manifestForVerifiedSizes(wrongRole, lengths());
            fail("Wrong model role accepted");
        } catch (IOException expected) { }
        final KnowledgeModelFiles.Entry untrustedWeight = new KnowledgeModelFiles.Entry(
                original.role, original.format, "a".repeat(64), original.name, original.bytes);
        try {
            KnowledgeE5OfficialPackage.manifestForVerifiedSizes(untrustedWeight, lengths());
            fail("Unrecognized ONNX weight accepted");
        } catch (IOException expected) { }
    }

    @Test public void sourceSelectionRejectsUnpinnedBytesAndDuplicatesWithoutOutput()
            throws Exception {
        final KnowledgeModelFiles.Entry entry = weight(0);
        final List<KnowledgeModelPackageStore.Source> chosen = new ArrayList<>();
        for (final KnowledgeModelCatalog.Companion companion :
                KnowledgeModelCatalog.e5Companions(entry.role, entry.sha256)) {
            chosen.add(new KnowledgeModelPackageStore.Source(companion.filename,
                    () -> new ByteArrayInputStream("synthetic invalid bytes".getBytes(
                            StandardCharsets.UTF_8))));
        }
        try {
            KnowledgeE5OfficialPackage.fromSelectedFiles(entry, chosen);
            fail("Unpinned companion was accepted");
        } catch (IOException expected) { }
        chosen.set(2, chosen.get(1));
        try {
            KnowledgeE5OfficialPackage.fromSelectedFiles(entry, chosen);
            fail("Duplicate selection accepted");
        } catch (IOException expected) { }
        chosen.remove(2);
        try {
            KnowledgeE5OfficialPackage.fromSelectedFiles(entry, chosen);
            fail("Incomplete selection accepted");
        } catch (IOException expected) { }
    }

    @Test public void boundedStreamingHashRejectsTamperingOversizeAndCancellation()
            throws Exception {
        final byte[] payload = "known synthetic bytes".getBytes(StandardCharsets.UTF_8);
        final KnowledgeModelPackageStore.Source source = new KnowledgeModelPackageStore.Source(
                "config.json", () -> new ByteArrayInputStream(payload));
        assertEquals(payload.length, KnowledgeE5OfficialPackage.verify(
                source, sha(payload), payload.length));
        try {
            KnowledgeE5OfficialPackage.verify(source, "0".repeat(64), payload.length);
            fail("Mismatched digest accepted");
        } catch (IOException expected) { }
        try {
            KnowledgeE5OfficialPackage.verify(source, sha(payload), payload.length - 1);
            fail("Oversized selected input accepted");
        } catch (IOException expected) { }
        Thread.currentThread().interrupt();
        try {
            KnowledgeE5OfficialPackage.verify(source, sha(payload), payload.length);
            fail("Interrupted selection accepted");
        } catch (InterruptedIOException expected) { }
        finally { Thread.interrupted(); }
    }
}

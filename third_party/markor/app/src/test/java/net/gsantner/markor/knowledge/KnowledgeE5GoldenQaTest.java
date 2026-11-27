/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeE5GoldenQaTest {
    @Test public void shaMatchesIndependentPythonReferenceFixture() {
        assertArrayEquals(new String[]{"emoji_query", "en_query", "ru_query", "zh_passage"},
                KnowledgeE5GoldenQa.NAMES);
        final int[][] ids = {{0, 10, 2}, {0, 11, 2}, {0, 12, 2}, {0, 13, 2}};
        assertEquals("212e27cf4da57fe4795cfedcb37c7e208810e8063953dfefab92bbb651110d4c",
                KnowledgeE5GoldenQa.fingerprint(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ids));
        ids[0][1] = 14;
        assertNotEquals("212e27cf4da57fe4795cfedcb37c7e208810e8063953dfefab92bbb651110d4c",
                KnowledgeE5GoldenQa.fingerprint(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ids));
    }

    @Test public void refusesMissingBadOrOverlengthSamples() {
        final String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        for (final int[][] value : new int[][][]{null, {}, {{0, 2}},
                {{0, -1, 2}, {0, 4, 2}, {0, 5, 2}, {0, 6, 2}}}) {
            try {
                KnowledgeE5GoldenQa.fingerprint(hash, value);
                fail("Invalid golden QA fixture accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void manualOfflineComparisonRequiresSameFileAndGoldenIds() {
        final String tokenizer = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final String fingerprint = "212e27cf4da57fe4795cfedcb37c7e208810e8063953dfefab92bbb651110d4c";
        final KnowledgeE5GoldenQa.Report local = new KnowledgeE5GoldenQa.Report(
                tokenizer, fingerprint, new int[]{3, 3, 3, 3}, 10L);
        assertEquals(KnowledgeE5GoldenQa.ReferenceVerdict.MATCH,
                KnowledgeE5GoldenQa.compareReference(local, tokenizer, fingerprint));
        assertEquals(KnowledgeE5GoldenQa.ReferenceVerdict.DIFFERENT_TOKENIZER,
                KnowledgeE5GoldenQa.compareReference(local, "b" + tokenizer.substring(1),
                        fingerprint));
        assertEquals(KnowledgeE5GoldenQa.ReferenceVerdict.DIFFERENT_TOKEN_IDS,
                KnowledgeE5GoldenQa.compareReference(local, tokenizer,
                        "0" + fingerprint.substring(1)));
        // No mutable readiness flag or model package store is involved.
        assertEquals(fingerprint, local.fingerprintSha256);
    }

    @Test public void malformedOfflineReferenceNeverCountsAsMismatchOrMatch() {
        final String tokenizer = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final String fingerprint = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        final KnowledgeE5GoldenQa.Report local = new KnowledgeE5GoldenQa.Report(
                tokenizer, fingerprint, new int[]{3, 3, 3, 3}, 0L);
        for (final String invalid : new String[]{"", "0", tokenizer.toUpperCase(),
                "g" + tokenizer.substring(1), tokenizer + "0", " " + tokenizer,
                tokenizer + "\n"}) {
            try {
                KnowledgeE5GoldenQa.compareReference(local, invalid, fingerprint);
                fail("Malformed tokenizer SHA accepted");
            } catch (IllegalArgumentException expected) { }
            try {
                KnowledgeE5GoldenQa.compareReference(local, tokenizer, invalid);
                fail("Malformed fingerprint SHA accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            KnowledgeE5GoldenQa.compareReference(null, tokenizer, fingerprint);
            fail("Missing Android QA result accepted");
        } catch (IllegalArgumentException expected) { }
    }
}

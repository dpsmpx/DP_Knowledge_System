/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class KnowledgeHybridRankingTest {
    @Test
    public void agreementOutranksIndividualListsAndDuplicatesDoNotInflateScores() {
        final List<String> ordered = KnowledgeHybridRanking.combine(
                Arrays.asList("a.md", "shared.md", "a.md", "lexical.md"),
                Arrays.asList("semantic.md", "shared.md", "shared.md"), 6);
        assertEquals("shared.md", ordered.get(0));
        assertEquals(4, ordered.size());
        assertEquals(1, Collections.frequency(ordered, "shared.md"));
    }

    @Test
    public void deterministicTieAndMissingModelFallback() {
        assertEquals(Arrays.asList("a.md", "z.md"), KnowledgeHybridRanking.combine(
                Collections.singletonList("z.md"), Collections.singletonList("a.md"), 2));
        assertEquals(Arrays.asList("first.md", "second.md"), KnowledgeHybridRanking.combine(
                Arrays.asList("first.md", "second.md"), Collections.emptyList(), 2));
        assertTrue(KnowledgeHybridRanking.combine(Collections.emptyList(),
                Collections.emptyList(), 4).isEmpty());
    }

    @Test
    public void rejectsUnboundedListsAndOutput() {
        try {
            KnowledgeHybridRanking.combine(Collections.emptyList(), Collections.emptyList(), 25);
            fail("Output limit should be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            KnowledgeHybridRanking.combine(Collections.nCopies(41, "same.md"),
                    Collections.emptyList(), 6);
            fail("Unbounded input should be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void balancedPreviewIncludesVerifiedSemanticSourceWithoutLosingTopTwo() {
        final List<String> verified = Arrays.asList(
                "lex-one.md", "lex-opposite.md", "lex-three.md",
                "semantic-only.md", "lex-four.md");
        assertEquals(Arrays.asList(0, 1, 3), KnowledgeHybridRanking.selectBalancedIndices(
                verified, Arrays.asList("lex-one.md", "lex-opposite.md", "lex-three.md", "lex-four.md"),
                Collections.singletonList("semantic-only.md"), 3));
        assertEquals(Arrays.asList(0, 1, 2), KnowledgeHybridRanking.selectBalancedIndices(
                Arrays.asList("shared.md", "lex-one.md", "semantic-only.md"),
                Arrays.asList("shared.md", "lex-one.md"),
                Arrays.asList("shared.md", "semantic-only.md"), 3));
        assertEquals(Arrays.asList(0, 1, 3), KnowledgeHybridRanking.selectBalancedIndices(
                Arrays.asList("sem-one.md", "sem-two.md", "sem-three.md", "lexical-only.md"),
                Collections.singletonList("lexical-only.md"),
                Arrays.asList("sem-one.md", "sem-two.md", "sem-three.md"), 3));
    }

    @Test
    public void balancedPreviewKeepsFusedRankWhenOneChannelMissingAndCapsReads() {
        assertEquals(Arrays.asList(0, 1, 2), KnowledgeHybridRanking.selectBalancedIndices(
                Arrays.asList("a.md", "b.md", "c.md"),
                Arrays.asList("a.md", "b.md", "c.md"), Collections.emptyList(), 3));
        final java.util.List<String> verified = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) verified.add("rank" + i + ".md");
        verified.add("outside.md");
        assertEquals(Arrays.asList(0, 1, 2), KnowledgeHybridRanking.selectBalancedIndices(
                verified, verified, Collections.singletonList("outside.md"), 3));
        try {
            KnowledgeHybridRanking.selectBalancedIndices(verified, verified,
                    Collections.emptyList(), 25);
            fail("Unbounded request must fail");
        } catch (IllegalArgumentException expected) {
            // Bounded by API contract.
        }
    }
}

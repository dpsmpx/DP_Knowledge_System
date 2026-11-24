/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded reciprocal-rank fusion of two independent, untrusted retrieval lists.
 * Ranking is relevance only: it never implies a fact, proof, or approval.
 */
public final class KnowledgeHybridRanking {
    private static final int RRF_K = 60;
    private static final int MAX_CANDIDATES = 40;
    public static final int MAX_VERIFIED_PREVIEW_CANDIDATES = 12;

    private KnowledgeHybridRanking() {
    }

    public static List<String> combine(final List<String> lexical,
                                       final List<String> semantic, final int limit) {
        if (lexical == null || semantic == null || limit < 1 || limit > 24
                || lexical.size() > MAX_CANDIDATES || semantic.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Invalid bounded hybrid retrieval inputs");
        }
        final Map<String, Double> scores = new HashMap<>();
        addRank(scores, lexical);
        addRank(scores, semantic);
        final List<String> sorted = new ArrayList<>(scores.keySet());
        Collections.sort(sorted, Comparator.comparingDouble((String key) -> scores.get(key))
                .reversed().thenComparing(Comparator.naturalOrder()));
        return new ArrayList<>(sorted.subList(0, Math.min(sorted.size(), limit)));
    }

    /** Preserve the two strongest RRF hits, then represent each retrieval
     * channel if a verified source from it exists in the bounded shortlist.
     * Remaining slots follow fused rank. This is source diversity, not an
     * assertion that one channel is more accurate or that a source is true.
     * The input must contain only byte-verified sources in original RRF order.
     */
    public static List<Integer> selectBalancedIndices(final List<String> verifiedPaths,
                                                       final List<String> lexical,
                                                       final List<String> semantic,
                                                       final int limit) {
        if (verifiedPaths == null || lexical == null || semantic == null
                || limit < 1 || limit > 24 || verifiedPaths.size() > MAX_CANDIDATES
                || lexical.size() > MAX_CANDIDATES || semantic.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Invalid bounded hybrid preview selection");
        }
        final int total = Math.min(verifiedPaths.size(), MAX_VERIFIED_PREVIEW_CANDIDATES);
        final int target = Math.min(total, limit);
        final List<Integer> picked = new ArrayList<>();
        final Set<String> lexicalSet = new HashSet<>(lexical);
        final Set<String> semanticSet = new HashSet<>(semantic);
        final boolean[] used = new boolean[total];
        for (int i = 0; i < Math.min(2, target); i++) {
            picked.add(i);
            used[i] = true;
        }
        if (picked.size() < target && !coversChannel(picked, verifiedPaths, semanticSet)) {
            chooseFirstFromChannel(picked, used, verifiedPaths, semanticSet, total);
        }
        if (picked.size() < target && !coversChannel(picked, verifiedPaths, lexicalSet)) {
            chooseFirstFromChannel(picked, used, verifiedPaths, lexicalSet, total);
        }
        for (int i = 0; i < total && picked.size() < target; i++) {
            if (!used[i]) picked.add(i);
        }
        Collections.sort(picked);
        return picked;
    }

    private static boolean coversChannel(final List<Integer> picked,
                                         final List<String> paths, final Set<String> channel) {
        for (final int index : picked) {
            if (channel.contains(paths.get(index))) return true;
        }
        return false;
    }

    private static void chooseFirstFromChannel(final List<Integer> picked,
                                               final boolean[] used, final List<String> paths,
                                               final Set<String> channel, final int total) {
        for (int i = 0; i < total; i++) {
            if (!used[i] && channel.contains(paths.get(i))) {
                picked.add(i);
                used[i] = true;
                return;
            }
        }
    }

    private static void addRank(final Map<String, Double> scores, final List<String> rows) {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            final String path = rows.get(i);
            if (path == null || path.isEmpty() || !seen.add(path)) continue;
            scores.put(path, scores.getOrDefault(path, 0.0) + 1.0 / (RRF_K + i + 1));
        }
    }
}

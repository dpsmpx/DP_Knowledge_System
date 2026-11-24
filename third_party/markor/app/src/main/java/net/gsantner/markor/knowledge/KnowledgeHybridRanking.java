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

    private static void addRank(final Map<String, Double> scores, final List<String> rows) {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            final String path = rows.get(i);
            if (path == null || path.isEmpty() || !seen.add(path)) continue;
            scores.put(path, scores.getOrDefault(path, 0.0) + 1.0 / (RRF_K + i + 1));
        }
    }
}

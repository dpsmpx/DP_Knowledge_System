/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

/** Pure policy: incomplete scans or first visits are never change notifications. */
public final class KnowledgeBackgroundPolicy {
    private KnowledgeBackgroundPolicy() {
    }

    public static int changedNotes(final int indexed, final int removed) {
        return Math.max(0, indexed) + Math.max(0, removed);
    }

    public static boolean shouldNotify(final boolean userOptedIn,
                                       final boolean baselineForSameVault,
                                       final boolean completeTraversal,
                                       final int indexed,
                                       final int removed) {
        return userOptedIn && baselineForSameVault && completeTraversal
                && changedNotes(indexed, removed) > 0;
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Do not hand a source-fragment identifier to decision review unless the
 * immutable editor snapshot matches both the persisted Markdown and the live
 * buffer. Later source SHA and journal-head checks still run at commit.
 */
final class KnowledgeEditorReviewGuard {
    private KnowledgeEditorReviewGuard() { }

    static boolean matchesSavedSource(final String savedSource,
                                      final String selectionSnapshot,
                                      final String currentBuffer) {
        return savedSource != null && selectionSnapshot != null && currentBuffer != null
                && savedSource.equals(selectionSnapshot)
                && selectionSnapshot.equals(currentBuffer);
    }
}

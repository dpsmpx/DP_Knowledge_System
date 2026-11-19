/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeEditorReviewGuardTest {
    @Test public void identicalEmojiAndMarkdownAreEligible() {
        final String source = "# Note\n🎯 Марс является планетой.\n";
        assertTrue(KnowledgeEditorReviewGuard.matchesSavedSource(source, source, source));
    }

    @Test public void unsavedEditOrDiskChangeBlocksHandoff() {
        assertFalse(KnowledgeEditorReviewGuard.matchesSavedSource("saved", "saved", "edited"));
        assertFalse(KnowledgeEditorReviewGuard.matchesSavedSource("changed on disk", "saved", "saved"));
        assertFalse(KnowledgeEditorReviewGuard.matchesSavedSource("abc\r\n", "abc\n", "abc\n"));
    }

    @Test public void missingInputsNeverAuthorize() {
        assertFalse(KnowledgeEditorReviewGuard.matchesSavedSource(null, "a", "a"));
        assertFalse(KnowledgeEditorReviewGuard.matchesSavedSource("a", null, "a"));
        assertFalse(KnowledgeEditorReviewGuard.matchesSavedSource("a", "a", null));
    }
}

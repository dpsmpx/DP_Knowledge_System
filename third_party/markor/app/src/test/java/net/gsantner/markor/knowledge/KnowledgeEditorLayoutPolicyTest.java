package net.gsantner.markor.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KnowledgeEditorLayoutPolicyTest {
    @Test public void narrowPhoneKeepsVerticalBoundedDock() {
        final KnowledgeEditorLayoutPolicy policy = KnowledgeEditorLayoutPolicy.forWindow(393, 873, 393);
        assertFalse(policy.sideDock);
        assertEquals(0, policy.dockWidthDp);
        assertTrue(policy.phoneScrollDp >= 96 && policy.phoneScrollDp <= 200);
    }

    @Test public void shortLandscapePhoneIsNeverTreatedAsTablet() {
        assertFalse(KnowledgeEditorLayoutPolicy.forWindow(873, 393, 393).sideDock);
    }

    @Test public void largeTabletGetsBoundedSidePanel() {
        final KnowledgeEditorLayoutPolicy policy = KnowledgeEditorLayoutPolicy.forWindow(1280, 800, 800);
        assertTrue(policy.sideDock);
        assertEquals(360, policy.dockWidthDp);
    }

    @Test public void multiWindowNeverProducesNegativeSizes() {
        final KnowledgeEditorLayoutPolicy policy = KnowledgeEditorLayoutPolicy.forWindow(0, 0, 800);
        assertFalse(policy.sideDock);
        assertTrue(policy.phoneScrollDp >= 96);
        assertFalse(KnowledgeEditorLayoutPolicy.forWindow(720, 300, 800).sideDock);
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeWorkspaceNavigationTest {
    private KnowledgeWorkspaceNavigation fresh() {
        return new KnowledgeWorkspaceNavigation(10, 20, 30, 40);
    }

    @Test public void libraryIsDefaultAndOnlyFinishDestination() {
        final KnowledgeWorkspaceNavigation state = fresh();
        assertEquals(10, state.page());
        assertEquals(KnowledgeWorkspaceNavigation.BackAction.FINISH, state.backAction());
        assertFalse(state.openDecisionDetail());
    }

    @Test public void allFourTabsAreDirectlyReachableAndBackTargetsLibrary() {
        for (final int destination : new int[]{20, 30, 40}) {
            final KnowledgeWorkspaceNavigation state = fresh();
            assertEquals(destination, state.select(destination));
            assertEquals(KnowledgeWorkspaceNavigation.BackAction.LIBRARY, state.backAction());
            assertEquals(10, state.select(10));
            assertEquals(KnowledgeWorkspaceNavigation.BackAction.FINISH, state.backAction());
        }
    }

    @Test public void decisionDetailNeedsDecisionsTabAndBackOnlyClosesDetail() {
        final KnowledgeWorkspaceNavigation state = fresh();
        state.select(20);
        assertTrue(state.openDecisionDetail());
        assertFalse(state.openDecisionDetail());
        assertEquals(KnowledgeWorkspaceNavigation.BackAction.CLOSE_DETAIL, state.backAction());
        state.closeDecisionDetail();
        assertFalse(state.detailOpen());
        assertEquals(20, state.page());
        assertEquals(KnowledgeWorkspaceNavigation.BackAction.LIBRARY, state.backAction());
    }

    @Test public void pageChangesCloseAnyStaleDecisionDetail() {
        final KnowledgeWorkspaceNavigation state = fresh();
        state.select(20);
        assertTrue(state.openDecisionDetail());
        state.select(30);
        assertFalse(state.detailOpen());
        assertFalse(state.openDecisionDetail());
        assertEquals(30, state.page());
    }

    @Test public void unknownOrCorruptRestoredPageFallsBackToLibrary() {
        final KnowledgeWorkspaceNavigation state = fresh();
        state.select(20);
        state.openDecisionDetail();
        assertEquals(10, state.select(Integer.MIN_VALUE));
        assertFalse(state.detailOpen());
        assertEquals(KnowledgeWorkspaceNavigation.BackAction.FINISH, state.backAction());
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateDestinationsAreRejected() {
        new KnowledgeWorkspaceNavigation(10, 20, 20, 40);
    }
}

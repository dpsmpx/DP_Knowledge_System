/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Pure UI-state policy shared by the activity and synthetic device checks.
 * It never opens notes, runs analysis, or modifies the knowledge journal.
 */
final class KnowledgeWorkspaceNavigation {
    enum BackAction { CLOSE_DETAIL, LIBRARY, FINISH }

    private final int library;
    private final int decisions;
    private final int processes;
    private final int settings;
    private int page;
    private boolean detailOpen;

    KnowledgeWorkspaceNavigation(final int library, final int decisions,
                                 final int processes, final int settings) {
        if (library == decisions || library == processes || library == settings
                || decisions == processes || decisions == settings || processes == settings) {
            throw new IllegalArgumentException("Navigation destinations must be unique");
        }
        this.library = library;
        this.decisions = decisions;
        this.processes = processes;
        this.settings = settings;
        this.page = library;
    }

    int select(final int requestedPage) {
        page = requestedPage == decisions || requestedPage == processes
                || requestedPage == settings ? requestedPage : library;
        detailOpen = false;
        return page;
    }

    int page() {
        return page;
    }

    boolean openDecisionDetail() {
        if (page != decisions || detailOpen) return false;
        detailOpen = true;
        return true;
    }

    boolean detailOpen() {
        return detailOpen;
    }

    void closeDecisionDetail() {
        detailOpen = false;
    }

    BackAction backAction() {
        if (detailOpen) return BackAction.CLOSE_DETAIL;
        return page == library ? BackAction.FINISH : BackAction.LIBRARY;
    }
}

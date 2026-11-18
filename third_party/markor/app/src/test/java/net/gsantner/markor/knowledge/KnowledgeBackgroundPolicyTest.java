/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public class KnowledgeBackgroundPolicyTest {
    @Test
    public void firstScanNeverProducesChangeNotification() {
        assertFalse(KnowledgeBackgroundPolicy.shouldNotify(true, false, true, 1200, 0));
    }

    @Test
    public void onlyCompleteCommittedScanCanAnnounceChanges() {
        assertFalse(KnowledgeBackgroundPolicy.shouldNotify(true, true, false, 7, 0));
        assertFalse(KnowledgeBackgroundPolicy.shouldNotify(true, true, true, 0, 0));
        assertFalse(KnowledgeBackgroundPolicy.shouldNotify(false, true, true, 7, 0));
        assertTrue(KnowledgeBackgroundPolicy.shouldNotify(true, true, true, 3, 2));
        assertEquals(5, KnowledgeBackgroundPolicy.changedNotes(3, 2));
    }

    @Test
    public void corruptedNegativeCountersNeverTriggerAnAlert() {
        assertEquals(0, KnowledgeBackgroundPolicy.changedNotes(-2, -3));
        assertFalse(KnowledgeBackgroundPolicy.shouldNotify(true, true, true, -2, -3));
    }
}

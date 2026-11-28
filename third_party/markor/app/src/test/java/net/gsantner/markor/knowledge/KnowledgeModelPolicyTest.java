/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeModelPolicyTest {
    @Test public void allFourRolesRemainOptionalWithSeveralCandidates() {
        for (final KnowledgeModelPolicy.Role role : KnowledgeModelPolicy.Role.values()) {
            assertTrue(KnowledgeModelPolicy.candidates(role).size() >= 2);
            try {
                KnowledgeModelPolicy.candidates(role).clear();
                fail("Catalogue is mutable");
            } catch (UnsupportedOperationException expected) { }
            assertEquals(KnowledgeModelPolicy.Gate.OFF,
                    KnowledgeModelPolicy.gate(KnowledgeModelPolicy.Provider.OFF, true, true));
        }
    }

    @Test public void offlineCannotSilentlyFallbackToAnApi() {
        assertEquals(KnowledgeModelPolicy.Gate.NOT_CONFIGURED,
                KnowledgeModelPolicy.gate(KnowledgeModelPolicy.Provider.ANDROID, false, false));
        assertEquals(KnowledgeModelPolicy.Gate.NOT_IMPLEMENTED,
                KnowledgeModelPolicy.gate(KnowledgeModelPolicy.Provider.ANDROID, true, false));
        assertEquals(KnowledgeModelPolicy.Gate.READY_LOCAL,
                KnowledgeModelPolicy.gate(KnowledgeModelPolicy.Provider.ANDROID, true, true));
        assertEquals(KnowledgeModelPolicy.Gate.NEEDS_NETWORK_CONSENT,
                KnowledgeModelPolicy.gate(KnowledgeModelPolicy.Provider.PERSONAL_PC, true, false));
        assertFalse(KnowledgeModelPolicy.mayTransferNoteText(
                KnowledgeModelPolicy.Provider.ANDROID, true));
        assertFalse(KnowledgeModelPolicy.mayTransferNoteText(
                KnowledgeModelPolicy.Provider.CLOUD, false));
        assertTrue(KnowledgeModelPolicy.mayTransferNoteText(
                KnowledgeModelPolicy.Provider.PERSONAL_PC, true));
    }
}

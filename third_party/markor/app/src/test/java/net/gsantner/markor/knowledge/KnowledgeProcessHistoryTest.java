/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeProcessHistoryTest {
    @Test public void boundedEventLogRoundTripsInReverseChronologicalOrder() {
        final KnowledgeProcessHistory history = new KnowledgeProcessHistory();
        for (int n = 1; n <= 40; n++) {
            history.record(KnowledgeProcessHistory.Operation.SCAN,
                    n % 2 == 0 ? KnowledgeProcessHistory.Outcome.COMPLETED
                            : KnowledgeProcessHistory.Outcome.STARTED, n);
        }
        assertEquals(32, history.recent().size());
        assertEquals(40L, history.recent().get(0).timestamp);
        assertEquals(9L, history.recent().get(31).timestamp);
        final KnowledgeProcessHistory restored =
                KnowledgeProcessHistory.fromStored(history.serialize());
        assertEquals(history.serialize(), restored.serialize());
    }

    @Test public void ignoresUnknownOperationAndInjectedPrivatePath() {
        final KnowledgeProcessHistory restored = KnowledgeProcessHistory.fromStored(
                "123|SCAN|STARTED\n124|/private/note.md|COMPLETED\n125|AUDIT|UNKNOWN\n");
        assertEquals(1, restored.recent().size());
        assertEquals("123|SCAN|STARTED\n", restored.serialize());
    }

    @Test public void corruptedLargePreferenceDoesNotGrowUnbounded() {
        final StringBuilder invalid = new StringBuilder();
        for (int i = 0; i < 8000; i++) invalid.append('X');
        assertTrue(KnowledgeProcessHistory.fromStored(invalid.toString()).recent().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsArbitraryNullCategory() {
        new KnowledgeProcessHistory().record(null, KnowledgeProcessHistory.Outcome.STARTED, 1);
    }
}

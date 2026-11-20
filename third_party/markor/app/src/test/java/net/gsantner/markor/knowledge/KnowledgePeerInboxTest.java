/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure comparison only; no Android identity, vault, secret or account fixture. */
public class KnowledgePeerInboxTest {
    private static final String EVENT_A = "{\"seq\":1,\"id\":\"J:a\"}\n";
    private static final String EVENT_B = "{\"seq\":2,\"id\":\"J:b\"}\n";
    private static final String FORK_B = "{\"seq\":2,\"id\":\"J:fork\"}\n";

    @Test public void olderRetainedSignedJournalIsBlockedAtEventBoundary() {
        assertTrue(KnowledgePeerInbox.isStrictLinePrefix(EVENT_A, EVENT_A + EVENT_B));
        assertFalse(KnowledgePeerInbox.isStrictLinePrefix(EVENT_A + EVENT_B, EVENT_A));
        assertFalse(KnowledgePeerInbox.isStrictLinePrefix(EVENT_A, EVENT_A));
        assertFalse(KnowledgePeerInbox.isStrictLinePrefix(EVENT_A.substring(0, EVENT_A.length() - 1),
                EVENT_A + EVENT_B));
    }

    @Test public void legitimateIndependentForkDoesNotLookLikeRollback() {
        assertFalse(KnowledgePeerInbox.isStrictLinePrefix(EVENT_A + FORK_B,
                EVENT_A + EVENT_B));
        assertFalse(KnowledgePeerInbox.isStrictLinePrefix("", EVENT_A));
        assertFalse(KnowledgePeerInbox.isStrictLinePrefix(null, EVENT_A));
    }
}

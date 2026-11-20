/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression for Preview #17's one-tap QA failing with JSONException. */
public class KnowledgePythonBridgeProtocolTest {
    @Test
    public void acceptsStage22EditorSelfTestProtocol() {
        assertTrue(KnowledgePythonBridge.isSupportedProtocol("dpksys.stage22_editor_selftest.v1"));
    }

    @Test
    public void keepsKnownProtocolsAndRejectsUnknownOnes() {
        for (final String protocol : new String[] {
                "dpksys.inspect.v1", "dpksys.decision.v1", "dpksys.query.v1",
                "dpksys.backup.v1", "dpksys.bulk.v1", "dpksys.relations.v1",
                "dpksys.audit.v1", "dpksys.sync-preview.v1", "dpksys.sync-events.v1",
                "dpksys.sync-review-commit.v1", "dpksys.sync-adopt.v1",
                "dpksys.sync-conflict-preview.v1", "dpksys.sync-conflict-resolve.v1",
                "dpksys.sync-ack-commit.v1", "dpksys.sync-retract-preview.v1",
                "dpksys.sync-retract-commit.v1"
        }) {
            assertTrue(protocol, KnowledgePythonBridge.isSupportedProtocol(protocol));
        }
        assertFalse(KnowledgePythonBridge.isSupportedProtocol("dpksys.unknown.v1"));
        assertFalse(KnowledgePythonBridge.isSupportedProtocol(null));
    }
}

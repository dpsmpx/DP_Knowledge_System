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
                "dpksys.audit.v1", "dpksys.entities.v1", "dpksys.source_lineage.v1",
                "dpksys.source_assessment.v1", "dpksys.version_review.v1",
                "dpksys.editor_context.v1", "dpksys.editor_highlights.v1",
                "dpksys.editor_record_evidence.v1", "dpksys.editor_source_records.v1"
        }) {
            assertTrue(protocol, KnowledgePythonBridge.isSupportedProtocol(protocol));
        }
        assertFalse(KnowledgePythonBridge.isSupportedProtocol("dpksys.unknown.v1"));
        assertFalse(KnowledgePythonBridge.isSupportedProtocol(null));
    }
}

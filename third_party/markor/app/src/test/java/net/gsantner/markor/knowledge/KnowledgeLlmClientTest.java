/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class KnowledgeLlmClientTest {
    @Test
    public void permitsExplicitPrivateNetworkOrEncryptedEndpoints() {
        assertEquals("http://192.168.1.20:1234/v1/chat/completions",
                KnowledgeLlmClient.endpoint("http://192.168.1.20:1234/v1").toString());
        assertEquals("http://100.100.1.3:11434/v1/chat/completions",
                KnowledgeLlmClient.endpoint("http://100.100.1.3:11434/v1").toString());
        assertEquals("https://api.example.org/v1/chat/completions",
                KnowledgeLlmClient.endpoint("https://api.example.org/v1").toString());
        assertTrue(KnowledgeLlmClient.privateLocalHost("127.0.0.1"));
        assertTrue(KnowledgeLlmClient.privateLocalHost("172.16.0.1"));
        assertTrue(KnowledgeLlmClient.privateLocalHost("10.0.0.2"));
    }

    @Test
    public void rejectsPublicHttpCredentialUrlsAndRedirectLikePaths() {
        for (final String bad : Arrays.asList(
                "http://example.org/v1", "http://8.8.8.8/v1",
                "http://169.254.1.1/v1", "http://192.169.1.1/v1",
                "http://192.168.01.1/v1", "http://my-server.local/v1",
                "http://127.0.0.1@evil.example/v1",
                "https://user:pass@example.com/v1",
                "https://api.example.org/v1?token=private",
                "https://api.example.org/v1#fragment",
                "https://api.example.org/admin",
                "https://api.example.org/v1/../v1/chat/completions",
                "file:///tmp/example", "ftp://example.org/v1")) {
            try {
                KnowledgeLlmClient.endpoint(bad);
                fail("Unexpectedly accepted endpoint: " + bad);
            } catch (IllegalArgumentException expected) {
                // Expected policy refusal.
            }
        }
    }

    @Test
    public void consentPreviewContainsExactlyNumberedSourcesAndQuestion() {
        final String preview = KnowledgeLlmClient.requestPreview(
                "What is known about Alex?",
                Arrays.asList(new KnowledgeLlmEvidence.Item("one.md", "Alex is a developer."),
                        new KnowledgeLlmEvidence.Item("nested/two.markdown", "Alex may be a designer.")));
        assertTrue(preview.contains("QUESTION:\nWhat is known about Alex?"));
        assertTrue(preview.contains("[1] one.md"));
        assertTrue(preview.contains("[2] nested/two.markdown"));
        assertTrue(preview.contains("UNTRUSTED SOURCE"));
        assertTrue(preview.contains("not confirmed facts"));
    }

    @Test
    public void refusesUnboundedOrUnsafeContext() {
        try {
            KnowledgeLlmClient.requestPreview("Question", Collections.singletonList(
                    new KnowledgeLlmEvidence.Item("../private.md", "confidential")));
            fail("Path traversal should be blocked");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            KnowledgeLlmClient.requestPreview("Question", Collections.singletonList(
                    new KnowledgeLlmEvidence.Item("safe.md", new String(new char[1500]))));
            fail("Oversized source excerpt should be blocked");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void excerptIsBoundedAndSelectsMatchingPartOfNote() {
        final String source = "# Heading\n" + new String(new char[1300]).replace('\0', 'a')
                + "\nPython enables a test.\n";
        final String excerpt = KnowledgeLlmEvidence.excerpt(source, "Explain Python");
        assertTrue(excerpt.contains("Python enables a test"));
        assertTrue(excerpt.length() <= KnowledgeLlmEvidence.MAX_EXCERPT_CHARS + 4);
        assertFalse(KnowledgeLlmEvidence.excerpt("", "Question").contains("secret"));
    }
}

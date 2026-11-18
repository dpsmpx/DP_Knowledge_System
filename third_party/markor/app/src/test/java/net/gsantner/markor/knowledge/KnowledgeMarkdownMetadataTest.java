/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class KnowledgeMarkdownMetadataTest {
    @Test
    public void extractsTitlesAndCandidateLinksWithoutAssertingIdentity() {
        final String markdown = "---\ntags: [test]\n---\n# Sample Heading\n"
                + "[[Folder/Target#Section|label]] and [local](notes/Example.md)\n"
                + "[[Folder/Target]]";
        final KnowledgeMarkdownMetadata.Parsed p = KnowledgeMarkdownMetadata.parse("note.md", markdown);
        assertEquals("Sample Heading", p.title);
        assertEquals(Arrays.asList("folder/target", "notes/example"), p.possibleLinks);
        assertEquals(markdown, p.searchableText);
    }

    @Test
    public void neverTreatsCodeFencesAsLinks() {
        final KnowledgeMarkdownMetadata.Parsed p = KnowledgeMarkdownMetadata.parse(
                "folder/note.md", "~~~\n[[NotARealLink]]\n~~~\n[[RealLink]]");
        assertEquals(Arrays.asList("reallink"), p.possibleLinks);
        assertEquals("note", p.title);
    }

    @Test
    public void ignoresNetworkTargetsAndAnchors() {
        assertEquals("", KnowledgeMarkdownMetadata.linkKey("https://example.com/file.md"));
        assertEquals("", KnowledgeMarkdownMetadata.linkKey("#heading"));
        assertEquals("project/test", KnowledgeMarkdownMetadata.linkKey("./Project/Test.markdown#H"));
    }

    @Test
    public void searchSyntaxCannotInjectFtsOperators() {
        assertEquals("\"hello\"* \"or\"* \"world\"*", KnowledgeMarkdownMetadata.ftsQuery("hello OR world"));
        assertEquals("\"drop\"* \"table\"*", KnowledgeMarkdownMetadata.ftsQuery("DROP; TABLE"));
        assertEquals("", KnowledgeMarkdownMetadata.ftsQuery("!!!"));
    }
}

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

    @Test
    public void llmEvidenceSearchUsesEscapedRecallOrNotArbitraryFtsSyntax() {
        assertEquals("\"python*\" OR \"works*\" OR \"programming*\"",
                KnowledgeMarkdownMetadata.ftsEvidenceQuery("How python works in programming?"));
        assertEquals("\"drop*\" OR \"table*\"",
                KnowledgeMarkdownMetadata.ftsEvidenceQuery("DROP; TABLE"));
        assertEquals("", KnowledgeMarkdownMetadata.ftsEvidenceQuery(""));
    }

    @Test
    public void llmEvidenceSearchRejectsOverlongQuestionsBeforeTokenizing() {
        final int bound = KnowledgeMarkdownMetadata.MAX_EVIDENCE_QUESTION_CHARS;
        assertEquals(1200, bound);
        final String accepted = "amber" + new String(new char[bound - 5]).replace('\0', ' ');
        assertEquals("\"amber*\"",
                KnowledgeMarkdownMetadata.ftsEvidenceQuery(accepted));
        final String tooLong = accepted + "x";
        assertEquals("", KnowledgeMarkdownMetadata.ftsEvidenceQuery(tooLong));
        assertEquals("", KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery(tooLong));
        assertEquals(0, KnowledgeMarkdownMetadata.ftsEvidenceTerms(tooLong).size());
    }

    @Test
    public void naturalQuestionsPreferInformativeTermsAndSafeShortPhrases() {
        assertEquals("\"amber*\" OR \"violet*\"",
                KnowledgeMarkdownMetadata.ftsEvidenceQuery("Which amber violet?"));
        assertEquals("\"amber* violet*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("Which amber violet?"));
        assertEquals("\"синтез*\" OR \"память*\"",
                KnowledgeMarkdownMetadata.ftsEvidenceQuery("Какие синтез память?"));
        assertEquals("\"синтез* память*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("Какие синтез память?"));
        assertEquals("\"which*\"", KnowledgeMarkdownMetadata.ftsEvidenceQuery("Which?"));
        assertEquals("", KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("Which?"));
        assertEquals("", KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery(
                "alpha bravo charlie delta echo"));
        assertEquals("\"drop* table*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("DROP; TABLE"));
        assertEquals("\"amber* violet*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("Tell me about amber violet"));
        assertEquals("\"cobalt* willow*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("What do we know about cobalt willow?"));
        assertEquals("\"citrine* juniper*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("Please explain citrine juniper"));
        assertEquals("\"saffron* otter*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("Describe saffron otter"));
        assertEquals("\"память* синтез*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery(
                        "Расскажи, что известно про память синтез?"));
        assertEquals("\"information* theory*\"",
                KnowledgeMarkdownMetadata.ftsEvidencePhraseQuery("information theory"));
        // Do not invent evidence for a question containing only filler terms.
        assertEquals("\"tell*\" OR \"about*\"",
                KnowledgeMarkdownMetadata.ftsEvidenceQuery("tell about"));
    }

    @Test
    public void titleEvidenceMatchesOnlyDistinctCompletePrefixWords() {
        final java.util.List<String> en = KnowledgeMarkdownMetadata.ftsEvidenceTerms(
                "Tell me about amber violet");
        assertEquals(2, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                "Violets related to amberlight", en));
        assertEquals(0, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                "chamber and ultraviolet", en));
        assertEquals(1, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                "Amber amber amber", en));
        assertEquals(2, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                "ПАМЯТЬ и СИНТЕЗ", KnowledgeMarkdownMetadata.ftsEvidenceTerms(
                        "Расскажи про память синтез")));
        assertEquals(0, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(null, en));
        assertEquals(0, KnowledgeMarkdownMetadata.titleEvidenceTermMatches("amber", null));
        assertEquals(0, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                new String(new char[255]).replace('\0', 'x') + "ambermore", en));
        assertEquals(1, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                new String(new char[250]).replace('\0', ' ') + "amber!", en));
        assertEquals(1, KnowledgeMarkdownMetadata.titleEvidenceTermMatches(
                "amberlight", Arrays.asList("amber", "amberl")));
    }
}

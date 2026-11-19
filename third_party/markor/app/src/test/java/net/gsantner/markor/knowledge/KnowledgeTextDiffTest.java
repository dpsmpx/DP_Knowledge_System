package net.gsantner.markor.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class KnowledgeTextDiffTest {
    private static String changed(final String text, final List<KnowledgeTextDiff.Range> ranges) {
        final StringBuilder result = new StringBuilder();
        for (KnowledgeTextDiff.Range range : ranges) {
            result.append('[').append(text, range.start, range.end).append(']');
        }
        return result.toString();
    }

    @Test public void identicalWordsHaveNoChanges() {
        final KnowledgeTextDiff.Result result = KnowledgeTextDiff.compare("Марс — планета.",
                "Марс — планета.");
        assertTrue(result.available);
        assertFalse(result.hasChanges());
    }

    @Test public void insertedNegationIsVisibleInFormalTextOnly() {
        final String source = "Марс является планетой.";
        final String formal = "Марс не является планетой.";
        final KnowledgeTextDiff.Result result = KnowledgeTextDiff.compare(source, formal);
        assertTrue(result.available);
        assertEquals("", changed(source, result.sourceChanges));
        assertEquals("[не]", changed(formal, result.formalChanges));
    }

    @Test public void duplicateWordsMatchInOrder() {
        final String source = "A A B";
        final KnowledgeTextDiff.Result result = KnowledgeTextDiff.compare(source, "A B");
        assertTrue(result.available);
        assertEquals("[A]", changed(source, result.sourceChanges));
        assertEquals(2, result.sourceChanges.get(0).start);
    }

    @Test public void emojiRangesNeverSplitUtf16SurrogatePairs() {
        final String source = "🐱 Марс";
        final String formal = "🐶 Марс";
        final KnowledgeTextDiff.Result result = KnowledgeTextDiff.compare(source, formal);
        assertTrue(result.available);
        assertEquals("[🐱]", changed(source, result.sourceChanges));
        assertEquals("[🐶]", changed(formal, result.formalChanges));
        assertEquals(2, result.sourceChanges.get(0).end);
        assertEquals(2, result.formalChanges.get(0).end);
    }

    @Test public void punctuationAndSymbolsAreCompared() {
        final KnowledgeTextDiff.Result result = KnowledgeTextDiff.compare("a (b)", "a [b]");
        assertTrue(result.available);
        assertEquals("[(][)]", changed("a (b)", result.sourceChanges));
        assertEquals("[[][]]", changed("a [b]", result.formalChanges));
    }

    @Test public void hugeAndEmptyInputsDoNotCreateExpensiveComparisons() {
        assertFalse(KnowledgeTextDiff.compare("", "planet(mars)").available);
        assertFalse(KnowledgeTextDiff.compare(repeated('x', 1025), "planet(mars)").available);
        assertFalse(KnowledgeTextDiff.compare(repeated('!', 129), "x").available);
    }

    private static String repeated(final char character, final int count) {
        final StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(character);
        return result.toString();
    }
}

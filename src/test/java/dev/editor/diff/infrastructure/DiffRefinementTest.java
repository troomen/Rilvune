package dev.editor.diff.infrastructure;

import dev.editor.diff.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DiffRefinementTest {
    private final DiffEngine engine = new MyersDiffEngine(DiffLimits.interactive());
    @Test void mixedBlockSeparatesUnmatchedLinesAndOnlyHighlightsChangedDigits() {
        DiffResult result = compare("port = 8080\nworkers = 2\nobsolete = true\nmode = keep\n",
                "port = 9090\ntimeout = 30\nworkers = 4\nmode = keep\n", false);
        assertEquals(1, result.hunks().size());
        var changes = result.hunks().getFirst().changes();
        assertEquals(List.of(DiffKind.CHANGED, DiffKind.ADDED, DiffKind.CHANGED, DiffKind.REMOVED), changes.stream().map(DiffLineChange::kind).toList());
        assertEquals(List.of("8", "8"), changes.getFirst().fragments().stream().map(fragment -> text(result.left(), fragment.left())).toList());
        assertEquals(List.of("9", "9"), changes.getFirst().fragments().stream().map(fragment -> text(result.right(), fragment.right())).toList());
        assertEquals("timeout = 30\n", result.rightLines().text(changes.get(1).right()));
        assertEquals("obsolete = true\n", result.leftLines().text(changes.getLast().left()));
        assertEquals(2, result.mapLine(Side.LEFT, 1)); assertEquals(1, result.mapLine(Side.RIGHT, 2));
        assertEquals(3, result.mapLine(Side.LEFT, 3));
    }
    @Test void sharedAssignmentPrefixesStillMatchWhenValuesHaveLittleInCommon() {
        var result = compare("level = debug\noutput = console\n", "level = info\n", false);
        var changes = result.hunks().getFirst().changes();
        assertEquals(List.of(DiffKind.CHANGED, DiffKind.REMOVED), changes.stream().map(DiffLineChange::kind).toList());
        assertEquals("debug", text(result.left(), changes.getFirst().fragments().getFirst().left()));
        assertEquals("info", text(result.right(), changes.getFirst().fragments().getFirst().right()));
    }
    @Test void inlineInsertionsAndDeletionsUseDirectionalKindsAndExactRanges() {
        var inserted = compare("name = foo;\n", "name = fooBar;\n", false);
        var edit = fragments(inserted).getFirst();
        assertEquals(DiffKind.ADDED, edit.kind()); assertTrue(edit.left().empty()); assertEquals("Bar", text(inserted.right(), edit.right()));
        var removed = compare("name = fooBar;\n", "name = foo;\n", false);
        edit = fragments(removed).getFirst();
        assertEquals(DiffKind.REMOVED, edit.kind()); assertTrue(edit.right().empty()); assertEquals("Bar", text(removed.left(), edit.left()));
    }
    @Test void unrelatedLineGroupsRemainWholeLineRemovalsAndAdditions() {
        var result = compare("AAAA\nBBBB\n", "1111\n2222\n", false);
        var changes = result.hunks().getFirst().changes();
        assertEquals(List.of(DiffKind.REMOVED, DiffKind.ADDED), changes.stream().map(DiffLineChange::kind).toList());
        assertTrue(changes.stream().allMatch(change -> change.fragments().isEmpty()));
    }
    @Test void ignoredWhitespacePreservesOriginalOffsetsAndFinalNewlineDifferences() {
        var result = compare("\tset( a b1 = 10 );\n", "set(ab2=20);\n", true);
        assertEquals(List.of("1", "1"), fragments(result).stream().map(fragment -> text(result.left(), fragment.left())).toList());
        assertEquals(List.of("2", "2"), fragments(result).stream().map(fragment -> text(result.right(), fragment.right())).toList());
        assertTrue(compare("\ta b = 1;\n", "ab=1;\n", true).hunks().isEmpty());
        var newline = compare("value = 1;", "value = 1;\n", true);
        assertEquals("\n", text(newline.right(), fragments(newline).getFirst().right()));
        assertEquals(DiffKind.ADDED, fragments(newline).getFirst().kind());
    }
    @Test void unicodeDetailOffsetsNeverSplitASurrogatePair() {
        var result = compare("emoji = \"😀\";\n", "emoji = \"😃\";\n", false);
        var fragment = fragments(result).getFirst();
        assertEquals("😀", text(result.left(), fragment.left())); assertEquals("😃", text(result.right(), fragment.right()));
        assertEquals(2, fragment.left().length()); assertEquals(2, fragment.right().length());
    }
    @Test void methodCallRewriteUsesCoherentReplacementsInsteadOfIncidentalLetterMatches() {
        var result = compare("        label.setFont(theme.interfaceFont().deriveFont(style, 12f));\n",
                "        UiTypography.interfaceFontAtSize(label, theme, style, 12);\n", false);
        var edits = fragments(result);
        assertEquals(3, edits.size());
        assertTrue(edits.stream().allMatch(edit -> edit.kind() == DiffKind.CHANGED));
        assertEquals("label.setFont(", text(result.left(), edits.getFirst().left()));
        assertEquals("UiTypography.interfaceFontAtSize(label, ", text(result.right(), edits.getFirst().right()));
        assertTrue(edits.stream().noneMatch(edit -> text(result.left(), edit.left()).contains("theme")
                || text(result.left(), edit.left()).contains("style")));
        assertReconstruction(result);
    }
    @Test void unrelatedIdentifiersAreWholeReplacementsButSimilarTokensKeepFineDetail() {
        var result = compare("call(label, level1);\n", "call(UiTypography, level2);\n", false);
        var edits = fragments(result);
        assertEquals(2, edits.size());
        assertEquals("label", text(result.left(), edits.getFirst().left()));
        assertEquals("UiTypography", text(result.right(), edits.getFirst().right()));
        assertEquals("1", text(result.left(), edits.getLast().left()));
        assertEquals("2", text(result.right(), edits.getLast().right()));
        assertReconstruction(result);
    }
    @Test void nearbySeparatorsCanBeHighlightedWithoutHidingUnchangedArguments() {
        var grouped = compare("use(old.first, keep, left);\n", "use(new.second, keep, right);\n", false);
        var edits = fragments(grouped);
        assertEquals(2, edits.size());
        assertEquals("old.first", text(grouped.left(), edits.getFirst().left()));
        assertEquals("new.second", text(grouped.right(), edits.getFirst().right()));
        assertTrue(edits.stream().noneMatch(edit -> text(grouped.left(), edit.left()).contains("keep")));
        assertReconstruction(grouped);
        var symbols = compare("old😀left\n", "new😀right\n", false);
        assertEquals(2, fragments(symbols).size());
        assertTrue(fragments(symbols).stream().noneMatch(edit -> text(symbols.left(), edit.left()).contains("😀")));
        assertReconstruction(symbols);
    }
    @Test void readableRegionsReconstructTokenRichSourcesWithUnicodeAndPunctuation() {
        Random random = new Random(63);
        String[] tokens = {"oldName", "newName", "label", "theme", "12", "34", ".", ", ", "(", ")", "😀", "😃", "e\u0301", "путь"};
        for (int sample = 0; sample < 200; sample++) {
            StringBuilder left = new StringBuilder("call("), right = new StringBuilder("call(");
            for (int index = 0; index < 10; index++) {
                left.append(tokens[random.nextInt(tokens.length)]); right.append(tokens[random.nextInt(tokens.length)]);
            }
            assertReconstruction(compare(left.append(");\n").toString(), right.append(");\n").toString(), false));
        }
    }
    @Test void refinementSharesTheRequestWorkBudget() {
        var bounded = new MyersDiffEngine(new DiffLimits(10000, 100, 50, Duration.ofSeconds(2)));
        assertThrows(DiffLimitException.class, () -> bounded.compare(snapshot("a", "value = " + "a".repeat(1000)),
                snapshot("b", "value = " + "b".repeat(1000)), DiffOptions.exact(), () -> false));
    }
    @Test void longAlmostEqualLinesRefineWithoutTokenizingUnchangedContent() {
        String prefix = "a".repeat(100_000), suffix = "b".repeat(100_000);
        var bounded = new MyersDiffEngine(new DiffLimits(1_000_000, 10, 500, Duration.ofSeconds(2)));
        var result = bounded.compare(snapshot("a", prefix + "1" + suffix), snapshot("b", prefix + "2" + suffix), DiffOptions.exact(), () -> false);
        assertEquals(1, fragments(result).size()); assertEquals("1", text(result.left(), fragments(result).getFirst().left()));
        assertEquals("2", text(result.right(), fragments(result).getFirst().right()));
    }
    @Test void randomizedRefinedEditsReconstructBothOriginalsAndKeepBlockTransfersExact() {
        Random random = new Random(42);
        for (int sample = 0; sample < 200; sample++) {
            String left = word(random), right = word(random); DiffResult result = compare(left, right, false);
            assertReconstruction(result);
        }
    }
    private void assertReconstruction(DiffResult result) {
        String left = result.left().text(), right = result.right().text();
        StringBuilder reconstructedRight = new StringBuilder(right), reconstructedLeft = new StringBuilder(left);
        for (DiffFragment fragment : fragments(result).reversed()) {
            reconstructedRight.replace(fragment.right().startOffset(), fragment.right().endOffset(), text(result.left(), fragment.left()));
            reconstructedLeft.replace(fragment.left().startOffset(), fragment.left().endOffset(), text(result.right(), fragment.right()));
        }
        assertEquals(left, reconstructedRight.toString()); assertEquals(right, reconstructedLeft.toString());
        StringBuilder transferred = new StringBuilder(right);
        for (DiffHunk hunk : result.hunks().reversed()) transferred.replace(hunk.right().startOffset(), hunk.right().endOffset(), result.leftLines().text(hunk.left()));
        assertEquals(left, transferred.toString());
    }
    private String word(Random random) {
        StringBuilder result = new StringBuilder("value = ");
        String[] values = {"a", "b", "c", "😀", "😃"};
        for (int index = random.nextInt(15); index > 0; index--) result.append(values[random.nextInt(values.length)]);
        return result.append(';').append(random.nextBoolean() ? "\n" : "").toString();
    }
    private List<DiffFragment> fragments(DiffResult result) { return result.hunks().stream().flatMap(hunk -> hunk.changes().stream()).flatMap(change -> change.fragments().stream()).toList(); }
    private DiffResult compare(String left, String right, boolean ignoreWhitespace) { return engine.compare(snapshot("a", left), snapshot("b", right), new DiffOptions(ignoreWhitespace), () -> false); }
    private TextSnapshot snapshot(String id, String text) { return new TextSnapshot(id, 0, text); }
    private String text(TextSnapshot snapshot, TextSpan span) { return snapshot.text().substring(span.startOffset(), span.endOffset()); }
}

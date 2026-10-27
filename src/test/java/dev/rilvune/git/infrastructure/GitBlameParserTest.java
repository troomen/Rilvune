package dev.rilvune.git.infrastructure;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class GitBlameParserTest {
    private String row(String id, int line, String path) {
        return id + " " + line + " " + line + " 1\nauthor <html>Author\nauthor-time 100\nauthor-tz +0530\nsummary subject\nfilename " + path + "\n\ttext\n";
    }
    @Test void fullObjectIdsMetadataAndQuotedPathsAreDecodedWithoutDisplayInterpretation() throws Exception {
        var lines = GitBlameParser.parse((row("a".repeat(64), 1, "\"tab\\tline\\n\\303\\251.txt\"") + row("b".repeat(64), 2, "plain")).getBytes(StandardCharsets.UTF_8));
        assertEquals(2, lines.size()); assertEquals("tab\tline\né.txt", lines.getFirst().originalPath());
        assertEquals("<html>Author", lines.getFirst().author()); assertEquals(19800, lines.getFirst().zone().getTotalSeconds());
        assertEquals(1, lines.getLast().line()); assertEquals(1, lines.getLast().originalLine());
    }
    @Test void malformedTruncatedOutOfOrderAndOversizedMetadataAreRejected() {
        for (String value : new String[]{"bad 1 1\n", row("a".repeat(40), 2, "a"), row("a".repeat(40), 1, "../outside"), row("a".repeat(40), 1, "\"bad\\q\""),
                row("a".repeat(40), 1, "a").replace("\ttext\n", ""), row("a".repeat(40), 1, "a").replace("summary subject", "summary " + "x".repeat(17000))})
            assertThrows(IOException.class, () -> GitBlameParser.parse(value.getBytes(StandardCharsets.UTF_8)));
    }
}

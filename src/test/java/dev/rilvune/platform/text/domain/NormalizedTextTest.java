package dev.rilvune.platform.text.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalizedTextTest {
    @Test void bomMixedEndingsAndFinalNewlineHaveOneSharedSourceRepresentation() throws Exception {
        assertEquals(new NormalizedText("a\nb\nc\nd", true, "MIXED"), NormalizedText.from("\ufeffa\r\nb\rc\nd"));
        assertEquals(new NormalizedText("a\n", false, "CRLF"), NormalizedText.from("a\r\n"));
        assertEquals("", NormalizedText.from("").text());
        assertEquals("\ufefftext", NormalizedText.from("\ufeff\ufefftext").text());
        assertEquals("\ufefftext", NormalizedText.content("\ufefftext", true).text());
        assertThrows(java.io.IOException.class, () -> NormalizedText.from("a\u0000b"));
    }
}

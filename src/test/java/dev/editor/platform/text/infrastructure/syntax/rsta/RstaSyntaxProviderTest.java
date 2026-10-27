package dev.editor.platform.text.infrastructure.syntax.rsta;

import dev.editor.platform.text.domain.syntax.*;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RstaSyntaxProviderTest {
    private final RstaSyntaxProvider provider = new RstaSyntaxProvider();
    private static final Map<String, String> SAMPLES = Map.ofEntries(
            Map.entry("java", "public class Example { int n = 42; }"), Map.entry("kotlin", "val value = \"hello\""),
            Map.entry("groovy", "def value = \"hello\""), Map.entry("javascript", "const value = \"hello\";"),
            Map.entry("typescript", "const value: number = 42;"), Map.entry("python", "def test():\n  return \"hello\""),
            Map.entry("c", "int value = 42;"), Map.entry("cpp", "class Example { int value = 42; };"),
            Map.entry("csharp", "public class Example { string s = \"hello\"; }"), Map.entry("go", "package main\nvar n = 42"),
            Map.entry("rust", "fn main() { let n = 42; }"), Map.entry("ruby", "def test\n  puts \"hello\"\nend"),
            Map.entry("php", "<?php echo \"hello\"; ?>"), Map.entry("lua", "local n = 42"),
            Map.entry("shell", "#!/bin/sh\necho \"hello\""), Map.entry("powershell", "$value = \"hello\""),
            Map.entry("batch", "@echo off\necho hello"), Map.entry("json", "{\"value\": 42}"),
            Map.entry("jsonc", "{ /* comment */ \"value\": true }"), Map.entry("yaml", "value: \"hello\""),
            Map.entry("xml", "<root value=\"hello\"/>"), Map.entry("html", "<p class=\"hello\">text</p>"),
            Map.entry("css", "body { color: red; }"), Map.entry("less", "@color: red; body { color: @color; }"),
            Map.entry("sql", "SELECT * FROM example WHERE value = 42;"), Map.entry("markdown", "# Heading\n**bold**"),
            Map.entry("properties", "# comment\nkey=value"), Map.entry("ini", "[section]\nkey=value"),
            Map.entry("dockerfile", "FROM alpine\nRUN echo \"hello\""), Map.entry("makefile", "target:\n\techo \"hello\""),
            Map.entry("env", "# comment\nKEY=\"hello\""));

    @Test void everyShippedModeHasMeaningfulSemanticOutputAndBoundedSourceOffsets() {
        assertEquals(provider.languages().size(), SAMPLES.size());
        for (var sample : SAMPLES.entrySet()) {
            SyntaxResult result = lex(sample.getKey(), sample.getValue());
            assertTrue(result.size() > 0, sample.getKey());
            int end = 0;
            for (int i = 0; i < result.size(); i++) {
                assertTrue(result.start(i) >= end, sample.getKey()); end = result.end(i);
                assertTrue(end <= sample.getValue().length()); assertNotEquals(SyntaxKind.DEFAULT, result.kind(i));
                assertFalse(sample.getValue().substring(result.start(i), end).contains("\n"));
            }
        }
    }
    @Test void allShippedAssociationsAndAllowlistedShebangsResolveWithoutRunningAProcess() {
        var resolver = new SyntaxLanguageResolver(new SyntaxCatalog(List.of(provider.contribution())));
        for (var language : provider.contribution().languages()) for (var rule : language.associations()) {
            if (rule.kind() == SyntaxAssociation.Kind.INTERPRETER) continue;
            String name = switch (rule.kind()) { case NAME -> rule.value(); case PREFIX -> rule.value() + "sample"; default -> "sample" + rule.value(); };
            assertEquals(language.id(), resolver.resolve(SyntaxSource.file(name), SyntaxSelection.AUTO, ""), name);
        }
        assertEquals(new SyntaxLanguageId("python"), resolver.resolve(SyntaxSource.file("script"), SyntaxSelection.AUTO, "#!/usr/bin/env python3.13\n"));
        assertEquals(new SyntaxLanguageId("shell"), resolver.resolve(SyntaxSource.file("script"), SyntaxSelection.AUTO, "#!/bin/bash\n"));
        assertEquals(SyntaxLanguageId.PLAIN, resolver.resolve(SyntaxSource.file("script"), SyntaxSelection.AUTO, "#!/usr/bin/env -S python3 -u"));
        assertEquals(SyntaxLanguageId.PLAIN, resolver.resolve(SyntaxSource.file("sample.tf"), SyntaxSelection.AUTO, "resource \"x\" {}"));
    }
    @Test void multilineContinuationEmptyLinesAndReusableTokenBuffersAreCopiedCorrectly() {
        String java = "/* start\n\ncontinued */\npublic class X {}\n";
        SyntaxResult first = lex("java", java);
        assertEquals(SyntaxKind.COMMENT, at(first, java.indexOf("continued")));
        assertEquals(SyntaxKind.KEYWORD, at(first, java.indexOf("public")));
        assertEquals(SyntaxKind.STRING, at(lex("python", "value = \"\"\"start\n\nstill string\n\"\"\""), 20));
        lex("java", "public int other = 2;");
        assertEquals(SyntaxKind.COMMENT, at(first, java.indexOf("continued")));
        for (String language : SAMPLES.keySet()) { assertEquals(0, lex(language, "").size()); lex(language, "\n\n"); }
    }
    @Test void mixedHtmlAndJavascriptStatesStayInsideTheirOwnSourceRanges() {
        String source = "<html>\n<script>\nconst n = 42;\n</script>\n<style>p { color: red; }</style>\n</html>";
        SyntaxResult result = lex("html", source);
        assertEquals(SyntaxKind.NUMBER, at(result, source.indexOf("42")));
        assertEquals(SyntaxKind.MARKUP, at(result, source.lastIndexOf("html")));
    }
    @Test void everyUpstreamTokenTypeHasAnExplicitReviewedDisposition() {
        assertEquals(39, TokenTypes.DEFAULT_NUM_TOKEN_TYPES, "Review the kind mapping for the new upstream release");
        for (int type = 0; type < TokenTypes.DEFAULT_NUM_TOKEN_TYPES; type++) assertNotNull(RstaTokenKinds.kind(type));
        assertEquals(SyntaxKind.DEFAULT, RstaTokenKinds.kind(-100));
    }
    @Test void customRstaTokenMakerNeedsNoGlobalFactoryAndSharesThePortableContract() {
        var contribution = RstaFixtureContribution.contribution();
        try (var lexer = contribution.provider().create(RstaFixtureContribution.ID)) {
            String text = "paint \"start\n\nend\" after";
            var result = lexer.lex(new SyntaxInput(RstaFixtureContribution.ID, text), budget());
            assertEquals(SyntaxKind.STRING, at(result, text.indexOf("end")));
            assertEquals(SyntaxKind.KEYWORD, at(result, text.indexOf("after")));
        }
    }
    @Test void distributionLicenseMatchesTheActualPinnedDependency() throws Exception {
        var artifact = java.nio.file.Path.of(org.fife.ui.rsyntaxtextarea.TokenMaker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var zip = new java.util.zip.ZipFile(artifact.toFile()); var input = zip.getInputStream(zip.getEntry("META-INF/LICENSE"))) {
            assertEquals(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip(),
                    java.nio.file.Files.readString(java.nio.file.Path.of("licenses/RSyntaxTextArea-LICENSE.txt")).strip());
        }
    }
    private SyntaxResult lex(String language, String text) {
        var id = new SyntaxLanguageId(language);
        try (var lexer = provider.create(id)) { return lexer.lex(new SyntaxInput(id, text), budget()); }
    }
    private static SyntaxKind at(SyntaxResult result, int offset) {
        int index = result.firstIntersecting(offset);
        return index < result.size() && result.start(index) <= offset ? result.kind(index) : SyntaxKind.DEFAULT;
    }
    private static SyntaxWorkBudget budget() {
        var l = SyntaxLimits.interactive();
        return new SyntaxWorkBudget(new SyntaxLimits(l.characters(), l.lines(), l.lineCharacters(), l.spans(), 2, 256, 512,
                l.payloadBytes(), Duration.ZERO, Duration.ofSeconds(10)), () -> false, System::nanoTime);
    }
}

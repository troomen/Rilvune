package dev.editor.platform.text.infrastructure.syntax.rsta;

import dev.editor.platform.text.domain.syntax.*;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static dev.editor.platform.text.domain.syntax.SyntaxAssociation.*;

/** The only production backend with RSTA knowledge. No global TokenMakerFactory is accessed. */
public final class RstaSyntaxProvider implements SyntaxLexerProvider {
    private record Mode(SyntaxLanguage language, Supplier<TokenMaker> maker) { }
    private final Map<SyntaxLanguageId, Mode> modes;

    public RstaSyntaxProvider() {
        List<Mode> values = new ArrayList<>();
        add(values, "java", "Java", JavaTokenMaker::new, ".java");
        add(values, "kotlin", "Kotlin", KotlinTokenMaker::new, ".kt", ".kts");
        add(values, "groovy", "Groovy", GroovyTokenMaker::new, ".groovy", ".gradle");
        add(values, "javascript", "JavaScript", JavaScriptTokenMaker::new, ".js", ".mjs", ".cjs");
        add(values, "typescript", "TypeScript", TypeScriptTokenMaker::new, ".ts", ".mts", ".cts");
        mode(values, "python", "Python", PythonTokenMaker::new, suffix(".py"), suffix(".pyw"), interpreter("python"));
        add(values, "c", "C", CTokenMaker::new, ".c", ".h");
        add(values, "cpp", "C++", CPlusPlusTokenMaker::new, ".cc", ".cpp", ".cxx", ".hh", ".hpp", ".hxx");
        add(values, "csharp", "C#", CSharpTokenMaker::new, ".cs");
        add(values, "go", "Go", GoTokenMaker::new, ".go");
        add(values, "rust", "Rust", RustTokenMaker::new, ".rs");
        mode(values, "ruby", "Ruby", RubyTokenMaker::new, suffix(".rb"), interpreter("ruby"));
        add(values, "php", "PHP", PHPTokenMaker::new, ".php");
        add(values, "lua", "Lua", LuaTokenMaker::new, ".lua");
        mode(values, "shell", "Shell", UnixShellTokenMaker::new, suffix(".sh"), suffix(".bash"), suffix(".zsh"),
                interpreter("sh"), interpreter("bash"), interpreter("zsh"), interpreter("dash"), interpreter("ksh"));
        add(values, "powershell", "PowerShell", PowerShellTokenMaker::new, ".ps1", ".psm1");
        add(values, "batch", "Windows batch", WindowsBatchTokenMaker::new, ".bat", ".cmd");
        add(values, "json", "JSON", JsonTokenMaker::new, ".json");
        add(values, "jsonc", "JSON with comments", JshintrcTokenMaker::new, ".jsonc");
        add(values, "yaml", "YAML", YamlTokenMaker::new, ".yaml", ".yml");
        add(values, "xml", "XML", XMLTokenMaker::new, ".xml", ".xsd", ".xsl", ".svg", ".pom");
        add(values, "html", "HTML", HTMLTokenMaker::new, ".html", ".htm");
        add(values, "css", "CSS", CSSTokenMaker::new, ".css");
        add(values, "less", "Less", LessTokenMaker::new, ".less");
        add(values, "sql", "SQL", SQLTokenMaker::new, ".sql");
        add(values, "markdown", "Markdown", MarkdownTokenMaker::new, ".md", ".markdown");
        add(values, "properties", "Properties", PropertiesFileTokenMaker::new, ".properties");
        add(values, "ini", "INI", IniTokenMaker::new, ".ini", ".cfg");
        mode(values, "dockerfile", "Dockerfile", DockerTokenMaker::new, name("Dockerfile"), prefix("Dockerfile."));
        mode(values, "makefile", "Makefile", MakefileTokenMaker::new, name("Makefile"), name("makefile"), name("GNUmakefile"), suffix(".mk"));
        mode(values, "env", "Environment", EnvTokenMaker::new, name(".env"), prefix(".env."));
        Map<SyntaxLanguageId, Mode> map = new LinkedHashMap<>();
        for (Mode value : values) if (map.putIfAbsent(value.language().id(), value) != null) throw new IllegalArgumentException("Duplicate RSTA mode");
        modes = Map.copyOf(map);
    }

    /** Also used by adapter-local custom token-maker contributions without exposing RSTA to composition. */
    RstaSyntaxProvider(List<SyntaxLanguage> languages, Map<SyntaxLanguageId, Supplier<TokenMaker>> makers) {
        Map<SyntaxLanguageId, Mode> map = new LinkedHashMap<>();
        for (var language : languages) map.put(language.id(), new Mode(language, java.util.Objects.requireNonNull(makers.get(language.id()))));
        if (map.size() != languages.size() || !map.keySet().equals(makers.keySet())) throw new IllegalArgumentException("RSTA descriptor mismatch");
        modes = Map.copyOf(map);
    }
    public SyntaxContribution contribution() { return new SyntaxContribution(this, modes.values().stream().map(Mode::language).toList()); }
    @Override public Set<SyntaxLanguageId> languages() { return modes.keySet(); }
    @Override public SyntaxLexer create(SyntaxLanguageId language) {
        Mode mode = modes.get(language);
        if (mode == null) throw new IllegalArgumentException("Unsupported RSTA language");
        return new RstaSyntaxLexer(language, mode.maker().get());
    }
    private static void add(List<Mode> modes, String id, String name, Supplier<TokenMaker> maker, String... suffixes) {
        mode(modes, id, name, maker, java.util.Arrays.stream(suffixes).map(SyntaxAssociation::suffix).toArray(SyntaxAssociation[]::new));
    }
    private static void mode(List<Mode> modes, String id, String name, Supplier<TokenMaker> maker, SyntaxAssociation... rules) {
        modes.add(new Mode(new SyntaxLanguage(new SyntaxLanguageId(id), name, List.of(rules)), maker));
    }
}

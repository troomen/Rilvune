package dev.editor.platform.text.domain.syntax;

import java.util.Locale;
import java.util.Objects;

public final class SyntaxLanguageResolver {
    private final SyntaxCatalog catalog;
    public SyntaxLanguageResolver(SyntaxCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    public SyntaxLanguageId resolve(SyntaxSource source, SyntaxSelection selection, String prefix) {
        if (!source.complete()) return SyntaxLanguageId.PLAIN;
        if (selection.language().isPresent()) return catalog.language(selection.language().orElseThrow()).id();
        if (source.basename().isEmpty()) return SyntaxLanguageId.PLAIN;
        String name = source.basename(), lower = name.toLowerCase(Locale.ROOT);
        var rules = catalog.associations();
        for (var rule : rules.entrySet()) if (rule.getKey().kind() == SyntaxAssociation.Kind.NAME && rule.getKey().value().equals(name)) return rule.getValue();
        SyntaxLanguageId best = null; int longest = -1;
        for (var rule : rules.entrySet()) if (rule.getKey().kind() == SyntaxAssociation.Kind.PREFIX
                && name.startsWith(rule.getKey().value()) && rule.getKey().value().length() > longest) {
            best = rule.getValue(); longest = rule.getKey().value().length();
        }
        if (best != null) return best;
        for (var rule : rules.entrySet()) if (rule.getKey().kind() == SyntaxAssociation.Kind.SUFFIX
                && lower.endsWith(rule.getKey().value()) && rule.getKey().value().length() > longest) {
            best = rule.getValue(); longest = rule.getKey().value().length();
        }
        if (best != null) return best;
        String interpreter = interpreter(prefix);
        if (!interpreter.isEmpty()) {
            var detected = rules.get(SyntaxAssociation.interpreter(interpreter));
            if (detected != null) return detected;
        }
        return SyntaxLanguageId.PLAIN;
    }

    private String interpreter(String prefix) {
        String line = prefix.substring(0, Math.min(256, prefix.length())).split("\\r?\\n", 2)[0];
        if (!line.startsWith("#!")) return "";
        String[] words = line.substring(2).trim().split("[ \\t]+");
        if (words.length == 0 || !words[0].startsWith("/")) return "";
        String executable = words[0].substring(words[0].lastIndexOf('/') + 1);
        if (executable.equals("env")) {
            if (words.length != 2 || !words[0].equals("/usr/bin/env")) return "";
            executable = words[1];
        } else if (words.length > 1) return "";
        if (!executable.matches("[a-z][a-z0-9.-]*")) return "";
        // Versioned interpreter names are deliberately allowlisted, not arbitrary command parsing.
        if (executable.matches("python[23](?:\\.[0-9]+)?")) return "python";
        if (executable.matches("ruby[0-9]+(?:\\.[0-9]+)*")) return "ruby";
        return executable;
    }
}

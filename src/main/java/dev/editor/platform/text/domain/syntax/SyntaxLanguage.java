package dev.editor.platform.text.domain.syntax;

import java.util.List;
import java.util.Objects;

public record SyntaxLanguage(SyntaxLanguageId id, String name, List<SyntaxAssociation> associations) {
    public static final SyntaxLanguage PLAIN = new SyntaxLanguage(SyntaxLanguageId.PLAIN, "Plain text", List.of());

    public SyntaxLanguage {
        Objects.requireNonNull(id);
        if (name == null || name.isBlank() || name.length() > 160 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid syntax display name");
        associations = List.copyOf(associations);
        if (associations.size() > 64) throw new IllegalArgumentException("Too many syntax associations");
    }
}

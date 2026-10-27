package dev.rilvune.agents.domain;

import java.util.*;

/** A non-secret ACP form. Only declared, validated answers cross the runtime boundary. */
public record AgentQuestion(String id, String title, List<Field> fields) {
    public AgentQuestion { fields = List.copyOf(fields); }
    public enum Type { TEXT, NUMBER, INTEGER, BOOLEAN, CHOICE, MULTIPLE }
    public record Choice(String value, String label) { @Override public String toString() { return label; } }
    public record Field(String key, String title, String description, Type type, boolean required, List<Choice> choices,
                        Object initial, Integer minLength, Integer maxLength, Double minimum, Double maximum,
                        Integer minItems, Integer maxItems, String pattern, String format) {
        public Field { choices = List.copyOf(choices); if (initial instanceof List<?> list) initial = List.copyOf(list); if (pattern.length() > 512) throw new IllegalArgumentException("Question pattern exceeds the validation limit"); }
        public void validate(Object value) {
            if (value == null) { if (required) throw invalid("is required"); return; }
            switch (type) {
                case TEXT, CHOICE -> {
                    if (!(value instanceof String text)) throw invalid("must be text");
                    if (required && text.isBlank() || minLength != null && text.length() < minLength || maxLength != null && text.length() > maxLength || text.length() > 32768) throw invalid("has an invalid length");
                    if (type == Type.CHOICE && choices.stream().noneMatch(choice -> choice.value().equals(text))) throw invalid("requires a listed choice");
                    if (!pattern.isBlank() && !java.util.regex.Pattern.compile(pattern).matcher(new PatternText(text)).find()) throw invalid("does not match the requested format");
                }
                case BOOLEAN -> { if (!(value instanceof Boolean)) throw invalid("must be true or false"); }
                case NUMBER, INTEGER -> {
                    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw invalid("must be a number");
                    double numeric = number.doubleValue();
                    if (type == Type.INTEGER && new java.math.BigDecimal(number.toString()).stripTrailingZeros().scale() > 0 || minimum != null && numeric < minimum || maximum != null && numeric > maximum) throw invalid("is outside the allowed range");
                }
                case MULTIPLE -> {
                    if (!(value instanceof List<?> list)) throw invalid("requires a list of choices");
                    if (required && list.isEmpty() || minItems != null && list.size() < minItems || maxItems != null && list.size() > maxItems || new HashSet<>(list).size() != list.size()) throw invalid("has an invalid number of choices");
                    for (Object item : list) if (choices.stream().noneMatch(choice -> choice.value().equals(item))) throw invalid("contains an unknown choice");
                }
            }
        }
        private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(title + " " + message); }
    }
    /** Bounds regex evaluation work while preserving JSON Schema's unanchored pattern semantics. */
    private static final class PatternText implements CharSequence {
        private final String text;
        private final int[] remaining;
        PatternText(String text) { this(text, new int[] { 1_000_000 }); }
        private PatternText(String text, int[] remaining) { this.text = text; this.remaining = remaining; }
        public int length() { return text.length(); }
        public char charAt(int index) { if (--remaining[0] < 0) throw new IllegalArgumentException("Question format check exceeds the validation budget"); return text.charAt(index); }
        public CharSequence subSequence(int start, int end) { return new PatternText(text.substring(start, end), remaining); }
        @Override public String toString() { return text; }
    }
    public void validate(Map<String, Object> values) {
        for (String key : values.keySet()) if (fields.stream().noneMatch(field -> field.key().equals(key))) throw new IllegalArgumentException("Unknown answer field");
        fields.forEach(field -> field.validate(values.get(field.key())));
    }
}

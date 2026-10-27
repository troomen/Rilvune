package dev.editor.agents.ui;

import java.util.ArrayList;
import java.util.List;

/** A deliberately small, literal-text format: headings, bold, inline code and fenced code.
 * No HTML, embedded resources or automatic link execution. The source remains available through Copy.
 */
final class AgentTextFormat {
    enum Style { TEXT, BOLD, CODE, HEADING, MUTED }
    record Span(String text, Style style) { }
    private AgentTextFormat() { }

    static List<Span> parse(String source) {
        List<Span> result = new ArrayList<>(); boolean fenced = false; char fence = '`'; int fenceLength = 0;
        for (String line : source.split("(?<=\n)", -1)) {
            String stripped = line.stripLeading();
            int run = 0;
            if (!stripped.isEmpty() && (stripped.charAt(0) == '`' || stripped.charAt(0) == '~')) {
                char first = stripped.charAt(0); while (run < stripped.length() && stripped.charAt(run) == first) run++;
                if (run >= 3 && (!fenced || first == fence && run >= fenceLength && stripped.substring(run).isBlank())) {
                    if (!fenced) {
                        fenced = true; fence = first; fenceLength = run;
                        String language = stripped.substring(run).strip();
                        if (!language.isEmpty()) result.add(new Span(language + "\n", Style.MUTED));
                    } else fenced = false;
                    continue;
                }
            }
            if (fenced) { result.add(new Span(line, Style.CODE)); continue; }
            int heading = 0; while (heading < Math.min(6, stripped.length()) && stripped.charAt(heading) == '#') heading++;
            if (heading > 0 && stripped.length() > heading && stripped.charAt(heading) == ' ') {
                result.add(new Span(stripped.substring(heading + 1), Style.HEADING)); continue;
            }
            int position = 0;
            while (position < line.length()) {
                int next = position;
                while (next < line.length() && line.charAt(next) != '`' && !line.startsWith("**", next)) next++;
                if (next == line.length()) { result.add(new Span(line.substring(position), Style.TEXT)); break; }
                if (next > position) result.add(new Span(line.substring(position, next), Style.TEXT));
                boolean code = line.charAt(next) == '`';
                String marker = code ? "`" : "**";
                int end = line.indexOf(marker, next + marker.length());
                if (end < 0) { result.add(new Span(line.substring(next), Style.TEXT)); break; }
                result.add(new Span(line.substring(next + marker.length(), end), code ? Style.CODE : Style.BOLD));
                position = end + marker.length();
            }
        }
        return result;
    }
}

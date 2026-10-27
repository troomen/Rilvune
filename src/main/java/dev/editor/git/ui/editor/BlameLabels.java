package dev.editor.git.ui.editor;

import dev.editor.git.domain.GitBlameLine;
import java.time.*;
import java.time.format.*;
import java.util.Locale;

/** Date formatting is explicit and independent of repository acquisition. */
final class BlameLabels {
    record Label(String shortText, String description) { }
    private final Clock clock;
    private final ZoneId zone;
    private final DateTimeFormatter date;
    BlameLabels(Clock clock, ZoneId zone, Locale locale) { this.clock = clock; this.zone = zone; date = DateTimeFormatter.ofPattern("dd MMM yyyy", locale); }
    Label format(GitBlameLine line) {
        LocalDate day = line.time().atZone(zone).toLocalDate(), today = LocalDate.now(clock.withZone(zone));
        String relative = day.equals(today) ? "Today" : day.equals(today.minusDays(1)) ? "Yesterday" : date.format(day);
        return new Label(relative + "  " + line.author(), line.author() + " · " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(line.time().atZone(line.zone())) + " · " + line.commitId() + " · " + line.subject());
    }
}

package dev.editor.agents.ui;

import dev.editor.agents.domain.AgentPermission;
import java.util.stream.Stream;

/** Literal approval text, bounded only in the summary. Full supplied data remains available for inspection. */
record AgentApprovalPresentation(String summary, String details) {
    static AgentApprovalPresentation from(AgentPermission request) {
        String input = Stream.of(request.details(), request.tool().input()).filter(value -> !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining("\n\n"));
        int end = Math.min(input.length(), 240), lines = 1;
        for (int index = 0; index < end; index++) if (input.charAt(index) == '\n' && ++lines > 2) { end = index; break; }
        if (end > 0 && end < input.length() && Character.isHighSurrogate(input.charAt(end - 1))) end--;
        boolean truncated = end < input.length();
        String summary = input.isBlank() ? "No action parameters were supplied." : input.substring(0, end) + (truncated ? "…" : "");
        String output = request.tool().output();
        String details = (truncated ? "Input\n" + input : "")
                + (output.isBlank() ? "" : (truncated ? "\n\n" : "") + "Output\n" + output);
        return new AgentApprovalPresentation(summary, details);
    }
}

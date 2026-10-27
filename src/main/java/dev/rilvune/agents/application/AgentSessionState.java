package dev.rilvune.agents.application;

import dev.rilvune.agents.domain.*;
import java.util.*;

/** Session telemetry and pending interactions. Owned by the service, or by one history-loading worker. */
final class AgentSessionState {
    private AgentUsage usage = AgentUsage.empty();
    private List<AgentCommand> commands = List.of();
    private final Map<String, AgentQuestion> questions = new LinkedHashMap<>();
    private final Map<String, AgentProgress> background = new LinkedHashMap<>();
    private AgentProgress current = AgentProgress.idle();
    void begin() { background.clear(); questions.clear(); current = new AgentProgress(AgentProgress.Kind.WAITING, "Waiting for the agent…"); }
    void waiting(String detail) { current = new AgentProgress(AgentProgress.Kind.WAITING, detail); }
    void problem(String detail) { current = new AgentProgress(AgentProgress.Kind.ERROR, detail); }
    void stopped(String detail) { end(); current = new AgentProgress(AgentProgress.Kind.STOPPED, detail); }
    void end() { background.clear(); questions.clear(); current = AgentProgress.idle(); }
    void commands(List<AgentCommand> commands) { this.commands = List.copyOf(commands); }
    AgentQuestion question(String id) { return questions.get(id); }
    void event(AgentEvent event) {
        if (event instanceof AgentEvent.Usage update) usage = usage.merge(update.usage());
        else if (event instanceof AgentEvent.Finished finished) usage = usage.merge(finished.usage());
        else if (event instanceof AgentEvent.Commands update) commands = update.commands();
        else if (event instanceof AgentEvent.Question question) questions.put(question.question().id(), question.question());
        else if (event instanceof AgentEvent.Resolved resolved) questions.remove(resolved.id());
        else if (event instanceof AgentEvent.Progress progress) {
            String key = progress.origin().id() + ":" + progress.key();
            if (progress.active()) background.put(key, progress.progress()); else { background.remove(key); if (progress.progress().kind() == AgentProgress.Kind.RETRYING || progress.progress().kind() == AgentProgress.Kind.COMPACTING) waiting("Waiting for the model…"); }
        } else if (event instanceof AgentEvent.Activity activity && activity.kind() == AgentActivityMessage.Kind.REASONING && activity.status() == AgentToolCall.Status.RUNNING)
            current = new AgentProgress(AgentProgress.Kind.THINKING, activity.origin().child() ? activity.origin().label() + " · Thinking…" : "Thinking…");
        else if (event instanceof AgentEvent.Text text && !text.text().isEmpty()) current = new AgentProgress(AgentProgress.Kind.RESPONDING, text.origin().child() ? text.origin().label() + " · Responding…" : "Responding…");
        else if (event instanceof AgentEvent.Tool tool) {
            var call = tool.call();
            if (call.status() == AgentToolCall.Status.RUNNING || call.status() == AgentToolCall.Status.PENDING)
                background.put("tool:" + call.id(), new AgentProgress(AgentProgress.Kind.TOOL, (call.origin().child() ? call.origin().label() + " · " : "") + call.title()));
            else background.remove("tool:" + call.id());
        }
    }
    AgentSessionDetails snapshot(AgentSnapshot.Phase phase, String status, boolean cancelling, boolean approval) {
        AgentProgress progress;
        if (phase == AgentSnapshot.Phase.ERROR || phase == AgentSnapshot.Phase.UNSUPPORTED) progress = new AgentProgress(AgentProgress.Kind.ERROR, status);
        else if (cancelling || phase == AgentSnapshot.Phase.STOPPING) progress = new AgentProgress(AgentProgress.Kind.STOPPING, status);
        else if (approval) progress = new AgentProgress(AgentProgress.Kind.APPROVAL, "Approval required");
        else if (!questions.isEmpty()) progress = new AgentProgress(AgentProgress.Kind.QUESTION, "Waiting for your answer");
        else if (phase == AgentSnapshot.Phase.BUSY) progress = background.values().stream()
                .max(Comparator.comparingInt(AgentSessionState::priority)).orElse(current);
        else if (phase == AgentSnapshot.Phase.READY) progress = current.kind() == AgentProgress.Kind.ERROR || current.kind() == AgentProgress.Kind.STOPPED ? current : new AgentProgress(AgentProgress.Kind.IDLE, status);
        else progress = new AgentProgress(phase == AgentSnapshot.Phase.STARTING || phase == AgentSnapshot.Phase.INSTALLING || phase == AgentSnapshot.Phase.AUTHORIZING || phase == AgentSnapshot.Phase.REVERTING ? AgentProgress.Kind.WAITING : AgentProgress.Kind.IDLE, status);
        return new AgentSessionDetails(progress, usage, commands, List.copyOf(questions.values()));
    }
    private static int priority(AgentProgress progress) { return switch (progress.kind()) { case RETRYING -> 3; case COMPACTING -> 2; default -> 1; }; }
}

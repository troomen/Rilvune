package dev.rilvune.agents.application;

import dev.rilvune.agents.domain.*;
import java.util.*;

/** Bounded display projection. Public rich content and provenance survive; native saved history is never trimmed. */
final class AgentTranscript {
    private final List<AgentMessage> messages = new ArrayList<>();
    private final Map<String, Long> entries = new HashMap<>();
    private long sequence, reply, user;
    private String wireMessage = "";
    private boolean truncated;
    private AgentSettings settings = AgentSettings.defaults();
    void settings(AgentSettings value) { settings = Objects.requireNonNull(value); trim(); }
    List<AgentMessage> messages() { return List.copyOf(messages); }
    boolean truncated() { return truncated; }
    void append(AgentMessage.Role role, String text) {
        reply = 0; wireMessage = ""; if (role == AgentMessage.Role.USER) entries.keySet().removeIf(key -> key.startsWith("activity:")); messages.add(new AgentTextMessage(++sequence, role, bounded(text, 65536))); trim();
        if (role == AgentMessage.Role.USER) user = sequence;
    }
    void event(AgentEvent event) {
        if (event instanceof AgentEvent.Text text && !text.text().isEmpty()) {
            if (text.role() == AgentMessage.Role.ASSISTANT && !text.messageId().isBlank()) linkUser(new AgentRevertPoint(AgentRevertPoint.Kind.ASSISTANT_MESSAGE, text.messageId()), text.origin());
            if (text.role() == AgentMessage.Role.ASSISTANT) finishReasoning(text.origin(), "");
            AgentMessage last = messages.isEmpty() ? null : messages.getLast();
            if (reply != 0 && last instanceof AgentTextMessage && last.id() == reply && last.role() == text.role() && last.origin().equals(text.origin())
                    && (text.messageId().isEmpty() || wireMessage.equals(text.messageId()))) {
                messages.set(messages.size() - 1, new AgentTextMessage(reply, text.role(), bounded(last.text() + text.text(), 65536), text.origin(), ((AgentTextMessage)last).revertPoint()));
            } else { if (text.role() == AgentMessage.Role.USER) entries.keySet().removeIf(key -> key.startsWith("activity:")); reply = ++sequence; messages.add(new AgentTextMessage(reply, text.role(), bounded(text.text(), 65536), text.origin())); }
            wireMessage = text.messageId();
            if (text.role() == AgentMessage.Role.USER && !text.origin().child()) {
                user = reply;
                if (!text.messageId().isBlank()) linkUser(new AgentRevertPoint(AgentRevertPoint.Kind.USER_MESSAGE, text.messageId()), text.origin());
            }
        } else if (event instanceof AgentEvent.Content content) {
            if (content.role() == AgentMessage.Role.ASSISTANT) finishReasoning(content.origin(), "");
            reply = 0; messages.add(new AgentContentMessage(++sequence, content.role(), content.content(), content.origin()));
        } else if (event instanceof AgentEvent.Tool tool) {
            AgentToolCall call = tool.call();
            if (!call.id().isBlank()) linkUser(new AgentRevertPoint(AgentRevertPoint.Kind.TOOL_CALL, call.id()), call.origin());
            if (find("tool:" + call.id()) == null) finishReasoning(call.origin(), "");
            replace("tool:" + call.id(), id -> new AgentToolMessage(id, call));
        } else if (event instanceof AgentEvent.Activity activity) {
            if (activity.kind() == AgentActivityMessage.Kind.REASONING && !activity.messageId().isBlank()) linkUser(new AgentRevertPoint(AgentRevertPoint.Kind.ASSISTANT_MESSAGE, activity.messageId()), activity.origin());
            if (activity.kind() == AgentActivityMessage.Kind.REASONING) finishReasoning(activity.origin(), activity.key());
            String key = "activity:" + activity.key(); AgentActivityMessage prior = find(key) instanceof AgentActivityMessage saved ? saved : null;
            String text = activity.append() && prior != null ? prior.text() + activity.text() : activity.text().isEmpty() && prior != null ? prior.text() : activity.text();
            replace(key, id -> new AgentActivityMessage(id, activity.key(), activity.kind(), activity.title(), bounded(text, 131072), activity.status(), activity.origin()));
        }
        trim();
    }
    private void linkUser(AgentRevertPoint point, AgentOrigin origin) {
        if (origin.child() || user == 0) return;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AgentTextMessage text && text.id() == user && text.revertPoint() == null) {
                messages.set(index, text.withRevertPoint(point)); return;
            }
        }
    }
    private AgentMessage find(String key) { Long id = entries.get(key); return id == null ? null : messages.stream().filter(message -> message.id() == id).findFirst().orElse(null); }
    private void finishReasoning(AgentOrigin origin, String continuingKey) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof AgentActivityMessage activity && activity.kind() == AgentActivityMessage.Kind.REASONING
                    && activity.origin().id().equals(origin.id()) && !activity.key().equals(continuingKey) && active(activity.status()))
                messages.set(index, new AgentActivityMessage(activity.id(), activity.key(), activity.kind(), activity.title(), activity.text(), AgentToolCall.Status.COMPLETED, activity.origin()));
        }
    }
    private void replace(String key, java.util.function.LongFunction<AgentMessage> create) {
        Long id = entries.get(key);
        if (id == null) { reply = 0; id = ++sequence; entries.put(key, id); messages.add(create.apply(id)); }
        else for (int index = 0; index < messages.size(); index++) if (messages.get(index).id() == id) { messages.set(index, create.apply(id)); return; }
    }
    void settleTools() { finish(true); }
    void finish(boolean cancelled) {
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if (message instanceof AgentToolMessage tool && active(tool.call().status())) messages.set(index, new AgentToolMessage(tool.id(), tool.call().withStatus(AgentToolCall.Status.CANCELLED)));
            else if (message instanceof AgentActivityMessage activity && active(activity.status())) messages.set(index, new AgentActivityMessage(activity.id(), activity.key(), activity.kind(), activity.title(), activity.text(),
                    !cancelled && activity.kind() == AgentActivityMessage.Kind.REASONING ? AgentToolCall.Status.COMPLETED : AgentToolCall.Status.CANCELLED, activity.origin()));
        }
    }
    private static boolean active(AgentToolCall.Status status) { return status == AgentToolCall.Status.PENDING || status == AgentToolCall.Status.RUNNING; }
    private void trim() {
        long weight = messages.stream().mapToLong(AgentMessage::retainedCharacters).sum();
        while (!messages.isEmpty() && (messages.size() > settings.displayMessageLimit() || weight > (long) settings.displayCharacterLimitMiB() * 1024 * 1024)) { weight -= messages.removeFirst().retainedCharacters(); truncated = true; }
        Set<Long> retained = new HashSet<>(); messages.forEach(message -> retained.add(message.id())); entries.values().removeIf(id -> !retained.contains(id));
    }
    private static String bounded(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit; return text.substring(0, end) + "\n[Display truncated]";
    }
}

package dev.rilvune.agents.domain;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Blocking adapter operations run on supplied workers, never on the UI thread. */
public interface AgentRuntime extends AutoCloseable {
    record Setup(boolean authenticated, AgentChatPage chats) { }
    Setup start() throws Exception;
    AgentLogin beginLogin() throws Exception;
    Setup awaitLogin() throws Exception;
    AgentSession newSession() throws Exception;
    /** Replay saved display events while restoring the original agent context; never resubmit old prompts. */
    AgentSession loadSession(String id, Consumer<AgentEvent> history) throws Exception;
    AgentChatPage listSessions(String cursor) throws Exception;
    void deleteSession(String id) throws Exception;
    AgentConfiguration selectModel(String id) throws Exception;
    AgentConfiguration selectEffort(String id) throws Exception;
    default AgentConfiguration selectOption(String id, String value) throws Exception { throw new UnsupportedOperationException("Session options are not supported"); }
    default void answer(String id, AgentQuestionAnswer answer) throws Exception { throw new UnsupportedOperationException("Questions are not supported"); }
    default AgentSession forkSession() throws Exception { throw new UnsupportedOperationException("Forking is not supported"); }
    /** Fork before the selected user turn. Does not restore files or destroy the original session. */
    default RevertedConversation revertConversation(AgentRevertPoint point) throws Exception { throw new UnsupportedOperationException("Conversation revert is not supported"); }
    record RevertedConversation(String sessionId, String prompt) { }
    void prompt(String text, BooleanSupplier cancelled) throws Exception;
    default void prompt(AgentPrompt prompt, BooleanSupplier cancelled) throws Exception {
        if (!prompt.attachments().isEmpty()) throw new UnsupportedOperationException("This agent does not support attachments");
        prompt(prompt.text(), cancelled);
    }
    void cancel() throws Exception;
    void permission(String requestId, String optionId) throws Exception;
    boolean alive();
    @Override void close();
}

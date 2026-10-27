package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.theme.FontCatalog;
import org.junit.jupiter.api.*;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static dev.editor.agents.domain.AgentSnapshot.Phase.BUSY;
import static dev.editor.agents.ui.AgentReasoningViewTest.named;
import static dev.editor.agents.ui.AgentReasoningViewTest.snapshot;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the real EDT queue to reproduce navigation racing a streaming render's deferred restoration. */
@Tag("ui") @Timeout(15)
class AgentConversationNavigationIntegrationTest {
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private AgentConversationView conversation;
    private JFrame frame;

    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }
    @AfterEach void close() throws Exception {
        edt(() -> { if (frame != null) frame.dispose(); return null; }); workers.shutdownNow();
    }

    @Test void toolRevealWinsOverRestorationQueuedByAnInterleavedStreamingRender() throws Exception {
        openConversation();
        int originalPosition = edt(() -> scroll().getVerticalScrollBar().getValue());
        CompletableFuture<Point> revealed = new CompletableFuture<>();
        edt(() -> {
            // The render runs after the intent, but before its deferred reveal. Its restoration
            // is consequently queued AFTER that reveal and must not undo the explicit navigation.
            SwingUtilities.invokeLater(() -> conversation.render(snapshot(BUSY, messages(" More streamed text."))));
            conversation.revealTool("target");
            SwingUtilities.invokeLater(() -> revealed.complete(scroll().getViewport().getViewPosition()));
            return null;
        });
        Point position = revealed.get(5, TimeUnit.SECONDS);
        edt(() -> {
            assertTrue(position.y < originalPosition, "The reveal must actually move away from the later answer");
            assertEquals(position, scroll().getViewport().getViewPosition(), "Queued transcript restoration must not undo the reveal");
            var disclosure = named(conversation, "agents.tool.details", JToggleButton.class);
            assertTrue(disclosure.isSelected()); assertTrue(disclosure.getVisibleRect().height > 0);
            assertTrue(named(conversation, "agents.latest", JButton.class).isVisible());
            conversation.render(snapshot(BUSY, messages(" More streamed text. And another update.")));
            return null;
        });
        edt(() -> {
            assertEquals(position, scroll().getViewport().getViewPosition(), "Later streaming must preserve the revealed reading position");
            return null;
        });
    }

    @Test void queuedToolRevealYieldsToANewerFollowLatestRequest() throws Exception {
        openConversation();
        edt(() -> { conversation.revealTool("target"); conversation.followLatest(); return null; });
        awaitLatest();
        edt(() -> { assertFalse(named(conversation, "agents.latest", JButton.class).isVisible()); return null; });
    }

    private void openConversation() throws Exception {
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            conversation = new AgentConversationView(theme, ignored -> { }, workers, new ArrayList<>());
            frame = new JFrame("Navigation regression"); frame.setContentPane(conversation);
            frame.setSize(420, 640); frame.setVisible(true);
            conversation.render(snapshot(BUSY, messages(""))); return null;
        });
        awaitLatest();
    }

    private void awaitLatest() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(() -> {
            JScrollBar bar = scroll().getVerticalScrollBar();
            return bar.getMaximum() > bar.getVisibleAmount() && bar.getMaximum() - bar.getValue() - bar.getVisibleAmount() < 2;
        })) {
            if (System.nanoTime() > deadline) fail("Conversation did not settle at the latest output");
            Thread.sleep(10);
        }
    }

    private JScrollPane scroll() { return named(conversation, "agents.conversation.scroll", JScrollPane.class); }
    private static List<AgentMessage> messages(String suffix) {
        return List.of(new AgentTextMessage(1, AgentMessage.Role.USER, "Inspect the source"),
                new AgentToolMessage(2, new AgentToolCall("target", "Inspect source", AgentToolCall.Status.COMPLETED, "", "Selected output\n".repeat(40))),
                new AgentTextMessage(3, AgentMessage.Role.ASSISTANT, "A much later answer that fills the conversation.\n".repeat(100) + suffix));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}

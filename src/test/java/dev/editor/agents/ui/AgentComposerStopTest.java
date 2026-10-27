package dev.editor.agents.ui;

import dev.editor.agents.domain.AgentProgress;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.platform.ui.Glyph;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.Dimension;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.editor.agents.domain.AgentSnapshot.Phase.*;
import static dev.editor.agents.ui.AgentReasoningViewTest.named;
import static dev.editor.agents.ui.AgentReasoningViewTest.snapshot;
import static org.junit.jupiter.api.Assertions.*;

class AgentComposerStopTest {
    @Test void stopRemainsACompactRedIconAndCancellationKeepsItsBehavior() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            AtomicInteger cancellations = new AtomicInteger();
            var composer = new AgentComposerView(theme, ignored -> { }, ignored -> { }, ignored -> { },
                    (id, value) -> { }, ignored -> 0, cancellations::incrementAndGet);
            var stop = named(composer, "agents.stop", JButton.class);
            var send = named(composer, "agents.send", JButton.class);
            composer.render(snapshot(READY, List.of()));
            assertFalse(stop.isVisible()); assertTrue(send.isVisible());
            composer.offer("Ready to send"); assertTrue(send.isEnabled());
            assertTrue(send.getText() == null || send.getText().isEmpty());
            assertEquals(new Dimension(28, 28), send.getPreferredSize());
            Map<?, ?> sendStyle = assertInstanceOf(Map.class, send.getClientProperty("FlatLaf.style"));
            Color sendHover = assertInstanceOf(Color.class, sendStyle.get("toolbar.hoverBackground"));
            Color sendPressed = assertInstanceOf(Color.class, sendStyle.get("toolbar.pressedBackground"));
            assertTrue(sendHover.getBlue() > sendHover.getRed());
            assertTrue(sendPressed.getBlue() > sendHover.getBlue());
            composer.render(snapshot(BUSY, List.of()));
            assertTrue(stop.isVisible()); assertTrue(stop.isEnabled()); assertFalse(send.isVisible());
            assertTrue(stop.getText() == null || stop.getText().isEmpty());
            assertEquals(new Dimension(28, 28), stop.getPreferredSize());
            assertEquals("toolBarButton", stop.getClientProperty("JButton.buttonType"));
            Map<?, ?> stopStyle = assertInstanceOf(Map.class, stop.getClientProperty("FlatLaf.style"));
            Color stopHover = assertInstanceOf(Color.class, stopStyle.get("toolbar.hoverBackground"));
            Color stopPressed = assertInstanceOf(Color.class, stopStyle.get("toolbar.pressedBackground"));
            assertTrue(stopHover.getRed() > stopHover.getBlue());
            assertTrue(stopPressed.getRed() > stopHover.getRed());
            assertNotEquals(sendHover, stopHover);
            Glyph icon = assertInstanceOf(Glyph.class, stop.getIcon());
            assertEquals(Glyph.Kind.STOP, icon.kind()); assertEquals(18, icon.size());
            assertTrue(icon.color().getRed() > icon.color().getGreen() + 80);
            stop.doClick(); assertEquals(1, cancellations.get());
            var escape = composer.getActionMap().get("agent-stop");
            escape.actionPerformed(new java.awt.event.ActionEvent(composer, 0, "agent-stop"));
            assertEquals(2, cancellations.get());

            composer.render(snapshot(BUSY, List.of(), new AgentProgress(AgentProgress.Kind.STOPPING, "Stopping…")));
            assertFalse(stop.isEnabled()); assertEquals("Stopping response…", stop.getToolTipText());
            SwingUtilities.updateComponentTreeUI(composer); composer.applyTheme(theme);
            assertEquals(new Dimension(28, 28), stop.getPreferredSize());
            assertTrue(stop.getText() == null || stop.getText().isEmpty());
            assertEquals(icon.color(), assertInstanceOf(Glyph.class, stop.getIcon()).color());
            assertEquals(sendStyle, send.getClientProperty("FlatLaf.style"));
            assertEquals(stopStyle, stop.getClientProperty("FlatLaf.style"));
            escape.actionPerformed(new java.awt.event.ActionEvent(composer, 0, "agent-stop"));
            stop.doClick(); assertEquals(2, cancellations.get());
            composer.render(snapshot(READY, List.of()));
            assertFalse(stop.isVisible()); assertTrue(send.isVisible());
            escape.actionPerformed(new java.awt.event.ActionEvent(composer, 0, "agent-stop"));
            assertEquals(2, cancellations.get());
        });
    }
}

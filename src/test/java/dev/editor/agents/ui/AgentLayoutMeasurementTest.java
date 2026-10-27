package dev.editor.agents.ui;

import dev.editor.agents.domain.AgentMessage;
import dev.editor.agents.domain.AgentTextMessage;
import dev.editor.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentLayoutMeasurementTest {
    @Test void measuringAValidatedTranscriptDoesNotInvalidateBoxLayoutDuringChildReflow() throws Exception {
        edt(() -> {
            var theme = EditorTheme.graphite(); theme.install();
            var column = new AgentUi.Column();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 4; i++) column.add(new AgentMessageView(
                        new AgentTextMessage(i, AgentMessage.Role.ASSISTANT, "A long wrapping message with text and details. ".repeat(30)),
                        "Assistant", theme, workers, new ArrayList<>()));
                var scroll = new JScrollPane(column); scroll.addNotify();
                try {
                    for (int width : new int[]{430, 340, 500, 300, 600}) {
                        scroll.setSize(width, 600); scroll.validate();
                        for (int repeat = 0; repeat < 5; repeat++) {
                            Dimension preferred = assertDoesNotThrow(column::getPreferredSize);
                            assertTrue(preferred.height > 0);
                        }
                    }
                } finally { scroll.removeNotify(); }
            }
            return null;
        });
    }

    @Test void preferredTextMeasurementDoesNotResizeTheComponentOrLoseSelection() throws Exception {
        edt(() -> {
            var text = new AgentTextView(EditorTheme.graphite());
            var parent = new JPanel(new BorderLayout()); parent.setSize(430, 600); parent.add(text);
            text.showText("A wrapping message. ".repeat(30), false); text.setBounds(0, 0, 120, 80); text.select(0, 10);
            Rectangle bounds = text.getBounds(); String selected = text.getSelectedText();
            for (int i = 0; i < 5; i++) {
                text.getPreferredSize(); assertEquals(bounds, text.getBounds()); assertEquals(selected, text.getSelectedText());
            }
            return null;
        });
    }

    @Test void columnMeasurementsUseNormalLayoutInvalidationInsteadOfClearingCachesOnEveryQuery() throws Exception {
        edt(() -> {
            var column = new AgentUi.Column(); var invalidations = new AtomicInteger();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS) {
                @Override public void invalidateLayout(Container target) { invalidations.incrementAndGet(); super.invalidateLayout(target); }
            });
            column.add(new JLabel("Stable content")); column.getPreferredSize(); invalidations.set(0);
            for (int i = 0; i < 5; i++) column.getPreferredSize();
            assertEquals(0, invalidations.get()); return null;
        });
    }

    @Test void boundedScrollMeasurementsClampContentWithoutMutatingPreferredSizeProperties() throws Exception {
        edt(() -> {
            var height = new AtomicInteger(40);
            var content = new JPanel() {
                @Override public Dimension getPreferredSize() { return new Dimension(200, height.get()); }
            };
            var scroll = AgentUi.boundedScroll(content, 90, 320, 4);
            var changes = new AtomicInteger(); scroll.addPropertyChangeListener("preferredSize", event -> changes.incrementAndGet());
            assertEquals(new Dimension(0, 90), scroll.getPreferredSize());
            height.set(180); assertEquals(new Dimension(0, 184), scroll.getPreferredSize());
            height.set(1000); assertEquals(new Dimension(0, 320), scroll.getPreferredSize());
            assertEquals(0, changes.get()); assertFalse(scroll.isPreferredSizeSet());
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            return null;
        });
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}

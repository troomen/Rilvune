package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.agents.domain.content.AgentContent;
import dev.rilvune.platform.theme.EditorTheme;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentApprovalFilesViewTest {
    @Test void everySuppliedDiffIsReviewedUnderItsUniqueFile() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var reviewed = new ArrayList<AgentContent.Diff>();
            var view = new AgentApprovalFilesView(EditorTheme.graphite(), reviewed::add);
            var first = new AgentContent.Diff("src/A.java", "a", "b");
            var second = new AgentContent.Diff("src/A.java", "b", "c");
            var third = new AgentContent.Diff("src/B.java", "before", "after");
            view.render(tool("edit", List.of(new AgentLocation("src/C.java", 2)), List.of(first, second, third)));
            assertTrue(all(view, JLabel.class).stream().anyMatch(label -> "Files · 3".equals(label.getText())));
            var rows = all(view, JPanel.class).stream().filter(row -> "agents.permission.file".equals(row.getName())).toList();
            assertEquals(3, rows.size());
            assertEquals(List.of("Review change 1", "Review change 2"), all(rows.getFirst(), JButton.class).stream().map(JButton::getText).toList());
            all(view, JButton.class).forEach(AbstractButton::doClick); assertEquals(List.of(first, second, third), reviewed);
            assertTrue(all(rows.getLast(), JLabel.class).stream().anyMatch(label -> "Diff unavailable".equals(label.getText())));
            assertTrue(all(rows.getLast(), JLabel.class).stream().anyMatch(label -> "Location: src/C.java:3".equals(label.getToolTipText())));
        });
    }
    @Test void missingChangesAndLiteralPathsHaveUnambiguousPresentation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var view = new AgentApprovalFilesView(EditorTheme.graphite(), ignored -> fail("No diff was supplied"));
            view.render(tool("edit", List.of(), List.of())); assertTrue(view.isVisible());
            assertTrue(all(view, JLabel.class).stream().anyMatch(label -> label.getText().contains("Diff unavailable")));
            view.render(tool("execute", List.of(), List.of())); assertFalse(view.isVisible());
            String path = "<html>/literal<&>.java";
            view.render(tool("edit", List.of(new AgentLocation(path, 0)), List.of()));
            for (JLabel label : all(view, JLabel.class)) if (label.getToolTipText() != null && label.getToolTipText().contains(path)) {
                assertFalse(label.getToolTipText().startsWith("<html>"));
                JToolTip tooltip = label.createToolTip(); tooltip.setTipText(label.getToolTipText());
                assertNull(tooltip.getClientProperty(BasicHTML.propertyKey));
            }
            assertTrue(all(view, JLabel.class).stream().anyMatch(label -> "literal<&>.java".equals(label.getText())));
        });
    }
    private static AgentToolCall tool(String kind, List<AgentLocation> locations, List<AgentContent> content) {
        return new AgentToolCall("tool", "Change files", AgentToolCall.Status.PENDING, "", "", kind, locations, content, AgentOrigin.MAIN);
    }
    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container container) result.addAll(all(container, type)); }
        return result;
    }
}

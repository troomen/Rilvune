package dev.editor.agents.ui;

import dev.editor.agents.domain.content.AgentContent;
import dev.editor.diff.domain.TextSnapshot;
import dev.editor.diff.infrastructure.MyersDiffEngine;
import dev.editor.diff.infrastructure.DiffLimits;
import dev.editor.diff.ui.SnapshotDiffDialog;
import dev.editor.platform.text.ui.TextEditorSettings;
import dev.editor.platform.theme.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Shared rich-content renderer for replies, tool results and approvals. Never dereferences resource URIs. */
final class AgentContentView extends AgentUi.Column {
    private final ExecutorService workers;
    private final List<SnapshotDiffDialog> dialogs;
    private EditorTheme theme;
    private List<AgentContent> content = List.of();
    private long generation;
    AgentContentView(EditorTheme theme, ExecutorService workers, List<SnapshotDiffDialog> dialogs) {
        this.theme = theme; this.workers = workers; this.dialogs = dialogs;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setOpaque(false);
    }
    void render(List<AgentContent> next) {
        if (content.equals(next)) return; content = List.copyOf(next); long version = ++generation; removeAll();
        for (AgentContent block : content) {
            JComponent view = view(block, version); view.setAlignmentX(LEFT_ALIGNMENT); add(view); add(Box.createVerticalStrut(7));
        }
        UiTypography.apply(this, theme); revalidate(); repaint();
    }
    private JComponent view(AgentContent block, long version) {
        if (block instanceof AgentContent.Text text) return text(text.text(), false);
        if (block instanceof AgentContent.Diff diff) {
            JPanel panel = column(); JButton review = AgentUi.quietButton("Review changes · " + fileName(diff.path()), "agents.diff.review", () -> review(diff));
            review.setToolTipText("File: " + diff.path()); panel.add(review);
            JTabbedPane sides = new JTabbedPane(); sides.setName("agents.diff.preview");
            sides.addTab("Before", scroll(text(diff.oldText().isEmpty() ? "Empty file" : diff.oldText(), false), 150));
            sides.addTab("After", scroll(text(diff.newText().isEmpty() ? "Empty file" : diff.newText(), false), 150)); panel.add(sides); return panel;
        }
        if (block instanceof AgentContent.Resource resource) {
            JPanel panel = column(); panel.add(text((resource.name().isBlank() ? "Resource" : resource.name()) + "\n" + resource.uri() + (resource.mimeType().isBlank() ? "" : "\n" + resource.mimeType()), false));
            if (!resource.text().isEmpty()) panel.add(text(resource.text(), false)); return panel;
        }
        if (block instanceof AgentContent.Media media) {
            JPanel panel = column(); JLabel caption = AgentUi.label(media.text()); caption.setToolTipText(media.text()); panel.add(caption);
            if (!media.data().isBlank()) {
                JButton copy = AgentUi.quietButton("Copy encoded data", "agents.content.copy", () -> AgentUi.copy(media.data())); panel.add(copy);
                if (media.mimeType().startsWith("image/") || media.kind().equals("image")) {
                    JLabel preview = AgentUi.label("Loading preview…"); preview.setName("agents.content.image"); panel.add(preview);
                    CompletableFuture.supplyAsync(() -> thumbnail(media.data()), workers).whenComplete((image, failure) -> SwingUtilities.invokeLater(() -> {
                        if (version != generation) { if (image != null) image.flush(); return; }
                        preview.setText(failure == null ? "" : "Preview unavailable · " + rootMessage(failure));
                        if (image != null) preview.setIcon(new ImageIcon(image)); panel.revalidate(); panel.repaint();
                    }));
                }
            } else panel.add(text("Referenced content · preview is not embedded", false));
            return panel;
        }
        if (block instanceof AgentContent.Terminal terminal) return text("Terminal " + terminal.id() + " · output is owned by the agent", false);
        AgentContent.Unknown unknown = (AgentContent.Unknown) block;
        return text("Additional content · " + unknown.kind() + "\n" + unknown.text(), false);
    }
    private AgentTextView text(String value, boolean formatted) { AgentTextView view = new AgentTextView(theme); view.setName("agents.content.text"); view.showText(value, formatted); return view; }
    static JScrollPane scroll(JComponent view, int height) {
        JScrollPane scroll = new JScrollPane(view); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setPreferredSize(new Dimension(0, height)); scroll.getVerticalScrollBar().setUnitIncrement(20); return scroll;
    }
    private JPanel column() { JPanel panel = new JPanel(); panel.setOpaque(false); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel; }
    void review(AgentContent.Diff diff) {
        SnapshotDiffDialog dialog = new SnapshotDiffDialog(SwingUtilities.getWindowAncestor(this), "Agent change · " + fileName(diff.path()),
                new SnapshotDiffDialog.Source("Before", diff.path(), new TextSnapshot("agent:before", 0, diff.oldText())),
                new SnapshotDiffDialog.Source("After", diff.path(), new TextSnapshot("agent:after", 0, diff.newText())),
                new TextEditorSettings(4, true, true, true, false, false), theme, new MyersDiffEngine(DiffLimits.interactive()), workers, ignored -> { });
        dialogs.add(dialog); dialog.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent event) { dialogs.remove(dialog); } }); dialog.setVisible(true);
    }
    void applyTheme(EditorTheme theme) { this.theme = theme; List<AgentContent> saved = content; content = List.of(); render(saved); }
    private static String fileName(String path) { int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); return path.substring(slash + 1); }
    private static String rootMessage(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return Objects.toString(failure.getMessage(), "Unsupported content"); }
    private static BufferedImage thumbnail(String encoded) {
        try {
            if (encoded.length() > 2 * 1024 * 1024) throw new IOException("Embedded image exceeds the preview limit");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream); ImageReader reader = null;
                while (readers.hasNext()) { var candidate = readers.next(); if ("java.desktop".equals(candidate.getOriginatingProvider().getClass().getModule().getName())) { reader = candidate; break; } candidate.dispose(); }
                if (reader == null) throw new IOException("Image format is not supported by Java");
                try {
                    reader.setInput(stream, true, true); int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long)width * height > 16777216) throw new IOException("Image dimensions exceed the preview limit");
                    var parameters = reader.getDefaultReadParam(); int sample = Math.max(1, (int)Math.ceil(Math.max(width / 260.0, height / 180.0))); parameters.setSourceSubsampling(sample, sample, 0, 0);
                    return reader.read(0, parameters);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException failure) { throw new CompletionException(failure); }
    }
}

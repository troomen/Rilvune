package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.AgentAttachment;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

/** Compact, keyboard-accessible attachment shelf; preview failures never remove an attachment. */
final class AgentAttachmentsView extends JPanel implements Scrollable {
    private List<AgentAttachment> attachments = List.of();
    private final Consumer<String> remove;
    private final Function<AgentAttachment, Path> path;
    private final Executor workers;
    private EditorTheme theme;
    private long generation;
    AgentAttachmentsView(EditorTheme theme, Consumer<String> remove, Function<AgentAttachment, Path> path, Executor workers) {
        this.theme = theme; this.remove = remove; this.path = path; this.workers = workers;
        setName("agents.attachments"); setOpaque(false); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }
    void render(List<AgentAttachment> value) {
        if (attachments.equals(value)) return;
        attachments = List.copyOf(value); long version = ++generation; removeAll();
        for (var attachment : attachments) {
            JPanel row = new JPanel(new BorderLayout(8, 0)); row.setOpaque(false); row.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            JLabel preview = AgentUi.label(""); preview.setIcon(new Glyph(Glyph.Kind.FILE, theme.muted(), 22)); preview.setPreferredSize(new Dimension(44, 36)); row.add(preview, BorderLayout.WEST);
            JLabel name = AgentUi.label(attachment.name()); name.setForeground(theme.text()); name.setToolTipText(attachment.name() + " · " + attachment.mimeType() + " · " + size(attachment.size()) + " · Saved snapshot" + (attachment.sourceUri().isEmpty() ? "" : " · " + attachment.sourceUri())); row.add(name);
            JButton close = UiComponents.iconButton(theme, Glyph.Kind.CLOSE, "Remove " + attachment.name(), () -> remove.accept(attachment.key())); close.setName("agents.attachment.remove"); row.add(close, BorderLayout.EAST); add(row);
            if (attachment.image()) CompletableFuture.supplyAsync(() -> {
                try { return AgentAttachmentImages.thumbnail(path.apply(attachment), 44, 36); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, workers).whenComplete((image, failure) -> SwingUtilities.invokeLater(() -> {
                if (version != generation) { if (image != null) image.flush(); return; }
                if (image != null) preview.setIcon(new ImageIcon(image));
                else preview.setToolTipText("Image attached · preview unavailable");
                revalidate(); repaint();
            }));
        }
        UiTypography.apply(this, theme); revalidate(); repaint();
    }
    void applyTheme(EditorTheme value) { theme = value; var saved = attachments; attachments = List.of(); render(saved); }
    public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 22; }
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(22, visible.height - 22); }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return false; }
    private static String size(long bytes) { return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0) : String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024)); }
}

package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.ToLongFunction;
import java.util.List;
import java.util.Map;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;

/** Draft editing stays available during a response; sending and stopping remain distinct actions. */
final class AgentComposerView extends JPanel {
    private final JTextArea input = new JTextArea(3, 20);
    private final CompactButton models = new CompactButton(160), effort = new CompactButton(90), options = new CompactButton(110);
    private final JButton attachment, commands;
    private String chatId = "";
    private EditorTheme theme;
    private final ToLongFunction<String> draftChanged;
    private long draftRevision = -1;
    private final JButton send, stop;
    private final JTextArea hint = AgentUi.text("agents.composer.hint");
    private final JLabel context = AgentUi.label("");
    private final JPanel box = new JPanel(new BorderLayout(0, 6));
    private AgentSnapshot snapshot;
    private boolean updating;
    private boolean sendOnEnter = true;
    private final Consumer<String> submit;
    private AgentAttachmentsView attachments;
    private JScrollPane attachmentScroll;
    private final JTextArea attachmentError = AgentUi.text("agents.attachment.error");
    private final JPanel notices = new JPanel(new BorderLayout(0, 3));
    private Runnable chooseAttachments = () -> { };

    AgentComposerView(EditorTheme theme, Consumer<String> submit, Consumer<String> select, Consumer<String> selectEffort, BiConsumer<String, String> selectOption, ToLongFunction<String> draftChanged, Runnable cancel) {
        super(new BorderLayout(0, 7)); this.submit = submit; this.draftChanged = draftChanged; setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setName("agents.composerView"); input.setName("agents.composer"); input.setLineWrap(true); input.setWrapStyleWord(true);
        input.setMargin(new Insets(9, 9, 6, 9)); input.putClientProperty("JTextArea.placeholderText", "Ask about your project…");
        hint.setRows(1); hint.setFocusable(false);
        input.getAccessibleContext().setAccessibleName("Message to agent");
        JScrollPane scroll = new JScrollPane(input); scroll.setBorder(BorderFactory.createEmptyBorder());
        box.add(scroll); add(context, BorderLayout.NORTH); add(box);
        models.setName("agents.model"); models.getAccessibleContext().setAccessibleName("Agent model");
        effort.setName("agents.effort"); effort.getAccessibleContext().setAccessibleName("Reasoning effort");
        models.addActionListener(event -> {
            if (snapshot == null) return;
            new AgentChoicePopup("Model", snapshot.models().stream().map(model -> new AgentChoicePopup.Choice(model.id(), model.name())).toList(),
                    snapshot.selectedModel(), this.theme, select).show(models, 0, models.getHeight());
        });
        effort.addActionListener(event -> {
            if (snapshot == null) return;
            new AgentChoicePopup("Effort", snapshot.efforts().stream().map(option -> new AgentChoicePopup.Choice(option.id(), option.name())).toList(),
                    snapshot.selectedEffort(), this.theme, selectEffort).show(effort, 0, effort.getHeight());
        });
        attachment = UiComponents.iconButton(theme, Glyph.Kind.PAPERCLIP, "Attach files… (up to 10 files, 20 MiB total)", () -> chooseAttachments.run());
        attachment.setName("agents.attach"); attachment.setEnabled(false); attachment.getAccessibleContext().setAccessibleDescription("Attach files, drop files here, or paste a copied image");
        options.setName("agents.options"); options.getAccessibleContext().setAccessibleName("Agent mode and session options");
        options.addActionListener(event -> {
            if (snapshot == null) return;
            var extra = extraOptions();
            if (extra.size() == 1) {
                var option = extra.getFirst(); new AgentChoicePopup(option.name(), option.choices().stream().map(choice -> new AgentChoicePopup.Choice(choice.id(), choice.name())).toList(),
                        option.value(), this.theme, value -> selectOption.accept(option.id(), value)).show(options, 0, options.getHeight());
            } else {
                JPopupMenu menu = new JPopupMenu();
                for (var option : extra) { JMenu group = new JMenu(option.name()); group.putClientProperty("html.disable", true);
                    for (var choice : option.choices()) { JRadioButtonMenuItem item = new JRadioButtonMenuItem(choice.name(), choice.id().equals(option.value())); item.putClientProperty("html.disable", true); item.addActionListener(action -> selectOption.accept(option.id(), choice.id())); group.add(item); } menu.add(group);
                } UiTypography.apply(menu, this.theme); menu.show(options, 0, options.getHeight());
            }
        });
        commands = UiComponents.iconButton(theme, Glyph.Kind.TERMINAL, "Commands", this::showCommands); commands.setName("agents.commands");
        JPanel utilities = AgentUi.row(); utilities.add(attachment); utilities.add(commands);
        JPanel buttons = AgentUi.row(); send = UiComponents.iconButton(theme, Glyph.Kind.SEND, "Send message (Enter)", this::send); send.setName("agents.send");
        stop = UiComponents.iconButton(theme, Glyph.Kind.STOP, "Stop response (Esc)", cancel); stop.setName("agents.stop");
        buttons.add(stop); buttons.add(send);
        box.add(new AgentComposerToolbar(List.of(models, effort, options), utilities, buttons), BorderLayout.SOUTH);
        notices.setOpaque(false); notices.add(hint); notices.add(attachmentError, BorderLayout.SOUTH); attachmentError.setVisible(false); add(notices, BorderLayout.SOUTH);
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "agent-send");
        input.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "agent-send");
        input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("agent-send", new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { send(); } });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "agent-stop");
        getActionMap().put("agent-stop", new AbstractAction() { @Override public void actionPerformed(ActionEvent event) {
            if (snapshot != null && snapshot.phase() == BUSY && !snapshot.cancelling()) cancel.run();
        } });
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); } public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
        applyTheme(theme);
    }
    private void showCommands() {
        if (snapshot == null) return;
        new AgentChoicePopup("Commands", snapshot.details().commands().stream().map(command -> new AgentChoicePopup.Choice(command.name(), "/" + command.name() + (command.argumentHint().isBlank() ? "" : " " + command.argumentHint()) + " · " + command.description())).toList(), "",
                theme, name -> offer("/" + name + " ")).show(commands, 0, commands.getHeight());
    }
    private void send() { if (canSend()) { attachmentError.setVisible(false); submit.accept(input.getText()); } }
    private boolean canSend() { return snapshot != null && snapshot.phase() == READY && !snapshot.importingAttachments() && AgentAttachment.withinLimits(snapshot.attachments()) && (!input.getText().isBlank() || !snapshot.attachments().isEmpty()) && input.getText().length() <= 32_768; }
    private boolean interactionPending() { return snapshot != null && snapshot.phase() == BUSY && !snapshot.cancelling() && (!snapshot.permissions().isEmpty() || !snapshot.details().questions().isEmpty()); }
    private void sizeInput() { input.setRows(interactionPending() ? Math.min(3, Math.max(2, input.getLineCount())) : Math.min(8, Math.max(3, input.getLineCount()))); }
    private void changed() { if (!updating) draftRevision = draftChanged.applyAsLong(input.getText()); sizeInput(); availability(); revalidate(); }
    private void availability() {
        if (snapshot == null) return;
        boolean busy = snapshot.phase() == BUSY; int length = input.getText().length();
        send.setEnabled(canSend()); attachment.setEnabled(attachments != null && (snapshot.phase() == READY || busy) && !snapshot.importingAttachments());
        send.setVisible(!busy); stop.setVisible(busy); stop.setEnabled(busy && !snapshot.cancelling()); stop.setToolTipText(snapshot.cancelling() ? "Stopping response…" : "Stop response (Esc)");
        hint.setText(length > 32_768 ? "Message too long · " + length + " / 32768 characters"
                : !AgentAttachment.withinLimits(snapshot.attachments()) ? "Recovered attachments exceed 10 files or 20 MiB · remove some before sending"
                : snapshot.importingAttachments() ? "Saving attachments…"
                : snapshot.attachments().stream().anyMatch(item -> item.image() || item.mimeType().equals("application/pdf")) ? "Images and PDFs need a compatible model · " + (sendOnEnter ? "Enter to send" : "Ctrl+Enter to send")
                : busy ? "Draft your next message while the agent works."
                : sendOnEnter ? "Enter to send · Shift+Enter for a new line" : "Ctrl+Enter to send · Enter for a new line");
        hint.setToolTipText(hint.getText());
    }
    void render(AgentSnapshot snapshot) {
        if (this.snapshot != null && (!this.snapshot.chatId().equals(snapshot.chatId()) || this.snapshot.importingAttachments() && !snapshot.importingAttachments())) attachmentError.setVisible(false);
        this.snapshot = snapshot; updating = true;
        if (!chatId.equals(snapshot.chatId()) || snapshot.draftRevision() >= draftRevision) { chatId = snapshot.chatId(); if (!input.getText().equals(snapshot.draft())) input.setText(snapshot.draft()); draftRevision = snapshot.draftRevision(); }
        models.setText(snapshot.models().stream().filter(model -> model.id().equals(snapshot.selectedModel())).findFirst().map(AgentModel::name).orElse("Model"));
        effort.setText(snapshot.efforts().stream().filter(option -> option.id().equals(snapshot.selectedEffort())).findFirst().map(AgentEffort::name).orElse("Effort"));
        updating = false; models.setEnabled(snapshot.phase() == READY); models.setToolTipText("Model: " + snapshot.selectedModel());
        if (attachments != null) {
            attachments.render(snapshot.attachments()); attachmentScroll.setVisible(!snapshot.attachments().isEmpty());
            attachmentScroll.setPreferredSize(new Dimension(0, Math.min(126, snapshot.attachments().size() * 44 + 4)));
        }
        effort.setEnabled(snapshot.phase() == READY); effort.setVisible(!snapshot.efforts().isEmpty()); effort.setToolTipText("Reasoning effort: " + snapshot.selectedEffort());
        var extra = extraOptions(); options.setVisible(!extra.isEmpty()); options.setEnabled(snapshot.phase() == READY);
        options.setText(extra.size() == 1 ? extra.getFirst().choices().stream().filter(choice -> choice.id().equals(extra.getFirst().value())).findFirst().map(AgentConfigOption.Choice::name).orElse(extra.getFirst().name()) : "Options");
        options.setToolTipText("Agent mode and session options"); commands.setVisible(!snapshot.details().commands().isEmpty()); commands.setEnabled(snapshot.phase() == READY || snapshot.phase() == BUSY);
        input.setEnabled(snapshot.phase() != STOPPING && snapshot.phase() != CLOSED && (snapshot.phase() == READY || snapshot.phase() == BUSY || !snapshot.models().isEmpty()));
        context.setText(snapshot.project() == null ? "" : "Project · " + snapshot.project().getFileName());
        context.setToolTipText(snapshot.project() == null ? null : snapshot.project().toString());
        boolean pending = interactionPending(); context.setVisible(!pending); hint.setVisible(!pending); notices.setVisible(!pending || attachmentError.isVisible()); sizeInput();
        input.putClientProperty("JTextArea.placeholderText", pending ? "Draft your next message…" : "Ask about your project…"); availability();
    }
    private List<AgentConfigOption> extraOptions() { return snapshot.configuration().options().stream().filter(option -> option.category() != AgentConfigOption.Category.MODEL && option.category() != AgentConfigOption.Category.EFFORT).toList(); }
    void offer(String text) { input.setText(text); focus(); }
    void configureAttachments(Runnable choose, Consumer<List<java.nio.file.Path>> files, Consumer<Image> images,
                              Consumer<String> remove, java.util.function.Function<AgentAttachment, java.nio.file.Path> path, java.util.concurrent.Executor workers) {
        chooseAttachments = choose;
        attachments = new AgentAttachmentsView(theme, remove, path, workers);
        attachmentScroll = new JScrollPane(attachments); attachmentScroll.setName("agents.attachments.scroll"); attachmentScroll.setBorder(BorderFactory.createEmptyBorder());
        attachmentScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); attachmentScroll.setVisible(false); attachmentScroll.getVerticalScrollBar().setUnitIncrement(22);
        box.add(attachmentScroll, BorderLayout.NORTH);
        input.setTransferHandler(new AgentAttachmentTransferHandler(input.getTransferHandler(), files, images, this::attachmentError, this::canAttach));
        box.setTransferHandler(new AgentAttachmentTransferHandler(null, files, images, this::attachmentError, this::canAttach));
        setTransferHandler(new AgentAttachmentTransferHandler(null, files, images, this::attachmentError, this::canAttach));
        applyTheme(theme);
    }
    private boolean canAttach() { return snapshot != null && (snapshot.phase() == READY || snapshot.phase() == BUSY); }
    void attachmentError(String message) { attachmentError.setText(message); attachmentError.setVisible(!message.isBlank()); notices.setVisible(hint.isVisible() || attachmentError.isVisible()); revalidate(); }
    void settings(AgentSettings value) {
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), value.sendOnEnter() ? "agent-send" : DefaultEditorKit.insertBreakAction);
        send.setToolTipText(value.sendOnEnter() ? "Send message (Enter)" : "Send message (Ctrl+Enter)");
        sendOnEnter = value.sendOnEnter(); availability();
    }
    void reset() { updating = true; input.setText(""); updating = false; chatId = ""; draftRevision = -1; attachmentError.setVisible(false); }
    void focus() { input.requestFocusInWindow(); }
    void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface()); box.setBackground(theme.raised());
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(theme.border()), BorderFactory.createEmptyBorder(4, 4, 5, 4)));
        UiTypography.apply(this, theme); UiTypography.interfaceFont(hint, theme, Font.PLAIN, -1);
        input.setBackground(theme.raised()); input.setForeground(theme.text()); input.setCaretColor(theme.text());
        models.setIcon(new Glyph(Glyph.Kind.CHEVRON, theme.muted(), 12)); effort.setIcon(new Glyph(Glyph.Kind.CHEVRON, theme.muted(), 12));
        options.setIcon(new Glyph(Glyph.Kind.CHEVRON, theme.muted(), 12));
        Color stopColor = new Color(0xF07878);
        send.setIcon(new Glyph(Glyph.Kind.SEND, theme.accent(), 20)); stop.setIcon(new Glyph(Glyph.Kind.STOP, stopColor, 18));
        actionHover(send, theme.accent()); actionHover(stop, stopColor);
        commands.setIcon(new Glyph(Glyph.Kind.TERMINAL, theme.muted(), 18));
        attachment.setIcon(new Glyph(Glyph.Kind.PAPERCLIP, theme.text(), 18));
        hint.setForeground(theme.muted()); context.setForeground(theme.muted());
        attachmentError.setForeground(AgentUi.errorColor(theme));
        if (attachments != null) { attachments.applyTheme(theme); attachmentScroll.getViewport().setBackground(theme.raised()); }
    }
    private void actionHover(JButton button, Color color) {
        button.putClientProperty("FlatLaf.style", Map.of(
                "toolbar.hoverBackground", blend(theme.raised(), color, 0.18),
                "toolbar.pressedBackground", blend(theme.raised(), color, 0.28)));
    }
    private static Color blend(Color background, Color color, double amount) {
        return new Color((int)(background.getRed() * (1 - amount) + color.getRed() * amount),
                (int)(background.getGreen() * (1 - amount) + color.getGreen() * amount),
                (int)(background.getBlue() * (1 - amount) + color.getBlue() * amount));
    }
    private static final class CompactButton extends JButton {
        private final int limit;
        CompactButton(int limit) {
            this.limit = limit; putClientProperty("html.disable", true); putClientProperty("JButton.buttonType", "toolBarButton");
            setHorizontalTextPosition(SwingConstants.LEFT);
        }
        @Override public Dimension getPreferredSize() { Dimension size = super.getPreferredSize(); return new Dimension(Math.min(limit, size.width), size.height); }
    }
}

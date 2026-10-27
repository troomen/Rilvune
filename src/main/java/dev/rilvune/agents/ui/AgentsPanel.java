package dev.rilvune.agents.ui;

import dev.rilvune.agents.application.AgentService;
import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.*;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.rilvune.diff.ui.SnapshotDiffDialog;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import static dev.rilvune.agents.domain.AgentSnapshot.Phase.*;

/** Composes agent views and forwards user intent. Runtime, OAuth and session ownership stay in the service. */
public final class AgentsPanel extends JPanel implements AutoCloseable {
    private final AgentService service;
    private final Function<URI, CompletableFuture<Void>> browse;
    private final AutoCloseable subscription;
    private final AgentSetupView setup;
    private final AgentComposerView composer;
    private final AgentConversationView conversation;
    private final AgentChatView chatView;
    private final AgentChatSwitcherView chats;
    private final JTextArea persistenceWarning = AgentUi.text("agents.history.warning");
    private final JPanel body = new JPanel(new BorderLayout()), footer = new JPanel(new BorderLayout(0, 6));
    private final JScrollPane setupScroll;
    private final AgentStatusView status;
    private final ExecutorService viewWorkers = Executors.newFixedThreadPool(2, Thread.ofVirtual().name("agent-preview-", 0).factory());
    private final List<SnapshotDiffDialog> dialogs = new ArrayList<>();
    private final Timer animation = new Timer(80, event -> { if (isShowing()) repaint(); });
    private final JButton newChat, more;
    private AgentLogin shownLogin;
    private Path project;
    private Boolean chatLayout;
    private AgentSnapshot.Phase previousPhase;
    private boolean closed;
    private long browserOperation;
    private EditorTheme theme;
    private AgentSnapshot displayed;
    private AgentSettings settings = AgentSettings.defaults();

    public AgentsPanel(AgentService service, EditorTheme theme, Function<URI, CompletableFuture<Void>> browse) {
        super(new BorderLayout()); this.service = service; this.browse = browse; this.theme = theme; setName("agents.panel");
        composer = new AgentComposerView(theme, this::send, service::selectModel, service::selectEffort, service::selectOption, service::updateDraft, service::cancel);
        conversation = new AgentConversationView(theme, composer::offer, viewWorkers, dialogs);
        setup = new AgentSetupView(theme, service::connect, service::cancel, this::openBrowser);
        AgentInteractionView interactions = new AgentInteractionView(theme, service::permission, service::answer, conversation::revealTool, viewWorkers, dialogs);
        chatView = new AgentChatView(conversation, interactions);
        status = new AgentStatusView(theme);
        setupScroll = new JScrollPane(setup); setupScroll.setBorder(BorderFactory.createEmptyBorder());
        setupScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setupScroll.getVerticalScrollBar().setUnitIncrement(20);
        newChat = UiComponents.iconButton(theme, Glyph.Kind.PLUS, "New chat", service::newConversation); newChat.setName("agents.newChat");
        newChat.setToolTipText("Start a new saved chat; the current conversation remains in history");
        chats = new AgentChatSwitcherView(theme, service::switchConversation, () -> service.refreshChats(true), () -> service.refreshChats(false));
        more = UiComponents.iconButton(theme, Glyph.Kind.MENU, "Conversation actions", this::showMenu); more.setName("agents.more");
        JPanel heading = new JPanel(new BorderLayout()); heading.add(UiComponents.header(theme, "AI Assistant", newChat, more), BorderLayout.NORTH);
        JPanel context = new JPanel(new BorderLayout()); context.setOpaque(false); context.add(chats, BorderLayout.NORTH); context.add(status);
        persistenceWarning.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10)); persistenceWarning.setRows(2); context.add(persistenceWarning, BorderLayout.SOUTH); heading.add(context);
        add(heading, BorderLayout.NORTH); add(body); footer.add(composer); add(footer, BorderLayout.SOUTH);
        addHierarchyListener(event -> { if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) animate(); });
        applyTheme(theme); subscription = service.listen(this::render);
    }
    private void send(String text) {
        if (service.send(text)) { render(service.snapshot()); conversation.followLatest(); composer.focus(); }
    }
    private void openBrowser(URI uri) {
        long operation = ++browserOperation; AgentLogin attempt = shownLogin;
        setup.browserOpening();
        CompletableFuture<Void> opening;
        try { opening = browse.apply(uri); }
        catch (RuntimeException unavailable) { opening = CompletableFuture.failedFuture(unavailable); }
        opening.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
            if (!closed && operation == browserOperation && Objects.equals(attempt, shownLogin)) setup.browserFinished(failure == null);
        }));
    }
    private void showMenu() {
        JPopupMenu menu = new JPopupMenu(); JMenuItem copy = new JMenuItem("Copy conversation"); copy.setEnabled(!service.snapshot().messages().isEmpty());
        copy.addActionListener(event -> {
            StringBuilder text = new StringBuilder();
            for (AgentMessage message : service.snapshot().messages()) text.append(message.role()).append("\n").append(message.text()).append("\n\n");
            AgentUi.copy(text.toString());
        });
        menu.add(copy);
        JMenuItem fork = new JMenuItem("Branch chat…"); fork.setEnabled(service.snapshot().phase() == READY && service.snapshot().session() != null && service.snapshot().session().capabilities().contains(AgentSession.Capability.FORK)); fork.addActionListener(event -> service.forkConversation()); menu.add(fork);
        JMenuItem rename = new JMenuItem("Rename chat…"); rename.setEnabled(!service.snapshot().chatId().isEmpty());
        rename.addActionListener(event -> {
            String name = (String)JOptionPane.showInputDialog(this, "Chat name", "Rename chat", JOptionPane.PLAIN_MESSAGE, null, null, service.snapshot().chatTitle());
            if (name != null) service.renameConversation(name);
        }); menu.add(rename);
        JMenuItem delete = new JMenuItem("Delete chat…"); delete.setEnabled(service.snapshot().phase() == READY && !service.snapshot().chatId().isEmpty());
        delete.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, "Delete this conversation and its saved agent context?\nProject files are not affected.", "Delete chat", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
                service.deleteConversation();
        }); menu.add(delete);
        UiTypography.apply(menu, theme); menu.show(more, 0, more.getHeight());
    }
    private void render(AgentSnapshot snapshot) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> render(snapshot)); return; }
        if (closed) return;
        if (!Objects.equals(project, snapshot.project())) { closePreviews(); project = snapshot.project(); composer.reset(); conversation.followLatest(); }
        boolean chat = !snapshot.models().isEmpty() || !snapshot.messages().isEmpty();
        if (!Objects.equals(chatLayout, chat)) {
            chatLayout = chat; body.removeAll();
            if (chat) { setupScroll.setViewportView(null); body.add(setup, BorderLayout.NORTH); body.add(chatView); }
            else { setupScroll.setViewportView(setup); body.add(setupScroll); }
        }
        footer.setVisible(chat); setup.render(snapshot, chat); composer.render(snapshot); chatView.render(snapshot);
        newChat.setEnabled(snapshot.phase() == READY); chats.render(snapshot);
        persistenceWarning.setText(snapshot.persistenceError().isEmpty() ? "" : "Chat preferences need attention: " + snapshot.persistenceError()); persistenceWarning.setVisible(!snapshot.persistenceError().isEmpty());
        status.render(snapshot);
        displayed = snapshot; animate();
        boolean newLogin = snapshot.login() != null && !Objects.equals(shownLogin, snapshot.login()); shownLogin = snapshot.login();
        if (newLogin && settings.openLoginInBrowser()) openBrowser(shownLogin.uri());
        if (snapshot.phase() == READY && previousPhase != READY && previousPhase != BUSY) SwingUtilities.invokeLater(composer::focus);
        if (snapshot.phase() != previousPhase && (snapshot.phase() == AUTH_REQUIRED || snapshot.phase() == NOT_INSTALLED || snapshot.phase() == AgentSnapshot.Phase.ERROR))
            SwingUtilities.invokeLater(setup::focusPrimary);
        previousPhase = snapshot.phase(); revalidate(); repaint();
    }
    private void animate() {
        boolean active = !closed && isShowing() && displayed != null && displayed.details().progress().animated();
        if (active && !animation.isRunning()) animation.start(); else if (!active) animation.stop();
    }
    private void closePreviews() { for (var dialog : List.copyOf(dialogs)) dialog.dispose(); }
    public void settings(AgentSettings value) { settings = Objects.requireNonNull(value); composer.settings(value); }
    public void applyTheme(EditorTheme theme) {
        this.theme = theme; setBackground(theme.surface()); body.setBackground(theme.surface()); footer.setBackground(theme.surface());
        UiTypography.apply(this, theme); status.applyTheme(theme); chatView.applyTheme(theme);
        setupScroll.getViewport().setBackground(theme.surface()); setup.applyTheme(theme); composer.applyTheme(theme); chats.applyTheme(theme); persistenceWarning.setForeground(theme.muted());
        newChat.setIcon(new Glyph(Glyph.Kind.PLUS, theme.text(), 18));
        more.setIcon(new Glyph(Glyph.Kind.MENU, theme.text(), 18));
    }
    @Override public void close() {
        closed = true; animation.stop(); closePreviews(); viewWorkers.shutdownNow();
        try { subscription.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}

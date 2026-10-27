package dev.rilvune.agents.ui;

import dev.rilvune.agents.domain.*;
import dev.rilvune.platform.theme.*;
import dev.rilvune.platform.ui.Glyph;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Searchable, paged project history, with an explicit active conversation. */
final class AgentChatSwitcherView extends JPanel {
    private final JButton selected = AgentUi.quietButton("New chat", "agents.chats", this::open);
    private final JPopupMenu popup = new JPopupMenu();
    private final JTextField search = new JTextField();
    private final DefaultListModel<AgentChat> model = new DefaultListModel<>();
    private final JList<AgentChat> list = new JList<>(model);
    private final JButton more, refresh;
    private final Consumer<String> choose;
    private AgentSnapshot snapshot;
    private EditorTheme theme;

    AgentChatSwitcherView(EditorTheme theme, Consumer<String> choose, Runnable loadMore, Runnable reload) {
        super(new BorderLayout()); this.choose = choose; setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8)); add(selected);
        selected.setHorizontalAlignment(SwingConstants.LEFT); selected.getAccessibleContext().setAccessibleName("Switch saved chat");
        search.setName("agents.chats.search"); search.putClientProperty("JTextField.placeholderText", "Search loaded chats…");
        list.setName("agents.chats.list"); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setVisibleRowCount(8);
        list.setCellRenderer(new DefaultListCellRenderer() {
            { putClientProperty("html.disable", true); }
            @Override public Component getListCellRendererComponent(JList<?> owner, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(owner, value, index, selected, focus);
                if (value instanceof AgentChat chat) {
                    String date = chat.updatedAt().equals(Instant.EPOCH) ? "" : DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(chat.updatedAt());
                    setText(chat.title() + (date.isEmpty() ? "" : "  ·  " + date));
                    setToolTipText("Chat: " + chat.title());
                    setFont(AgentChatSwitcherView.this.theme.interfaceFont().deriveFont(snapshot != null && chat.id().equals(snapshot.chatId()) ? Font.BOLD : Font.PLAIN));
                }
                return this;
            }
        });
        more = AgentUi.button("Load older chats", "agents.chats.more", () -> { moreLoading(); loadMore.run(); });
        refresh = AgentUi.quietButton("Refresh", "agents.chats.refresh", () -> { moreLoading(); reload.run(); });
        JPanel actions = AgentUi.row(); actions.add(more); actions.add(refresh);
        popup.setLayout(new BorderLayout(0, 8)); popup.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); popup.add(search, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(340, 240)); popup.add(scroll); popup.add(actions, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { filter(); } public void removeUpdate(DocumentEvent event) { filter(); } public void changedUpdate(DocumentEvent event) { filter(); }
        });
        search.addActionListener(event -> choose()); list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "choose");
        list.getActionMap().put("choose", new AbstractAction() { public void actionPerformed(ActionEvent event) { choose(); } });
        list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) {
            int index = list.locationToIndex(event.getPoint()); if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint()) && SwingUtilities.isLeftMouseButton(event)) choose();
        } });
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "list"); search.getActionMap().put("list", new AbstractAction() { public void actionPerformed(ActionEvent event) { list.requestFocusInWindow(); } });
        popup.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) { SwingUtilities.invokeLater(search::requestFocusInWindow); }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) { } public void popupMenuCanceled(PopupMenuEvent event) { }
        }); applyTheme(theme);
    }
    private void moreLoading() { more.setEnabled(false); refresh.setEnabled(false); }
    private void choose() { AgentChat chat = list.getSelectedValue(); if (chat != null) { popup.setVisible(false); choose.accept(chat.id()); } }
    private void open() { filter(); popup.show(selected, 0, selected.getHeight()); }
    private void filter() {
        if (snapshot == null) return; String query = search.getText().toLowerCase(java.util.Locale.ROOT); model.clear();
        for (AgentChat chat : snapshot.history().chats()) if (chat.title().toLowerCase(java.util.Locale.ROOT).contains(query)) model.addElement(chat);
        int active = java.util.stream.IntStream.range(0, model.size()).filter(index -> model.get(index).id().equals(snapshot.chatId())).findFirst().orElse(model.isEmpty() ? -1 : 0);
        list.setSelectedIndex(active); list.ensureIndexIsVisible(active);
    }
    void render(AgentSnapshot snapshot) {
        this.snapshot = snapshot; selected.setText(snapshot.chatTitle()); setVisible(snapshot.session() != null || !snapshot.history().chats().isEmpty());
        selected.setEnabled(snapshot.phase() == AgentSnapshot.Phase.READY);
        if (!selected.isEnabled()) popup.setVisible(false);
        selected.setToolTipText(snapshot.phase() == AgentSnapshot.Phase.BUSY ? "Finish or stop this response before switching chats" : "Chat: " + snapshot.chatTitle());
        more.setVisible(!snapshot.history().nextCursor().isEmpty()); more.setEnabled(true); refresh.setEnabled(true);
        if (popup.isVisible()) filter();
    }
    void applyTheme(EditorTheme theme) { this.theme = theme; setBackground(theme.surface()); UiTypography.apply(this, theme); UiTypography.apply(popup, theme); selected.setIcon(new Glyph(Glyph.Kind.CHEVRON, theme.muted(), 12)); selected.setHorizontalTextPosition(SwingConstants.LEFT); }
    @Override public Dimension getPreferredSize() { return new Dimension(0, super.getPreferredSize().height); }
}

package dev.editor.platform.tabs.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.platform.theme.EditorTheme;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.*;

/** Renders arbitrary workspace tabs. It owns neither documents nor persistence decisions. */
public final class TabHost extends JPanel {
    private final JTabbedPane pane = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel welcome = new JPanel(new GridBagLayout());
    private final Map<String, WorkspaceTab> tabs = new LinkedHashMap<>();
    private final Function<WorkspaceTab, JPopupMenu> menus;
    private EditorTheme theme;

    public TabHost(EditorTheme theme, JComponent welcomeContent, Consumer<WorkspaceTab> selected,
                   Function<WorkspaceTab, JPopupMenu> menus) {
        this.theme = theme;
        this.menus = menus;
        setLayout(cards);
        welcome.add(welcomeContent);
        add(welcome, "empty"); add(pane, "tabs"); cards.show(this, "empty");
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        pane.setFocusTraversalKeysEnabled(false);
        pane.putClientProperty("html.disable", Boolean.TRUE);
        pane.addChangeListener(event -> {
            tabs.values().forEach(this::update);
            active().ifPresent(selected);
        });
        pane.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int index = pane.indexAtLocation(event.getX(), event.getY());
                if (index >= 0) tabFor(pane.getComponentAt(index)).ifPresent(tab -> showMenu(tab, event));
            }
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
        });
        bind("ctrl TAB", "next-tab", 1);
        bind("ctrl shift TAB", "previous-tab", -1);
        UiTypography.apply(this, theme);
    }

    public void show(WorkspaceTab tab, boolean activate) {
        WorkspaceTab existing = tabs.get(tab.id());
        if (existing != null && existing != tab) throw new IllegalArgumentException("Duplicate tab identity: " + tab.id());
        Component previous = pane.getSelectedComponent();
        if (existing == null) {
            tabs.put(tab.id(), tab);
            pane.addTab(literalTitle(tab.presentation().title()), tab.component());
            pane.setTabComponentAt(pane.indexOfComponent(tab.component()), label(tab));
        }
        UiTypography.apply(tab.component(), theme);
        UiTypography.apply(pane.getTabComponentAt(pane.indexOfComponent(tab.component())), theme);
        update(tab);
        if (activate || previous == null) pane.setSelectedComponent(tab.component());
        else pane.setSelectedComponent(previous);
        cards.show(this, "tabs"); revalidate(); repaint();
        if (activate) tab.focusContent();
    }

    public Optional<WorkspaceTab> active() { return tabFor(pane.getSelectedComponent()); }
    public List<WorkspaceTab> tabs() { return List.copyOf(tabs.values()); }
    public void reorder(List<String> order) {
        String selectedId = active().map(WorkspaceTab::id).orElse("");
        java.util.ArrayList<WorkspaceTab> sorted = new java.util.ArrayList<>();
        for (String id : order) if (tabs.containsKey(id) && !sorted.contains(tabs.get(id))) sorted.add(tabs.get(id));
        for (WorkspaceTab tab : tabs.values()) if (!sorted.contains(tab)) sorted.add(tab);
        pane.removeAll(); tabs.clear();
        for (WorkspaceTab tab : sorted) show(tab, false);
        select(selectedId);
    }
    public void select(String id) {
        WorkspaceTab tab = tabs.get(id);
        if (tab != null) { pane.setSelectedComponent(tab.component()); tab.focusContent(); }
    }
    public void next(int direction) {
        if (pane.getTabCount() == 0) return;
        pane.setSelectedIndex(Math.floorMod(pane.getSelectedIndex() + direction, pane.getTabCount()));
        active().ifPresent(WorkspaceTab::focusContent);
    }
    public CompletableFuture<Boolean> requestCloseActive() {
        return active().map(WorkspaceTab::requestClose).orElseGet(() -> CompletableFuture.completedFuture(true));
    }
    public void remove(String id) {
        WorkspaceTab tab = tabs.remove(id);
        if (tab == null) return;
        RuntimeException failure = null;
        try { pane.remove(tab.component()); } catch (RuntimeException removal) { failure = removal; }
        try { tab.dispose(); }
        catch (RuntimeException disposal) { if (failure == null) failure = disposal; else failure.addSuppressed(disposal); }
        finally { if (tabs.isEmpty()) cards.show(this, "empty"); revalidate(); repaint(); }
        if (failure != null) throw failure;
    }
    public void update(WorkspaceTab tab) {
        int index = pane.indexOfComponent(tab.component());
        if (index < 0) return;
        TabPresentation value = tab.presentation();
        pane.setTitleAt(index, literalTitle(value.title())); pane.setToolTipTextAt(index, "Tab: " + value.tooltip());
        if (!(pane.getTabComponentAt(index) instanceof JPanel header)) return;
        JLabel name = (JLabel) header.getComponent(0);
        name.setText(value.title()); UiTypography.interfaceFont(name, theme); name.setForeground(pane.getSelectedIndex() == index ? value.selectedForeground() : value.foreground());
        // Prefix untrusted descriptions so Swing cannot interpret HTML in tooltips.
        name.setToolTipText("Tab: " + value.tooltip());
        name.getAccessibleContext().setAccessibleDescription(value.tooltip());
    }
    public void applyTheme(EditorTheme value) {
        theme = value; UiTypography.apply(this, theme); tabs.values().forEach(this::update);
    }
    public void setWelcome(JComponent content) {
        welcome.removeAll(); welcome.add(content); welcome.revalidate(); welcome.repaint();
    }

    private String literalTitle(String title) {
        // BasicTabbedPaneUI can create hidden HTML views even with a custom header.
        return javax.swing.plaf.basic.BasicHTML.isHTMLString(title) ? "\u200B" + title : title;
    }

    private Optional<WorkspaceTab> tabFor(Component component) {
        return tabs.values().stream().filter(tab -> tab.component() == component).findFirst();
    }
    private JPanel label(WorkspaceTab tab) {
        JPanel label = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); label.setOpaque(false);
        JLabel title = new JLabel(); title.putClientProperty("html.disable", Boolean.TRUE); label.add(title);
        JButton close = new JButton("×"); close.setFocusable(false); close.setToolTipText("Close tab · Ctrl+W");
        close.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5)); close.setContentAreaFilled(false);
        close.addActionListener(event -> tab.requestClose()); label.add(close);
        MouseAdapter select = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) select(tab.id());
                showMenu(tab, event);
            }
            @Override public void mouseReleased(MouseEvent event) { showMenu(tab, event); }
        };
        label.addMouseListener(select); title.addMouseListener(select);
        return label;
    }
    private void showMenu(WorkspaceTab tab, MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        JPopupMenu menu = menus.apply(tab);
        if (menu != null) { UiTypography.apply(menu, theme); menu.show(event.getComponent(), event.getX(), event.getY()); }
    }
    private void bind(String shortcut, String id, int direction) {
        pane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(shortcut), id);
        pane.getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { next(direction); }
        });
    }
}

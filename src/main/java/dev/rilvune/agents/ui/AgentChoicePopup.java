package dev.rilvune.agents.ui;

import dev.rilvune.platform.theme.*;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;

/** Shared searchable selector for compact model and effort buttons. */
final class AgentChoicePopup extends JPopupMenu {
    record Choice(String id, String name) { @Override public String toString() { return name; } }
    AgentChoicePopup(String title, List<Choice> choices, String selected, EditorTheme theme, Consumer<String> choose) {
        setLayout(new BorderLayout(0, 6)); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JTextField search = new JTextField(); search.putClientProperty("JTextField.placeholderText", "Search " + title.toLowerCase() + "…"); search.setName("agents.choice.search");
        DefaultListModel<Choice> model = new DefaultListModel<>(); JList<Choice> list = new JList<>(model); list.setName("agents.choice.list");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setCellRenderer(new DefaultListCellRenderer() { { putClientProperty("html.disable", true); } });
        Runnable filter = () -> {
            model.clear(); String query = search.getText().toLowerCase(java.util.Locale.ROOT);
            for (Choice choice : choices) if ((choice.name() + " " + choice.id()).toLowerCase(java.util.Locale.ROOT).contains(query)) model.addElement(choice);
            int index = java.util.stream.IntStream.range(0, model.size()).filter(i -> model.get(i).id().equals(selected)).findFirst().orElse(model.isEmpty() ? -1 : 0);
            list.setSelectedIndex(index); list.ensureIndexIsVisible(index);
        };
        Runnable accept = () -> { Choice choice = list.getSelectedValue(); if (choice != null) { setVisible(false); choose.accept(choice.id()); } };
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { filter.run(); } public void removeUpdate(DocumentEvent event) { filter.run(); } public void changedUpdate(DocumentEvent event) { filter.run(); }
        });
        search.addActionListener(event -> accept.run()); list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) {
            int index = list.locationToIndex(event.getPoint()); if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint()) && SwingUtilities.isLeftMouseButton(event)) accept.run();
        } });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "choose"); list.getActionMap().put("choose", new AbstractAction() { public void actionPerformed(ActionEvent event) { accept.run(); } });
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "list"); search.getActionMap().put("list", new AbstractAction() { public void actionPerformed(ActionEvent event) { list.requestFocusInWindow(); } });
        add(search, BorderLayout.NORTH); JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(300, Math.min(260, Math.max(50, choices.size() * 26)))); add(scroll);
        filter.run(); UiTypography.apply(this, theme); addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) { SwingUtilities.invokeLater(search::requestFocusInWindow); }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) { } public void popupMenuCanceled(PopupMenuEvent event) { }
        });
    }
}

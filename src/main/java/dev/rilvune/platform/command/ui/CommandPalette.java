package dev.rilvune.platform.command.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.platform.command.domain.Command;
import dev.rilvune.platform.command.domain.CommandRegistry;
import dev.rilvune.platform.theme.EditorTheme;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public final class CommandPalette {
    private final CommandRegistry commands;
    private final EditorTheme theme;

    public CommandPalette(CommandRegistry commands, EditorTheme theme) {
        this.commands = commands;
        this.theme = theme;
    }

    public void show(JFrame owner) {
        JDialog dialog = new JDialog(owner, "Quick actions", true);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JTextField query = new JTextField();
        query.putClientProperty("JTextField.placeholderText", "Type a command…");
        query.getAccessibleContext().setAccessibleName("Filter commands");
        DefaultListModel<Command> matches = new DefaultListModel<>();
        JList<Command> list = new JList<>(matches);
        list.setFixedCellHeight(36);
        list.setSelectionBackground(theme.selection());
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(source, value, index, selected, focused);
                setText(((Command) value).title());
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                return this;
            }
        });
        Runnable filter = () -> {
            matches.clear();
            String search = query.getText().toLowerCase(Locale.ROOT);
            for (Command command : commands.commands()) {
                if (command.available() && command.title().toLowerCase(Locale.ROOT).contains(search)) matches.addElement(command);
            }
            if (!matches.isEmpty()) list.setSelectedIndex(0);
        };
        Runnable execute = () -> {
            Command selected = list.getSelectedValue();
            if (selected != null) { dialog.dispose(); commands.execute(selected.id()); }
        };
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter.run(); }
            @Override public void removeUpdate(DocumentEvent event) { filter.run(); }
            @Override public void changedUpdate(DocumentEvent event) { filter.run(); }
        });
        query.addActionListener(event -> execute.run());
        query.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "next-command");
        query.getActionMap().put("next-command", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                list.setSelectedIndex(Math.min(matches.size() - 1, list.getSelectedIndex() + 1));
            }
        });
        query.getInputMap().put(KeyStroke.getKeyStroke("UP"), "previous-command");
        query.getActionMap().put("previous-command", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)); }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "execute-command");
        list.getActionMap().put("execute-command", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { execute.run(); }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) execute.run();
            }
        });
        content.add(query, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        UiTypography.apply(content, theme);
        dialog.setContentPane(content);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JPanel.WHEN_IN_FOCUSED_WINDOW);
        dialog.setSize(540, 320);
        dialog.setLocationRelativeTo(owner);
        filter.run();
        dialog.setVisible(true);
    }
}

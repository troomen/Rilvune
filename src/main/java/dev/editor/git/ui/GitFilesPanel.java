package dev.editor.git.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.git.domain.*;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;

final class GitFilesPanel extends JPanel {
    private List<GitChange> changes = List.of();
    private final FilesModel model = new FilesModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<FilesModel> sorter = new TableRowSorter<>(model);
    private final JTextField filter = new JTextField();
    private final JLabel heading = new JLabel("Files");
    private Consumer<GitChange> selected = ignored -> { };
    private boolean changing;
    private long dataVersion;
    GitFilesPanel(EditorTheme theme, Consumer<GitChange> open, Consumer<GitChange> history) {
        super(new BorderLayout()); setMinimumSize(new Dimension(180, 80));
        table.setName("git.files"); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setShowGrid(false); table.setFillsViewportHeight(true); table.setRowSorter(sorter);
        table.getColumnModel().getColumn(0).setMaxWidth(54); table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int row, int col) { super.getTableCellRendererComponent(t, v, s, f, row, col); putClientProperty("html.disable", Boolean.TRUE); setToolTipText(model.change(t.convertRowIndexToModel(row)).displayPath()); setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6)); return this; }
        });
        filter.setName("git.fileFilter"); filter.putClientProperty("JTextField.placeholderText", "Filter displayed paths…");
        filter.getDocument().addDocumentListener(new DocumentListener() { public void insertUpdate(DocumentEvent e) { apply(); } public void removeUpdate(DocumentEvent e) { apply(); } public void changedUpdate(DocumentEvent e) { apply(); } private void apply() { String text = filter.getText().toLowerCase(java.util.Locale.ROOT); sorter.setRowFilter(new RowFilter<>() { public boolean include(Entry<? extends FilesModel, ? extends Integer> entry) { return changes.get(entry.getIdentifier()).displayPath().toLowerCase(java.util.Locale.ROOT).contains(text); } }); } });
        JPanel header = new JPanel(new BorderLayout(8, 0)); header.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8)); header.add(heading, BorderLayout.WEST); header.add(filter);
        table.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !changing && selection() != null) selected.accept(selection()); });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); } @Override public void mouseReleased(MouseEvent e) { popup(e); }
            private void popup(MouseEvent e) { if (!e.isPopupTrigger()) return; int row = table.rowAtPoint(e.getPoint()); if (row < 0) return; table.setRowSelectionInterval(row, row); GitChange change = selection(); long captured = dataVersion; JPopupMenu menu = new JPopupMenu(); JMenuItem item = new JMenuItem("Open working file in editor"); item.addActionListener(event -> { if (captured == dataVersion) open.accept(change); }); menu.add(item); JMenuItem log = new JMenuItem("Show File History"); log.addActionListener(event -> { if (captured == dataVersion) history.accept(change); }); menu.add(log); JMenuItem copy = new JMenuItem("Copy path"); copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(change.path()), null)); menu.add(copy); menu.show(table, e.getX(), e.getY()); }
        });
        add(header, BorderLayout.NORTH); add(new JScrollPane(table)); applyTheme(theme);
    }
    void onSelection(Consumer<GitChange> selected) { this.selected = selected; }
    GitChange selection() { int row = table.getSelectedRow(); return row < 0 ? null : changes.get(table.convertRowIndexToModel(row)); }
    void data(List<GitChange> changes, String identity) {
        changing = true; dataVersion++; this.changes = List.copyOf(changes); model.fireTableDataChanged(); table.clearSelection();
        for (int i = 0; i < changes.size(); i++) if (changes.get(i).identity().equals(identity)) { int row = table.convertRowIndexToView(i); if (row >= 0) table.setRowSelectionInterval(row, row); }
        changing = false; heading.setText("Files · " + changes.size());
    }
    void selectFirst() { if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0); }
    void applyTheme(EditorTheme theme) { UiTypography.interfaceFont(table, theme); table.setRowHeight(Math.max(27, theme.interfaceFont().getSize() + 12)); table.setBackground(theme.surface()); table.setForeground(theme.text()); table.setSelectionBackground(theme.selection()); table.setSelectionForeground(theme.text()); UiTypography.interfaceFont(table.getTableHeader(), theme); }
    private final class FilesModel extends AbstractTableModel {
        public int getRowCount() { return changes.size(); } public int getColumnCount() { return 3; } public String getColumnName(int column) { return switch(column) { case 0 -> "I / W"; case 1 -> "Path"; default -> "Status"; }; }
        public Object getValueAt(int row, int column) { GitChange change = changes.get(row); return switch(column) { case 0 -> "" + change.index() + " " + change.worktree(); case 1 -> change.displayPath(); default -> change.status(); }; }
        GitChange change(int row) { return changes.get(row); }
    }
}

package dev.editor.git.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.git.domain.*;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, paged history table with real-parent graph lanes and full-row targets. */
final class GitHistoryPanel extends JPanel {
    private final HistoryModel model = new HistoryModel();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);
    private final JButton more = new JButton("Load more history");
    private final JLabel summary = new JLabel("History");
    private EditorTheme theme;
    private List<GitCommitHeader> commits = List.of();
    private List<GitRef> refs = List.of();
    private List<CommitGraph.Row> graph = List.of();
    private Consumer<List<GitCommitHeader>> selected = ignored -> { };
    private boolean changing;
    GitHistoryPanel(EditorTheme theme, Runnable loadMore, Optional<Runnable> workingTree) {
        super(new BorderLayout()); this.theme = theme; setMinimumSize(new Dimension(220, 100));
        table.setName("git.history"); table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(false); table.setFillsViewportHeight(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.getColumnModel().getColumn(0).setCellRenderer(new GraphRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(70); table.getColumnModel().getColumn(0).setMaxWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(360); table.getColumnModel().getColumn(2).setPreferredWidth(105);
        table.getColumnModel().getColumn(3).setPreferredWidth(95); table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !changing) selected.accept(selection()); });
        JPanel header = new JPanel(new BorderLayout()); header.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        header.add(summary);
        workingTree.ifPresent(action -> { JButton local = new JButton("◌  Working tree"); local.setToolTipText("Local disk and index state"); local.addActionListener(event -> action.run()); header.add(local, BorderLayout.EAST); });
        more.addActionListener(event -> loadMore.run()); more.setEnabled(false);
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> { JScrollBar bar = scroll.getVerticalScrollBar(); if (!event.getValueIsAdjusting() && more.isEnabled() && bar.getVisibleAmount() > 0 && bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - table.getRowHeight() * 5) loadMore.run(); });
        table.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { popup(e); } @Override public void mouseReleased(MouseEvent e) { popup(e); } });
        add(header, BorderLayout.NORTH); add(scroll); add(more, BorderLayout.SOUTH); applyTheme(theme);
    }
    void onSelection(Consumer<List<GitCommitHeader>> selected) { this.selected = selected; }
    void singleSelection() { table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); }
    void selectFirst() { if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0); }
    void data(List<GitCommitHeader> commits, List<GitRef> refs, boolean filtered, boolean hasMore, Set<String> selection) {
        changing = true; Point position = scroll.getViewport().getViewPosition();
        this.commits = List.copyOf(commits); this.refs = List.copyOf(refs); graph = CommitGraph.layout(commits, filtered);
        model.fireTableDataChanged(); table.clearSelection();
        for (int i = 0; i < commits.size(); i++) if (selection.contains(commits.get(i).id())) table.addRowSelectionInterval(i, i);
        changing = false; more.setEnabled(hasMore); more.setText(hasMore ? "Load more history" : commits.isEmpty() ? "No matching commits" : "End of history");
        summary.setText(commits.size() + " commits" + (filtered ? " · filtered · dashed edges are boundaries" : " · local history"));
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(position));
    }
    void limit(String message) { more.setEnabled(false); more.setText(message); }
    void loading() { more.setEnabled(false); more.setText("Loading…"); }
    List<GitCommitHeader> selection() { return Arrays.stream(table.getSelectedRows()).filter(row -> row >= 0 && row < commits.size()).mapToObj(commits::get).toList(); }
    void select(String id) { for (int row = 0; row < commits.size(); row++) if (commits.get(row).id().equals(id)) { table.setRowSelectionInterval(row, row); table.scrollRectToVisible(table.getCellRect(row, 1, true)); return; } }
    void applyTheme(EditorTheme theme) { this.theme = theme; table.setRowHeight(Math.max(27, theme.interfaceFont().getSize() + 12)); UiTypography.interfaceFont(table, theme); table.setBackground(theme.surface()); table.setForeground(theme.text()); table.setSelectionBackground(theme.selection()); table.setSelectionForeground(theme.text()); UiTypography.interfaceFont(table.getTableHeader(), theme); repaint(); }
    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) return; int row = table.rowAtPoint(event.getPoint()); if (row < 0) return;
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
        JPopupMenu menu = new JPopupMenu(); JMenuItem copy = new JMenuItem("Copy full commit ID"); copy.addActionListener(ignored -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(commits.get(row).id()), null)); menu.add(copy); menu.show(table, event.getX(), event.getY());
    }
    private final class HistoryModel extends AbstractTableModel {
        private final String[] names = {"Graph", "Subject / references", "Author", "Date", "Commit"};
        public int getRowCount() { return commits.size(); } public int getColumnCount() { return names.length; } public String getColumnName(int c) { return names[c]; }
        public Object getValueAt(int row, int column) {
            GitCommitHeader commit = commits.get(row); return switch(column) {
                case 0 -> graph.get(row);
                case 1 -> { String labels = String.join("  ", refs.stream().filter(ref -> ref.objectId().equals(commit.id())).map(GitRef::label).toList()); yield (labels.isEmpty() ? "" : "[" + labels + "]  ") + commit.subject(); }
                case 2 -> commit.author(); case 3 -> commit.date().substring(0, Math.min(10, commit.date().length())); default -> commit.shortId();
            };
        }
    }
    private final class RowRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column); putClientProperty("html.disable", Boolean.TRUE); setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6)); setToolTipText(column == 4 ? commits.get(row).id() : String.valueOf(value)); return this;
        }
    }
    private final class GraphRenderer extends JComponent implements TableCellRenderer {
        private CommitGraph.Row row; private boolean selected;
        private final Color[] colors = {new Color(0x6EA8FA), new Color(0xB99AF2), new Color(0x78B99A), new Color(0xDBB575), new Color(0xDD8292)};
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r, int c) { row = (CommitGraph.Row)value; this.selected = selected; return this; }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(selected ? theme.selection() : theme.surface()); g.fillRect(0, 0, getWidth(), getHeight());
            int center = getHeight() / 2; double step = Math.min(15, (getWidth() - 16.0) / Math.max(1, row.lanes()));
            for (int lane : row.continuing()) { g.setColor(colors[lane % colors.length]); g.setStroke(new BasicStroke(1.6f)); int x = 10 + (int)(lane * step); g.drawLine(x, 0, x, getHeight()); }
            int x = 10 + (int)(row.lane() * step); g.setColor(colors[row.lane() % colors.length]); g.setStroke(new BasicStroke(1.7f)); g.drawLine(x, 0, x, center);
            for (CommitGraph.Edge edge : row.edges()) {
                g.setStroke(edge.boundary() ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{3, 3}, 0) : new BasicStroke(1.6f));
                g.drawLine(x, center, 10 + (int)(edge.to() * step), getHeight());
            }
            g.setColor(theme.surface()); g.fillOval(x - 4, center - 4, 8, 8); g.setColor(colors[row.lane() % colors.length]); g.setStroke(new BasicStroke(2)); g.drawOval(x - 4, center - 4, 8, 8); g.dispose();
        }
    }
}

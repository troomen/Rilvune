package dev.rilvune.platform.text.ui;

import dev.rilvune.platform.text.ui.minimap.TextMinimap;
import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.TextHighlightColors;
import dev.rilvune.platform.text.domain.syntax.*;
import dev.rilvune.platform.text.ui.syntax.*;

import javax.swing.*;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** A text viewport and its local interaction state. Files and persistence belong to its host. */
public final class TextEditor extends JPanel implements AutoCloseable {
    private final Optional<TextEditCommands> editing;
    private final Consumer<String> report;
    private Function<TextEditor, JPopupMenu> menuFactory;
    private final JTextArea text;
    private final JScrollPane scroll;
    private final EditorGutter numbers;
    private final FindBar find;
    private final TextMinimap minimap;
    private final DocumentListener documentListener;
    private final CaretListener caretListener;
    private TextEditorSettings settings;
    private EditorTheme theme;
    private SyntaxSupport syntaxSupport = SyntaxSupport.none();
    private SyntaxHandle syntax = SyntaxSupport.unavailable("No complete source identity");
    private SyntaxSource syntaxSource = SyntaxSource.derived();
    private final SyntaxForeground syntaxForeground = new SyntaxForeground(() -> syntax.result(), () -> theme);
    private final java.awt.event.HierarchyListener syntaxHierarchy = event -> {
        if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) syntaxActivity();
    };
    private final java.awt.event.FocusListener syntaxFocus = new java.awt.event.FocusAdapter() {
        @Override public void focusGained(java.awt.event.FocusEvent event) { syntaxActivity(); }
        @Override public void focusLost(java.awt.event.FocusEvent event) { syntaxActivity(); }
    };
    private boolean closed;
    private int popupOffset = -1;
    private long gutterGeometry;
    private TextLineLayout lineLayout = TextLineLayout.empty();
    private Consumer<TextLineLayout.Fold> expandFold = ignored -> { };

    public TextEditor(Document model, Optional<TextEditCommands> editing, TextEditorSettings settings,
                      EditorTheme theme, Consumer<String> report, Function<TextEditor, JPopupMenu> menuFactory) {
        this(model, editing, settings, theme, report, menuFactory, java.util.concurrent.ForkJoinPool.commonPool());
    }

    public TextEditor(Document model, Optional<TextEditCommands> editing, TextEditorSettings settings,
                      EditorTheme theme, Consumer<String> report, Function<TextEditor, JPopupMenu> menuFactory,
                      java.util.concurrent.Executor searchExecutor) {
        super(new BorderLayout());
        this.editing = Objects.requireNonNull(editing, "editing");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.theme = Objects.requireNonNull(theme, "theme");
        this.report = Objects.requireNonNull(report, "report");
        this.menuFactory = Objects.requireNonNull(menuFactory, "menuFactory");
        text = new JTextArea(Objects.requireNonNull(model, "model")) {
            @Override public void updateUI() {
                if (TextEditor.this.lineLayout != null && TextEditor.this.lineLayout.decorated()) setUI(new LineLayoutTextAreaUI(() -> TextEditor.this.lineLayout, syntaxForeground));
                else setUI(SyntaxTextAreaUI.forCurrentLookAndFeel(syntaxForeground));
            }
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (TextEditor.this.settings.highlightCurrentLine()) {
                    try {
                        Rectangle2D bounds = modelToView2D(getCaretPosition());
                        if (bounds != null) {
                            Graphics overlay = graphics.create();
                            try {
                                overlay.setColor(TextHighlightColors.CURRENT_LINE_OVERLAY);
                                overlay.fillRect(0, (int) bounds.getY(), getWidth(), (int) bounds.getHeight());
                            } finally { overlay.dispose(); }
                        }
                    } catch (BadLocationException ignored) { }
                }
            }
            @Override public JPopupMenu getComponentPopupMenu() {
                return TextEditor.this.menuFactory.apply(TextEditor.this);
            }
            @Override protected void processMouseEvent(MouseEvent event) {
                if (event.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event)) {
                    try { lineLayout.fold(getLineOfOffset(viewToModel2D(event.getPoint()))).ifPresent(expandFold); }
                    catch (BadLocationException ignored) { }
                }
                if (event.isPopupTrigger()) popupOffset = viewToModel2D(event.getPoint());
                try { super.processMouseEvent(event); }
                finally { popupOffset = -1; }
            }
            @Override public void setEditable(boolean value) {
                super.setEditable(value && TextEditor.this.editing.isPresent());
            }
        };
        text.setEditable(editing.isPresent());
        text.setBorder(BorderFactory.createEmptyBorder(12, 10, 30, 18));
        text.getAccessibleContext().setAccessibleName("Document text");
        text.setFocusTraversalKeysEnabled(false);
        numbers = new EditorGutter(text, theme);
        scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        minimap = new TextMinimap(text, scroll.getViewport(), () -> lineLayout, theme);
        find = new FindBar(text, editing, this::editable, searchExecutor, report);
        add(find, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER); add(minimap, BorderLayout.EAST);
        documentListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { repaintNumbers(); }
            @Override public void removeUpdate(DocumentEvent event) { repaintNumbers(); }
            @Override public void changedUpdate(DocumentEvent event) { }
        };
        model.addDocumentListener(documentListener);
        caretListener = event -> {
            try { lineLayout.fold(text.getLineOfOffset(event.getDot())).ifPresent(expandFold); } catch (BadLocationException ignored) { }
            numbers.repaint(); text.repaint();
        };
        text.addCaretListener(caretListener);
        text.addHierarchyListener(syntaxHierarchy); text.addFocusListener(syntaxFocus);
        bind("ctrl Z", "undo", this::undo);
        bind("ctrl shift Z", "redo", this::redo);
        bind("ctrl Y", "redo-alternative", this::redo);
        bind("ctrl F", "find", () -> find(false));
        bind("ctrl R", "replace", () -> find(true));
        bind("F3", "next", () -> find.next(true));
        bind("shift F3", "previous", () -> find.next(false));
        bind("ctrl G", "go-to-line", this::goToLineDialog);
        bind("TAB", "indent", () -> indent(false));
        bind("shift TAB", "unindent", () -> indent(true));
        bind("ENTER", "newline", this::newLine);
        bind("alt shift UP", "line-up", () -> moveLines(-1));
        bind("alt shift DOWN", "line-down", () -> moveLines(1));
        applySettings(settings, theme);
    }

    public void setLineLayout(TextLineLayout layout, Consumer<TextLineLayout.Fold> expand) {
        if (lineLayout.equals(layout)) return;
        lineLayout = Objects.requireNonNull(layout); expandFold = Objects.requireNonNull(expand);
        if (text.getUI() instanceof LineLayoutTextAreaUI decorated) decorated.refreshLayout();
        else if (layout.decorated()) text.updateUI();
        numbers.setLayout(layout); minimap.invalidatePreview(); text.revalidate(); numbers.revalidate(); text.repaint();
    }
    public JTextArea textComponent() { return text; }
    public void installSyntax(SyntaxSupport support, SyntaxSource source) {
        if (closed) throw new IllegalStateException("Text view is closed");
        syntax.close(); syntaxSupport = Objects.requireNonNull(support); syntaxSource = Objects.requireNonNull(source);
        syntax = support.acquire(text.getDocument(), source, text::repaint);
        syntax.enabled(settings.syntaxHighlighting()); syntaxActivity(); text.repaint();
    }
    public SyntaxSupport syntaxSupport() { return syntaxSupport; }
    public SyntaxSource syntaxSource() { return syntaxSource; }
    public SyntaxHandle syntax() { return syntax; }
    public void syntaxSource(SyntaxSource source) { syntaxSource = Objects.requireNonNull(source); syntax.source(source); }
    public void replaceSyntaxSource(SyntaxSource source) { syntaxSource = Objects.requireNonNull(source); syntax.replaceSource(source); }
    private void syntaxActivity() { if (text != null && !closed) syntax.activity(text.isShowing(), text.hasFocus()); }
    public JScrollPane scrollPane() { return scroll; }
    public TextEditorSettings settings() { return settings; }
    public boolean wordWrap() { return settings.softWrap(); }
    public int contextOffset() { return popupOffset >= 0 ? popupOffset : text.getCaretPosition(); }
    public void setGutterPresentation(GutterPresentation value, GutterActions actions) {
        boolean resized = numbers.presentation().annotated() != value.annotated();
        Point position = scroll.getViewport().getViewPosition(); int anchor = text.viewToModel2D(position);
        int length = text.getDocument().getLength(); long version = resized ? ++gutterGeometry : gutterGeometry;
        double inset = 0;
        try { Rectangle2D bounds = text.modelToView2D(anchor); if (bounds != null) inset = position.y - bounds.getY(); }
        catch (BadLocationException ignored) { }
        numbers.setInspection(value, actions);
        if (resized) {
            revalidate(); double relative = inset;
            SwingUtilities.invokeLater(() -> {
                if (closed || gutterGeometry != version || text.getDocument().getLength() != length) return;
                try { Rectangle2D bounds = text.modelToView2D(anchor); if (bounds != null) scroll.getViewport().setViewPosition(new Point(position.x, Math.max(0, (int)(bounds.getY() + relative)))); }
                catch (BadLocationException ignored) { }
            });
        }
    }
    public EditorGutter gutter() { return numbers; }
    public void setLineMarkers(java.util.List<TextLineMarker> markers) { numbers.setMarkers(markers); }
    public void setMenuFactory(Function<TextEditor, JPopupMenu> factory) { menuFactory = Objects.requireNonNull(factory); }
    public boolean editable() { return !closed && text.isEditable() && editing.map(TextEditCommands::enabled).orElse(false); }
    public void setEditable(boolean value) { text.setEditable(value); }
    public void undo() { if (editable()) editing.orElseThrow().undo(); }
    public void redo() { if (editable()) editing.orElseThrow().redo(); }
    public void cut() { if (editable()) text.cut(); }
    public void copy() { text.copy(); }
    public void paste() { if (editable()) text.paste(); }
    public void find(boolean replace) { find.showBar(replace && editable()); }
    public void focusText() { text.requestFocusInWindow(); }
    public void caret(int offset) { text.setCaretPosition(Math.clamp(offset, 0, text.getDocument().getLength())); }
    public void goToLine(int line) {
        try { caret(text.getLineStartOffset(Math.clamp(line - 1, 0, text.getLineCount() - 1))); focusText(); }
        catch (BadLocationException ignored) { }
    }
    public void goToLineDialog() {
        String line = JOptionPane.showInputDialog(this, "Line number:", "Go to line", JOptionPane.PLAIN_MESSAGE);
        if (line != null) try { goToLine(Integer.parseInt(line)); }
        catch (NumberFormatException exception) { report.accept("Enter a line number."); }
    }
    public void applySettings(TextEditorSettings value, EditorTheme theme) {
        ViewState state = captureView();
        settings = Objects.requireNonNull(value, "settings");
        this.theme = Objects.requireNonNull(theme); syntax.enabled(value.syntaxHighlighting());
        UiTypography.editorFont(text, theme); text.setForeground(theme.text()); text.setBackground(theme.surface());
        text.setCaretColor(theme.text()); text.setSelectionColor(theme.selection());
        text.setTabSize(settings.tabSize()); text.setLineWrap(settings.softWrap()); text.setWrapStyleWord(true);
        scroll.setRowHeaderView(numbers);
        numbers.showNumbers(settings.showLineNumbers());
        numbers.applyTheme(theme);
        minimap.setVisible(settings.showMinimap()); minimap.applyTheme(theme);
        UiTypography.apply(this, theme);
        Point position = state.viewport(); if (settings.softWrap()) position.x = 0;
        restoreView(new ViewState(state.dot(), state.mark(), position));
        revalidate(); repaint();
    }
    public ViewState captureView() {
        return new ViewState(text.getCaret().getDot(), text.getCaret().getMark(), scroll.getViewport().getViewPosition());
    }
    public void restoreView(ViewState state) {
        caret(state.mark()); text.moveCaretPosition(Math.min(state.dot(), text.getDocument().getLength()));
        SwingUtilities.invokeLater(() -> { if (!closed) scroll.getViewport().setViewPosition(state.viewport()); });
    }
    public record ViewState(int dot, int mark, Point viewport) {
        public ViewState { viewport = new Point(viewport); }
        @Override public Point viewport() { return new Point(viewport); }
    }
    private void edit(Runnable action) { if (editable()) editing.orElseThrow().atomic(action); }
    private void repaintNumbers() {
        if (closed) return;
        gutterGeometry++;
        if (lineLayout.decorated()) setLineLayout(TextLineLayout.empty(), ignored -> { });
        numbers.revalidate(); numbers.repaint();
    }
    private void bind(String shortcut, String name, Runnable action) {
        text.getInputMap().put(KeyStroke.getKeyStroke(shortcut), name);
        text.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }
    private int[] selectedLines() throws BadLocationException {
        int start = text.getLineOfOffset(text.getSelectionStart());
        int end = text.getLineOfOffset(Math.max(text.getSelectionStart(), text.getSelectionEnd() - 1));
        return new int[]{start, end, text.getLineStartOffset(start), text.getLineEndOffset(end)};
    }
    public void indent(boolean backwards) {
        edit(() -> {
            try {
                if (!backwards && text.getSelectionStart() == text.getSelectionEnd()) {
                    int line = text.getLineOfOffset(text.getCaretPosition()); int column = text.getCaretPosition() - text.getLineStartOffset(line);
                    text.replaceSelection(settings.useSpaces() ? " ".repeat(settings.tabSize() - column % settings.tabSize()) : "\t"); return;
                }
                int[] lines = selectedLines();
                for (int line = lines[1]; line >= lines[0]; line--) {
                    int offset = text.getLineStartOffset(line);
                    if (!backwards) text.insert(settings.useSpaces() ? " ".repeat(settings.tabSize()) : "\t", offset);
                    else {
                        String value = text.getText(offset, text.getLineEndOffset(line) - offset); int count = 0;
                        if (value.startsWith("\t")) count = 1;
                        else while (count < Math.min(value.length(), settings.tabSize()) && value.charAt(count) == ' ') count++;
                        text.replaceRange("", offset, offset + count);
                    }
                }
                text.select(text.getLineStartOffset(lines[0]), text.getLineEndOffset(lines[1]));
            } catch (BadLocationException exception) { throw new IllegalStateException(exception); }
        });
    }
    public void newLine() {
        edit(() -> {
            try {
                int offset = text.getLineStartOffset(text.getLineOfOffset(text.getSelectionStart()));
                String previous = text.getText(offset, text.getSelectionStart() - offset); int indentation = 0;
                while (indentation < previous.length() && (previous.charAt(indentation) == ' ' || previous.charAt(indentation) == '\t')) indentation++;
                text.replaceSelection("\n" + previous.substring(0, indentation));
            } catch (BadLocationException exception) { throw new IllegalStateException(exception); }
        });
    }
    public void moveLines(int direction) {
        edit(() -> {
            try {
                int[] lines = selectedLines();
                if ((direction < 0 && lines[0] == 0) || (direction > 0 && lines[1] == text.getLineCount() - 1)) return;
                int neighbor = direction < 0 ? lines[0] - 1 : lines[1] + 1;
                int from = direction < 0 ? text.getLineStartOffset(neighbor) : lines[2];
                int to = direction < 0 ? lines[3] : text.getLineEndOffset(neighbor);
                String selected = text.getText(lines[2], lines[3] - lines[2]);
                String other = text.getText(text.getLineStartOffset(neighbor), text.getLineEndOffset(neighbor) - text.getLineStartOffset(neighbor));
                boolean ended = text.getText(from, to - from).endsWith("\n");
                selected = selected.endsWith("\n") ? selected.substring(0, selected.length() - 1) : selected;
                other = other.endsWith("\n") ? other.substring(0, other.length() - 1) : other;
                String result = (direction < 0 ? selected + "\n" + other : other + "\n" + selected) + (ended ? "\n" : "");
                text.replaceRange(result, from, to);
                int selectionStart = direction < 0 ? from : from + other.length() + 1;
                text.select(selectionStart, selectionStart + selected.length());
            } catch (BadLocationException exception) { throw new IllegalStateException(exception); }
        });
    }

    /** Hosts may reuse this menu or provide their own factory without subclassing the editor. */
    public static JPopupMenu standardMenu(TextEditor editor) {
        JPopupMenu menu = new JPopupMenu();
        if (editor.editing.isPresent()) {
            item(menu, "Undo", editor::undo, editor.editable());
            item(menu, "Redo", editor::redo, editor.editable());
            menu.addSeparator();
            item(menu, "Cut", editor::cut, editor.editable());
        }
        item(menu, "Copy", editor::copy, true);
        if (editor.editing.isPresent()) item(menu, "Paste", editor::paste, editor.editable());
        item(menu, "Select all", editor.text::selectAll, true);
        menu.addSeparator();
        item(menu, "Find…", () -> editor.find(false), true);
        if (editor.editing.isPresent()) item(menu, "Replace…", () -> editor.find(true), editor.editable());
        return menu;
    }
    private static void item(JPopupMenu menu, String label, Runnable action, boolean enabled) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(event -> action.run());
        menu.add(item);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        syntax.close(); text.removeHierarchyListener(syntaxHierarchy); text.removeFocusListener(syntaxFocus);
        text.getDocument().removeDocumentListener(documentListener);
        text.removeCaretListener(caretListener);
        minimap.close();
        numbers.dispose();
        find.close();
        text.setEditable(false);
        // Swing's UI and caret also listen to the model. Detach them from a still-live shared file.
        text.setDocument(new javax.swing.text.PlainDocument());
    }
}

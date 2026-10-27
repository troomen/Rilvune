package dev.rilvune.settings.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.settings.application.SettingsService;
import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.agents.domain.AgentSettings;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import dev.rilvune.platform.text.domain.syntax.SyntaxSource;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.text.ui.syntax.SyntaxSupport;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/** A detached form draft; only successful Apply publishes preferences to live views. */
public final class SettingsDialog extends JDialog {
    private record Page(String id, String group, String title, String keywords) {
        @Override public String toString() { return title; }
    }

    private static final List<Page> PAGES = List.of(
            new Page("appearance", "Appearance & Behavior", "Appearance", "interface ui font size theme"),
            new Page("general", "Editor", "General", "tabs spaces indent soft wrap line numbers minimap overview"),
            new Page("font", "Editor", "Font", "text font size monospace preview cyrillic"),
            new Page("highlighting", "Editor", "Highlighting", "syntax colors automatic language detection preview"),
            new Page("files", "", "Files", "hidden files encoding utf-8 line endings lf crlf autosave recovery"),
            new Page("agents", "", "Agents", "ai assistant chat enter shortcut sign-in browser restore history display limits"));
    private static final String SAMPLE = "// A quiet space for your next idea\n"
            + "public final class Welcome {\n"
            + "    public static void main(String[] args) {\n"
            + "        String greeting = \"Hello, world!\";\n"
            + "        String message = \"Привет, мир!\";\n"
            + "        System.out.println(greeting);\n"
            + "    }\n"
            + "}\n\n"
            + "Il1 O0  {} [] ()  != <= >=  0123456789\n"
            + "Съешь ещё этих мягких французских булок.";

    private final SettingsService service;
    private final FontCatalog fonts;
    private EditorTheme theme;
    private EditorSettings applied;
    private final JTextField search = new JTextField();
    private final JTree navigation = new JTree();
    private final JPanel pages = new JPanel(new CardLayout());
    private JPanel sidebar;
    private final JLabel breadcrumb = new JLabel();
    private final JLabel message = new JLabel(" ");
    private final JButton apply = new JButton("Apply");
    private final JButton okay = new JButton("OK");
    private final JButton cancel = new JButton("Cancel");
    private final JButton reload = new JButton("Reload from disk");
    private final JComboBox<String> uiFamily;
    private final JComboBox<String> editorFamily;
    private final JSpinner uiSize = new JSpinner(new SpinnerNumberModel(14, 10, 36, 1));
    private final JSpinner editorSize = new JSpinner(new SpinnerNumberModel(15, 8, 72, 1));
    private final JCheckBox monoOnly = new JCheckBox("Show only monospaced fonts", true);
    private final JTextArea editorPreview = new JTextArea(SAMPLE, 12, 45);
    private final JTextArea uiPreview = new JTextArea("Project   File   Edit   View\n\n"
            + "Workspace / notes.txt\n\nA comfortable interface, in your own type.\n"
            + "Удобный интерфейс и читаемый текст.", 6, 40);
    private final JLabel fontNotice = new JLabel(" ");
    private final JCheckBox softWrap = new JCheckBox("Wrap long lines");
    private final JCheckBox lineNumbers = new JCheckBox("Show line numbers");
    private final JCheckBox minimap = new JCheckBox("Show minimap");
    private final JCheckBox syntaxHighlighting = new JCheckBox("Enable syntax highlighting");
    private final TextEditor highlightingPreview;
    private final JCheckBox currentLine = new JCheckBox("Highlight the current line");
    private final JCheckBox spaces = new JCheckBox("Use spaces for indentation");
    private final JSpinner tabSize = new JSpinner(new SpinnerNumberModel(4, 1, 16, 1));
    private final JCheckBox hiddenFiles = new JCheckBox("Show excluded directories (.git, build, caches…)");
    private final JComboBox<String> lineEnding = new JComboBox<>(new String[]{"LF", "CRLF"});
    private final JCheckBox agentSendOnEnter = new JCheckBox("Send messages with Enter");
    private final JCheckBox agentOpenBrowser = new JCheckBox("Automatically open the browser for sign-in");
    private final JCheckBox agentRestoreChat = new JCheckBox("Restore the last selected chat when opening a project");
    private final JSpinner agentMessages = new JSpinner(new SpinnerNumberModel(2000, 100, 10000, 100));
    private final JSpinner agentCharacters = new JSpinner(new SpinnerNumberModel(4, 1, 32, 1));
    private boolean loading;
    private boolean busy;
    private String selectedPage = "appearance";
    private AutoCloseable subscription;

    public SettingsDialog(Frame owner, EditorTheme theme, SettingsService service, FontCatalog fonts) {
        this(owner, theme, service, fonts, SyntaxSupport.none());
    }

    public SettingsDialog(Frame owner, EditorTheme theme, SettingsService service, FontCatalog fonts, SyntaxSupport syntax) {
        super(owner, "Settings", ModalityType.APPLICATION_MODAL);
        this.theme = theme;
        this.service = service;
        this.fonts = fonts;
        this.applied = service.current();
        JTextArea sample = new JTextArea("// Highlighting follows the detected language\n"
                + "public final class Welcome {\n"
                + "    private final String greeting = \"Hello, world!\";\n"
                + "    public int answer() { return 42; }\n"
                + "}\n");
        highlightingPreview = new TextEditor(sample.getDocument(), java.util.Optional.empty(),
                previewSettings(applied), theme, ignored -> { }, TextEditor::standardMenu);
        highlightingPreview.setName("settingsHighlightingPreview");
        highlightingPreview.textComponent().getAccessibleContext().setAccessibleName("Syntax highlighting preview");
        highlightingPreview.installSyntax(syntax, SyntaxSource.file("Welcome.java"));
        uiFamily = new JComboBox<>(fonts.families().toArray(String[]::new));
        editorFamily = new JComboBox<>(fonts.monospacedFamilies().toArray(String[]::new));
        uiFamily.setName("settingsUiFamily");
        uiSize.setName("settingsUiSize");
        editorFamily.setName("settingsEditorFamily");
        editorSize.setName("settingsEditorSize");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        setName("settingsDialog");
        add(sidebar(), BorderLayout.WEST);
        add(content(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        bindChanges();
        loadForm(applied);
        rebuildNavigation("");
        selectPage("appearance");
        applyTheme(theme);
        subscription = service.listen(settings -> {
            applied = settings;
            applyTheme(EditorTheme.from(settings));
        });
        getRootPane().setDefaultButton(okay);
        getRootPane().registerKeyboardAction(event -> { if (!busy) dispose(); },
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Rectangle bounds = owner == null
                ? java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
                : owner.getGraphicsConfiguration().getBounds();
        setMinimumSize(new Dimension(Math.min(820, bounds.width - 40), Math.min(580, bounds.height - 60)));
        setSize(Math.min(1080, bounds.width - 60), Math.min(780, bounds.height - 80));
        setLocationRelativeTo(owner);
        service.warning().ifPresent(this::showMessage);
        if (!fonts.missingFamilies(applied).isEmpty()) {
            showMessage("Unavailable font: " + String.join(", ", fonts.missingFamilies(applied)) + ". Using Hack until you choose another font.");
        }
    }

    /** Also used by the screenshot entry point to render the real settings screen. */
    public void selectPage(String id) {
        Page page = PAGES.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
        selectedPage = id;
        ((CardLayout) pages.getLayout()).show(pages, id);
        breadcrumb.setText(page.group().isEmpty() ? page.title() : page.group() + "  ›  " + page.title());
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) navigation.getModel().getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject().equals(page)) {
                TreePath path = new TreePath(node.getPath());
                if (!path.equals(navigation.getSelectionPath())) navigation.setSelectionPath(path);
                navigation.scrollPathToVisible(path);
                break;
            }
        }
    }

    private JComponent sidebar() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        sidebar = panel;
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, theme.border()),
                BorderFactory.createEmptyBorder(18, 12, 16, 12)));
        panel.setPreferredSize(new Dimension(290, 0));
        search.putClientProperty("JTextField.placeholderText", "Search settings");
        search.setName("settingsSearch");
        panel.add(search, BorderLayout.NORTH);
        navigation.setRootVisible(false);
        navigation.setShowsRootHandles(true);
        navigation.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setLeafIcon(null);
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        navigation.setCellRenderer(renderer);
        navigation.addTreeSelectionListener(event -> {
            Object node = navigation.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode selected && selected.getUserObject() instanceof Page page) {
                selectPage(page.id());
            }
        });
        JScrollPane scroll = new JScrollPane(navigation);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JComponent content() {
        JPanel panel = new JPanel(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(22, 28, 24, 28));
        UiTypography.interfaceFont(breadcrumb, theme, Font.BOLD, 0);
        panel.add(breadcrumb, BorderLayout.NORTH);
        pages.add(scrolling(appearancePage()), "appearance");
        pages.add(scrolling(fontPage()), "font");
        pages.add(scrolling(generalPage()), "general");
        pages.add(scrolling(highlightingPage()), "highlighting");
        pages.add(scrolling(filesPage()), "files");
        pages.add(scrolling(agentsPage()), "agents");
        panel.add(pages, BorderLayout.CENTER);
        return panel;
    }

    private JComponent footer() {
        JPanel panel = new JPanel(new BorderLayout(12, 8));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, theme.border()),
                BorderFactory.createEmptyBorder(12, 18, 12, 18)));
        message.setName("settingsMessage");
        message.setForeground(theme.muted());
        panel.add(message, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        reload.setVisible(false);
        reload.addActionListener(event -> reload());
        okay.setName("settingsOK");
        apply.setName("settingsApply");
        cancel.setName("settingsCancel");
        okay.addActionListener(event -> apply(true));
        apply.addActionListener(event -> apply(false));
        cancel.addActionListener(event -> { if (!busy) dispose(); });
        okay.putClientProperty("JButton.buttonType", "roundRect");
        okay.setBackground(theme.accent());
        okay.setForeground(java.awt.Color.WHITE);
        buttons.add(reload);
        buttons.add(okay);
        buttons.add(cancel);
        buttons.add(apply);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private JPanel appearancePage() {
        JPanel panel = vertical();
        panel.add(section("Interface"));
        panel.add(note("Graphite theme · Changes apply to every open window."));
        panel.add(Box.createVerticalStrut(18));
        panel.add(fontRow(uiFamily, uiSize));
        panel.add(Box.createVerticalStrut(22));
        panel.add(section("Preview"));
        previewStyle(uiPreview);
        panel.add(new JScrollPane(uiPreview));
        panel.add(Box.createVerticalStrut(16));
        panel.add(note("The editor font is configured separately in Editor → Font."));
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel fontPage() {
        JPanel panel = vertical();
        panel.add(section("Editor font"));
        panel.add(fontRow(editorFamily, editorSize));
        panel.add(Box.createVerticalStrut(10));
        monoOnly.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(monoOnly);
        fontNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        fontNotice.setForeground(theme.muted());
        panel.add(fontNotice);
        panel.add(Box.createVerticalStrut(20));
        panel.add(section("Preview"));
        previewStyle(editorPreview);
        editorPreview.setTabSize(4);
        JScrollPane previewScroll = new JScrollPane(editorPreview);
        previewScroll.setMinimumSize(new Dimension(300, 200));
        panel.add(previewScroll);
        panel.add(Box.createVerticalStrut(12));
        panel.add(note("Hack is included with the editor. All fonts listed are available here."));
        return panel;
    }

    private JPanel generalPage() {
        JPanel panel = vertical();
        panel.add(section("Display"));
        panel.add(softWrap);
        panel.add(lineNumbers);
        panel.add(currentLine);
        minimap.setName("settingsMinimap"); panel.add(minimap);
        panel.add(Box.createVerticalStrut(26));
        panel.add(section("Indentation"));
        panel.add(spaces);
        panel.add(Box.createVerticalStrut(12));
        panel.add(fieldRow("Tab size:", tabSize));
        panel.add(Box.createVerticalStrut(12));
        panel.add(note("Indentation settings affect new edits. Existing text is kept as it is."));
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel highlightingPage() {
        JPanel panel = vertical();
        panel.add(section("Syntax highlighting"));
        syntaxHighlighting.setName("settingsSyntaxHighlighting");
        panel.add(syntaxHighlighting);
        panel.add(Box.createVerticalStrut(12));
        panel.add(note("Language is detected automatically from the filename and supported shebangs."));
        panel.add(Box.createVerticalStrut(20));
        panel.add(section("Preview"));
        highlightingPreview.setPreferredSize(new Dimension(440, 230));
        highlightingPreview.setMinimumSize(new Dimension(200, 180));
        panel.add(highlightingPreview);
        return panel;
    }

    private static TextEditorSettings previewSettings(EditorSettings settings) {
        return new TextEditorSettings(settings.tabSize(), settings.useSpaces(), settings.softWrap(),
                false, false, false, settings.syntaxHighlighting());
    }

    private JPanel filesPage() {
        JPanel panel = vertical();
        panel.add(section("New files"));
        panel.add(fieldRow("Encoding:", new JLabel("UTF-8")));
        panel.add(Box.createVerticalStrut(12));
        panel.add(fieldRow("Line endings:", lineEnding));
        panel.add(Box.createVerticalStrut(12));
        panel.add(note("Existing files keep their original byte-order mark and line endings."));
        panel.add(Box.createVerticalStrut(28));
        panel.add(section("Project tree"));
        panel.add(hiddenFiles);
        panel.add(Box.createVerticalStrut(28));
        panel.add(section("Continuous synchronization"));
        panel.add(note("Every edit is saved automatically. External changes update open files."));
        panel.add(Box.createVerticalStrut(8));
        panel.add(note("Conflicts wait for your decision. Untitled documents are kept as drafts."));
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel agentsPage() {
        JPanel panel = vertical();
        panel.add(section("Messages"));
        agentSendOnEnter.setName("settingsAgentSendOnEnter"); panel.add(agentSendOnEnter);
        panel.add(note("Ctrl+Enter always sends. Shift+Enter always inserts a new line."));
        panel.add(Box.createVerticalStrut(18));
        panel.add(section("Sign-in and startup"));
        agentOpenBrowser.setName("settingsAgentOpenBrowser"); panel.add(agentOpenBrowser);
        agentRestoreChat.setName("settingsAgentRestoreChat"); panel.add(agentRestoreChat);
        panel.add(note("Chat restoration takes effect the next time a project opens. Saved chats remain available."));
        panel.add(Box.createVerticalStrut(18));
        panel.add(section("Conversation display"));
        agentMessages.setName("settingsAgentMessages");
        agentCharacters.setName("settingsAgentCharacters");
        panel.add(fieldRow("Maximum display entries:", agentMessages));
        panel.add(Box.createVerticalStrut(12));
        panel.add(fieldRow("Maximum text (Mi characters):", agentCharacters));
        panel.add(Box.createVerticalStrut(12));
        panel.add(note("Limits apply immediately to the display only, not the agent's saved history or context. Increase limits and reopen a chat to reload omitted entries."));
        panel.add(note("These preferences apply to every project. Choose models, reasoning effort and session modes in the chat controls."));
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private void bindChanges() {
        onTextChange(search, () -> rebuildNavigation(search.getText()));
        for (JComboBox<String> combo : List.of(uiFamily, editorFamily, lineEnding)) combo.addActionListener(event -> changed());
        for (JSpinner spinner : List.of(uiSize, editorSize, tabSize, agentMessages, agentCharacters)) spinner.addChangeListener(event -> changed());
        for (JCheckBox check : List.of(softWrap, lineNumbers, currentLine, minimap, syntaxHighlighting, spaces, hiddenFiles, agentSendOnEnter, agentOpenBrowser, agentRestoreChat)) check.addActionListener(event -> changed());
        monoOnly.addActionListener(event -> {
            String selected = (String) editorFamily.getSelectedItem();
            loading = true;
            List<String> choices = monoOnly.isSelected() ? fonts.monospacedFamilies() : fonts.families();
            editorFamily.setModel(new DefaultComboBoxModel<>(choices.toArray(String[]::new)));
            // Filtering choices must not silently change the font already chosen in this draft.
            if (selected != null) selectFamily(editorFamily, selected);
            loading = false;
            changed();
        });
    }

    private void loadForm(EditorSettings settings) {
        loading = true;
        selectFamily(uiFamily, settings.uiFontFamily());
        if (!fonts.monospacedFamilies().contains(settings.editorFontFamily())) {
            monoOnly.setSelected(false);
            editorFamily.setModel(new DefaultComboBoxModel<>(fonts.families().toArray(String[]::new)));
        }
        selectFamily(editorFamily, settings.editorFontFamily());
        uiSize.setValue(settings.uiFontSize());
        editorSize.setValue(settings.editorFontSize());
        tabSize.setValue(settings.tabSize());
        spaces.setSelected(settings.useSpaces());
        softWrap.setSelected(settings.softWrap());
        lineNumbers.setSelected(settings.showLineNumbers());
        currentLine.setSelected(settings.highlightCurrentLine());
        minimap.setSelected(settings.showMinimap());
        syntaxHighlighting.setSelected(settings.syntaxHighlighting());
        hiddenFiles.setSelected(settings.showHiddenFiles());
        lineEnding.setSelectedItem(settings.defaultLineEnding());
        agentSendOnEnter.setSelected(settings.agents().sendOnEnter());
        agentOpenBrowser.setSelected(settings.agents().openLoginInBrowser());
        agentRestoreChat.setSelected(settings.agents().restoreLastChat());
        agentMessages.setValue(settings.agents().displayMessageLimit());
        agentCharacters.setValue(settings.agents().displayCharacterLimitMiB());
        loading = false;
        changed();
    }

    private EditorSettings formValue() {
        return new EditorSettings((String) uiFamily.getSelectedItem(), (Integer) uiSize.getValue(),
                (String) editorFamily.getSelectedItem(), (Integer) editorSize.getValue(),
                (Integer) tabSize.getValue(), spaces.isSelected(), softWrap.isSelected(),
                lineNumbers.isSelected(), currentLine.isSelected(), minimap.isSelected(), hiddenFiles.isSelected(),
                (String) lineEnding.getSelectedItem(), new AgentSettings(agentSendOnEnter.isSelected(),
                        agentOpenBrowser.isSelected(), agentRestoreChat.isSelected(),
                        (Integer) agentMessages.getValue(), (Integer) agentCharacters.getValue()), syntaxHighlighting.isSelected());
    }

    private static void selectFamily(JComboBox<String> combo, String family) {
        boolean listed = false;
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (family.equals(combo.getItemAt(index))) { listed = true; break; }
        }
        // A missing persisted family remains explicit; Apply requests an available replacement.
        if (!listed) combo.addItem(family);
        combo.setSelectedItem(family);
    }

    private void changed() {
        if (loading) return;
        EditorSettings draft = formValue();
        highlightingPreview.applySettings(previewSettings(draft), EditorTheme.from(draft));
        apply.setEnabled(!busy && !draft.equals(applied));
        UiTypography.previewFont(editorPreview, fonts.font(draft.editorFontFamily(), Font.PLAIN, draft.editorFontSize()));
        UiTypography.previewFont(uiPreview, fonts.font(draft.uiFontFamily(), Font.PLAIN, draft.uiFontSize()));
        editorPreview.setTabSize(draft.tabSize());
        editorPreview.setLineWrap(draft.softWrap());
        editorPreview.setWrapStyleWord(true);
        String actual = editorPreview.getFont().getFamily(Locale.ROOT);
        fontNotice.setText(" " + actual + "  ·  " + draft.editorFontSize() + " pt");
    }

    private void apply(boolean closeAfter) {
        if (busy) return;
        try {
            for (JSpinner spinner : List.of(uiSize, editorSize, tabSize, agentMessages, agentCharacters)) spinner.commitEdit();
            EditorSettings draft = formValue();
            if (!fonts.missingFamilies(draft).isEmpty()) {
                showMessage("Choose an available font. Missing: " + String.join(", ", fonts.missingFamilies(draft)));
                return;
            }
            if (draft.equals(applied) && service.warning().isEmpty()) {
                if (closeAfter) dispose();
                return;
            }
            setBusy(true);
            showMessage("Applying settings…");
            service.apply(draft).whenComplete((saved, failure) -> SwingUtilities.invokeLater(() -> {
                if (!isDisplayable()) return;
                setBusy(false);
                if (failure != null) {
                    showMessage(messageOf(failure));
                    reload.setVisible(true);
                } else {
                    applied = saved;
                    changed();
                    showMessage("Settings applied");
                    reload.setVisible(false);
                    if (closeAfter) dispose();
                }
            }));
        } catch (java.text.ParseException | IllegalArgumentException failure) {
            showMessage("Check the setting values: " + failure.getMessage());
        }
    }

    private void reload() {
        if (busy) return;
        setBusy(true);
        service.reload().whenComplete((loaded, failure) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) return;
            setBusy(false);
            if (failure != null) showMessage(messageOf(failure));
            else {
                applied = loaded;
                loadForm(loaded);
                reload.setVisible(false);
                showMessage(service.warning().orElse("Settings reloaded"));
            }
        }));
    }

    private void setBusy(boolean value) {
        busy = value;
        setEnabledRecursively(pages, !value);
        // The spinner editor owns arrow-button states; restore those after recursive enablement.
        if (!value) for (JSpinner spinner : List.of(uiSize, editorSize, tabSize, agentMessages, agentCharacters)) spinner.setEnabled(true);
        okay.setEnabled(!value);
        cancel.setEnabled(!value);
        reload.setEnabled(!value);
        setDefaultCloseOperation(value ? WindowConstants.DO_NOTHING_ON_CLOSE : WindowConstants.DISPOSE_ON_CLOSE);
        changed();
    }

    private void applyTheme(EditorTheme updated) {
        theme = updated;
        SwingUtilities.updateComponentTreeUI(this);
        UiTypography.apply(getContentPane(), updated);
        navigation.setRowHeight(Math.max(28, updated.interfaceFont().getSize() + 14));
        sidebar.setPreferredSize(new Dimension(Math.max(290, updated.interfaceFont().getSize() * 16 + 50), 0));
        changed();
        revalidate();
        repaint();
    }

    private void rebuildNavigation(String query) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Settings");
        String filter = query.strip().toLowerCase(Locale.ROOT);
        String firstMatch = null;
        for (Page page : PAGES) {
            if (!(page.group() + " " + page.title() + " " + page.keywords()).toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (firstMatch == null) firstMatch = page.id();
            DefaultMutableTreeNode parent = root;
            if (!page.group().isEmpty()) {
                for (int index = 0; index < root.getChildCount(); index++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(index);
                    if (child.getUserObject().equals(page.group())) parent = child;
                }
                if (parent == root) { parent = new DefaultMutableTreeNode(page.group()); root.add(parent); }
            }
            parent.add(new DefaultMutableTreeNode(page));
        }
        navigation.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < navigation.getRowCount(); row++) navigation.expandRow(row);
        if (!filter.isEmpty() && firstMatch != null) selectPage(firstMatch);
        else selectPage(selectedPage);
    }

    private JPanel section(String title) {
        JPanel row = new JPanel(new BorderLayout(14, 0));
        JLabel label = new JLabel(title);
        UiTypography.interfaceFont(label, theme, Font.BOLD, 0);
        row.add(label, BorderLayout.WEST);
        JPanel divider = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        divider.add(new JSeparator(), constraints);
        row.add(divider, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 16, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        return row;
    }

    private JPanel fontRow(JComboBox<String> family, JSpinner size) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(0, 0, 0, 10);
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("Font:"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        family.setPrototypeDisplayValue("DejaVu Sans Mono");
        family.setMinimumSize(new Dimension(100, family.getPreferredSize().height));
        family.setMaximumRowCount(16);
        panel.add(family, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Size:"), constraints);
        constraints.gridx = 3;
        size.setPreferredSize(new Dimension(84, size.getPreferredSize().height));
        panel.add(size, constraints);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        return panel;
    }

    private JPanel fieldRow(String title, JComponent field) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.add(new JLabel(title + "  "));
        field.setPreferredSize(new Dimension(100, field.getPreferredSize().height));
        panel.add(field);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        return panel;
    }

    private JTextArea note(String text) {
        JTextArea label = new JTextArea(text, 2, 20);
        label.setEditable(false);
        label.setFocusable(false);
        label.setLineWrap(true);
        label.setWrapStyleWord(true);
        label.setForeground(theme.muted());
        label.setBackground(theme.surface());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JScrollPane scrolling(JPanel panel) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        JPanel wrapper = new WidthTrackingPanel();
        wrapper.add(panel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrapper);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(22);
        return scroll;
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        private WidthTrackingPanel() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height - 24; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private void previewStyle(JTextArea preview) {
        preview.setEditable(false);
        preview.setMargin(new Insets(18, 18, 18, 18));
        preview.setBackground(theme.surface());
        preview.setForeground(theme.text());
        preview.setCaretPosition(0);
    }

    private static void onTextChange(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        });
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) setEnabledRecursively(child, enabled);
        }
    }

    private void showMessage(String text) {
        message.setText("<html>" + html(text) + "</html>");
        message.setToolTipText(text);
    }

    private static String html(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static String messageOf(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.io.UncheckedIOException) && cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    @Override public void dispose() {
        highlightingPreview.close();
        if (subscription != null) {
            try { subscription.close(); }
            catch (Exception failure) { throw new IllegalStateException("Cannot release settings listener", failure); }
            subscription = null;
        }
        super.dispose();
    }
}

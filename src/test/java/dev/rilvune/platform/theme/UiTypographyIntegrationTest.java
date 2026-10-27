package dev.rilvune.platform.theme;

import dev.rilvune.platform.text.ui.ReadOnlyTextModel;
import dev.rilvune.platform.text.ui.TextEditor;
import dev.rilvune.platform.text.ui.TextEditorSettings;
import dev.rilvune.platform.ui.UiComponents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Font;
import java.awt.RenderingHints;
import java.util.Optional;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ui")
class UiTypographyIntegrationTest {
    @BeforeAll static void fonts() throws Exception { FontCatalog.registerBundled(); }

    @Test void componentsCreatedAfterThemeInstallationIncludeTransientControlsAndRenderers() throws Exception {
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            JLabel label = new JLabel("Late label");
            JMenuItem menu = new JMenuItem("Transient action");
            JTable table = new JTable(new Object[][]{{"File"}}, new Object[]{"Name"});
            var renderer = (JComponent)table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
            for (JComponent control : new JComponent[]{label, menu, renderer}) {
                assertEquals("Hack", control.getFont().getFamily());
                assertAntialias(control);
            }
        });
    }

    @Test void themeRebuildPreservesEditorRolesHeadingSizesAndDraftPreviewFonts() throws Exception {
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            JPanel root = new JPanel();
            JLabel heading = new JLabel("Heading");
            UiTypography.interfaceFont(heading, theme, Font.BOLD, 5);
            JTextArea preview = new JTextArea("Draft preview");
            Font draftFont = new Font("Monospaced", Font.ITALIC, 23);
            UiTypography.previewFont(preview, draftFont);
            JMenu menu = new JMenu("View"); JMenuItem item = new JMenuItem("History"); menu.add(item);
            JPanel header = UiComponents.header(theme, "Project");
            try (TextEditor editor = editor(theme)) {
                root.add(editor); root.add(heading); root.add(preview); root.add(menu); root.add(header);
                var updated = new EditorTheme(theme.chrome(), theme.surface(), theme.raised(), theme.border(),
                        theme.text(), theme.muted(), theme.accent(), theme.selection(),
                        theme.interfaceFont().deriveFont(18f), theme.codeFont().deriveFont(21f));
                updated.install(); SwingUtilities.updateComponentTreeUI(root); UiTypography.apply(root, updated);
                assertEquals(18, item.getFont().getSize());
                assertEquals(21, editor.textComponent().getFont().getSize());
                assertEquals(21, editor.scrollPane().getRowHeader().getView().getFont().getSize());
                assertEquals(23, heading.getFont().getSize()); assertTrue(heading.getFont().isBold());
                assertEquals(draftFont, preview.getFont());
                assertEquals(44, header.getPreferredSize().height);
                for (JComponent control : new JComponent[]{item, heading, preview, editor.textComponent()}) assertAntialias(control);
            }
        });
    }

    @Test void newlyCreatedSharedEditorHasTheSameTypographyAsAnExistingEditor() throws Exception {
        edt(() -> {
            EditorTheme theme = EditorTheme.graphite(); theme.install();
            try (TextEditor existing = editor(theme)) {
                UiTypography.apply(existing, theme);
                try (TextEditor late = editor(theme)) {
                    assertEquals(existing.textComponent().getFont(), late.textComponent().getFont());
                    assertAntialias(late.textComponent());
                    assertAntialias((JComponent)late.scrollPane().getRowHeader().getView());
                }
            }
        });
    }

    private static TextEditor editor(EditorTheme theme) {
        return new TextEditor(new ReadOnlyTextModel("sample\n").document(), Optional.empty(),
                new TextEditorSettings(4, true, false, true, false, true), theme, ignored -> { }, TextEditor::standardMenu);
    }

    private static void assertAntialias(JComponent control) {
        assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, control.getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING));
    }

    private static void edt(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null); SwingUtilities.invokeAndWait(task); task.get();
    }
}

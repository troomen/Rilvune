package dev.editor.platform.ui;

import dev.editor.platform.theme.UiTypography;
import dev.editor.platform.theme.EditorTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

public final class UiComponents {
    private UiComponents() { }

    public static JButton iconButton(EditorTheme theme, Glyph.Kind icon, String name, Runnable action) {
        JButton button = new JButton(new Glyph(icon, theme.text(), 18));
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        button.setPreferredSize(new Dimension(28, 28));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFocusable(true);
        button.addActionListener(event -> action.run());
        return button;
    }

    public static JPanel header(EditorTheme theme, String title, JButton... actions) {
        JPanel header = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                return new Dimension(0, Math.max(42, getFont().getSize() + 26));
            }
        };
        header.setOpaque(false);
        UiTypography.interfaceFont(header, theme);
        header.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));
        JLabel label = new JLabel(title);
        UiTypography.interfaceFont(label, theme, Font.BOLD, 0);
        header.add(label, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
        buttons.setOpaque(false);
        for (JButton action : actions) buttons.add(action);
        header.add(buttons, BorderLayout.EAST);
        return header;
    }
}

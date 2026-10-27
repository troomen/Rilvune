package dev.editor.app.ui.about;

import dev.editor.platform.theme.UiTypography;
import dev.editor.platform.theme.EditorTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;

public final class AboutDialog extends JDialog {
    public AboutDialog(Frame owner, EditorTheme theme, Path profile) {
        super(owner, "About Editor", true);
        JPanel content = new JPanel(new BorderLayout(0, 22));
        content.setBorder(BorderFactory.createEmptyBorder(28, 32, 22, 32));
        JLabel title = new JLabel("Editor  0.2.0"); UiTypography.interfaceFontAtSize(title, theme, Font.BOLD, 24);
        content.add(title, BorderLayout.NORTH);
        JTextArea details = new JTextArea("A desktop editor for your files.\n\n"
                + "Java " + Runtime.version() + " · " + System.getProperty("os.name") + "\nSwing · FlatLaf · Hack\n\n"
                + "Profile\n" + profile + "\n\nPreferences and sessions: UTF-8 Properties files\nRecovery: versioned document snapshots\nNo database or project metadata is required.");
        details.setEditable(false); details.setOpaque(false); UiTypography.interfaceFont(details, theme);
        content.add(details, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton copy = new JButton("Copy profile path"); copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(profile.toString()), null)); buttons.add(copy);
        JButton close = new JButton("Close"); close.addActionListener(event -> dispose()); buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH); UiTypography.apply(content, theme); setContentPane(content); getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack(); setLocationRelativeTo(owner); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }
}

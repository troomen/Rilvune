package dev.rilvune.git.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.git.domain.GitCommit;
import dev.rilvune.platform.text.ui.ReadOnlyTextModel;
import dev.rilvune.platform.theme.EditorTheme;
import javax.swing.*;

/** Shared read-only commit details for repository and file history. */
final class GitCommitDetails extends JScrollPane {
    private final ReadOnlyTextModel model = new ReadOnlyTextModel("");
    private final JTextArea text = new JTextArea();
    GitCommitDetails() {
        text.setDocument(model.document()); text.setEditable(false);
        text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12)); text.setName("git.commitDetails");
        setViewportView(text);
    }
    void show(GitCommit commit) {
        show(commit.message() + "\n\nCommit  " + commit.id() + "\nAuthor  " + commit.author() + "\nDate    " + commit.date()
                + "\nParents " + String.join("\n        ", commit.parents()));
    }
    void show(String value) { if (!text.getText().equals(value)) { model.replaceSnapshot(value); text.setCaretPosition(0); } }
    JTextArea textComponent() { return text; }
    void applyTheme(EditorTheme theme) { UiTypography.interfaceFont(text, theme); text.setBackground(theme.surface()); text.setForeground(theme.text()); }
}

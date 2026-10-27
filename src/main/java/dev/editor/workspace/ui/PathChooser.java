package dev.editor.workspace.ui;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileSystemView;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Dialog navigation is rooted in the explicitly mounted, persistent host directory. */
public final class PathChooser {
    private final Path root;
    public PathChooser(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Optional<Path> choose(Component owner, Path initial, boolean directory, boolean save, String title) {
        FileSystemView view = new FileSystemView() {
            @Override public File createNewFolder(File containing) throws IOException { return FileSystemView.getFileSystemView().createNewFolder(containing); }
            @Override public File[] getRoots() { return new File[]{root.toFile()}; }
            @Override public File getHomeDirectory() { return root.toFile(); }
            @Override public File getDefaultDirectory() { return root.toFile(); }
            @Override public boolean isRoot(File file) { return file.toPath().toAbsolutePath().normalize().equals(root); }
            @Override public File getParentDirectory(File file) { return isRoot(file) ? null : super.getParentDirectory(file); }
        };
        JFileChooser chooser = new JFileChooser(initial == null ? root.toFile() : initial.toFile(), view);
        chooser.setDialogTitle(title); chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        int result = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
        if (result != JFileChooser.APPROVE_OPTION) return Optional.empty();
        Path chosen = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!chosen.startsWith(root)) {
            JOptionPane.showMessageDialog(owner, "Choose a path inside " + root + ".\nSet BROWSE_ROOT to make another host directory available.", "Outside available directories", JOptionPane.ERROR_MESSAGE);
            return Optional.empty();
        }
        return Optional.of(chosen);
    }
}

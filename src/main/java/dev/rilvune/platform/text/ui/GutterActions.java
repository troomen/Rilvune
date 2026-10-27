package dev.rilvune.platform.text.ui;

import javax.swing.JPopupMenu;

public interface GutterActions {
    void marker(int id);
    void annotation(int line);
    JPopupMenu menu(int line);
    static GutterActions none() {
        return new GutterActions() { public void marker(int id) { } public void annotation(int line) { } public JPopupMenu menu(int line) { return new JPopupMenu(); } };
    }
}

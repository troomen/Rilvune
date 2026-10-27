package dev.rilvune.platform.layout.ui;

import javax.swing.JComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicitly supplied, already constructed views. No discovery or hidden lifecycle. */
public final class PanelRegistry {
    private final Map<String, JComponent> panels = new LinkedHashMap<>();

    public void register(String id, JComponent panel) {
        Objects.requireNonNull(panel, "panel");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Panel id is required");
        if (panels.putIfAbsent(id, panel) != null) throw new IllegalArgumentException("Duplicate panel: " + id);
    }

    public JComponent require(String id) {
        JComponent panel = panels.get(id);
        if (panel == null) throw new IllegalArgumentException("Unknown panel: " + id);
        return panel;
    }
}

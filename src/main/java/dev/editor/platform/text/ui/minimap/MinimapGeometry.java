package dev.editor.platform.text.ui.minimap;

import java.awt.Rectangle;

/** Maps the actual Swing document surface, including wrapping, folds and virtual padding. */
record MinimapGeometry(int width, int height, int contentHeight) {
    static final int WIDTH = 64;
    static MinimapGeometry of(int width, int availableHeight, int contentHeight, double glyphScale) {
        int content = Math.max(1, contentHeight);
        int height = Math.max(1, Math.min(Math.min(availableHeight, 2048), (int)Math.ceil(content * glyphScale)));
        return new MinimapGeometry(Math.max(1, Math.min(width, WIDTH)), height, content);
    }
    double scale() { return height / (double)contentHeight; }
    double documentY(double y) { return y / scale(); }
    int viewY(double y, double grabOffset, int extent) {
        return (int)Math.clamp(Math.round(documentY(y) - grabOffset), 0, Math.max(0, contentHeight - extent));
    }
    Rectangle viewport(int viewY, int extent) {
        int size = Math.min(height, Math.max(4, (int)Math.ceil(extent * scale())));
        int top = Math.clamp((int)Math.round(viewY * scale()), 0, height - size);
        return new Rectangle(0, top, width, size);
    }
}

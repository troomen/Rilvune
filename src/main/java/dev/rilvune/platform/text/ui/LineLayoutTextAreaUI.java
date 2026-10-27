package dev.rilvune.platform.text.ui;

import dev.rilvune.platform.theme.UiTypography;
import dev.rilvune.platform.text.ui.syntax.SyntaxForeground;
import dev.rilvune.platform.text.ui.syntax.SyntaxViews;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;
import java.awt.*;
import java.util.function.Supplier;

/** Supported Swing View composition: one real source line per child, with optional view-only space. */
final class LineLayoutTextAreaUI extends BasicTextAreaUI {
    private final Supplier<TextLineLayout> layout;
    private final SyntaxForeground foreground;
    LineLayoutTextAreaUI(Supplier<TextLineLayout> layout) { this(layout, null); }
    LineLayoutTextAreaUI(Supplier<TextLineLayout> layout, SyntaxForeground foreground) { this.layout = layout; this.foreground = foreground; }
    void refreshLayout() { invalidate(getRootView(getComponent())); getComponent().revalidate(); getComponent().repaint(); }
    private void invalidate(View view) {
        for (int index = 0; index < view.getViewCount(); index++) invalidate(view.getView(index));
        if (view instanceof BoxView box) { box.layoutChanged(View.Y_AXIS); box.layoutChanged(View.X_AXIS); box.preferenceChanged(null, true, true); }
    }
    @Override public View create(Element element) { return element.isLeaf() ? new Line(element) : new Lines(element); }
    private final class Lines extends BoxView {
        Lines(Element element) { super(element, View.Y_AXIS); }
        @Override protected void loadChildren(ViewFactory factory) {
            View[] lines = new View[getElement().getElementCount()];
            for (int line = 0; line < lines.length; line++) lines[line] = new Line(getElement().getElement(line));
            replace(0, getViewCount(), lines);
        }
        @Override protected void layoutMinorAxis(int span, int axis, int[] offsets, int[] spans) { for (int i = 0; i < spans.length; i++) { offsets[i] = 0; spans[i] = Math.max(span, (int)getView(i).getPreferredSpan(axis)); } }
        @Override public float getMaximumSpan(int axis) { return axis == X_AXIS ? Integer.MAX_VALUE : super.getMaximumSpan(axis); }
    }
    private final class Line extends BoxView {
        Line(Element element) { super(element, View.Y_AXIS); }
        // Element offsets move after insert/remove events; a captured index would become stale.
        private int number() { return getElement().getParentElement().getElementIndex(getStartOffset()); }
        @Override protected void loadChildren(ViewFactory factory) {
            Element root = new SingleLineElement(getElement());
            JTextArea area = (JTextArea)getComponent();
            View text = SyntaxViews.create(root, area.getLineWrap(), area.getWrapStyleWord(), foreground);
            replace(0, getViewCount(), new View[]{text});
        }
        private int height() { return getComponent().getFontMetrics(getComponent().getFont()).getHeight(); }
        private int padding() { return layout.get().padding().getOrDefault(number(), 0) * height(); }
        private TextLineLayout.Fold fold() { return layout.get().fold(number()).orElse(null); }
        @Override public float getPreferredSpan(int axis) {
            if (axis != Y_AXIS) return super.getPreferredSpan(axis);
            var fold = fold(); if (fold != null) return fold.firstLine() == number() ? height() + padding() : 0;
            return super.getPreferredSpan(axis) + padding();
        }
        @Override public float getMinimumSpan(int axis) { return axis == Y_AXIS ? getPreferredSpan(axis) : super.getMinimumSpan(axis); }
        @Override public float getMaximumSpan(int axis) { return axis == Y_AXIS ? getPreferredSpan(axis) : Integer.MAX_VALUE; }
        @Override protected void layoutMinorAxis(int span, int axis, int[] offsets, int[] spans) {
            if (spans.length > 0) { offsets[0] = 0; spans[0] = Math.max(span, (int)getView(0).getPreferredSpan(axis)); }
        }
        @Override protected void layoutMajorAxis(int span, int axis, int[] offsets, int[] spans) { if (spans.length > 0) { offsets[0] = padding(); spans[0] = Math.max(0, span - padding()); } }
        @Override protected int getViewIndexAtPosition(int position) { return 0; }
        @Override public int getViewIndex(int position, Position.Bias bias) { return 0; }
        @Override public void paint(Graphics graphics, Shape allocation) {
            var fold = fold();
            if (fold == null) { super.paint(graphics, allocation); return; }
            if (fold.firstLine() != number()) return;
            Rectangle bounds = allocation.getBounds(); Graphics2D g = (Graphics2D) graphics.create();
            UiTypography.antialias(g);
            g.setColor(UIManager.getColor("Panel.background")); g.fillRect(bounds.x, bounds.y + padding(), bounds.width, height());
            g.setColor(UIManager.getColor("Label.disabledForeground")); g.setFont(getComponent().getFont());
            g.drawString("▶  " + (fold.endLine() - fold.firstLine()) + " unchanged lines · click to expand", bounds.x + 6, bounds.y + padding() + g.getFontMetrics().getAscent()); g.dispose();
        }
        @Override public Shape modelToView(int position, Shape allocation, Position.Bias bias) throws BadLocationException {
            if (fold() != null) { Rectangle bounds = allocation.getBounds(); return new Rectangle(bounds.x, bounds.y + padding(), 1, fold().firstLine() == number() ? height() : 0); }
            return super.modelToView(position, allocation, bias);
        }
        @Override public int viewToModel(float x, float y, Shape allocation, Position.Bias[] bias) {
            if (fold() != null) { bias[0] = Position.Bias.Forward; return getStartOffset(); }
            return super.viewToModel(x, y, allocation, bias);
        }
    }
    private record SingleLineElement(Element line) implements Element {
        public Document getDocument() { return line.getDocument(); } public Element getParentElement() { return line.getParentElement(); }
        public String getName() { return "source-line"; } public AttributeSet getAttributes() { return line.getAttributes(); }
        public int getStartOffset() { return line.getStartOffset(); } public int getEndOffset() { return line.getEndOffset(); }
        public int getElementIndex(int offset) { return 0; } public int getElementCount() { return 1; } public Element getElement(int index) { return index == 0 ? line : null; } public boolean isLeaf() { return false; }
    }
}

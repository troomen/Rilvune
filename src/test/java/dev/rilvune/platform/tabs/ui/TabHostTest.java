package dev.rilvune.platform.tabs.ui;

import dev.rilvune.platform.theme.EditorTheme;

import java.awt.Color;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TabHostTest {
    @Test void tabsNeedNoFileIdentityAndKeepBackgroundSelectionAndKeyboardOrder() throws Exception {
        edt(() -> {
            TabHost host = new TabHost(EditorTheme.graphite(), new JLabel("Welcome"), ignored -> { }, ignored -> new JPopupMenu());
            TestTab first = new TestTab("virtual-a", () -> CompletableFuture.completedFuture(false));
            TestTab second = new TestTab("virtual-b", () -> CompletableFuture.completedFuture(false));
            host.show(first, true); host.show(second, false);
            assertSame(first, host.active().orElseThrow());
            host.next(1); assertSame(second, host.active().orElseThrow());
            host.next(1); assertSame(first, host.active().orElseThrow());
            assertTrue(first.focused > 0); assertTrue(second.focused > 0);
            host.applyTheme(EditorTheme.graphite());
            assertSame(first, host.active().orElseThrow());
            assertEquals(2, host.tabs().size());
            host.remove(first.id()); host.remove(first.id());
            assertEquals(1, first.disposed);
            assertSame(second, host.active().orElseThrow());
            host.remove(second.id());
            assertTrue(host.active().isEmpty());
            return null;
        });
    }

    @Test void closeRequestsStayWithTheTabOwnerAndCanBeCancelled() throws Exception {
        edt(() -> {
            TabHost host = new TabHost(EditorTheme.graphite(), new JLabel("Welcome"), ignored -> { }, ignored -> new JPopupMenu());
            CompletableFuture<Boolean> decision = new CompletableFuture<>();
            TestTab tab = new TestTab("no-path", () -> decision);
            host.show(tab, true);
            CompletableFuture<Boolean> result = host.requestCloseActive();
            assertFalse(result.isDone()); assertEquals(1, host.tabs().size());
            decision.complete(false);
            assertFalse(result.join()); assertEquals(1, host.tabs().size()); assertEquals(0, tab.disposed);
            host.remove(tab.id()); assertEquals(1, tab.disposed);
            return null;
        });
    }

    @Test void selectedAndUnselectedTitleColorsFollowSelectionAndThemeAndTreatNamesAsLiteral() throws Exception {
        edt(() -> {
            TabHost host = new TabHost(EditorTheme.graphite(), new JLabel("Welcome"), ignored -> { }, ignored -> new JPopupMenu());
            TestTab first = new TestTab("<html><b>literal.txt", () -> CompletableFuture.completedFuture(true));
            first.foreground = Color.BLUE; first.selectedForeground = Color.CYAN;
            TestTab second = new TestTab("other.txt", () -> CompletableFuture.completedFuture(true));
            host.show(first, true); host.show(second, false);
            javax.swing.JTabbedPane pane = (javax.swing.JTabbedPane) host.getComponent(1);
            JLabel label = (JLabel)((javax.swing.JPanel)pane.getTabComponentAt(0)).getComponent(0);
            assertEquals(Color.CYAN, label.getForeground()); assertNull(label.getClientProperty("html"));
            assertEquals(first.id(), label.getText());
            host.select(second.id()); assertEquals(Color.BLUE, label.getForeground());
            host.applyTheme(EditorTheme.graphite()); assertEquals(Color.BLUE, label.getForeground());
            host.select(first.id()); assertEquals(Color.CYAN, label.getForeground()); return null;
        });
    }

    private static final class TestTab implements WorkspaceTab {
        private final String id;
        private final JLabel view = new JLabel("Virtual content");
        private final Supplier<CompletableFuture<Boolean>> close;
        private Color foreground = Color.GRAY, selectedForeground = Color.GRAY;
        private int focused;
        private int disposed;
        private TestTab(String id, Supplier<CompletableFuture<Boolean>> close) { this.id = id; this.close = close; }
        @Override public String id() { return id; }
        @Override public JComponent component() { return view; }
        @Override public TabPresentation presentation() { return new TabPresentation(id, "No filesystem identity", foreground, selectedForeground); }
        @Override public void focusContent() { focused++; }
        @Override public CompletableFuture<Boolean> requestClose() { return close.get(); }
        @Override public void dispose() { disposed++; }
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}

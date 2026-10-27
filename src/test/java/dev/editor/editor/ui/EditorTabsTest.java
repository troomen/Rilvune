package dev.editor.editor.ui;

import dev.editor.editor.application.*;
import dev.editor.editor.domain.*;
import dev.editor.platform.tabs.ui.TabPresentation;
import dev.editor.platform.theme.EditorTheme;
import dev.editor.session.domain.TabDescriptor;
import dev.editor.settings.domain.EditorSettings;

import java.util.concurrent.*;

import javax.swing.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorTabsTest {
    @Test void registrationRollbackPreservesThePrimaryErrorAndLeavesNoGhostTab() throws Exception {
        edt(() -> {
            var tabs = new EditorTabs(EditorTheme.graphite(), EditorSettings.defaults(), ignored -> { }, ignored -> { }, () -> { }, () -> { });
            var focusFailure = new IllegalStateException("Cannot focus new content");
            var disposalFailure = new IllegalStateException("Cannot dispose failed content");
            var tab = new TestTabView(focusFailure, disposalFailure);
            assertSame(focusFailure, assertThrows(IllegalStateException.class, () -> tabs.register(tab, true)));
            assertArrayEquals(new Throwable[]{disposalFailure}, focusFailure.getSuppressed());
            assertTrue(tabs.tabs().isEmpty()); assertTrue(tabs.activeTab().isEmpty());
            assertTrue(tabs.host.getComponent(0).isVisible(), "Rollback must restore the welcome surface even if disposal fails");
            var healthy = new TestTabView(null, null); tabs.register(healthy, true);
            assertSame(healthy, tabs.activeTab().orElseThrow()); tabs.disposeAfterShutdown();
            return null;
        });
    }
    private record State() implements EditorTabState { }
    private record Model(String id) implements EditorTabModel<State> { public State state() { return new State(); } }
    private record Descriptor(String id) implements TabDescriptor { }
    private static final class TestTabView implements EditorTabView {
        private final JLabel component = new JLabel("Content");
        private final RuntimeException focusFailure, disposalFailure;
        private final EditorTabController controller = new EditorTabController() {
            public EditorTabModel<?> model() { return new Model("test"); }
            public TabCloseHandler closeHandler() { return ReadOnlyTabCloseHandler.INSTANCE; }
        };
        private TestTabView(RuntimeException focusFailure, RuntimeException disposalFailure) { this.focusFailure = focusFailure; this.disposalFailure = disposalFailure; }
        public EditorTabController controller() { return controller; }
        public String id() { return "test"; }
        public JComponent component() { return component; }
        public TabPresentation presentation() { return new TabPresentation("Test", "Test content", java.awt.Color.GRAY); }
        public TabDescriptor descriptor() { return new Descriptor(id()); }
        public void applySettings(EditorSettings settings, EditorTheme theme) { }
        public void preserveViewState(Runnable update) { update.run(); }
        public void refreshState() { }
        public void focusContent() { if (focusFailure != null) throw focusFailure; }
        public CompletableFuture<Boolean> requestClose() { return CompletableFuture.completedFuture(false); }
        public void dispose() { if (disposalFailure != null) throw disposalFailure; }
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}

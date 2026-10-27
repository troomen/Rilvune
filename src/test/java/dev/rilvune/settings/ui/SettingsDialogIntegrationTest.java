package dev.rilvune.settings.ui;

import dev.rilvune.settings.application.SettingsService;
import dev.rilvune.settings.domain.EditorSettings;
import dev.rilvune.settings.infrastructure.PropertiesSettingsStore;
import dev.rilvune.platform.theme.EditorTheme;
import dev.rilvune.platform.theme.FontCatalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
@EnabledIfSystemProperty(named = "java.awt.headless", matches = "false")
@Timeout(20)
class SettingsDialogIntegrationTest {
    @TempDir Path profile;

    @Test
    void applyPublishesAfterPersistenceAndCancelKeepsLastSuccessfulApply() throws Exception {
        FontCatalog.registerBundled();
        var io = Executors.newSingleThreadExecutor();
        var store = new PropertiesSettingsStore(profile);
        var service = new SettingsService(store, io, SwingUtilities::invokeLater);
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        SettingsDialog dialog = onEdt(() -> {
            EditorTheme.graphite().install();
            service.listen(value -> EditorTheme.from(value).install());
            var window = new SettingsDialog(null, EditorTheme.graphite(), service, new FontCatalog());
            window.setModal(false);
            window.selectPage("font");
            window.setVisible(true);
            return window;
        });
        try {
            assertFalse(onEdt(() -> find(dialog, "settingsApply", JButton.class).isEnabled()));
            onEdt(() -> {
                find(dialog, "settingsEditorSize", JSpinner.class).setValue(24);
                find(dialog, "settingsMinimap", JCheckBox.class).doClick(); return null;
            });
            assertTrue(onEdt(() -> find(dialog, "settingsApply", JButton.class).isEnabled()));
            onEdt(() -> {
                dialog.selectPage("agents");
                find(dialog, "settingsAgentSendOnEnter", JCheckBox.class).doClick();
                find(dialog, "settingsAgentOpenBrowser", JCheckBox.class).doClick();
                find(dialog, "settingsAgentRestoreChat", JCheckBox.class).doClick();
                find(dialog, "settingsAgentMessages", JSpinner.class).setValue(500);
                find(dialog, "settingsAgentCharacters", JSpinner.class).setValue(2);
                return null;
            });
            assertEquals(15, service.current().editorFontSize());
            assertTrue(service.current().showMinimap());
            assertEquals(15, store.load().settings().editorFontSize());
            io.execute(() -> {
                entered.countDown();
                try { gate.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            CompletableFuture<EditorSettings> committed = new CompletableFuture<>();
            try (var subscription = service.listen(committed::complete)) {
                onEdt(() -> { find(dialog, "settingsApply", JButton.class).doClick(); return null; });
                assertFalse(onEdt(() -> find(dialog, "settingsApply", JButton.class).isEnabled()));
                assertFalse(onEdt(() -> find(dialog, "settingsCancel", JButton.class).isEnabled()));
                assertEquals(15, service.current().editorFontSize());
                gate.countDown();
                assertEquals(24, committed.get(8, TimeUnit.SECONDS).editorFontSize());
            }
            // Finish the completion callback that restores the form controls.
            onEdt(() -> null);
            onEdt(() -> null);
            assertEquals(24, store.load().settings().editorFontSize());
            assertEquals(new dev.rilvune.agents.domain.AgentSettings(false, false, false, 500, 2), service.current().agents());
            assertEquals(service.current().agents(), store.load().settings().agents());
            assertFalse(service.current().showMinimap()); assertFalse(store.load().settings().showMinimap());
            assertFalse(onEdt(() -> find(dialog, "settingsApply", JButton.class).isEnabled()));
            onEdt(() -> {
                find(dialog, "settingsEditorSize", JSpinner.class).setValue(30);
                find(dialog, "settingsAgentSendOnEnter", JCheckBox.class).doClick();
                find(dialog, "settingsCancel", JButton.class).doClick();
                return null;
            });
            assertEquals(24, service.current().editorFontSize());
            assertEquals(24, store.load().settings().editorFontSize());
            assertFalse(service.current().showMinimap()); assertFalse(store.load().settings().showMinimap());
            assertFalse(onEdt(dialog::isDisplayable));
            assertFalse(service.current().agents().sendOnEnter());
        } finally {
            gate.countDown();
            onEdt(() -> { dialog.dispose(); return null; });
            io.shutdown();
            assertTrue(io.awaitTermination(8, TimeUnit.SECONDS));
        }
    }

    private static <T extends Component> T find(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T onEdt(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        SwingUtilities.invokeAndWait(task);
        return task.get(8, TimeUnit.SECONDS);
    }
}

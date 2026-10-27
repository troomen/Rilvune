package dev.rilvune.platform.text.ui;

import org.junit.jupiter.api.*;
import javax.swing.*;
import javax.swing.text.PlainDocument;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FindBarTest {
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private FindBar bar;
    private JTextArea area;
    private EditorUndo undo;
    private int calculations;

    @Test void findOffersAVisibleToggleForReplacementControls() throws Exception {
        edt(() -> {
            area = new JTextArea("example"); undo = new EditorUndo(() -> true);
            bar = new FindBar(area, Optional.of(undo), () -> true, work::add, message -> fail(message));
            bar.showBar(false);
            JToggleButton toggle = Arrays.stream(((JPanel) bar.getComponent(0)).getComponents())
                    .filter(JToggleButton.class::isInstance).map(JToggleButton.class::cast)
                    .filter(button -> "Replace".equals(button.getText())).findFirst().orElseThrow();
            assertEquals("Replace", toggle.getText()); assertFalse(bar.getComponent(1).isVisible());
            toggle.doClick(); assertTrue(bar.getComponent(1).isVisible());
            toggle.doClick(); assertFalse(bar.getComponent(1).isVisible());
            bar.showBar(true); assertTrue(toggle.isSelected()); assertTrue(bar.getComponent(1).isVisible());
            return null;
        });
    }

    private void create(String text) throws Exception {
        edt(() -> {
            PlainDocument model = new PlainDocument(); model.insertString(0, text, null);
            undo = new EditorUndo(() -> true); model.addUndoableEditListener(undo);
            area = new JTextArea(model);
            bar = new FindBar(area, Optional.of(undo), () -> true, task -> { calculations++; work.add(task); }, message -> fail(message));
            field("query").setText("a"); field("replacement").setText("bb");
            bar.setVisible(true); bar.replaceAll(); return null;
        });
    }
    private JTextField field(String name) {
        try { Field field = FindBar.class.getDeclaredField(name); field.setAccessible(true); return (JTextField) field.get(bar); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private Runnable take() throws Exception { Runnable task = work.poll(5, TimeUnit.SECONDS); assertNotNull(task); return task; }
    private void finishOne() throws Exception { take().run(); edt(() -> null); }
    @AfterEach void close() throws Exception { edt(() -> { if (bar != null) bar.close(); return null; }); }

    @Test void fiftyThousandReplacementsNeedOnlyOneCalculationAndOneRefreshAndOneUndo() throws Exception {
        create("a".repeat(50000)); finishOne(); finishOne();
        edt(() -> {
            assertEquals("bb".repeat(50000), area.getText());
            assertEquals(2, calculations); assertTrue(work.isEmpty());
            undo.undo(); assertEquals("a".repeat(50000), area.getText());
            undo.redo(); assertEquals("bb".repeat(50000), area.getText());
            return null;
        });
    }

    @Test void editingDuringCalculationInvalidatesTheReplacementAndKeepsOnlyLatestScan() throws Exception {
        create("aaa"); Runnable old = take();
        edt(() -> { for (int i = 0; i < 30; i++) area.append("a"); return null; });
        old.run(); edt(() -> null); finishOne();
        assertEquals("a".repeat(33), edt(area::getText));
        assertEquals(2, calculations); assertTrue(work.isEmpty());
    }

    @Test void changedQueryDiscardsAnAlreadyComputedReplacementBeforePresentation() throws Exception {
        create("aaa"); Runnable old = take();
        edt(() -> { old.run(); field("query").setText("z"); return null; });
        finishOne();
        assertEquals("aaa", edt(area::getText));
        assertTrue(work.isEmpty());
    }

    @Test void closingDuringCalculationDoesNotApplyOrScheduleMoreWork() throws Exception {
        create("aaa"); Runnable old = take();
        edt(() -> { bar.close(); return null; }); old.run(); edt(() -> null);
        assertEquals("aaa", edt(area::getText)); assertTrue(work.isEmpty());
    }

    @Test void hiddenBarDoesNotScanOnDocumentChanges() throws Exception {
        create("aaa"); Runnable old = take();
        edt(() -> { bar.hideBar(); area.append("xyz"); return null; }); old.run(); edt(() -> null);
        assertEquals("aaaxyz", edt(area::getText)); assertTrue(work.isEmpty());
    }

    @Test void noMatchesDoNotMutateTheModelOrCreateAnUndoEntry() throws Exception {
        create("bbb"); finishOne();
        assertEquals("bbb", edt(area::getText)); assertFalse(edt(undo::canUndo));
        assertEquals(1, calculations); assertTrue(work.isEmpty());
    }

    @Test void readOnlySearchNavigatesWithoutReceivingAMutationCapability() throws Exception {
        edt(() -> {
            area = new JTextArea("a b a"); area.setEditable(false);
            bar = new FindBar(area, Optional.empty(), () -> false, work::add, message -> fail(message));
            field("query").setText("a"); bar.setVisible(true); bar.replaceAll();
            assertTrue(work.isEmpty()); bar.next(true); return null;
        });
        finishOne();
        assertEquals(0, edt(area::getSelectionStart)); assertEquals(1, edt(area::getSelectionEnd));
        assertEquals("a b a", edt(area::getText));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(5, TimeUnit.SECONDS);
    }
}

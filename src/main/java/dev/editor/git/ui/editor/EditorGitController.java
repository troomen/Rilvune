package dev.editor.git.ui.editor;

import dev.editor.diff.domain.*;
import dev.editor.diff.ui.SnapshotDiffDialog;
import dev.editor.git.application.*;
import dev.editor.git.domain.*;
import dev.editor.platform.text.ui.*;
import dev.editor.platform.theme.*;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One ordinary editor's inspection lifecycle. All live-document access stays on the EDT. */
public final class EditorGitController implements TextEditorExtension, GutterActions {
    private record Scope(RepositoryLocation repository, String head, String path, String historicalPath, boolean ignored, boolean unmerged, boolean newFile, boolean fresh) { }
    private record Result(EditorBaseline baseline, LiveEditorDocument.Snapshot document, DiffResult diff, int[] attribution) { }
    private final EditorGitService service;
    private final LiveEditorDocument document;
    private final TextEditor editor;
    private final DiffEngine engine;
    private final ExecutorService workers;
    private final Consumer<CommitNavigation> navigate;
    private final Consumer<String> report;
    private final Clock clock;
    private final ZoneId zone;
    private final BlameLabels labels;
    private final AutoCloseable statusListener, documentListener;
    private final javax.swing.Timer debounce, midnight;
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, Action> previousActions = new HashMap<>();
    private EditorTheme theme;
    private ProjectGitStatus status = ProjectGitStatus.empty(null, ProjectGitStatus.Phase.NO_REPOSITORY, "No project open");
    private Scope scope;
    private EditorBaseline baseline;
    private EditorGitService.Acquisition<EditorBaseline> baselineRequest;
    private EditorGitService.Acquisition<List<GitBlameLine>> blameRequest;
    private List<GitBlameLine> blame = List.of();
    private Map<Integer, GitBlameLine> displayedBlame = Map.of();
    private Result result;
    private LiveEditorDocument.Snapshot observedDocument;
    private ChangePopup popup;
    private final List<SnapshotDiffDialog> fullDiffs = new ArrayList<>();
    private Future<?> comparison, validation;
    private boolean active, annotated, running, again, closed, blameLoaded;
    private String message = "", blameError = "";

    public EditorGitController(EditorGitService service, LiveEditorDocument document, TextEditor editor, DiffEngine engine,
                               ExecutorService workers, EditorTheme theme, Clock clock, ZoneId zone, Locale locale,
                               Consumer<CommitNavigation> navigate, Consumer<String> report) {
        this.service = service; this.document = document; this.editor = editor; this.engine = engine; this.workers = workers;
        this.theme = theme; this.clock = clock; this.zone = zone; this.labels = new BlameLabels(clock, zone, locale); this.navigate = navigate; this.report = report;
        debounce = new javax.swing.Timer(200, event -> refresh()); debounce.setRepeats(false);
        midnight = new javax.swing.Timer(1000, event -> { render(); scheduleMidnight(); }); midnight.setRepeats(false);
        bind("F7", "git.editor.next", () -> move(1)); bind("shift F7", "git.editor.previous", () -> move(-1)); bind("ctrl shift F7", "git.editor.preview", this::showCurrent);
        documentListener = document.observe(this::documentChanged);
        statusListener = service.observe(next -> { status = next; repositoryChanged(); });
        render();
    }
    private void bind(String shortcut, String name, Runnable action) {
        JComponent target = editor.textComponent(); previousActions.put(name, target.getActionMap().get(name));
        target.getInputMap().put(KeyStroke.getKeyStroke(shortcut), name);
        target.getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
    }
    private Scope scope(LiveEditorDocument.Snapshot snapshot) {
        ProjectGitStatus state = status; Path path = snapshot.path();
        if (!state.available() || path == null || !path.startsWith(state.location().root()) || path.equals(state.location().root())) return null;
        String relative = state.location().root().relativize(path).toString(); String historical = relative;
        if (relative.equals(".git") || relative.startsWith(".git/")) return null;
        boolean unmerged = false, newFile = false;
        GitChange change = service.change(relative);
        if (change != null) {
            if (change.untracked()) return null;
            historical = change.oldPath(); unmerged = change.unmerged(); newFile = change.index() == 'A';
        }
        GitPathStatus status = state.paths().get(path);
        return new Scope(state.location(), state.local().head(), relative, historical, status != null && status.kind() == GitPathStatus.Kind.IGNORED,
                unmerged, newFile, state.phase() == ProjectGitStatus.Phase.READY);
    }
    private void repositoryChanged() {
        if (closed) return;
        Scope next = document.snapshot().map(this::scope).orElse(null);
        if (!Objects.equals(next, scope)) {
            scope = next; invalidate(); releaseBaseline(); baseline = null; blame = List.of(); blameLoaded = false; blameError = "";
            if (active) debounce.restart();
        }
        render();
    }
    private void documentChanged() {
        if (closed) return;
        Optional<LiveEditorDocument.Snapshot> current = document.snapshot();
        LiveEditorDocument.Snapshot observed = current.orElse(null);
        if (Objects.equals(observedDocument, observed)) return;
        observedDocument = observed;
        Scope next = current.map(this::scope).orElse(null);
        if (!Objects.equals(next, scope)) { repositoryChanged(); return; }
        if (result != null && current.isPresent() && result.document().revision() == current.get().revision()
                && result.document().canRevert() == current.get().canRevert()) return;
        invalidate(); if (active) debounce.restart();
    }
    private void invalidate() {
        generation.incrementAndGet(); result = null; closePopup(); displayedBlame = Map.of();
        if (running) again = true;
        render();
    }
    private void refresh() {
        if (closed || !active) return;
        Optional<LiveEditorDocument.Snapshot> current = document.snapshot();
        if (current.isEmpty()) { message = "Waiting for the current document revision"; render(); return; }
        Scope next = scope(current.get());
        if (!Objects.equals(next, scope)) { repositoryChanged(); return; }
        if (scope == null || scope.ignored()) { message = "Git inspection is unavailable for this document"; render(); return; }
        if (!scope.fresh()) { message = "Repository status is stale · refresh before inspecting changes"; render(); return; }
        try { EditorGitService.requireSize(current.get().text()); }
        catch (IOException error) { message = error.getMessage(); render(); return; }
        if (baseline == null) { loadBaseline(); return; }
        if (!baseline.file().present() && !scope.newFile()) {
            message = "No committed version; waiting for a confirmed new working-tree file"; render(); return;
        }
        loadBlame();
        if (running) { again = true; return; }
        if (result != null && result.document().revision() == current.get().revision() && result.baseline().equals(baseline)) { render(); return; }
        long version = generation.get(); EditorBaseline pinned = baseline; LiveEditorDocument.Snapshot snapshot = current.get();
        running = true; again = false; message = "";
        comparison = workers.submit(() -> {
            Result computed = null; String failure = "";
            try {
                DiffResult diff = engine.compare(new TextSnapshot(pinned.file().blobId(), 0, pinned.source().text()),
                        new TextSnapshot(snapshot.id(), snapshot.revision(), snapshot.text()), DiffOptions.exact(), () -> closed || generation.get() != version);
                computed = new Result(pinned, snapshot, diff, EditorAttribution.unchangedLines(diff));
            } catch (CancellationException ignored) { }
            catch (RuntimeException error) { failure = error.getMessage() == null ? "Cannot compare this file" : error.getMessage(); }
            Result loaded = computed; String error = failure;
            service.dispatch(() -> {
                running = false; comparison = null;
                if (closed) return;
                if (active && version == generation.get()) { result = loaded; message = error; render(); }
                if (again) { again = false; debounce.restart(); }
            });
        });
    }
    private void loadBaseline() {
        if (baselineRequest != null) return;
        message = "Loading committed version…"; render(); Scope requested = scope;
        var acquisition = service.baseline(requested.repository(), requested.head(), requested.historicalPath()); baselineRequest = acquisition;
        acquisition.result().whenComplete((value, error) -> service.dispatch(() -> {
            if (closed || !active || baselineRequest != acquisition || !requested.equals(scope)) return;
            if (error != null) { message = message(error); render(); return; }
            baseline = value; message = ""; refresh();
        }));
    }
    private void loadBlame() {
        if (!annotated || baseline == null || !baseline.file().present() || blameRequest != null || blameLoaded || !blameError.isEmpty()) return;
        EditorBaseline pinned = baseline; var acquisition = service.blame(pinned); blameRequest = acquisition;
        acquisition.result().whenComplete((value, error) -> service.dispatch(() -> {
            if (closed || !active || !annotated || blameRequest != acquisition || !pinned.equals(baseline)) return;
            if (error != null) blameError = message(error); else { blame = value; blameLoaded = true; }
            render();
        }));
    }
    private void render() {
        if (closed) return;
        List<GutterPresentation.Marker> markers = new ArrayList<>(); List<GutterPresentation.Annotation> annotations = new ArrayList<>();
        boolean showAnnotations = annotated && baseline != null && baseline.file().present() && scope != null && scope.fresh();
        if (result != null) {
            for (int i = 0; i < result.diff().hunks().size(); i++) {
                DiffHunk hunk = result.diff().hunks().get(i);
                String kind = hunk.left().empty() ? "Added" : hunk.right().empty() ? "Deleted" : "Modified";
                markers.add(new GutterPresentation.Marker(i, hunk.right().startOffset(), hunk.right().endOffset(),
                        hunk.left().empty() ? GutterColors.ADDED : hunk.right().empty() ? GutterColors.DELETED : GutterColors.MODIFIED, kind + " · click to inspect · F7 / Shift+F7"));
            }
        }
        Map<Integer, GitBlameLine> mapped = new HashMap<>();
        if (showAnnotations && result != null && blameLoaded) {
            Map<String, BlameLabels.Label> formatted = new HashMap<>();
            for (int line = 0; line < result.attribution().length; line++) {
                int previous = result.attribution()[line];
                if (previous < 0 || previous >= blame.size()) annotations.add(new GutterPresentation.Annotation(line, "", "", false, 0));
                else {
                    GitBlameLine original = blame.get(previous); mapped.put(line, original);
                    BlameLabels.Label label = formatted.computeIfAbsent(original.commitId(), ignored -> labels.format(original));
                    annotations.add(new GutterPresentation.Annotation(line, label.shortText(), label.description(), true, original.commitId().hashCode()));
                }
            }
        }
        displayedBlame = Map.copyOf(mapped);
        String description = scope == null ? (status.available() || status.message().isEmpty() ? "Git inspection is unavailable for this document" : status.message()) : message;
        if (result != null && result.diff().hunks().isEmpty() && description.isEmpty()) description = "No source changes relative to HEAD";
        if (showAnnotations && !blameLoaded) description = blameError.isEmpty() ? "Loading Git blame…" : "Blame unavailable: " + blameError + " · right-click to retry";
        if (showAnnotations && blameLoaded && blame.isEmpty()) description = "No committed lines";
        if (result != null && baseline.file().present() && (baseline.source().byteOrderMark() != result.document().bom() || !baseline.source().lineEnding().equals(result.document().lineEnding())))
            description = "File format differs from HEAD (BOM/line endings); source markers describe normalized text";
        editor.setGutterPresentation(new GutterPresentation(markers, annotations, showAnnotations, description), this);
    }
    public void annotate(boolean value) {
        if (closed) return;
        annotated = value; blameError = "";
        if (!value) { releaseBlame(); blame = List.of(); blameLoaded = false; midnight.stop(); }
        else { loadBlame(); scheduleMidnight(); }
        render();
    }
    private void scheduleMidnight() {
        if (!annotated || !active || closed) { midnight.stop(); return; }
        Instant now = clock.instant(), next = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        midnight.setInitialDelay((int)Math.max(1000, Duration.between(now, next).toMillis())); midnight.restart();
    }
    @Override public JPopupMenu menu(int line) {
        JPopupMenu menu = new JPopupMenu(); JCheckBoxMenuItem toggle = new JCheckBoxMenuItem("Annotate with Git Blame", annotated);
        toggle.setName("git.editor.annotate"); toggle.setEnabled(annotated || baseline != null && baseline.file().present());
        toggle.setToolTipText(toggle.isEnabled() ? "Show committed date and author beside source lines" : "Available for committed regular text files after loading HEAD");
        toggle.addActionListener(event -> annotate(toggle.isSelected())); menu.add(toggle);
        GitBlameLine selected = displayedBlame.get(line);
        if (selected != null && scope != null) {
            CommitNavigation target = new CommitNavigation(scope.repository(), selected.commitId(), selected.originalPath());
            item(menu, "Show Commit in Git History", () -> { if (!closed && scope != null && scope.repository().equals(target.repository())) navigate.accept(target); }, true);
            item(menu, "Copy Commit ID", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected.commitId()), null), true);
        }
        if (!blameError.isEmpty() || !message.isEmpty()) item(menu, "Retry Git Inspection", () -> { releaseBaseline(); baseline = null; blame = List.of(); blameLoaded = false; blameError = ""; invalidate(); refresh(); }, true);
        menu.addSeparator(); item(menu, "Previous Change · Shift+F7", () -> move(-1), result != null && !result.diff().hunks().isEmpty());
        item(menu, "Next Change · F7", () -> move(1), result != null && !result.diff().hunks().isEmpty());
        item(menu, "Show Change · Ctrl+Shift+F7", this::showCurrent, result != null && !result.diff().hunks().isEmpty()); return menu;
    }
    private void item(JPopupMenu menu, String label, Runnable action, boolean enabled) { JMenuItem item = new JMenuItem(label); item.setEnabled(enabled); item.addActionListener(event -> action.run()); menu.add(item); }
    @Override public void annotation(int line) { GitBlameLine value = displayedBlame.get(line); if (value != null && scope != null) navigate.accept(new CommitNavigation(scope.repository(), value.commitId(), value.originalPath())); }
    private boolean current(Result expected) {
        Optional<LiveEditorDocument.Snapshot> snapshot = document.snapshot();
        return !closed && active && result == expected && baseline == expected.baseline() && scope != null && scope.fresh()
                && snapshot.isPresent() && snapshot.get().id().equals(expected.document().id()) && Objects.equals(snapshot.get().path(), expected.document().path())
                && snapshot.get().revision() == expected.document().revision();
    }
    private boolean canRevert(Result expected) { return current(expected) && document.snapshot().orElseThrow().canRevert() && editor.editable() && !scope.unmerged(); }
    @Override public void marker(int id) {
        if (result == null || id < 0 || id >= result.diff().hunks().size() || !current(result)) return;
        closePopup(); Result pinned = result; DiffHunk hunk = pinned.diff().hunks().get(id);
        popup = new ChangePopup(editor, pinned.diff(), id, baseline.commitId().isEmpty() ? "empty tree" : baseline.commitId().substring(0, 10), theme, canRevert(pinned),
                () -> revert(pinned, id), () -> fullDiff(pinned), () -> moveAndPreview(id, -1), () -> moveAndPreview(id, 1), report);
        popup.show(editor, hunk.right());
    }
    private void moveAndPreview(int index, int direction) { if (result == null || result.diff().hunks().isEmpty()) return; int next = Math.floorMod(index + direction, result.diff().hunks().size()); editor.caret(result.diff().hunks().get(next).right().startOffset()); marker(next); }
    private void move(int direction) {
        if (result == null || result.diff().hunks().isEmpty()) return;
        int caret = editor.textComponent().getCaretPosition(); List<DiffHunk> hunks = result.diff().hunks(); int selected = direction > 0 ? 0 : hunks.size() - 1;
        if (direction > 0) { for (int i = 0; i < hunks.size(); i++) if (hunks.get(i).right().startOffset() > caret) { selected = i; break; } }
        else { for (int i = hunks.size() - 1; i >= 0; i--) if (hunks.get(i).right().startOffset() < caret) { selected = i; break; } }
        closePopup(); editor.caret(hunks.get(selected).right().startOffset()); editor.focusText();
    }
    private void showCurrent() {
        if (result == null || result.diff().hunks().isEmpty()) return;
        int caret = editor.textComponent().getCaretPosition(), index = 0;
        for (int i = 0; i < result.diff().hunks().size(); i++) { if (result.diff().hunks().get(i).right().startOffset() > caret) break; index = i; }
        marker(index);
    }
    private void fullDiff(Result pinned) {
        if (!current(pinned)) return; closePopup();
        fullDiffs.forEach(Window::dispose); fullDiffs.clear();
        String historical = pinned.baseline().file().path();
        var beforeSyntax = dev.editor.platform.text.domain.syntax.SyntaxSource.file(historical.substring(historical.lastIndexOf('/') + 1));
        var afterSyntax = pinned.document().path() == null ? dev.editor.platform.text.domain.syntax.SyntaxSource.draft()
                : dev.editor.platform.text.domain.syntax.SyntaxSource.file(pinned.document().path().getFileName().toString());
        SnapshotDiffDialog dialog = new SnapshotDiffDialog(SwingUtilities.getWindowAncestor(editor), "Git editor comparison · captured snapshots",
                new SnapshotDiffDialog.Source("HEAD", pinned.baseline().commitId(), pinned.diff().left(), beforeSyntax), new SnapshotDiffDialog.Source("Editor snapshot", "Revision " + pinned.document().revision(), pinned.diff().right(), afterSyntax),
                editor.settings(), theme, engine, workers, report, editor.syntaxSupport());
        fullDiffs.removeIf(view -> !view.isDisplayable()); fullDiffs.add(dialog); dialog.setVisible(true);
    }
    private void revert(Result pinned, int index) {
        if (!canRevert(pinned) || validation != null) return;
        DiffHunk hunk = pinned.diff().hunks().get(index); String before = pinned.diff().leftLines().text(hunk.left()), expected = pinned.diff().rightLines().text(hunk.right());
        if (before.isEmpty() && !expected.isEmpty() && hunk.right().length() == pinned.document().text().length()) {
            if (JOptionPane.showConfirmDialog(editor, "Clear all current text? The file and Git index will remain. Undo restores the text.", "Revert whole-file addition", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        }
        closePopup(); long version = generation.get(); String relative = scope.path();
        validation = workers.submit(() -> {
            boolean valid = false; String failure = "Repository changed; refresh before reverting";
            try { valid = service.validate(pinned.baseline(), relative); } catch (IOException error) { failure = error.getMessage(); }
            boolean approved = valid; String error = failure;
            service.dispatch(() -> {
                validation = null;
                if (closed || version != generation.get() || !canRevert(pinned)) return;
                if (!approved || !document.replace(pinned.document().revision(), hunk.right().startOffset(), expected, before)) {
                    report.accept(approved ? "Document changed; the old revert action was discarded" : error); invalidate(); debounce.restart();
                }
            });
        });
    }
    private String message(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error.getMessage() == null ? "Git inspection unavailable" : error.getMessage(); }
    private void releaseBlame() { if (blameRequest != null) { blameRequest.close(); blameRequest = null; } }
    private void releaseBaseline() { if (baselineRequest != null) { baselineRequest.close(); baselineRequest = null; } releaseBlame(); }
    private void closePopup() { if (popup != null) { popup.close(); popup = null; } }
    @Override public void active(boolean value) {
        if (closed || active == value) return; active = value;
        if (value) { debounce.restart(); scheduleMidnight(); }
        else { debounce.stop(); midnight.stop(); generation.incrementAndGet(); closePopup(); releaseBaseline(); }
    }
    @Override public void applyTheme(EditorTheme palette) {
        theme = palette; closePopup(); fullDiffs.removeIf(view -> !view.isDisplayable());
        fullDiffs.forEach(view -> view.applySettings(editor.settings(), palette)); render();
    }
    @Override public void close() {
        if (closed) return; closed = true; generation.incrementAndGet(); debounce.stop(); midnight.stop(); closePopup(); releaseBaseline();
        if (comparison != null) comparison.cancel(true); if (validation != null) validation.cancel(true);
        fullDiffs.forEach(Window::dispose); fullDiffs.clear();
        try { documentListener.close(); statusListener.close(); } catch (Exception error) { report.accept(message(error)); }
        for (String name : previousActions.keySet()) { if (previousActions.get(name) == null) editor.textComponent().getActionMap().remove(name); else editor.textComponent().getActionMap().put(name, previousActions.get(name)); }
    }
}

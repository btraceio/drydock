package app.drydock.intellij.review;

import app.drydock.review.Severity;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.EventQueue;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.Icon;

/**
 * Paints a {@link ReviewEditorMarkModel} snapshot into this project's open
 * source editors (AC5's editor half): one grouped gutter mark per model
 * {@link ReviewEditorMarkModel.Group} — the worst severity in force as its
 * icon, the finding count as a badge, the group's tooltip — whose click
 * navigates through {@link ReviewTabOpener#openFinding} to the group's
 * first finding in store order. {@link #applyModel} removes every previous
 * highlighter before painting the snapshot, so a replace is idempotent and
 * an empty snapshot clears the editors.
 *
 * <p><strong>Who gets painted.</strong> Only editors of this project whose
 * document resolves to a file inside the checkout — never other projects,
 * project-less editors, documents without a file, directories, or paths
 * outside the checkout — and only groups whose zero-based line the document
 * still contains. Path mapping is lexical ({@code Path#startsWith} against
 * the absolute, normalized checkout root): no {@code toRealPath}, no file
 * probes, so nothing here blocks the EDT.
 *
 * <p><strong>Threading — one contract.</strong> Construction and listener
 * registration happen on the EDT — the constructor asserts it
 * ({@code requireEdt()}), so the platform entry ({@link #platform}) must be
 * created there and {@link Disposer#register} plus
 * {@code EditorFactory.addEditorFactoryListener} never run off it. Every
 * later mark and listener-callback entry runs on the EDT; one that arrives
 * elsewhere hops there through the injected {@link ReviewDiffInlays.EdtQueue}.
 * The source-editor
 * {@code EditorFactoryListener} is registered through the injected
 * {@link SourceEditors} seam with the project-owned parent —
 * {@code EditorFactory.addEditorFactoryListener(listener, parent)} on 263,
 * never the root — and the same parent's disposal tears this layer down
 * through its own child {@code Disposable}, releasing every highlighter
 * even if the owner never calls {@link #dispose} explicitly. A
 * {@code RangeHighlighter} is not a {@code Disposable} on 263 (cairn
 * {@code intellij-findings-triage/2026-10-24-platform-inlay-gutter-api-263}):
 * each is removed through the markup model, each release isolated and
 * logged so one failure never strands the rest.
 */
public final class ReviewEditorHighlighters {

    /**
     * The open-editor seam (fakes pin it in tests): the currently existing
     * editors of every project, and registration of the created/released
     * listener with the project-owned parent.
     */
    public interface SourceEditors {
        List<SourceEditor> editors();

        /** Registers the listener; the returned handle unregisters it. */
        ReviewDiffInlays.Owned register(Listener listener);
    }

    /** The editor life the layer reacts to; the platform delivers both on its own thread. */
    public interface Listener {
        void editorCreated(SourceEditor editor);

        void editorReleased(SourceEditor editor);
    }

    /**
     * One open editor the layer may paint (fakes pin it in tests): its
     * project, the file behind its document ({@code null} when the
     * document has none), its line count, and the grouped gutter mark.
     */
    public interface SourceEditor {
        Project project();

        String filePath();

        boolean isDirectory();

        int lineCount();

        ReviewDiffInlays.Owned addGroupMark(int line, GroupMark mark);
    }

    /** One gutter mark's UI-agnostic content: severity icon, count, tooltip, and the click. */
    public record GroupMark(Severity severity, int count, String tooltip, Runnable click) {
        public GroupMark {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(click, "click");
            if (count < 1) {
                throw new IllegalArgumentException("a group holds at least one finding: " + count);
            }
        }
    }

    /** The navigation a gutter click performs; production is {@link ReviewTabOpener#openFinding}. */
    public interface Navigation {
        void openFinding(Project project, String filePath, String findingId);
    }

    private static final Logger LOG = System.getLogger(ReviewEditorHighlighters.class.getName());

    private final Project project;
    private final Path checkout;
    private final ReviewDiffInlays.EdtQueue edt;
    private final SourceEditors sources;
    private final Navigation navigation;
    /** The handles this layer owns, by editor; a released editor's markup dies with it, but the handles are released and forgotten anyway. */
    private final Map<SourceEditor, List<ReviewDiffInlays.Owned>> marksByEditor = new LinkedHashMap<>();
    private ReviewEditorMarkModel model;
    private ReviewDiffInlays.Owned listenerHandle;
    private boolean disposed;

    /**
     * The production layer: platform editors and listener registration over
     * the project-owned {@code parent}, the application's EDT queue, and
     * {@link ReviewTabOpener#openFinding} as the click's navigation. EDT
     * only — the constructor asserts it, so create this on the EDT (the
     * wiring owner does) or the construction throws.
     */
    public static ReviewEditorHighlighters platform(Project project, Path checkout, Disposable parent) {
        return new ReviewEditorHighlighters(project, checkout, parent,
                ReviewDiffInlays.platformEdt(), platformSources(parent), ReviewTabOpener::openFinding);
    }

    ReviewEditorHighlighters(Project project, Path checkout, Disposable parent,
                             ReviewDiffInlays.EdtQueue edt, SourceEditors sources, Navigation navigation) {
        // Registration (two Disposer.register calls, the factory listener)
        // belongs to the EDT like every mark operation; rejecting an
        // off-EDT construction leaves no half-registered state behind.
        requireEdt();
        this.project = Objects.requireNonNull(project, "project");
        // Lexical mapping against an absolute, normalized root:
        // toAbsolutePath/normalize are pure algebra, never the blocking
        // toRealPath or any file probe.
        this.checkout = Objects.requireNonNull(checkout, "checkout").toAbsolutePath().normalize();
        this.edt = Objects.requireNonNull(edt, "edt");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        Disposable owned = Disposer.newDisposable();
        Disposer.register(Objects.requireNonNull(parent, "parent"), owned);
        Disposer.register(owned, this::dispose);
        this.listenerHandle = sources.register(sourceListener);
    }

    private final Listener sourceListener = new Listener() {
        @Override public void editorCreated(SourceEditor editor) { onEdt(() -> decorateLater(editor)); }

        @Override public void editorReleased(SourceEditor editor) { onEdt(() -> releaseEditor(editor)); }
    };

    /**
     * Applies a snapshot to every existing project editor, removing every
     * previous highlighter first; editors created later get it from the
     * listener. EDT (hops).
     */
    public void applyModel(ReviewEditorMarkModel snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        onEdt(() -> {
            if (disposed) {
                return;
            }
            model = snapshot;
            releaseAllMarks();
            for (SourceEditor editor : sources.editors()) {
                decorateSafely(editor);
            }
        });
    }

    /** Releases every highlighter and the listener; idempotent. EDT (hops). */
    public void dispose() {
        onEdt(() -> {
            if (disposed) {
                return;
            }
            disposed = true;
            releaseAllMarks();
            model = null;
            ReviewDiffInlays.Owned handle = listenerHandle;
            listenerHandle = null;
            if (handle != null) {
                try {
                    handle.release();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Unregistering the source-editor listener failed", e);
                }
            }
        });
    }

    private void onEdt(Runnable operation) {
        if (EventQueue.isDispatchThread()) {
            operation.run();
            return;
        }
        edt.invokeLater(operation);
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewEditorHighlighters is constructed and registered on the EDT");
        }
    }

    // ---- painting and bookkeeping -------------------------------------------

    private void decorateLater(SourceEditor editor) {
        if (disposed || model == null) {
            return; // before the first snapshot there is nothing to paint
        }
        decorateSafely(editor);
    }

    /** One editor's failure never starves its siblings: logged, then the enumeration continues. */
    private void decorateSafely(SourceEditor editor) {
        try {
            decorate(editor);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Decorating a review source editor failed; the other editors continue", e);
        }
    }

    private void decorate(SourceEditor editor) {
        if (editor.project() != project) {
            return; // another project's editor, or a project-less one
        }
        // A queued create-callback can race a newer snapshot's full apply:
        // this editor's previous generation goes before the repaint, so no
        // editor ever carries two generations of highlighters.
        releaseEditor(editor);
        String relative = relativePathOf(editor);
        if (relative == null) {
            return; // no file, a directory, or outside this checkout
        }
        ReviewEditorMarkModel.FileMarks file = model.forPath(relative).orElse(null);
        if (file == null) {
            return; // this snapshot marks other files
        }
        for (ReviewEditorMarkModel.Group group : file.byLine().values()) {
            if (group.line() >= editor.lineCount()) {
                continue; // the document no longer contains that line; never a guess
            }
            try {
                ReviewDiffInlays.Owned handle = editor.addGroupMark(group.line(), new GroupMark(
                        group.severity(), group.count(), group.tooltip(), () -> navigation.openFinding(project,
                        relative, group.navigationFindingId())));
                // Only a real handle is bookkept: a null one has nothing to
                // release, and it must not reach the release list.
                if (handle != null) {
                    marksByEditor.computeIfAbsent(editor, ignored -> new ArrayList<>()).add(handle);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Adding a review gutter mark failed; the group is skipped and its siblings continue", e);
            }
        }
    }

    /**
     * The editor's file as a checkout-relative diff path, or {@code null}
     * when it is not a file this checkout can vouch for. Lexical only.
     */
    private String relativePathOf(SourceEditor editor) {
        String filePath = editor.filePath();
        if (filePath == null || editor.isDirectory()) {
            return null;
        }
        Path file = Path.of(filePath);
        if (!file.startsWith(checkout)) {
            return null;
        }
        Path relative = checkout.relativize(file);
        return relative.toString().isEmpty() ? null : relative.toString().replace('\\', '/');
    }

    private void releaseEditor(SourceEditor editor) {
        List<ReviewDiffInlays.Owned> marks = marksByEditor.remove(editor);
        if (marks != null) {
            releaseEach(marks);
        }
    }

    private void releaseAllMarks() {
        for (List<ReviewDiffInlays.Owned> marks : marksByEditor.values()) {
            releaseEach(marks);
        }
        marksByEditor.clear();
    }

    /** Releases every handle even when an earlier one throws; the list is cleared before any release runs. */
    private static void releaseEach(List<ReviewDiffInlays.Owned> marks) {
        List<ReviewDiffInlays.Owned> pending = List.copyOf(marks);
        marks.clear();
        for (ReviewDiffInlays.Owned handle : pending) {
            try {
                handle.release();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Releasing a review source-editor gutter mark failed", e);
            }
        }
    }

    // ---- production adapters --------------------------------------------------

    /** The production seam: {@code EditorFactory} editors and listener registration with the project-owned parent. */
    static SourceEditors platformSources(Disposable parent) {
        return new SourceEditors() {
            @Override public List<SourceEditor> editors() {
                List<SourceEditor> editors = new ArrayList<>();
                for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                    editors.add(new PlatformSourceEditor(editor));
                }
                return editors;
            }

            @SuppressWarnings("removal") // no replacement exists: explicit unregister for an early close, the registration parent already covers project disposal
            @Override public ReviewDiffInlays.Owned register(Listener listener) {
                EditorFactoryListener platform = new EditorFactoryListener() {
                    @Override public void editorCreated(EditorFactoryEvent event) {
                        listener.editorCreated(new PlatformSourceEditor(event.getEditor()));
                    }

                    @Override public void editorReleased(EditorFactoryEvent event) {
                        listener.editorReleased(new PlatformSourceEditor(event.getEditor()));
                    }
                };
                // With the parent, never the root (263): the platform removes
                // the listener when the project-owned parent is disposed.
                EditorFactory.getInstance().addEditorFactoryListener(platform, parent);
                return () -> EditorFactory.getInstance().removeEditorFactoryListener(platform);
            }
        };
    }

    /**
     * One open platform editor. A record on purpose: its structural
     * equality on the wrapped {@code Editor} keeps the layer's bookkeeping
     * stable although every event and enumeration wraps anew.
     */
    record PlatformSourceEditor(Editor editor) implements SourceEditor {
        @Override public Project project() { return editor.getProject(); }

        @Override public String filePath() {
            VirtualFile file = file();
            return file == null ? null : file.getPath();
        }

        @Override public boolean isDirectory() {
            VirtualFile file = file();
            return file != null && file.isDirectory();
        }

        @Override public int lineCount() { return editor.getDocument().getLineCount(); }

        @Override public ReviewDiffInlays.Owned addGroupMark(int line, GroupMark mark) {
            RangeHighlighter highlighter = editor.getMarkupModel()
                    .addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null);
            try {
                highlighter.setGutterIconRenderer(new GroupedGutterIcon(mark));
            } catch (RuntimeException | Error failure) {
                // The highlighter exists but would become untracked once this
                // rethrows: remove it here or the editor keeps a bare line
                // highlighter nobody owns.
                try {
                    if (!editor.isDisposed() && highlighter.isValid()) {
                        editor.getMarkupModel().removeHighlighter(highlighter);
                    }
                } catch (RuntimeException cleanupFailure) {
                    LOG.log(Level.WARNING, "Removing a half-installed review gutter mark failed", cleanupFailure);
                }
                throw failure;
            }
            // A highlighter is not Disposable on 263: removal is this
            // explicit call, guarded because a released editor's markup is
            // already gone with the editor.
            return () -> {
                if (!editor.isDisposed() && highlighter.isValid()) {
                    editor.getMarkupModel().removeHighlighter(highlighter);
                }
            };
        }

        private VirtualFile file() {
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            return file != null && file.isValid() ? file : null;
        }
    }

    /** The grouped gutter mark: the severity icon with a count badge, the group tooltip, and the click's navigation. */
    private static final class GroupedGutterIcon extends GutterIconRenderer {
        private final GroupMark mark;

        GroupedGutterIcon(GroupMark mark) {
            this.mark = Objects.requireNonNull(mark, "mark");
        }

        @Override public Icon getIcon() { return icon(mark.severity(), mark.count()); }

        @Override public String getTooltipText() { return mark.tooltip(); }

        @Override public AnAction getClickAction() {
            return new AnAction() {
                @Override public void actionPerformed(@NotNull AnActionEvent event) {
                    mark.click().run();
                }
            };
        }

        @Override public boolean equals(Object other) {
            return other instanceof GroupedGutterIcon icon && mark.equals(icon.mark);
        }

        @Override public int hashCode() { return mark.hashCode(); }
    }

    private static Icon icon(Severity severity, int count) {
        Icon base = SeverityPresentation.icon(severity);
        return count > 1 ? new CountBadgeIcon(base, count) : base;
    }

    /**
     * The severity icon with the finding count as a badge at its corner.
     * Package-private for its paint-state test.
     */
    record CountBadgeIcon(Icon base, int count) implements Icon {
        CountBadgeIcon {
            Objects.requireNonNull(base, "base");
            if (count < 2) {
                throw new IllegalArgumentException("a badge marks a group of several findings: " + count);
            }
        }

        @Override public int getIconWidth() { return base.getIconWidth(); }

        @Override public int getIconHeight() { return base.getIconHeight(); }

        @Override public void paintIcon(Component context, Graphics graphics, int x, int y) {
            // Paint through a disposable copy: the badge's bold font and its
            // colors never leak into the caller's Graphics state (font,
            // color, transform, clip all stay as the gutter left them).
            Graphics badge = graphics.create();
            try {
                base.paintIcon(context, badge, x, y);
                String text = String.valueOf(count);
                badge.setFont(badge.getFont().deriveFont(Font.BOLD, 9f));
                int width = Math.max(badge.getFontMetrics().stringWidth(text) + 4, 11);
                int badgeX = x + getIconWidth() - width;
                int badgeY = y + getIconHeight() - 10;
                badge.setColor(new Color(0xB3, 0x1B, 0x1B));
                badge.fillOval(badgeX, badgeY, width, 10);
                badge.setColor(Color.WHITE);
                badge.drawString(text, badgeX + (width - badge.getFontMetrics().stringWidth(text)) / 2,
                        badgeY + 8);
            } finally {
                badge.dispose();
            }
        }
    }
}

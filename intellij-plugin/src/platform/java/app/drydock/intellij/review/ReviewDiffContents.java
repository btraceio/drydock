package app.drydock.intellij.review;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;

import java.awt.EventQueue;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The two contents of a review diff (spec §2): the right side is the real
 * project file whenever its document holds exactly the reviewed text, so the
 * IDE's navigation (Go to Declaration, Find Usages, Quick Documentation, Call
 * Hierarchy) works on it; otherwise both sides are typed text contents.
 *
 * <p>The right side is file-backed only when the document text <em>equals</em>
 * the reviewed right text. {@link ReviewContents#matchesRows} is not enough
 * here: the git fragments are character offsets into the reviewed text, so a
 * difference outside the diff rows would still misplace them.</p>
 *
 * <p>A file-backed side is the live project document. An edit while the diff
 * is open makes the viewer rediff and call the fragment computer again with
 * the new text; {@link #fragments} then refuses the precomputed git fragments,
 * returns the platform's own comparison, and marks the request
 * {@link Staleness stale}.</p>
 */
public final class ReviewDiffContents {

    /**
     * The request's staleness: set once the right side no longer holds the
     * reviewed text. Readers use this one static key instance.
     */
    public static final Key<Staleness> STALENESS_KEY = Key.create("drydock.review.diffStaleness");

    private ReviewDiffContents() {
    }

    /** How the right side of one request is backed. */
    public enum RightSide {
        /** The project file's own document: PSI, navigation. */
        FILE,
        /** A typed text content holding the reviewed text: highlighting, positions not the file's. */
        SNAPSHOT
    }

    /** The two contents and how the right one is backed. */
    public record Sides(DiffContent left, DiffContent right, RightSide rightSide) {
        public Sides {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(rightSide, "rightSide");
        }
    }

    /**
     * The pure decision: a found file whose document text equals the
     * reviewed right text is {@link RightSide#FILE}; anything else is a
     * {@link RightSide#SNAPSHOT}.
     */
    static RightSide choose(CharSequence documentText, CharSequence reviewedRight) {
        Objects.requireNonNull(reviewedRight, "reviewedRight");
        if (documentText == null) {
            return RightSide.SNAPSHOT;
        }
        return CharSequence.compare(documentText, reviewedRight) == 0 ? RightSide.FILE : RightSide.SNAPSHOT;
    }

    /**
     * Loads the worktree file's document off the EDT, so the EDT build that
     * follows finds it in memory instead of reading the file. Blocking (VFS
     * refresh, file read): never call on the EDT.
     */
    static void warm(Optional<Path> worktreeFile) {
        if (worktreeFile.isEmpty()) {
            return;
        }
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("warm reads the file: never on the EDT");
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktreeFile.get());
        if (file != null) {
            ReadAction.run(() -> FileDocumentManager.getInstance().getDocument(file));
        }
    }

    /**
     * The request's two contents (EDT, read access). The left side is always
     * a typed revision content with the file's path, so it is highlighted in
     * the file's language. The right side follows {@link #choose}.
     */
    static Sides create(Project project, ReviewDiffRequests.RequestContext context) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(context, "context");
        DiffContentFactory factory = DiffContentFactory.getInstance();
        String left = context.plan().leftText().toString();
        String right = context.plan().rightText().toString();
        VirtualFile file = context.worktreeFile()
                .map(path -> LocalFileSystem.getInstance().findFileByNioFile(path))
                .orElse(null);
        Document document = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        RightSide side = choose(document == null ? null : document.getImmutableCharSequence(), right);
        FilePath filePath = file != null
                ? VcsUtil.getFilePath(file)
                : VcsUtil.getFilePath(context.worktreeFile().map(Path::toString).orElse(context.path()), false);
        DiffContent leftContent = factory.create(project, left, filePath);
        DiffContent rightContent = side == RightSide.FILE
                ? factory.create(project, file)
                : factory.create(project, right, filePath);
        return new Sides(leftContent, rightContent, side);
    }

    /**
     * The fragment computer's guard (pure): the precomputed git fragments
     * when the viewer compares the reviewed texts; otherwise the request is
     * marked stale and the platform's own comparison is returned, so the
     * highlighting stays true to the text on screen.
     */
    static List<LineFragment> fragments(ReviewDiffRequests.Plan plan, CharSequence text1, CharSequence text2,
                                        Staleness staleness, Supplier<List<LineFragment>> platform) {
        if (CharSequence.compare(text1, plan.leftText()) == 0
                && CharSequence.compare(text2, plan.rightText()) == 0) {
            return plan.fragments();
        }
        staleness.markStale();
        return platform.get();
    }

    /** The platform comparison used once the texts no longer match the review. */
    static List<LineFragment> platformComparison(CharSequence text1, CharSequence text2,
                                                 ComparisonPolicy policy, ProgressIndicator indicator) {
        return ComparisonManager.getInstance().compareLines(text1, text2, policy, indicator);
    }

    /**
     * One request's staleness flag. Set from the viewer's rediff thread;
     * listeners run on the thread that marks it, so UI listeners hop to the
     * EDT themselves.
     */
    public static final class Staleness {
        private final AtomicBoolean stale = new AtomicBoolean();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        public boolean isStale() {
            return stale.get();
        }

        /** Marks the request stale; listeners fire once, on the first mark. */
        void markStale() {
            if (stale.compareAndSet(false, true)) {
                for (Runnable listener : listeners) {
                    listener.run();
                }
            }
        }

        /** Adds a listener for the first {@link #markStale}. */
        public void onStale(Runnable listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
        }
    }
}

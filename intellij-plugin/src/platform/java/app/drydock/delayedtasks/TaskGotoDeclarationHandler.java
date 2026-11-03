package app.drydock.delayedtasks;

import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import org.jetbrains.annotations.Nullable;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code [[id]]} / {@code [[project:id]]} editor navigation of the
 * delayed tasks (T21a, P5). A caret inside a bracketed task reference in a
 * TASK document -- a file under the task store root, outside its reserved
 * {@code plans/} and {@code runs/} metadata directories -- navigates to the
 * referenced task's store file, resolved through the LAST snapshot the
 * {@link TaskStoreWatcher} published (T13a): never a synchronous store
 * scan, never a disk read on the resolving path. Before the first snapshot
 * there is simply nothing to offer (no blocking fetch); a missing,
 * ambiguous or broken reference offers no navigation either, with the
 * graph's concrete reason logged (the {@link GotoDeclarationHandler} API has
 * no channel for a refusal reason, so {@code getGotoDeclarationTargets}
 * answers empty; the committed live-editor matrix is T21b).
 *
 * <p>The bracket match is plain character scanning over a BOUNDED window
 * (at most {@link #WINDOW_CHARS} on each side of the caret, read through
 * the document's live {@link CharSequence} -- no text copy, no full-file
 * scan, no Markdown PSI or any other Markdown dependency). The matching
 * rule: the innermost {@code [[} at or before the caret within the window
 * opens the candidate, the first following {@code ]]} closes it, and the
 * caret must lie inside the brackets or on either boundary; malformed or
 * nested brackets therefore never crash -- they simply offer nothing. A
 * closing pair outside the window does not resolve (the window is the
 * bound).</p>
 *
 * <p>Resolution is COMPOSED over the accepted {@link TaskGraph} (P2),
 * never re-implemented: one synthetic probe document carrying the single
 * caret reference is planted into a copy of the cached snapshot (the same
 * seam the accepted prerequisite picker composes its refusals through) and
 * the graph resolves it exactly as the store would -- bare ids globally,
 * qualified ids per project slug, collisions refused, wrong-scope ids
 * named. Navigation goes to the task's FILE ({@code
 * <root>/<repository-id>/<task-id>.md}, the store's own layout); the
 * snapshot carries no per-file offsets, so no line location is offered.
 * The project-slug mapping is read from the plugin service's live
 * in-memory repository catalog (the same catalogs the task service itself
 * slugs through) and mirrored through this file's package-private
 * {@link #slug} until the graph's caller-supplied mapping gets a shared
 * platform seam.</p>
 */
public final class TaskGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final Logger LOG = System.getLogger(TaskGotoDeclarationHandler.class.getName());

    /** The matched-character budget on each side of the caret; the window
     * bounds every scan, whatever the line or file length. */
    static final int WINDOW_CHARS = 512;

    /** Cap of a project display slug, the same length the core caps slugs at. */
    private static final int SLUG_CAP = 60;

    /** The reserved store metadata directories (P4, by exact name): their
     * documents are store metadata, never task documents. */
    private static final Set<String> RESERVED_DIRECTORIES = Set.of("plans", "runs");

    /** One resolution outcome over the cached snapshot: exactly one of
     * target / reason is set. Headless-testable seam for the T21b matrix. */
    record Resolution(TaskDocument target, String reason) {
        static Resolution resolved(TaskDocument target) { return new Resolution(target, null); }

        static Resolution refused(String reason) { return new Resolution(null, reason); }
    }

    /**
     * The caret's task reference, if any: the text between the innermost
     * {@code [[} at or before the caret (within {@code window} chars) and
     * the first following {@code ]]}, provided the caret lies inside the
     * bracketed span (both boundaries included). Plain character scanning
     * over the given sequence only -- no copy, no line or file structure,
     * no crash on malformed or nested brackets.
     */
    static String referenceAt(CharSequence text, int caret, int window) {
        if (text == null || caret < 0) {
            return null;
        }
        int length = text.length();
        if (caret > length) {
            return null;
        }
        int low = Math.max(0, caret - window);
        int high = Math.min(length, caret + window);
        int open = -1;
        for (int at = Math.min(caret, length - 2); at >= low; at--) {
            if (text.charAt(at) == '[' && text.charAt(at + 1) == '[') {
                open = at;
                break;
            }
        }
        if (open < 0) {
            return null;
        }
        for (int at = open + 2; at < high - 1; at++) {
            if (text.charAt(at) == ']' && text.charAt(at + 1) == ']') {
                return caret <= at + 2 ? text.subSequence(open + 2, at).toString() : null;
            }
        }
        return null;
    }

    /**
     * One reference text against the cached snapshot through the accepted
     * {@link TaskGraph} resolution (P2): bare ids resolve globally, qualified
     * ids per the supplied project-slug mapping, an ambiguous or missing id is
     * refused with the graph's own concrete reason. Pure and synchronous over
     * in-memory data only.
     */
    static Resolution resolve(String reference, TaskSnapshot snapshot,
            Function<RepositoryId, String> projectSlugs) {
        String ref = reference == null ? "" : reference;
        if (ref.isBlank()) {
            return Resolution.refused("the reference text is empty");
        }
        TaskDocument probe = new TaskDocument(freshProbeId(snapshot, ref),
                snapshot.repositories().isEmpty() ? RepositoryId.newId() : snapshot.repositories().get(0),
                "The reference being resolved", null, null, null, List.of(ref), null, "", "");
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>(snapshot.byId());
        byId.put(probe.id(), probe);
        List<TaskDocument> documents = new ArrayList<>(snapshot.documents());
        documents.add(probe);
        TaskSnapshot extended = new TaskSnapshot(documents, snapshot.repositories(), byId,
                snapshot.bySessionReference(), snapshot.failures(), snapshot.idCollisions());
        return TaskGraph.of(extended, projectSlugs).diagnosticsOf(probe.id()).map(diagnostics -> {
            if (!diagnostics.resolved().isEmpty()) {
                return Resolution.resolved(diagnostics.resolved().get(0).target());
            }
            return !diagnostics.broken().isEmpty() ? Resolution.refused(diagnostics.broken().get(0).reason())
                    : Resolution.refused("no task with id '" + ref + "'");
        }).orElseGet(() -> Resolution.refused("no task with id '" + ref + "'"));
    }

    /** The store file of one resolved task: the store's own
     * {@code <root>/<repository-id>/<task-id>.md} layout. */
    static Path fileOf(TaskDocument target, Path root) {
        return root.resolve(target.repository().toString()).resolve(target.id().text() + ".md");
    }

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement psiElement, int offset,
            Editor editor) {
        try {
            return targets(psiElement, offset, editor);
        } catch (RuntimeException e) {
            // Navigation is never worth a crash: any platform failure just
            // declines, loudly enough to be diagnosed.
            LOG.log(Level.WARNING, "Drydock task navigation failed; no target is offered", e);
            return null;
        }
    }

    private PsiElement @Nullable [] targets(@Nullable PsiElement psiElement, int offset, Editor editor) {
        if (editor == null) {
            return null;
        }
        Project project = psiElement != null ? psiElement.getProject() : editor.getProject();
        if (project == null) {
            return null;
        }
        DrydockPluginService service = DrydockPluginService.getInstance();
        if (service == null) {
            return null;
        }
        Path root = service.taskService().root();
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        Path path = file == null ? null : file.toNioPath();
        if (path == null || !isTaskDocument(path.toAbsolutePath().normalize(), root)) {
            return null; // restricted: only store-root task documents navigate
        }
        TaskStoreWatcher.WatchedSnapshot watched = service.taskService().watchedSnapshot();
        if (watched == null) {
            return null; // no snapshot published yet: nothing to offer, no fetch
        }
        int caret = offset >= 0 && offset <= document.getTextLength()
                ? offset : editor.getCaretModel().getOffset();
        String reference = referenceAt(document.getCharsSequence(), caret, WINDOW_CHARS);
        if (reference == null) {
            return null;
        }
        Resolution resolution = resolve(reference, watched.snapshot(), slugsOf(service));
        if (resolution.target() == null) {
            LOG.log(Level.DEBUG, "No task target for reference " + reference + ": " + resolution.reason());
            return null; // missing, ambiguous or broken: no navigation offered
        }
        VirtualFile targetFile = VirtualFileManager.getInstance()
                .findFileByNioPath(fileOf(resolution.target(), root));
        if (targetFile == null) {
            return null; // not in the VFS (the watcher refreshes it): no navigation
        }
        PsiFile target = ReadAction.computeBlocking(
                () -> PsiManager.getInstance(project).findFile(targetFile));
        return target == null ? null : new PsiElement[] {target};
    }

    /** Whether a caret document is a task document: under the store root,
     * but not inside the reserved metadata directories (P4) and not the
     * root itself. */
    static boolean isTaskDocument(Path document, Path root) {
        if (!document.startsWith(root) || document.equals(root)) {
            return false;
        }
        Path relative = root.relativize(document);
        return !RESERVED_DIRECTORIES.contains(relative.getName(0).toString());
    }

    /** The live repository catalog as the graph's project-slug mapping: the
     * same in-memory state the task service itself slugs through, read once
     * here, no I/O. */
    private static Function<RepositoryId, String> slugsOf(DrydockPluginService service) {
        ApplicationStateStore stateStore = service.stateStore();
        Map<RepositoryId, String> slugs = new HashMap<>();
        if (stateStore != null) {
            for (Repository repository : stateStore.state().repositories()) {
                slugs.put(repository.id(), slug(repository.displayName()));
            }
        }
        return slugs::get;
    }

    /** The display-name slug (P2): the same projection the task service
     * applies to this catalog's names -- lowercase letters, digits and inner
     * hyphens, capped. */
    static String slug(String name) {
        StringBuilder out = new StringBuilder(SLUG_CAP + 1);
        for (int i = 0; i < name.length() && out.length() < SLUG_CAP; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '-') {
            out.setLength(length - 1);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** An id nothing can name: not stored, not collided, and never the
     * reference itself (the same probe discipline the accepted picker
     * uses). */
    private static TaskId freshProbeId(TaskSnapshot snapshot, String reference) {
        Set<TaskId> collided = new HashSet<>();
        for (TaskSnapshot.IdCollision collision : snapshot.idCollisions()) {
            collided.add(collision.id());
        }
        for (int attempt = 1; ; attempt++) {
            TaskId candidate = TaskId.of("goto-probe" + (attempt == 1 ? "" : "-" + attempt));
            boolean self = reference.equals(candidate.text()) || reference.endsWith(":" + candidate.text());
            if (!snapshot.byId().containsKey(candidate) && !collided.contains(candidate) && !self) {
                return candidate;
            }
        }
    }
}

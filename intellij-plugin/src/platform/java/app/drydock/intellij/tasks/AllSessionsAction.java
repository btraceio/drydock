package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * "All Sessions…": a picker over the COMPLETE session catalog, reachable from the session host's
 * toolbar, the Drydock tool window's title and Find Action. The inbox shows a bounded, filtered,
 * grouped slice (four rows, collapsed Idle, project chip); this lists every session with no query,
 * so row five and a collapsed Idle session are one keystroke away.
 *
 * <p>It reads the catalog snapshot, never {@code InboxList.entries()}, and keeps its own local
 * search (the popup's filter), so the inbox's query, project chip and selection are neither read
 * nor cleared. Moving the highlight selects nothing: only Enter or a click commits, through
 * {@link SessionActivation} keyed by the stable id with the intent the row showed. Rows that share
 * a name carry their full id, so two identical titles are never ambiguous. No default shortcut.</p>
 */
public final class AllSessionsAction extends DumbAwareAction {

    /** One picker row; {@code text} is what the local search matches and the list shows. */
    record Row(ManagedSessionId id, String title, String status, String intent, String worktree,
               boolean disambiguate, SessionActivation.Intent captured) {

        String text() {
            return title + (disambiguate ? " [" + id + "]" : "") + "  ·  " + status.toLowerCase(Locale.ROOT)
                    + "  ·  " + intent + "  ·  " + worktree;
        }

        /** What the local search matches: the shown text plus the id, so a pasted id always finds it. */
        String searchText() {
            return text() + " " + id;
        }
    }

    /** Test seams; production values are the defaults. */
    static volatile Supplier<List<ManagedAgentSession>> catalog = () -> DrydockPluginService.getInstance()
            .stateStore().state().sessions();
    static volatile Supplier<SessionActivation> activation = SessionActivation::production;
    static volatile BiConsumer<Project, List<Row>> presenter = AllSessionsAction::showPopup;

    public AllSessionsAction() {
        super("All Sessions…", "Search every session, including stopped and filtered-out ones",
                AllIcons.Actions.Find);
    }

    /** Pure: every session, most recently opened first, with intents from {@code activation}. */
    static List<Row> rows(List<ManagedAgentSession> sessions, SessionActivation activation) {
        Map<String, Integer> names = new HashMap<>();
        for (ManagedAgentSession session : sessions) {
            names.merge(session.displayName().strip().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<ManagedAgentSession> ordered = new ArrayList<>(sessions);
        ordered.sort(Comparator.comparing(ManagedAgentSession::lastOpenedAt).reversed());
        List<Row> rows = new ArrayList<>(ordered.size());
        for (ManagedAgentSession session : ordered) {
            SessionActivation.Intent intent = activation.intentOf(session.id())
                    .orElse(SessionActivation.Intent.START_OR_RESUME);
            Path place = session.workspace().worktreeRoot().orElse(session.workspace().workingDirectory());
            Path leaf = place.getFileName();
            rows.add(new Row(session.id(), session.displayName(), session.status().name(),
                    SessionActivation.labelOf(intent, Optional.empty()).text(),
                    leaf == null ? place.toString() : leaf.toString(),
                    names.get(session.displayName().strip().toLowerCase(Locale.ROOT)) > 1, intent));
        }
        return rows;
    }

    private static void showPopup(Project project, List<Row> rows) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
                .setTitle("All Sessions")
                .setNamerForFiltering(Row::searchText)
                .setRenderer(SimpleListCellRenderer.create("", Row::text))
                .setItemChosenCallback(row -> activate(project, row))
                .setRequestFocus(true)
                .createPopup()
                .showCenteredInCurrentWindow(project);
    }

    /** Commit: only an explicit choice reaches here. */
    static void activate(Project project, Row row) {
        if (!project.isDisposed()) {
            activation.get().activate(project, row.id(), row.captured());
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        presenter.accept(project, rows(catalog.get(), activation.get()));
    }
}

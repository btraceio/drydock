package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskSnapshot.IdCollision;

import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The cross-project prerequisite picker of the new-task dialog (T18a). All
 * searchable state comes from ONE immutable {@link Index} built once from one
 * {@link TaskSnapshot} by the caller: searching filters that in-memory index
 * and NEVER re-scans the store, not per keystroke and not per selection. The
 * picker performs no I/O of any kind - no store read, no worktree, no agent
 * (P5: picking prerequisites prepares nothing).
 *
 * <p>A selected hit builds the CANONICAL prerequisite text the store expects
 * (P2): {@code <project>:<id>} qualified by the repository's unique display
 * slug, or the bare {@code <id>} when the repository has no (or an ambiguous)
 * slug - the bare id is globally unique, which is exactly what the accepted
 * {@link TaskGraph} resolution consumes. Ambiguous and broken refs are
 * validated by that same production resolution, composed, never
 * re-implemented here: {@link Index#refusals} plants a synthetic pending
 * document carrying the proposed refs into a copy of the snapshot and reads
 * {@link TaskGraph}'s own broken-reference reasons back.</p>
 *
 * <p>Keyboard: Add and Remove are real focusable buttons; Enter/Space press
 * them natively and the hosting DialogWrapper maps Esc to cancel. Keystrokes
 * in the search field coalesce through a one-shot 150 ms Swing timer, so one
 * pause refills the hits list once, never once per key (AGENTS.md UI hygiene;
 * the refill is an in-memory filter, so it never blocks).</p>
 */
public final class TaskPrerequisitePicker {

    /** At most this many hits per refill: the list stays scannable, the filter cheap. */
    private static final int RESULT_LIMIT = 50;

    /** The typing pause that coalesces keystrokes into one refill (AGENTS.md). */
    private static final int TYPING_PAUSE_MILLIS = 150;

    /** One search hit: the project it lives in, the task, and the ref a click adds. */
    public record Match(String project, String title, TaskId id, String reference) {

        /** The one-line result text: project, title, id. */
        public String display() {
            String project = this.project == null || this.project.isBlank() ? "(no project)" : this.project;
            return project + " - " + title + "  [" + id.text() + "]";
        }
    }

    /**
     * The immutable search index over ONE scan (T18a): the snapshot's
     * documents plus each repository's display slug, resolved once when the
     * index is built. Nothing here reads the store again; the slug mapping is
     * a frozen copy, so a later catalog change cannot leak into a search.
     */
    public static final class Index {

        private final TaskSnapshot snapshot;
        private final Map<RepositoryId, String> slugs;
        private final Map<String, Long> slugCounts;

        private Index(TaskSnapshot snapshot, Map<RepositoryId, String> slugs) {
            this.snapshot = snapshot;
            this.slugs = slugs;
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String slug : slugs.values()) {
                if (slug != null) {
                    counts.merge(slug, 1L, Long::sum);
                }
            }
            this.slugCounts = counts;
        }

        /** Builds the index for one scan; the slug mapping is applied once per repository. */
        public static Index of(TaskSnapshot snapshot, Function<RepositoryId, String> projectSlugs) {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(projectSlugs, "projectSlugs");
            Map<RepositoryId, String> resolved = new LinkedHashMap<>();
            for (RepositoryId repository : snapshot.repositories()) {
                String slug = projectSlugs.apply(repository);
                resolved.put(repository, slug == null || slug.isBlank() ? null : slug);
            }
            return new Index(snapshot, resolved);
        }

        /** The snapshot this index was built from; the picker never rescans the store for it. */
        public TaskSnapshot snapshot() {
            return snapshot;
        }

        /**
         * Case-insensitive search over task titles and ids across ALL
         * repositories, in snapshot order, at most {@code limit} hits; an
         * empty query answers no hits (nothing typed, nothing to show).
         */
        public List<Match> search(String text, int limit) {
            String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || limit <= 0) {
                return List.of();
            }
            List<Match> matches = new ArrayList<>();
            for (TaskDocument document : snapshot.documents()) {
                if (matches.size() >= limit) {
                    break;
                }
                String reference = canonicalReference(document);
                if (document.title().toLowerCase(Locale.ROOT).contains(needle)
                        || document.id().text().contains(needle)
                        || reference.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(new Match(slugs.get(document.repository()), document.title(),
                            document.id(), reference));
                }
            }
            return List.copyOf(matches);
        }

        /**
         * The canonical prerequisite text for one task (P2): qualified
         * {@code <slug>:<id>} while the repository's slug is unique among the
         * snapshot's repositories, else the bare {@code <id>} (globally
         * unique, resolvable everywhere). Matches the production resolution
         * the store consumes; an ambiguous slug is never qualified, because
         * P2 breaks every ref qualified by it.
         */
        public String canonicalReference(TaskDocument document) {
            String slug = slugs.get(document.repository());
            return slug != null && slugCounts.getOrDefault(slug, 0L) == 1L
                    ? slug + ":" + document.id().text() : document.id().text();
        }

        /**
         * Every refusal for one proposed prerequisite list, composed over the
         * production resolution (never re-implemented): a synthetic pending
         * document carrying the refs is planted into a copy of the snapshot,
         * {@link TaskGraph} resolves it exactly as the store will, and this
         * returns its broken-reference reasons verbatim - unknown project
         * slugs, missing ids, ambiguous collision stems, wrong-scope ids.
         * Valid refs and an empty list answer no refusals.
         */
        public List<String> refusals(List<String> after) {
            List<String> refs = after == null ? List.of() : List.copyOf(after);
            if (refs.isEmpty()) {
                return List.of();
            }
            TaskId probeId = freshProbeId(refs);
            TaskDocument pending = new TaskDocument(probeId, probeRepository(),
                    "The task being created", null, null, null, refs, null, "", "");
            Map<TaskId, TaskDocument> byId = new LinkedHashMap<>(snapshot.byId());
            byId.put(probeId, pending);
            List<TaskDocument> documents = new ArrayList<>(snapshot.documents());
            documents.add(pending);
            TaskSnapshot extended = new TaskSnapshot(documents, snapshot.repositories(), byId,
                    snapshot.bySessionReference(), snapshot.failures(), snapshot.idCollisions());
            return TaskGraph.of(extended, slugs::get).diagnosticsOf(probeId)
                    .map(diagnostics -> diagnostics.broken().stream()
                            .map(broken -> "'" + broken.reference() + "': " + broken.reason())
                            .collect(Collectors.toList()))
                    .orElse(List.of());
        }

        /** A repository to park the synthetic pending document under (its own identity
         * never takes part in resolving the refs it carries). */
        private RepositoryId probeRepository() {
            return snapshot.repositories().isEmpty() ? RepositoryId.newId() : snapshot.repositories().get(0);
        }

        /** An id nothing can name: not stored, not collided, not among the proposed refs. */
        private TaskId freshProbeId(List<String> refs) {
            Set<TaskId> collided = new HashSet<>();
            for (IdCollision collision : snapshot.idCollisions()) {
                collided.add(collision.id());
            }
            for (int attempt = 1; ; attempt++) {
                TaskId candidate = TaskId.of("new-task-probe" + (attempt == 1 ? "" : "-" + attempt));
                boolean namedByRef = refs.stream().anyMatch(ref ->
                        ref.equals(candidate.text()) || ref.endsWith(":" + candidate.text()));
                if (!snapshot.byId().containsKey(candidate) && !collided.contains(candidate) && !namedByRef) {
                    return candidate;
                }
            }
        }
    }

    private final Index index;
    private final JBTextField search = new JBTextField();
    private final DefaultListModel<Match> results = new DefaultListModel<>();
    private final DefaultListModel<String> chosen = new DefaultListModel<>();
    private final JList<Match> resultList = new JList<>(results);
    private final JList<String> chosenList = new JList<>(chosen);
    private final JButton add = new JButton("Add");
    private final JButton remove = new JButton("Remove");
    /** One-shot typing pause: keystrokes coalesce into one refill, never one per key. */
    private final Timer pause = new Timer(TYPING_PAUSE_MILLIS, event -> refreshResults());

    /** One picker over one indexed snapshot; the caller builds the index once, off the EDT. */
    public TaskPrerequisitePicker(Index index) {
        this.index = Objects.requireNonNull(index, "index");
        pause.setRepeats(false);
        resultList.setVisibleRowCount(6);
        resultList.setCellRenderer(new MatchRenderer());
        chosenList.setVisibleRowCount(4);
        add.setEnabled(false);
        remove.setEnabled(false);
        search.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                pause.restart();
            }
        });
        resultList.addListSelectionListener(event -> add.setEnabled(!resultList.isSelectionEmpty()));
        chosenList.addListSelectionListener(event -> remove.setEnabled(!chosenList.isSelectionEmpty()));
        add.addActionListener(event -> addSelected());
        remove.addActionListener(event -> removeSelected());
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    addSelected();
                }
            }
        });
    }

    /** The immutable index this picker searches; building it again is the caller's call. */
    public Index index() {
        return index;
    }

    /** The chosen prerequisite reference texts, immutable, in pick order. */
    public List<String> references() {
        List<String> refs = new ArrayList<>(chosen.size());
        for (int i = 0; i < chosen.size(); i++) {
            refs.add(chosen.get(i));
        }
        return List.copyOf(refs);
    }

    /** Prefills the chosen references (the dialog's edit path); a null clears them. */
    public void setReferences(List<String> refs) {
        chosen.clear();
        if (refs != null) {
            for (String ref : refs) {
                chosen.addElement(ref);
            }
        }
    }

    /** Every production-resolution refusal for the currently chosen refs. */
    public List<String> refusals() {
        return index.refusals(references());
    }

    /** The picker's panel: search field, hits, Add, the chosen refs, Remove. */
    public JComponent component() {
        JPanel top = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        top.add(search, BorderLayout.CENTER);
        JPanel hits = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(4)));
        hits.add(ScrollPaneFactory.createScrollPane(resultList), BorderLayout.CENTER);
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addRow.add(add);
        hits.add(addRow, BorderLayout.SOUTH);
        JPanel chosenRow = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(4)));
        JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        removeRow.add(remove);
        chosenRow.add(removeRow, BorderLayout.NORTH);
        chosenRow.add(ScrollPaneFactory.createScrollPane(chosenList), BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), JBUI.scale(6)));
        panel.add(top, BorderLayout.NORTH);
        panel.add(hits, BorderLayout.CENTER);
        panel.add(chosenRow, BorderLayout.SOUTH);
        return panel;
    }

    /** Appends the selected hit's canonical reference; clears the selection so a
     * second Enter cannot add it twice. */
    private void addSelected() {
        Match match = resultList.getSelectedValue();
        if (match == null) {
            return;
        }
        chosen.addElement(match.reference());
        resultList.clearSelection();
    }

    /** Removes the chosen selections, highest index first so removals stay stable. */
    private void removeSelected() {
        int[] selected = chosenList.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) {
            chosen.remove(selected[i]);
        }
    }

    /** One refill from the index: an in-memory filter, never a store read. */
    private void refreshResults() {
        results.clear();
        for (Match match : index.search(search.getText(), RESULT_LIMIT)) {
            results.addElement(match);
        }
    }

    /** Renders a hit as its one-line display text. */
    private static final class MatchRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof Match match) {
                setText(match.display());
            }
            return this;
        }
    }
}

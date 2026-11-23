package app.drydock.intellij.tasks;

import app.drydock.git.JiraIssueWriter;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskMirrorPlan;
import app.drydock.tasks.TaskMirrorPlan.Disposition;
import app.drydock.tasks.TaskMirrorRun.Accepted;
import app.drydock.tasks.TaskMirrorRun.Consent;
import app.drydock.tasks.TaskMirrorRun.Destination;
import app.drydock.tasks.TaskMirrorRun.JiraChoice;
import app.drydock.tasks.TaskMirrorRun.Proposal;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single confirmation for "mirror this task and its prerequisites", for
 * either target. It shows the whole staged plan in a scrollable plain-text
 * area (no HTML, so task text can never inject markup, and nothing is
 * truncated) and collects only choices: per shared project the Jira key/type
 * for projects that need creates, and optional "skip" boxes for prerequisite
 * projects. The summary re-stages whenever a choice changes through
 * {@link Staging}, a capture-only model that calls no tracker, store or
 * settings writer. Persisting the accepted Jira choices is the
 * coordinator's job after revalidation; this class never remembers them.
 *
 * <p>EDT only. {@link #ask} returns {@link Consent.Cancel} when the dialog
 * is cancelled or disposed, and {@link Consent.Accept} only on OK.</p>
 */
final class TaskMirrorDialog extends DialogWrapper {

    private static final String SKIPPED_BY_USER = "skipped by you in the confirmation dialog";

    private final Proposal proposal;
    private final Staging staging;
    private final JTextArea summary = new JTextArea();
    private final Map<String, JBTextField> keyFields = new LinkedHashMap<>();
    private final Map<String, JBTextField> typeFields = new LinkedHashMap<>();
    private final Map<String, JBCheckBox> skipBoxes = new LinkedHashMap<>();

    private TaskMirrorDialog(Project ide, Proposal proposal, Staging staging) {
        super(ide, true);
        this.proposal = proposal;
        this.staging = staging;
        boolean jira = proposal.kind() == TaskMirror.Kind.JIRA;
        setTitle((jira ? "Mirror to Jira — " : "Mirror to GitHub — ") + proposal.plan().root().title());
        setOKButtonText(jira ? "Create Tickets" : "Create Issues");
        init();
        restage();
    }

    /**
     * Shows the dialog modally and returns the consent answer. Must run on the
     * EDT. Cancel, close and disposal all answer {@link Consent.Cancel}.
     */
    static Consent ask(Project ide, Proposal proposal) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        TaskMirrorPlan plan = proposal.plan();
        if (plan.refusal().isPresent()) {
            return new Consent.Refuse(plan.refusal().get().message());
        }
        if (plan.root() == null || plan.dispositionOf(plan.root().id()).isEmpty()) {
            return new Consent.Refuse("Nothing is prepared for the selected task.");
        }
        TaskMirrorDialog dialog = new TaskMirrorDialog(ide, proposal, new Staging(proposal, initialChoices(proposal)));
        boolean accepted = dialog.showAndGet();
        return accepted ? new Consent.Accept(dialog.staging.accepted()) : new Consent.Cancel();
    }

    /** Proposal-supplied choices win; otherwise the last answer remembered for the project's tasks. */
    private static Map<String, JiraChoice> initialChoices(Proposal proposal) {
        Map<String, JiraChoice> initial = new LinkedHashMap<>();
        if (proposal.kind() != TaskMirror.Kind.JIRA) return initial;
        for (TaskDocument doc : proposal.plan().order()) {
            if (!(proposal.plan().dispositionOf(doc.id()).orElse(null) instanceof Disposition.Create)) continue;
            Destination dest = proposal.destinations().get(doc.id());
            if (dest == null || initial.containsKey(dest.project())) continue;
            JiraChoice given = proposal.choices().get(dest.project());
            if (given != null) {
                initial.put(dest.project(), given);
                continue;
            }
            String key = JiraMirrorDialog.lastProjectKey(doc.repository());
            String type = JiraMirrorDialog.lastIssueType(doc.repository());
            initial.put(dest.project(), new JiraChoice(key == null ? "" : key,
                    type == null || type.isBlank() ? JiraMirrorDialog.DEFAULT_ISSUE_TYPE : type));
        }
        return initial;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel controls = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = JBUI.insets(2, 0, 2, 8);
        boolean jira = proposal.kind() == TaskMirror.Kind.JIRA;
        String rootProject = rootProject();
        for (String project : staging.createProjects()) {
            if (jira) {
                JiraChoice initial = staging.choiceOf(project);
                JBTextField key = new JBTextField(initial.key(), 10);
                key.getEmptyText().setText("PROJ");
                JBTextField type = new JBTextField(initial.type(), 12);
                keyFields.put(project, key);
                typeFields.put(project, type);
                DocumentListener listener = new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { changed(); }
                    @Override public void removeUpdate(DocumentEvent e) { changed(); }
                    @Override public void changedUpdate(DocumentEvent e) { changed(); }
                };
                key.getDocument().addDocumentListener(listener);
                type.getDocument().addDocumentListener(listener);
                c.gridx = 0;
                controls.add(new JBLabel("Project " + project + " — Jira key:"), c);
                c.gridx = 1;
                controls.add(key, c);
                c.gridx = 2;
                controls.add(new JBLabel("type:"), c);
                c.gridx = 3;
                controls.add(type, c);
            } else {
                c.gridx = 0;
                controls.add(new JBLabel("Project " + project), c);
            }
            if (!project.equals(rootProject)) {
                JBCheckBox skip = new JBCheckBox("Skip this prerequisite project");
                skip.addActionListener(e -> changed());
                skipBoxes.put(project, skip);
                c.gridx = 4;
                controls.add(skip, c);
            }
            c.gridy++;
        }
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setBorder(JBUI.Borders.empty(4));
        JBScrollPane scroll = new JBScrollPane(summary);
        scroll.setPreferredSize(new Dimension(JBUI.scale(720), JBUI.scale(420)));
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(new JBScrollPane(controls), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected String getDimensionServiceKey() {
        return "app.drydock.intellij.tasks.TaskMirrorDialog";
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (proposal.kind() != TaskMirror.Kind.JIRA) return null;
        for (Map.Entry<String, JBTextField> entry : keyFields.entrySet()) {
            String project = entry.getKey();
            if (staging.isSkipped(project)) continue;
            String key = entry.getValue().getText().strip().toUpperCase(Locale.ROOT);
            var refusal = JiraIssueWriter.projectKeyRefusal(key);
            if (refusal.isPresent()) {
                return new ValidationInfo(project + ": " + refusal.get(), entry.getValue());
            }
            JBTextField type = typeFields.get(project);
            if (type.getText().strip().isEmpty()) {
                return new ValidationInfo(project + ": name the issue type, e.g. "
                        + JiraMirrorDialog.DEFAULT_ISSUE_TYPE, type);
            }
        }
        return null;
    }

    private String rootProject() {
        Destination dest = proposal.destinations().get(proposal.plan().root().id());
        return dest != null ? dest.project() : "";
    }

    /** Captures the controls into the staging model and refreshes the summary. */
    private void changed() {
        for (Map.Entry<String, JBTextField> entry : keyFields.entrySet()) {
            staging.setChoice(entry.getKey(), entry.getValue().getText(), typeFields.get(entry.getKey()).getText());
        }
        for (Map.Entry<String, JBCheckBox> entry : skipBoxes.entrySet()) {
            staging.setSkipped(entry.getKey(), entry.getValue().isSelected());
        }
        restage();
    }

    private void restage() {
        summary.setText(staging.render());
        summary.setCaretPosition(0);
    }

    /**
     * Capture-only staging model: holds the chosen Jira key/type per project
     * and the skipped prerequisite projects, renders the summary and yields
     * the accepted plan. It performs no I/O and calls no writer.
     */
    static final class Staging {
        private final Proposal proposal;
        private final Map<String, JiraChoice> choices;
        private final Set<String> skipped = new LinkedHashSet<>();

        Staging(Proposal proposal, Map<String, JiraChoice> initial) {
            this.proposal = proposal;
            this.choices = new LinkedHashMap<>(initial);
        }

        /** Projects (in plan order) that have at least one task proposed for creation. */
        List<String> createProjects() {
            Set<String> projects = new LinkedHashSet<>();
            for (TaskDocument doc : proposal.plan().order()) {
                if (proposal.plan().dispositionOf(doc.id()).orElse(null) instanceof Disposition.Create) {
                    projects.add(projectOf(doc));
                }
            }
            return new ArrayList<>(projects);
        }

        JiraChoice choiceOf(String project) {
            JiraChoice choice = choices.get(project);
            return choice != null ? choice : new JiraChoice("", JiraMirrorDialog.DEFAULT_ISSUE_TYPE);
        }

        boolean isSkipped(String project) {
            return skipped.contains(project);
        }

        void setSkipped(String project, boolean skip) {
            if (skip) skipped.add(project); else skipped.remove(project);
        }

        void setChoice(String project, String key, String type) {
            choices.put(project, new JiraChoice(key.strip().toUpperCase(Locale.ROOT), type.strip()));
        }

        private String projectOf(TaskDocument doc) {
            Destination dest = proposal.destinations().get(doc.id());
            return dest != null ? dest.project() : "repository " + doc.repository().value();
        }

        private Disposition effective(TaskDocument doc) {
            Disposition base = proposal.plan().dispositionOf(doc.id())
                    .orElse(new Disposition.Skip("no destination was prepared for this task"));
            if (base instanceof Disposition.Create && skipped.contains(projectOf(doc))) {
                return new Disposition.Skip(SKIPPED_BY_USER);
            }
            return base;
        }

        /** The accepted plan: only create-to-skip changes, and the choices of projects still creating. */
        Accepted accepted() {
            Map<TaskId, Disposition> dispositions = new LinkedHashMap<>();
            Map<String, JiraChoice> kept = new LinkedHashMap<>();
            for (TaskDocument doc : proposal.plan().order()) {
                Disposition d = effective(doc);
                dispositions.put(doc.id(), d);
                if (d instanceof Disposition.Create && proposal.kind() == TaskMirror.Kind.JIRA) {
                    kept.put(projectOf(doc), choiceOf(projectOf(doc)));
                }
            }
            return new Accepted(dispositions, kept);
        }

        private String identity(TaskDocument doc) {
            return "'" + doc.title() + "' (" + doc.id().text() + ", " + projectOf(doc) + ")";
        }

        /** The full plain-text summary of the currently staged plan. */
        String render() {
            TaskMirrorPlan plan = proposal.plan();
            boolean jira = proposal.kind() == TaskMirror.Kind.JIRA;
            StringBuilder out = new StringBuilder();
            out.append("Target: ").append(jira ? "Jira" : "GitHub").append('\n');
            out.append("Selected task: ").append(identity(plan.root())).append("\n\n");

            Map<String, List<TaskDocument>> creates = new LinkedHashMap<>();
            List<TaskDocument> reuses = new ArrayList<>();
            List<TaskDocument> skips = new ArrayList<>();
            for (TaskDocument doc : plan.order()) {
                Disposition d = effective(doc);
                if (d instanceof Disposition.Create) {
                    creates.computeIfAbsent(projectOf(doc), k -> new ArrayList<>()).add(doc);
                } else if (d instanceof Disposition.Reuse) {
                    reuses.add(doc);
                } else {
                    skips.add(doc);
                }
            }

            int total = creates.values().stream().mapToInt(List::size).sum();
            out.append("Will create (").append(total).append("):\n");
            if (creates.isEmpty()) out.append("  nothing\n");
            for (Map.Entry<String, List<TaskDocument>> group : creates.entrySet()) {
                Destination first = proposal.destinations().get(group.getValue().get(0).id());
                out.append("  Project ").append(group.getKey());
                if (first != null) out.append(" — ").append(first.description());
                if (jira) {
                    JiraChoice choice = choiceOf(group.getKey());
                    out.append(" — key ").append(choice.key().isEmpty() ? "(not chosen)" : choice.key())
                            .append(", type ").append(choice.type().isEmpty() ? "(not chosen)" : choice.type());
                    if (first != null && first.jiraSite() != null) out.append(" on ").append(first.jiraSite());
                }
                out.append('\n');
                for (TaskDocument doc : group.getValue()) {
                    out.append("    + ").append(identity(doc)).append('\n');
                    List<TaskDocument> pre = plan.prerequisitesOf(doc.id());
                    if (!pre.isEmpty()) {
                        out.append("        after: ");
                        out.append(String.join(", ", pre.stream().map(p -> "'" + p.title() + "' ("
                                + p.id().text() + ")").toList())).append('\n');
                    }
                }
            }

            out.append("\nWill reuse (").append(reuses.size()).append("):\n");
            if (reuses.isEmpty()) out.append("  nothing\n");
            for (TaskDocument doc : reuses) {
                Disposition.Reuse reuse = (Disposition.Reuse) effective(doc);
                out.append("  = ").append(identity(doc)).append(" -> ").append(reuse.address()).append('\n');
            }

            out.append("\nSkipped (").append(skips.size() + plan.excluded().size()).append("):\n");
            if (skips.isEmpty() && plan.excluded().isEmpty()) out.append("  nothing\n");
            for (TaskDocument doc : skips) {
                out.append("  - ").append(identity(doc)).append(": ")
                        .append(((Disposition.Skip) effective(doc)).reason()).append('\n');
            }
            for (TaskMirrorPlan.Exclusion ex : plan.excluded()) {
                out.append("  - ").append(identity(ex.task())).append(": in a prerequisite cycle ")
                        .append(String.join(" -> ", ex.titles())).append('\n');
            }

            if (!plan.droppedEdges().isEmpty() || !plan.brokenReferences().isEmpty()) {
                out.append("\nRelationships that cannot be mirrored:\n");
                for (TaskMirrorPlan.DroppedEdge edge : plan.droppedEdges()) {
                    out.append("  - '").append(edge.reference()).append("' on ").append(identity(edge.source()));
                    if (edge.target() != null) out.append(" -> ").append(identity(edge.target()));
                    out.append(": ").append(edge.reason()).append('\n');
                }
            }
            if (!plan.cycles().isEmpty()) {
                out.append("\nPrerequisite cycles found:\n");
                for (TaskMirrorPlan.CycleWarning cycle : plan.cycles()) {
                    out.append("  - ").append(String.join(" -> ", cycle.titles())).append('\n');
                }
            }

            out.append('\n');
            if (jira) {
                out.append("Note: for reused tickets Jira still ensures the direct Blocks links between"
                        + " addressed prerequisites on the same site.\n");
            } else {
                out.append("Note: the dependency list in the body of a reused GitHub issue is not repaired;"
                        + " only newly created issues list their prerequisites.\n");
            }
            return out.toString();
        }
    }
}

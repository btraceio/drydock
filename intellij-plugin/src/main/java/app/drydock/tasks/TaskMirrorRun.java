package app.drydock.tasks;

import app.drydock.tasks.TaskMirrorPlan.Disposition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Platform-independent coordinator of one "mirror this task and its
 * prerequisites" run. It owns the consent gate, the per-run address ledger
 * and the structured aggregate; every effect (consent, revalidation, create,
 * link, local record, remembered choices, progress, cancellation) is an
 * injected callback, so it has no Swing, IntelliJ, settings, secret, executor
 * or notification dependency.
 *
 * <p>Flow: a whole-run refusal, or a root that cannot be created, refuses
 * before any callback; consent comes next and cancel/refusal ends the run
 * with no effect; after acceptance one revalidation may stop the run or
 * narrow creates to reuse; only then are accepted Jira choices remembered.
 * Nodes are then processed serially in plan order (prerequisites first),
 * each confirmed create entering the ledger <em>before</em> its local record
 * is attempted, and Jira links are ensured afterwards, one attempt per
 * oriented pair. Failures are scoped to their operation, nothing is rolled
 * back and no write is retried.</p>
 */
public final class TaskMirrorRun {

    /** How the run ended. */
    public enum Status { REFUSED, CANCELLED, STOPPED, COMPLETED }

    /** The operation a {@link Failure} belongs to. */
    public enum Operation { CREATE, PROPERTY, RECORD, REMEMBER, LOOKUP, LINK }

    /** A Jira project key and issue type chosen for one resolved shared project. */
    public record JiraChoice(String key, String type) {
        public JiraChoice {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * Where one task's issue goes: the resolved shared project it belongs to, a
     * description for the consent display and, for Jira, the normalized site of
     * that project's usable connection (null when it has none).
     */
    public record Destination(String project, String description, String jiraSite) {
        public Destination {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(description, "description");
        }
    }

    /** What consent is asked about: the plan with prepared dispositions and destinations. */
    public record Proposal(TaskMirror.Kind kind, TaskMirrorPlan plan, Map<TaskId, Destination> destinations,
            Map<String, JiraChoice> choices) {
        public Proposal {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(plan, "plan");
            destinations = Map.copyOf(destinations);
            choices = Map.copyOf(choices);
        }
    }

    /** The frozen accepted plan: dispositions (a create may only have become a skip) and Jira choices. */
    public record Accepted(Map<TaskId, Disposition> dispositions, Map<String, JiraChoice> choices) {
        public Accepted {
            dispositions = Map.copyOf(dispositions);
            choices = Map.copyOf(choices);
        }
    }

    /** The consent answer. */
    public sealed interface Consent {
        /** The user cancelled. */
        record Cancel() implements Consent { }

        /** Consent could not be obtained or the staged plan is unusable. */
        record Refuse(String message) implements Consent { }

        /** The accepted plan. */
        record Accept(Accepted plan) implements Consent { }
    }

    /** The pre-write revalidation answer. */
    public sealed interface Revalidation {
        /** Nothing material changed. */
        record Unchanged() implements Revalidation { }

        /** Newly found mirrors (task to exact address) that narrow creates to reuse. */
        record Narrow(Map<TaskId, String> mirrors) implements Revalidation {
            public Narrow {
                mirrors = Map.copyOf(mirrors);
            }
        }

        /** A material change or a failed revalidation. */
        record Stop(String message) implements Revalidation { }
    }

    /** One create to perform; {@code dependencies} are the known direct prerequisites (GitHub only). */
    public record CreateRequest(TaskMirror.Kind kind, TaskDocument task, Destination destination,
            Optional<JiraChoice> choice, List<TaskMirror.Dependency> dependencies, BooleanSupplier cancelled) {
        public CreateRequest {
            dependencies = List.copyOf(dependencies);
        }
    }

    /** The result of a create. */
    public sealed interface CreateOutcome {
        /** A confirmed address, with an optional property warning. */
        record Created(String address, Optional<String> propertyWarning) implements CreateOutcome { }

        /** A confirmed failure or refusal: nothing was created. */
        record Failed(String message) implements CreateOutcome { }

        /** Whether anything was created cannot be established. */
        record Uncertain(String message) implements CreateOutcome { }
    }

    /** The result of recording an address on its task. */
    public sealed interface RecordOutcome {
        /** Recorded. */
        record Committed() implements RecordOutcome { }

        /** Refused by the store. */
        record Refused(String message) implements RecordOutcome { }
    }

    /** One Blocks link to ensure: {@code prerequisite} blocks {@code dependent}, same site. */
    public record LinkRequest(TaskDocument prerequisite, Destination prerequisiteDestination,
            String prerequisiteAddress, String prerequisiteKey, TaskDocument dependent,
            Destination dependentDestination, String dependentAddress, String dependentKey, String site) {
    }

    /** The result of ensuring one link. */
    public sealed interface LinkOutcome {
        /** The relationship was already present; {@code lookupProblem} is a failed other-endpoint lookup. */
        record AlreadyPresent(Optional<String> lookupProblem) implements LinkOutcome { }

        /** The link was created. */
        record Linked() implements LinkOutcome { }

        /** Presence could not be established, so no link was posted. */
        record LookupFailed(String message) implements LinkOutcome { }

        /** The link request was confirmed rejected. */
        record Failed(String message) implements LinkOutcome { }

        /** Whether the link was created cannot be established. */
        record Uncertain(String message) implements LinkOutcome { }
    }

    @FunctionalInterface public interface Consenter { Consent consent(Proposal proposal) throws Exception; }

    @FunctionalInterface public interface Revalidator { Revalidation revalidate(Accepted accepted) throws Exception; }

    @FunctionalInterface public interface Remember { void remember(Map<String, JiraChoice> choices) throws Exception; }

    @FunctionalInterface public interface Creator { CreateOutcome create(CreateRequest request) throws Exception; }

    @FunctionalInterface public interface Recorder { RecordOutcome record(TaskId task, String address) throws Exception; }

    @FunctionalInterface public interface Linker { LinkOutcome link(LinkRequest request) throws Exception; }

    /** Progress sink: {@code phase} may be called many times, {@code finish} exactly once on every exit. */
    public interface Progress {
        void phase(String text);

        void finish();
    }

    /** The injected effects. */
    public record Effects(Consenter consent, Revalidator revalidate, Remember remember, Creator creator,
            Recorder recorder, Linker linker, BooleanSupplier cancelled, Progress progress) {
        public Effects {
            Objects.requireNonNull(consent, "consent");
            Objects.requireNonNull(revalidate, "revalidate");
            Objects.requireNonNull(remember, "remember");
            Objects.requireNonNull(creator, "creator");
            Objects.requireNonNull(recorder, "recorder");
            Objects.requireNonNull(linker, "linker");
            Objects.requireNonNull(cancelled, "cancelled");
            Objects.requireNonNull(progress, "progress");
        }
    }

    /** A confirmed create; {@code recordProblem} is set when the local record did not commit. */
    public record Created(TaskDocument task, String address, Optional<String> propertyWarning,
            Optional<String> recordProblem) {
    }

    /** A reused address, kept verbatim. */
    public record Reused(TaskDocument task, String address) {
    }

    /** What was left out. */
    public enum OmissionKind { TASK, REFERENCE, RELATIONSHIP }

    /** Something left out of the run, with the reason. */
    public record Omission(OmissionKind kind, String subject, String reason) {
    }

    /** An operation-scoped failure or uncertainty. */
    public record Failure(Operation operation, String subject, String message, boolean uncertain) {
    }

    /** A Jira link that exists after the run. */
    public record EnsuredLink(TaskDocument prerequisite, TaskDocument dependent, boolean alreadyPresent) {
    }

    /** The structured aggregate of the run. */
    public record Result(Status status, String message, List<Created> created, List<Reused> reused,
            List<Omission> omissions, List<Failure> failures, List<EnsuredLink> links) {
        public Result {
            created = List.copyOf(created);
            reused = List.copyOf(reused);
            omissions = List.copyOf(omissions);
            failures = List.copyOf(failures);
            links = List.copyOf(links);
        }

        /** Whole success: completed with no omission, failure or uncertainty. */
        public boolean clean() {
            return status == Status.COMPLETED && omissions.isEmpty() && failures.isEmpty();
        }
    }

    private static final String CHECK_TRACKER = " Check the tracker before retrying; this run will not retry.";

    private final Effects effects;

    public TaskMirrorRun(Effects effects) {
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    /** Runs the proposal; progress is finished on every exit. */
    public Result run(Proposal proposal) {
        try {
            return execute(proposal);
        } finally {
            effects.progress().finish();
        }
    }

    private static Result ended(Status status, String message) {
        return new Result(status, message, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private boolean stopped() {
        return effects.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static void keepInterrupt(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }

    private Result execute(Proposal proposal) {
        TaskMirrorPlan plan = proposal.plan();
        Optional<TaskMirrorPlan.Refusal> refusal = plan.refusal();
        if (refusal.isPresent()) {
            return ended(Status.REFUSED, refusal.get().message());
        }
        TaskDocument root = plan.root();
        Optional<Disposition> rootDisposition = plan.dispositionOf(root.id());
        if (rootDisposition.isEmpty()) {
            return ended(Status.REFUSED, "'" + root.title() + "' cannot be mirrored: no destination was prepared");
        }
        if (rootDisposition.get() instanceof Disposition.Skip skip) {
            return ended(Status.REFUSED, "'" + root.title() + "' cannot be mirrored: " + skip.reason());
        }

        effects.progress().phase("Confirming the mirror plan");
        Consent consent;
        try {
            consent = effects.consent().consent(proposal);
        } catch (Exception e) {
            keepInterrupt(e);
            return ended(Status.REFUSED, "Confirmation failed: " + message(e));
        }
        if (consent instanceof Consent.Cancel) {
            return ended(Status.CANCELLED, "Cancelled; nothing was written.");
        }
        if (consent instanceof Consent.Refuse refuse) {
            return ended(Status.REFUSED, refuse.message());
        }
        Accepted accepted = ((Consent.Accept) consent).plan();

        // The accepted plan may only turn a create into a skip.
        Map<TaskId, Disposition> effective = new LinkedHashMap<>();
        for (TaskDocument doc : plan.order()) {
            effective.put(doc.id(), plan.dispositionOf(doc.id())
                    .orElse(new Disposition.Skip("no destination was prepared for this task")));
        }
        for (Map.Entry<TaskId, Disposition> entry : accepted.dispositions().entrySet()) {
            Disposition proposed = effective.get(entry.getKey());
            if (proposed == null) {
                return ended(Status.REFUSED, "The accepted plan names task '" + entry.getKey().text()
                        + "', which is not part of the proposed plan; nothing was written.");
            }
            if (proposed.equals(entry.getValue())) continue;
            if (proposed instanceof Disposition.Create && entry.getValue() instanceof Disposition.Skip) {
                effective.put(entry.getKey(), entry.getValue());
            } else {
                return ended(Status.REFUSED, "The accepted plan changes task '" + entry.getKey().text()
                        + "' beyond skipping a create; nothing was written.");
            }
        }
        boolean jira = proposal.kind() == TaskMirror.Kind.JIRA;
        if (jira) {
            for (TaskDocument doc : plan.order()) {
                if (!(effective.get(doc.id()) instanceof Disposition.Create)) continue;
                Destination dest = proposal.destinations().get(doc.id());
                if (dest != null && accepted.choices().get(dest.project()) != null) continue;
                String reason = "no Jira project key and issue type were chosen"
                        + (dest != null ? " for project " + dest.project() : "");
                if (doc.id().equals(root.id())) {
                    return ended(Status.REFUSED, "'" + root.title() + "' cannot be mirrored: " + reason
                            + "; nothing was written.");
                }
                effective.put(doc.id(), new Disposition.Skip(reason));
            }
        }
        if (effective.get(root.id()) instanceof Disposition.Skip rootSkip) {
            return ended(Status.REFUSED, "'" + root.title() + "' cannot be mirrored: " + rootSkip.reason()
                    + "; nothing was written.");
        }
        if (stopped()) {
            return ended(Status.CANCELLED, "Cancelled; nothing was written.");
        }

        effects.progress().phase("Revalidating before the first write");
        Revalidation revalidation;
        try {
            revalidation = effects.revalidate().revalidate(new Accepted(effective, accepted.choices()));
        } catch (Exception e) {
            keepInterrupt(e);
            return ended(Status.STOPPED, "Revalidation failed, so nothing was written: " + message(e));
        }
        if (revalidation instanceof Revalidation.Stop stop) {
            return ended(Status.STOPPED, "The plan changed since confirmation, so nothing was written: "
                    + stop.message());
        }
        if (revalidation instanceof Revalidation.Narrow narrow) {
            Map<TaskId, Disposition> narrowed = new LinkedHashMap<>(effective);
            for (Map.Entry<TaskId, String> found : narrow.mirrors().entrySet()) {
                if (!(effective.get(found.getKey()) instanceof Disposition.Create)) continue;
                String problem = narrowingProblem(proposal, found.getKey(), found.getValue());
                if (problem != null) {
                    return ended(Status.STOPPED, "The plan changed since confirmation, so nothing was written: "
                            + problem);
                }
                narrowed.put(found.getKey(), new Disposition.Reuse(found.getValue()));
            }
            effective = narrowed;
        }
        if (stopped()) {
            return ended(Status.CANCELLED, "Cancelled; nothing was written.");
        }

        List<Failure> failures = new ArrayList<>();
        if (jira) {
            Map<String, JiraChoice> needed = new LinkedHashMap<>();
            for (TaskDocument doc : plan.order()) {
                if (effective.get(doc.id()) instanceof Disposition.Create) {
                    String project = proposal.destinations().get(doc.id()).project();
                    needed.put(project, accepted.choices().get(project));
                }
            }
            if (!needed.isEmpty()) {
                try {
                    effects.remember().remember(needed);
                } catch (Exception e) {
                    keepInterrupt(e);
                    failures.add(new Failure(Operation.REMEMBER, "Jira project choices",
                            "could not remember the chosen Jira project key and type: " + message(e), false));
                }
            }
        }
        return carryOut(proposal, accepted.choices(), effective, failures);
    }

    /** Why a newly found mirror cannot narrow a create to reuse, or null when it can. */
    private static String narrowingProblem(Proposal proposal, TaskId id, String address) {
        TaskMirror.Kind kind = proposal.kind();
        if (TaskMirror.kindOf(address).filter(kind::equals).isEmpty()) {
            return "the newly found mirror of task '" + id.text() + "' is not a " + kind + " address";
        }
        if (kind == TaskMirror.Kind.JIRA) {
            Destination dest = proposal.destinations().get(id);
            if (!(TaskMirror.jiraAddress(address) instanceof TaskMirror.JiraAddress.Usable usable)
                    || dest == null || !usable.site().equals(dest.jiraSite())) {
                return "the newly found mirror " + address + " of task '" + id.text()
                        + "' is on a site other than the approved one";
            }
        }
        return null;
    }

    private String label(Proposal proposal, TaskDocument doc) {
        Destination dest = proposal.destinations().get(doc.id());
        return "'" + doc.title() + "' (" + doc.id().text() + ", "
                + (dest != null ? dest.project() : "repository " + doc.repository().value()) + ")";
    }

    private Result carryOut(Proposal proposal, Map<String, JiraChoice> choices, Map<TaskId, Disposition> effective,
            List<Failure> failures) {
        TaskMirrorPlan plan = proposal.plan();
        boolean jira = proposal.kind() == TaskMirror.Kind.JIRA;
        List<Omission> omissions = new ArrayList<>();
        Map<TaskId, String> ledger = new LinkedHashMap<>();
        Map<TaskId, String> unavailable = new HashMap<>();
        List<Created> created = new ArrayList<>();
        List<Reused> reused = new ArrayList<>();
        List<EnsuredLink> links = new ArrayList<>();
        Set<TaskId> attempted = new HashSet<>();

        for (TaskMirrorPlan.Exclusion x : plan.excluded()) {
            omissions.add(new Omission(OmissionKind.TASK, label(proposal, x.task()),
                    "in a prerequisite cycle: " + String.join(" -> ", x.titles())));
        }
        for (TaskMirrorPlan.DroppedEdge e : plan.droppedEdges()) {
            if (e.target() == null) {
                omissions.add(new Omission(OmissionKind.REFERENCE, label(proposal, e.source()),
                        "reference '" + e.reference() + "' omitted: " + e.reason()));
            } else {
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, label(proposal, e.source()) + " after "
                        + label(proposal, e.target()), e.reason()));
            }
        }
        for (TaskDocument doc : plan.order()) {
            if (effective.get(doc.id()) instanceof Disposition.Reuse reuse) {
                ledger.put(doc.id(), reuse.address());
                reused.add(new Reused(doc, reuse.address()));
            }
        }

        boolean halted = false;
        for (TaskDocument doc : plan.order()) {
            Disposition d = effective.get(doc.id());
            if (d instanceof Disposition.Reuse) {
                if (!jira && !plan.prerequisitesOf(doc.id()).isEmpty()) {
                    omissions.add(new Omission(OmissionKind.RELATIONSHIP, label(proposal, doc),
                            "reused GitHub issue: its dependency section was not checked or repaired "
                                    + "(dependencies are written only when an issue is created)"));
                }
                continue;
            }
            if (d instanceof Disposition.Skip skip) {
                unavailable.put(doc.id(), "skipped: " + skip.reason());
                omissions.add(new Omission(OmissionKind.TASK, label(proposal, doc), "skipped: " + skip.reason()));
                continue;
            }
            if (halted || stopped()) {
                halted = true;
                unavailable.put(doc.id(), "not started: the run was cancelled");
                omissions.add(new Omission(OmissionKind.TASK, label(proposal, doc),
                        "not started: the run was cancelled"));
                continue;
            }
            if (!attempted.add(doc.id())) continue;
            Destination dest = proposal.destinations().get(doc.id());
            List<TaskMirror.Dependency> dependencies = new ArrayList<>();
            if (!jira) {
                for (TaskDocument prerequisite : plan.prerequisitesOf(doc.id())) {
                    String address = ledger.get(prerequisite.id());
                    if (address != null) {
                        dependencies.add(new TaskMirror.Dependency(address, prerequisite.title()));
                    } else {
                        omissions.add(new Omission(OmissionKind.RELATIONSHIP, label(proposal, doc) + " after "
                                + label(proposal, prerequisite), "not listed in the new issue: the prerequisite is "
                                + unavailable.getOrDefault(prerequisite.id(), "unavailable")));
                    }
                }
            }
            Optional<JiraChoice> choice = jira ? Optional.ofNullable(choices.get(dest.project())) : Optional.empty();
            effects.progress().phase("Creating " + label(proposal, doc));
            CreateOutcome outcome;
            try {
                outcome = effects.creator().create(new CreateRequest(proposal.kind(), doc, dest, choice,
                        dependencies, effects.cancelled()));
            } catch (Exception e) {
                keepInterrupt(e);
                outcome = new CreateOutcome.Uncertain("the create did not return a result (" + message(e) + ")");
            }
            if (outcome instanceof CreateOutcome.Failed failed) {
                unavailable.put(doc.id(), "failed to create");
                failures.add(new Failure(Operation.CREATE, label(proposal, doc), failed.message(), false));
            } else if (outcome instanceof CreateOutcome.Uncertain uncertain) {
                unavailable.put(doc.id(), "of unknown state (the create may have succeeded)");
                failures.add(new Failure(Operation.CREATE, label(proposal, doc),
                        uncertain.message() + CHECK_TRACKER, true));
            } else {
                CreateOutcome.Created ok = (CreateOutcome.Created) outcome;
                ledger.put(doc.id(), ok.address());
                ok.propertyWarning().ifPresent(w -> failures.add(new Failure(Operation.PROPERTY,
                        label(proposal, doc), w + " (the issue " + ok.address() + " was created)", false)));
                Optional<String> recordProblem = record(proposal, doc, ok.address(), failures);
                created.add(new Created(doc, ok.address(), ok.propertyWarning(), recordProblem));
            }
        }

        if (jira) {
            ensureLinks(proposal, ledger, unavailable, omissions, failures, links);
        }
        String message = halted || stopped() ? "Cancelled part-way; completed work was kept and nothing was rolled back."
                : "Done.";
        return new Result(Status.COMPLETED, message, created, reused, omissions, failures, links);
    }

    /** Records a confirmed address; the ledger already holds it, so no outcome can lose it. */
    private Optional<String> record(Proposal proposal, TaskDocument doc, String address, List<Failure> failures) {
        effects.progress().phase("Recording " + address + " on " + label(proposal, doc));
        boolean interrupted = Thread.interrupted();
        String problem = null;
        try {
            RecordOutcome outcome = effects.recorder().record(doc.id(), address);
            if (outcome instanceof RecordOutcome.Refused refused) {
                problem = refused.message();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) interrupted = true;
            problem = message(e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (problem == null) return Optional.empty();
        String advice = "the issue " + address + " WAS created but could not be recorded on the task (" + problem
                + "); add exactly that address to the task's `mirrors` by hand";
        failures.add(new Failure(Operation.RECORD, label(proposal, doc), advice, false));
        return Optional.of(advice);
    }

    private void ensureLinks(Proposal proposal, Map<TaskId, String> ledger, Map<TaskId, String> unavailable,
            List<Omission> omissions, List<Failure> failures, List<EnsuredLink> links) {
        TaskMirrorPlan plan = proposal.plan();
        Set<String> pairs = new HashSet<>();
        boolean halted = false;
        for (TaskMirrorPlan.Edge edge : plan.edges()) {
            TaskDocument pre = edge.prerequisite();
            TaskDocument dep = edge.dependent();
            String subject = label(proposal, pre) + " blocks " + label(proposal, dep);
            String preAddress = ledger.get(pre.id());
            String depAddress = ledger.get(dep.id());
            if (preAddress == null || depAddress == null) {
                TaskDocument missing = preAddress == null ? pre : dep;
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, subject, "no Jira issue for "
                        + label(proposal, missing) + ": " + unavailable.getOrDefault(missing.id(), "unavailable")));
                continue;
            }
            TaskMirror.JiraAddress preJira = TaskMirror.jiraAddress(preAddress);
            TaskMirror.JiraAddress depJira = TaskMirror.jiraAddress(depAddress);
            String endpoints = " (" + preAddress + " and " + depAddress + ")";
            if (!(preJira instanceof TaskMirror.JiraAddress.Usable p)
                    || !(depJira instanceof TaskMirror.JiraAddress.Usable q)) {
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, subject,
                        "an endpoint address cannot be linked" + endpoints));
                continue;
            }
            if (!p.site().equals(q.site())) {
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, subject,
                        "the endpoints are on different Jira sites, never linked" + endpoints));
                continue;
            }
            if (p.key().equals(q.key())) {
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, subject,
                        "both tasks map to the same Jira issue, so a link to itself is impossible" + endpoints));
                continue;
            }
            Destination preDest = proposal.destinations().get(pre.id());
            Destination depDest = proposal.destinations().get(dep.id());
            if (preDest == null || !p.site().equals(preDest.jiraSite())
                    || depDest == null || !q.site().equals(depDest.jiraSite())) {
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, subject,
                        "an endpoint's project has no usable connection for the address's site (" + p.site()
                                + ")" + endpoints));
                continue;
            }
            if (!pairs.add(p.site() + "\n" + p.key() + "\n" + q.key())) continue;
            if (halted || stopped()) {
                halted = true;
                omissions.add(new Omission(OmissionKind.RELATIONSHIP, subject,
                        "not attempted: the run was cancelled"));
                continue;
            }
            effects.progress().phase("Linking " + p.key() + " blocks " + q.key());
            LinkOutcome outcome;
            try {
                outcome = effects.linker().link(new LinkRequest(pre, preDest, preAddress, p.key(), dep, depDest,
                        depAddress, q.key(), p.site()));
            } catch (Exception e) {
                keepInterrupt(e);
                outcome = new LinkOutcome.Uncertain("the link request did not return a result (" + message(e) + ")");
            }
            if (outcome instanceof LinkOutcome.Linked) {
                links.add(new EnsuredLink(pre, dep, false));
            } else if (outcome instanceof LinkOutcome.AlreadyPresent present) {
                links.add(new EnsuredLink(pre, dep, true));
                present.lookupProblem().ifPresent(m -> failures.add(new Failure(Operation.LOOKUP, subject, m, false)));
            } else if (outcome instanceof LinkOutcome.LookupFailed lookup) {
                failures.add(new Failure(Operation.LOOKUP, subject, lookup.message() + " (no link was created)", false));
            } else if (outcome instanceof LinkOutcome.Failed failed) {
                failures.add(new Failure(Operation.LINK, subject, failed.message(), false));
            } else {
                failures.add(new Failure(Operation.LINK, subject,
                        ((LinkOutcome.Uncertain) outcome).message() + CHECK_TRACKER, true));
            }
        }
    }
}

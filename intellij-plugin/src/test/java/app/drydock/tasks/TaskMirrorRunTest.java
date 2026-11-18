package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskMirrorPlan.Disposition;
import app.drydock.tasks.TaskMirrorRun.Accepted;
import app.drydock.tasks.TaskMirrorRun.Consent;
import app.drydock.tasks.TaskMirrorRun.CreateOutcome;
import app.drydock.tasks.TaskMirrorRun.CreateRequest;
import app.drydock.tasks.TaskMirrorRun.Destination;
import app.drydock.tasks.TaskMirrorRun.Effects;
import app.drydock.tasks.TaskMirrorRun.JiraChoice;
import app.drydock.tasks.TaskMirrorRun.LinkOutcome;
import app.drydock.tasks.TaskMirrorRun.LinkRequest;
import app.drydock.tasks.TaskMirrorRun.Operation;
import app.drydock.tasks.TaskMirrorRun.Proposal;
import app.drydock.tasks.TaskMirrorRun.RecordOutcome;
import app.drydock.tasks.TaskMirrorRun.Result;
import app.drydock.tasks.TaskMirrorRun.Revalidation;
import app.drydock.tasks.TaskMirrorRun.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskMirrorRunTest {

    private static final String SITE = "https://acme.atlassian.net";

    private final RepositoryId repo = RepositoryId.newId();
    private final List<TaskDocument> docs = new ArrayList<>();
    private final Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
    private final Map<TaskId, Disposition> overrides = new LinkedHashMap<>();
    private final Map<TaskId, Destination> destinations = new LinkedHashMap<>();
    private final Map<String, JiraChoice> choices = new LinkedHashMap<>();

    private final List<String> log = new ArrayList<>();
    private final List<CreateRequest> creates = new ArrayList<>();
    private final List<LinkRequest> linkRequests = new ArrayList<>();
    private final List<Map<String, JiraChoice>> remembered = new ArrayList<>();
    private int finished;

    private TaskMirror.Kind kind = TaskMirror.Kind.GITHUB;
    private Consent consent;
    private Revalidation revalidation = new Revalidation.Unchanged();
    private Function<CreateRequest, CreateOutcome> creator = this::defaultCreate;
    private Function<TaskId, RecordOutcome> recorder = id -> new RecordOutcome.Committed();
    private Function<LinkRequest, LinkOutcome> linker = r -> new LinkOutcome.Linked();
    private BooleanSupplier cancelled = () -> false;

    @AfterEach
    void progressAlwaysFinishes() {
        assertEquals(1, finished, "progress must finish exactly once");
    }

    private void add(String id, String... after) { addWith(id, TaskStatus.OPEN, List.of(), after); }

    private void addWith(String id, TaskStatus status, List<String> mirrors, String... after) {
        TaskDocument d = new TaskDocument(TaskId.of(id), repo, "T " + id, status, null, Optional.empty(),
                List.of(after), List.of(), mirrors, Optional.empty(), "", "");
        docs.add(d);
        byId.put(d.id(), d);
        destinations.put(d.id(), new Destination("proj", "dest", kind == TaskMirror.Kind.JIRA ? SITE : null));
    }

    private CreateOutcome defaultCreate(CreateRequest r) {
        String id = r.task().id().text();
        return new CreateOutcome.Created(kind == TaskMirror.Kind.GITHUB
                ? "https://github.com/o/r/issues/" + Math.abs(id.hashCode() % 100000)
                : SITE + "/browse/K-" + Math.abs(id.hashCode() % 100000), Optional.empty());
    }

    private Result run(String root) {
        TaskSnapshot s = new TaskSnapshot(docs, List.of(repo), byId, Map.of(), List.of(), List.of());
        TaskGraph g = TaskGraph.of(s, r -> "main");
        Map<TaskId, Disposition> disp = new LinkedHashMap<>();
        for (TaskDocument d : docs) {
            disp.put(d.id(), overrides.getOrDefault(d.id(), Disposition.forMirrors(d.mirrors(), kind)));
        }
        TaskMirrorPlan plan = TaskMirrorPlan.of(s, g, TaskId.of(root)).withDispositions(disp);
        Proposal proposal = new Proposal(kind, plan, destinations, choices);
        Consent answer = consent != null ? consent
                : new Consent.Accept(new Accepted(Map.of(), choices));
        Effects effects = new Effects(
                p -> { log.add("consent"); return answer; },
                a -> { log.add("revalidate"); return revalidation; },
                m -> { log.add("remember"); remembered.add(m); },
                r -> { log.add("create " + r.task().id().text()); creates.add(r); return creator.apply(r); },
                (id, address) -> {
                    log.add("record " + id.text());
                    return recorder.apply(id);
                },
                r -> { log.add("link " + r.prerequisiteKey() + ">" + r.dependentKey()); linkRequests.add(r);
                    return linker.apply(r); },
                () -> cancelled.getAsBoolean(),
                new TaskMirrorRun.Progress() {
                    public void phase(String text) { }
                    public void finish() { finished++; }
                });
        return new TaskMirrorRun(effects).run(proposal);
    }

    private List<String> logged(String prefix) { return log.stream().filter(l -> l.startsWith(prefix)).toList(); }

    private void assertNoEffects() {
        assertEquals(List.of(), logged("create"));
        assertEquals(List.of(), logged("record"));
        assertEquals(List.of(), logged("link"));
        assertEquals(List.of(), remembered);
    }

    private void useJira() {
        kind = TaskMirror.Kind.JIRA;
        choices.put("proj", new JiraChoice("K", "Task"));
    }

    private static String jiraUrl(String key) { return SITE + "/browse/" + key; }

    // ---- ordering and GitHub bodies

    @Test
    void chainCreatesInOrderWithDirectDependenciesOnly() {
        add("root", "middle");
        add("middle", "base");
        add("base");
        add("unrelated");
        Result r = run("root");
        assertEquals(List.of("create base", "record base", "create middle", "record middle", "create root",
                "record root"), log.subList(2, log.size()));
        assertEquals(List.of(), creates.get(0).dependencies());
        assertEquals(1, creates.get(1).dependencies().size());
        assertEquals(created(r, "base"), creates.get(1).dependencies().get(0).address());
        assertEquals(List.of(new TaskMirror.Dependency(created(r, "middle"), "T middle")),
                creates.get(2).dependencies());
        assertTrue(r.clean());
        assertEquals(3, r.created().size());
    }

    private static String created(Result r, String id) {
        return r.created().stream().filter(c -> c.task().id().text().equals(id)).findFirst().orElseThrow().address();
    }

    @Test
    void diamondOrderAndDuplicateReferencesGiveOneDependency() {
        add("root", "right", "left", "main:left", "left");
        add("left", "base");
        add("right", "base");
        add("base");
        Result r = run("root");
        assertEquals(List.of("create base", "create left", "create right", "create root"), logged("create"));
        assertEquals(2, creates.get(3).dependencies().size());
        assertEquals(List.of("T left", "T right"), creates.get(3).dependencies().stream()
                .map(TaskMirror.Dependency::title).toList());
        assertTrue(r.clean());
    }

    @Test
    void thousandChainCreatesPrerequisitesFirstAllStatuses() {
        int n = 1000;
        TaskStatus[] statuses = TaskStatus.values();
        IntStream.range(0, n).forEach(i -> {
            String after = i + 1 < n ? "t" + (i + 1) : null;
            addWith("t" + i, statuses[i % statuses.length], List.of(), after == null ? new String[0]
                    : new String[] {after});
        });
        Result r = run("t0");
        List<String> order = logged("create");
        assertEquals(n, order.size());
        assertEquals("create t" + (n - 1), order.get(0));
        assertEquals("create t0", order.get(n - 1));
        assertTrue(statuses.length >= 5);
        assertTrue(r.clean());
    }

    @Test
    void reusedMiddleKeepsDeeperCreatesAndIsNeverRepaired() {
        String reusedUrl = "https://github.com/o/r/issues/9";
        add("root", "middle");
        addWith("middle", TaskStatus.DONE, List.of(jiraUrl("X-1"), reusedUrl), "base");
        add("base");
        Result r = run("root");
        assertEquals(List.of("create base", "create root"), logged("create"));
        assertEquals(List.of("record base", "record root"), logged("record"));
        assertEquals(reusedUrl, creates.get(1).dependencies().get(0).address());
        assertEquals(1, r.reused().size());
        assertFalse(r.clean());
        assertTrue(r.omissions().stream().anyMatch(o -> o.subject().contains("middle")
                && o.reason().contains("not checked or repaired")));
    }

    @Test
    void otherSystemMirrorStillCreates() {
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("X-1")));
        run("root");
        assertEquals(List.of("create root"), logged("create"));
    }

    @Test
    void failedSkippedAndUncertainPrerequisitesLeaveMissingRelationships() {
        add("root", "a", "b", "c");
        add("a");
        add("b");
        add("c");
        overrides.put(TaskId.of("a"), new Disposition.Skip("no checkout"));
        creator = req -> switch (req.task().id().text()) {
            case "b" -> new CreateOutcome.Failed("gh said no");
            case "c" -> new CreateOutcome.Uncertain("timed out");
            default -> defaultCreate(req);
        };
        Result r = run("root");
        assertEquals(List.of(), creates.get(creates.size() - 1).dependencies());
        assertEquals(List.of("record root"), logged("record"));
        assertEquals(3, r.omissions().stream().filter(o -> o.kind() == TaskMirrorRun.OmissionKind.RELATIONSHIP)
                .count());
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("no checkout")));
        TaskMirrorRun.Failure unknown = r.failures().stream().filter(TaskMirrorRun.Failure::uncertain)
                .findFirst().orElseThrow();
        assertEquals(Operation.CREATE, unknown.operation());
        assertTrue(unknown.message().contains("Check the tracker before retrying"));
        assertEquals(1, r.created().size());
        assertFalse(r.clean());
    }

    @Test
    void createExceptionIsUncertainAndNotRetried() {
        add("root");
        creator = req -> { throw new IllegalStateException("boom"); };
        Result r = run("root");
        assertEquals(1, logged("create").size());
        assertTrue(r.failures().get(0).uncertain());
        assertEquals(List.of(), logged("record"));
    }

    // ---- refusals and consent

    @Test
    void cyclicRootRefusesBeforeAnyCallback() {
        add("root", "a");
        add("a", "root");
        Result r = run("root");
        assertEquals(Status.REFUSED, r.status());
        assertTrue(r.message().contains("cycle"));
        assertEquals(List.of(), log);
    }

    @Test
    void selfCycleAndMissingRootRefuseWithoutCallbacks() {
        add("root", "root");
        assertEquals(Status.REFUSED, run("root").status());
        assertEquals(Status.REFUSED, run("nope").status());
        assertEquals(List.of(), log);
        assertEquals(2, finished);
        finished = 1;
    }

    @Test
    void rootDownstreamOfCycleDoesNotRefuseAndSkipsOnlyMembers() {
        add("root", "x", "ok");
        add("x", "y");
        add("y", "x");
        add("ok");
        Result r = run("root");
        assertEquals(Status.COMPLETED, r.status());
        assertEquals(List.of("create ok", "create root"), logged("create"));
        assertEquals(2, r.omissions().stream().filter(o -> o.kind() == TaskMirrorRun.OmissionKind.TASK).count());
        assertFalse(r.clean());
    }

    @Test
    void brokenReferenceOmitsOnlyItsEdge() {
        add("root", "ghost", "base");
        add("base");
        Result r = run("root");
        assertEquals(List.of("create base", "create root"), logged("create"));
        assertTrue(r.omissions().stream().anyMatch(o -> o.kind() == TaskMirrorRun.OmissionKind.REFERENCE));
        assertFalse(r.clean());
    }

    @Test
    void consentCancelHasNoEffects() {
        add("root", "a");
        add("a");
        consent = new Consent.Cancel();
        Result r = run("root");
        assertEquals(Status.CANCELLED, r.status());
        assertEquals(List.of("consent"), log);
        assertNoEffects();
    }

    @Test
    void consentRefusalAndFailureHaveNoEffects() {
        add("root");
        consent = new Consent.Refuse("nope");
        assertEquals("nope", run("root").message());
        assertEquals(List.of("consent"), log);
    }

    @Test
    void rootThatNeedsCreationWithoutDestinationRefusesAndCreatesNoOrphans() {
        add("root", "a");
        add("a");
        overrides.put(TaskId.of("root"), new Disposition.Skip("no local checkout"));
        Result r = run("root");
        assertEquals(Status.REFUSED, r.status());
        assertTrue(r.message().contains("no local checkout"));
        assertEquals(List.of(), log);
    }

    @Test
    void acceptedPlanMayOnlySkipCreates() {
        add("root", "a");
        add("a");
        consent = new Consent.Accept(new Accepted(Map.of(TaskId.of("a"), new Disposition.Reuse("x")), choices));
        assertEquals(Status.REFUSED, run("root").status());
        assertNoEffects();
        assertFalse(log.contains("revalidate"));
    }

    // ---- revalidation and remembered choices

    @Test
    void revalidationStopWritesNothingAndRemembersNothing() {
        useJira();
        add("root", "a");
        add("a");
        revalidation = new Revalidation.Stop("title changed");
        Result r = run("root");
        assertEquals(Status.STOPPED, r.status());
        assertTrue(r.message().contains("title changed"));
        assertEquals(List.of("consent", "revalidate"), log);
        assertNoEffects();
    }

    @Test
    void choicesRememberedOnlyAfterRevalidationAndSkippedProjectsNot() {
        useJira();
        add("root", "a");
        add("a");
        destinations.put(TaskId.of("a"), new Destination("other", "dest", SITE));
        choices.put("other", new JiraChoice("O", "Bug"));
        consent = new Consent.Accept(new Accepted(Map.of(TaskId.of("a"), new Disposition.Skip("skip project")),
                choices));
        Result r = run("root");
        assertEquals("consent", log.get(0));
        assertEquals("revalidate", log.get(1));
        assertEquals("remember", log.get(2));
        assertEquals(Map.of("proj", new JiraChoice("K", "Task")), remembered.get(0));
        assertEquals(List.of("create root"), logged("create"));
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("skip project")));
    }

    @Test
    void newlyFoundMirrorNarrowsToReuseWithoutRememberingUnneededChoice() {
        useJira();
        add("root", "a");
        add("a");
        destinations.put(TaskId.of("a"), new Destination("other", "dest", SITE));
        choices.put("other", new JiraChoice("O", "Bug"));
        revalidation = new Revalidation.Narrow(Map.of(TaskId.of("a"), jiraUrl("O-7")));
        Result r = run("root");
        assertEquals(List.of("create root"), logged("create"));
        assertEquals(Map.of("proj", new JiraChoice("K", "Task")), remembered.get(0));
        assertEquals(1, r.reused().size());
        assertEquals(List.of("link O-7>" + r.created().get(0).address().substring(r.created().get(0).address()
                .lastIndexOf('/') + 1)), logged("link"));
    }

    @Test
    void narrowingToAnotherSiteStops() {
        useJira();
        add("root", "a");
        add("a");
        revalidation = new Revalidation.Narrow(Map.of(TaskId.of("a"), "https://other.example.com/browse/O-7"));
        Result r = run("root");
        assertEquals(Status.STOPPED, r.status());
        assertNoEffects();
    }

    @Test
    void rootWithoutAcceptedJiraChoiceRefusesWholeRun() {
        useJira();
        add("root", "a");
        add("a");
        consent = new Consent.Accept(new Accepted(Map.of(), Map.of()));
        Result r = run("root");
        assertEquals(Status.REFUSED, r.status());
        assertEquals(List.of("consent"), log);
    }

    @Test
    void reusedRootNeedsNoChoiceAndPrerequisiteWithoutChoiceIsSkipped() {
        useJira();
        choices.clear();
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("K-1")), "a");
        add("a");
        consent = new Consent.Accept(new Accepted(Map.of(), Map.of()));
        Result r = run("root");
        assertEquals(Status.COMPLETED, r.status());
        assertEquals(List.of(), logged("create"));
        assertEquals(List.of(), remembered);
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("no Jira project key")));
    }

    // ---- Jira links

    @Test
    void jiraLinksDirectEdgesOnlyAcrossReusedEndpointsAndNeverTouchTheDescription() {
        useJira();
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("K-3")), "middle", "base", "middle");
        addWith("middle", TaskStatus.REVIEW, List.of(jiraUrl("K-2")), "base");
        addWith("base", TaskStatus.DROPPED, List.of(jiraUrl("K-1")));
        Result r = run("root");
        assertEquals(List.of(), logged("create"));
        assertEquals(List.of(), logged("record"));
        assertEquals(List.of("link K-1>K-2", "link K-1>K-3", "link K-2>K-3"), logged("link").stream().sorted()
                .toList());
        assertEquals(3, r.links().size());
        assertEquals(List.of(), creates.stream().flatMap(c -> c.dependencies().stream()).toList());
        assertTrue(r.clean());
    }

    @Test
    void jiraCreatesGetNoGithubDependenciesAndLinkAfterNodes() {
        useJira();
        add("root", "a");
        add("a");
        Result r = run("root");
        assertTrue(creates.stream().allMatch(c -> c.dependencies().isEmpty()));
        assertTrue(creates.stream().allMatch(c -> c.choice().equals(Optional.of(new JiraChoice("K", "Task")))));
        int lastCreate = log.lastIndexOf("record root");
        assertTrue(log.indexOf(logged("link").get(0)) > lastCreate);
        assertEquals(1, r.links().size());
    }

    @Test
    void twoTasksOnTheSameRemotePairGetOneAttempt() {
        useJira();
        addWith("a1", TaskStatus.OPEN, List.of(jiraUrl("K-1")));
        addWith("a2", TaskStatus.OPEN, List.of(jiraUrl("K-1")));
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("K-9")), "a1", "a2");
        run("root");
        assertEquals(List.of("link K-1>K-9"), logged("link"));
    }

    @Test
    void sameAddressStaleSiteAndCrossSiteAreSkippedNamingBothEndpoints() {
        useJira();
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("K-1")), "same", "stale", "cross", "noconn");
        addWith("same", TaskStatus.OPEN, List.of(jiraUrl("K-1")));
        addWith("stale", TaskStatus.OPEN, List.of(SITE + "/jira/browse/K-5"));
        addWith("cross", TaskStatus.OPEN, List.of("https://other.example.com/browse/K-6"));
        addWith("noconn", TaskStatus.OPEN, List.of(jiraUrl("K-7")));
        destinations.put(TaskId.of("noconn"), new Destination("proj2", "dest", null));
        Result r = run("root");
        assertEquals(List.of(), logged("link"));
        assertEquals(4, r.omissions().size());
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("itself")
                && o.reason().contains(jiraUrl("K-1"))));
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("different Jira sites")
                && o.reason().contains("https://other.example.com/browse/K-6")));
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("different Jira sites")
                && o.reason().contains(SITE + "/jira/browse/K-5")));
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("no usable connection")));
        assertEquals(4, r.reused().size() - 1);
    }

    @Test
    void propertyWarningAndRecordFailureStillLinkTheConfirmedAddress() {
        useJira();
        add("root", "a");
        add("a");
        creator = req -> new CreateOutcome.Created(jiraUrl("K-" + req.task().id().text().length() + "0"),
                req.task().id().text().equals("a") ? Optional.of("property failed") : Optional.empty());
        recorder = id -> id.text().equals("root") ? new RecordOutcome.Refused("store said no")
                : new RecordOutcome.Committed();
        Result r = run("root");
        assertEquals(1, logged("link").size());
        assertEquals(1, r.links().size());
        assertTrue(r.failures().stream().anyMatch(f -> f.operation() == Operation.PROPERTY));
        assertTrue(r.failures().stream().anyMatch(f -> f.operation() == Operation.RECORD
                && f.message().contains(jiraUrl("K-40"))));
        assertFalse(r.clean());
    }

    @Test
    void linkOutcomesAreDistinctAndIndependent() {
        useJira();
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("K-9")), "p1", "p2", "p3", "p4");
        addWith("p1", TaskStatus.OPEN, List.of(jiraUrl("K-1")));
        addWith("p2", TaskStatus.OPEN, List.of(jiraUrl("K-2")));
        addWith("p3", TaskStatus.OPEN, List.of(jiraUrl("K-3")));
        addWith("p4", TaskStatus.OPEN, List.of(jiraUrl("K-4")));
        linker = req -> switch (req.prerequisiteKey()) {
            case "K-1" -> new LinkOutcome.LookupFailed("403");
            case "K-2" -> new LinkOutcome.Failed("no Blocks type");
            case "K-3" -> new LinkOutcome.Uncertain("timeout");
            default -> new LinkOutcome.AlreadyPresent(Optional.of("other lookup failed"));
        };
        Result r = run("root");
        assertEquals(4, logged("link").size());
        assertEquals(List.of(Operation.LOOKUP, Operation.LINK, Operation.LINK, Operation.LOOKUP),
                r.failures().stream().map(TaskMirrorRun.Failure::operation).toList());
        assertTrue(r.failures().get(2).uncertain());
        assertTrue(r.failures().get(2).message().contains("Check the tracker"));
        assertEquals(1, r.links().size());
        assertTrue(r.links().get(0).alreadyPresent());
    }

    @Test
    void linkRequestCarriesTheEndpointsOwnProjects() {
        useJira();
        addWith("root", TaskStatus.OPEN, List.of(jiraUrl("K-9")), "a");
        addWith("a", TaskStatus.OPEN, List.of(jiraUrl("K-1")));
        destinations.put(TaskId.of("a"), new Destination("other", "dest", SITE));
        run("root");
        LinkRequest req = linkRequests.get(0);
        assertEquals("other", req.prerequisiteDestination().project());
        assertEquals("proj", req.dependentDestination().project());
        assertEquals(SITE, req.site());
        assertEquals("K-1", req.prerequisiteKey());
        assertEquals("K-9", req.dependentKey());
    }

    // ---- ledger, record failure, cancellation

    @Test
    void recordRefusalExceptionAndTimeoutKeepTheAddressAndNeverRecreate() {
        add("root", "a", "b", "c");
        add("a");
        add("b");
        add("c");
        recorder = id -> switch (id.text()) {
            case "a" -> new RecordOutcome.Refused("refused");
            case "b" -> throw new IllegalStateException("exploded");
            case "c" -> sneaky(new TimeoutException("timed out"));
            default -> new RecordOutcome.Committed();
        };
        Result r = run("root");
        assertEquals(4, logged("create").size());
        assertEquals(4, r.created().size());
        assertEquals(3, creates.get(3).dependencies().size());
        assertEquals(3, r.failures().stream().filter(f -> f.operation() == Operation.RECORD).count());
        r.created().stream().filter(c -> !c.task().id().text().equals("root")).forEach(c -> {
            assertTrue(c.recordProblem().orElseThrow().contains(c.address()));
            assertTrue(c.recordProblem().get().contains("`mirrors`"));
        });
        assertFalse(r.clean());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneaky(Throwable t) throws T { throw (T) t; }

    @Test
    void cancellationAfterPrerequisiteRecordsItAndStartsNothingFurther() {
        add("root", "a");
        add("a");
        cancelled = () -> log.contains("create a");
        Result r = run("root");
        assertEquals(List.of("create a"), logged("create"));
        assertEquals(List.of("record a"), logged("record"));
        assertEquals(1, r.created().size());
        assertTrue(r.omissions().stream().anyMatch(o -> o.reason().contains("cancelled")));
        assertEquals(Status.COMPLETED, r.status());
        assertTrue(r.message().contains("nothing was rolled back"));
        assertFalse(r.clean());
    }

    @Test
    void cancellationStopsJiraLinksButKeepsRecords() {
        useJira();
        add("root", "a");
        add("a");
        cancelled = () -> log.contains("record root");
        Result r = run("root");
        assertEquals(2, logged("record").size());
        assertEquals(List.of(), logged("link"));
        assertEquals(1, r.omissions().size());
    }

    @Test
    void cancellationBeforeConsentAnswerWritesNothing() {
        add("root");
        cancelled = () -> true;
        Result r = run("root");
        assertEquals(Status.CANCELLED, r.status());
        assertNoEffects();
    }

    @Test
    void interruptAfterCreateStillRecordsAndKeepsTheFlag() {
        add("root", "a");
        add("a");
        creator = req -> {
            CreateOutcome o = defaultCreate(req);
            Thread.currentThread().interrupt();
            return o;
        };
        try {
            Result r = run("root");
            assertEquals(List.of("record a"), logged("record"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, r.created().size());
            assertFalse(r.clean());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void createRequestExposesCancellationToTheWriter() {
        add("root");
        boolean[] seen = new boolean[1];
        creator = req -> { seen[0] = req.cancelled() != null; return defaultCreate(req); };
        run("root");
        assertTrue(seen[0]);
    }

    @Test
    void mixedAggregateIsNeverWholeSuccess() {
        add("root", "a", "b");
        add("a");
        add("b");
        overrides.put(TaskId.of("a"), new Disposition.Skip("misconfigured"));
        Result r = run("root");
        assertEquals(Status.COMPLETED, r.status());
        assertEquals(2, r.created().size());
        assertEquals(1, r.omissions().stream().filter(o -> o.kind() == TaskMirrorRun.OmissionKind.TASK).count());
        assertFalse(r.clean());
    }
}

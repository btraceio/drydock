package app.drydock.intellij.tasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.git.JiraConnection;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskMirrorPlan;
import app.drydock.tasks.TaskMirrorPlan.Disposition;
import app.drydock.tasks.TaskMirrorRun;
import app.drydock.tasks.TaskMirrorRun.Destination;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskStatus;

import com.intellij.notification.NotificationType;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mirror flow's project resolution ({@code TaskMirroring#resolveShared}):
 * a task whose repository id the shared list carries resolves directly, a
 * task carrying the install catalog's id for the same checkout resolves
 * through the shared list's root, and an id neither registry maps resolves
 * to nothing -- the caller refuses. Pinned through the package-private pure
 * helper: no platform types, no mocking, no I/O.
 */
class TaskMirroringResolveTest {

    private static SharedProject shared(RepositoryId id, String root) {
        return SharedProject.local(id, Path.of(root), "repo", Instant.EPOCH);
    }

    private static Repository install(RepositoryId id, String root) {
        return new Repository(id, Path.of(root), "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
    }

    @Test
    void sharedListIdResolvesDirectly() {
        RepositoryId id = RepositoryId.newId(); // the id the IDE dialog stored
        assertEquals(Optional.of(shared(id, "/shared/root")), TaskMirroring.resolveShared(
                List.of(shared(id, "/shared/root")),
                List.of(install(RepositoryId.newId(), "/install/root")),
                id));
    }

    @Test
    void installCatalogIdResolvesThroughTheSharedRoot() {
        RepositoryId sharedId = RepositoryId.newId(); // the shared registry's id
        RepositoryId installId = RepositoryId.newId(); // the id a task_create call stored
        assertEquals(Optional.of(shared(sharedId, "/same/root")), TaskMirroring.resolveShared(
                List.of(shared(sharedId, "/same/root")),
                List.of(install(installId, "/same/root")),
                installId));
    }

    @Test
    void remoteSharedProjectResolvesToo() {
        // A Jira mirror talks to the site alone; the remote check is the caller's.
        RepositoryId sharedId = RepositoryId.newId();
        RepositoryId installId = RepositoryId.newId();
        SharedProject remote = new SharedProject(sharedId, Path.of("/placeholder"), "remote",
                Instant.EPOCH, Optional.of(new SshRemote("host", "/remote/path")));
        assertEquals(Optional.of(remote), TaskMirroring.resolveShared(
                List.of(remote),
                List.of(install(installId, "/placeholder")),
                installId));
    }

    @Test
    void rootMustMatchForTheInstallIdToResolve() {
        RepositoryId sharedId = RepositoryId.newId();
        RepositoryId installId = RepositoryId.newId();
        assertTrue(TaskMirroring.resolveShared(
                List.of(shared(sharedId, "/other/root")),
                List.of(install(installId, "/same/root")),
                installId).isEmpty());
    }

    @Test
    void unknownIdResolvesToNothing() {
        assertTrue(TaskMirroring.resolveShared(
                List.of(shared(RepositoryId.newId(), "/shared/root")),
                List.of(install(RepositoryId.newId(), "/install/root")),
                RepositoryId.newId()).isEmpty());
    }

    @Test
    void theResolvedSharedIdKeysSettingsAndTheStoredRepositoryIdIsUntouched() {
        RepositoryId sharedId = RepositoryId.newId();
        RepositoryId storedInstallId = RepositoryId.newId();
        Optional<SharedProject> resolved = TaskMirroring.resolveShared(
                List.of(shared(sharedId, "/same/root")),
                List.of(install(storedInstallId, "/same/root")),
                storedInstallId);
        assertEquals(sharedId, resolved.orElseThrow().id());
        assertFalse(resolved.orElseThrow().id().equals(storedInstallId));
    }

    private static DelayedTaskRow row(List<String> mirrors) {
        return new DelayedTaskRow(new TaskState.TaskView(TaskId.of("root"), TaskState.Presentation.READY,
                List.of(), List.of()), "Root", RepositoryId.newId(), Optional.empty(), false, List.of(), mirrors);
    }

    @Test
    void availabilityStillRequiresNoMirrorOfTheRequestedSystem() {
        String github = "https://github.com/o/r/issues/1";
        String jira = "https://acme.atlassian.net/browse/K-1";
        assertTrue(TaskMirroring.offersGitHub(row(List.of())));
        assertTrue(TaskMirroring.offersJira(row(List.of())));
        assertFalse(TaskMirroring.offersGitHub(row(List.of(github))));
        assertTrue(TaskMirroring.offersJira(row(List.of(github))));
        assertTrue(TaskMirroring.offersGitHub(row(List.of(jira))));
        assertFalse(TaskMirroring.offersJira(row(List.of(jira))));
    }

    @Test
    void githubCreationNeedsALocalCheckoutAndAValidOverrideButNeverAnEnableFlag() {
        assertTrue(TaskMirroring.githubCreationProblem("P", true, false, Optional.empty()).isPresent());
        assertTrue(TaskMirroring.githubCreationProblem("P", false, false, Optional.empty()).isPresent());
        assertTrue(TaskMirroring.githubCreationProblem("P", false, true, Optional.of("--help")).isPresent());
        assertTrue(TaskMirroring.githubCreationProblem("P", false, true, Optional.empty()).isEmpty());
        assertTrue(TaskMirroring.githubCreationProblem("P", false, true, Optional.of("o/r")).isEmpty());
        assertTrue(TaskMirroring.githubCreationProblem("P", false, true, Optional.of("ghe.example.com/o/r"))
                .isEmpty());
    }

    private static JiraProjectSettings jiraSettings(boolean enabled) {
        return new JiraProjectSettings(enabled, JiraConnection.Deployment.CLOUD, "https://acme.atlassian.net",
                Optional.of("me@example.com"));
    }

    @Test
    void jiraCreationNeedsEnabledValidSettingsAndASecretButAcceptsRemoteProjects() {
        assertTrue(TaskMirroring.jiraCreationProblem("P", jiraSettings(false), true).isPresent());
        assertTrue(TaskMirroring.jiraCreationProblem("P", jiraSettings(true), false).isPresent());
        assertTrue(TaskMirroring.jiraCreationProblem("P", jiraSettings(true), true).isEmpty());
        assertFalse(TaskMirroring.jiraCreationProblem("P", jiraSettings(true), false).orElse("")
                .contains("secret-token"));
    }

    // ---- revalidation and report over real plan values -------------------

    private final RepositoryId repo = RepositoryId.newId();

    private TaskDocument doc(String id, String body, List<String> mirrors, String... after) {
        return new TaskDocument(TaskId.of(id), repo, "T " + id, TaskStatus.OPEN, null, Optional.empty(),
                List.of(after), List.of(), mirrors, Optional.empty(), "", body);
    }

    private TaskMirrorRun.Proposal proposal(List<TaskDocument> docs, Map<TaskId, Disposition> overrides,
                                            Destination destination) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        docs.forEach(d -> byId.put(d.id(), d));
        TaskSnapshot snapshot = new TaskSnapshot(docs, List.of(repo), byId, Map.of(), List.of(), List.of());
        TaskMirrorPlan plan = TaskMirrorPlan.of(snapshot, TaskGraph.of(snapshot, r -> "main"), TaskId.of("root"));
        Map<TaskId, Disposition> dispositions = new LinkedHashMap<>();
        Map<TaskId, Destination> destinations = new LinkedHashMap<>();
        for (TaskDocument d : plan.order()) {
            dispositions.put(d.id(), overrides.getOrDefault(d.id(),
                    Disposition.forMirrors(d.mirrors(), TaskMirror.Kind.GITHUB)));
            destinations.put(d.id(), destination);
        }
        return new TaskMirrorRun.Proposal(TaskMirror.Kind.GITHUB, plan.withDispositions(dispositions),
                destinations, Map.of());
    }

    private static TaskMirrorRun.Accepted acceptedOf(TaskMirrorRun.Proposal proposal) {
        Map<TaskId, Disposition> all = new LinkedHashMap<>();
        proposal.plan().order().forEach(d -> all.put(d.id(), proposal.plan().dispositionOf(d.id()).orElseThrow()));
        return new TaskMirrorRun.Accepted(all, Map.of());
    }

    private static final Destination DEST = new Destination("p", "GitHub repository github.com/o/r", null);
    private static final String ISSUE = "https://github.com/o/r/issues/7";

    @Test
    void anUnchangedFreshPlanRevalidatesAsUnchanged() {
        List<TaskDocument> docs = List.of(doc("base", "b", List.of()), doc("root", "r", List.of(), "base"));
        TaskMirrorRun.Proposal original = proposal(docs, Map.of(), DEST);
        assertInstanceOf(TaskMirrorRun.Revalidation.Unchanged.class,
                TaskMirroring.revalidation(acceptedOf(original), original, proposal(docs, Map.of(), DEST)));
    }

    @Test
    void aNewlyFoundMirrorNarrowsACreateToReuseAndNothingElse() {
        TaskMirrorRun.Proposal original = proposal(
                List.of(doc("base", "b", List.of()), doc("root", "r", List.of(), "base")), Map.of(), DEST);
        TaskMirrorRun.Proposal fresh = proposal(
                List.of(doc("base", "b", List.of(ISSUE)), doc("root", "r", List.of(), "base")), Map.of(), DEST);
        TaskMirrorRun.Revalidation answer = TaskMirroring.revalidation(acceptedOf(original), original, fresh);
        TaskMirrorRun.Revalidation.Narrow narrow = assertInstanceOf(TaskMirrorRun.Revalidation.Narrow.class, answer);
        assertEquals(Map.of(TaskId.of("base"), ISSUE), narrow.mirrors());
    }

    @Test
    void changedContentRelationsDestinationsOrARemovedReuseStopTheRun() {
        List<TaskDocument> docs = List.of(doc("base", "b", List.of()), doc("root", "r", List.of(), "base"));
        TaskMirrorRun.Proposal original = proposal(docs, Map.of(), DEST);
        TaskMirrorRun.Accepted accepted = acceptedOf(original);
        assertInstanceOf(TaskMirrorRun.Revalidation.Stop.class, TaskMirroring.revalidation(accepted, original,
                proposal(List.of(doc("base", "edited", List.of()), doc("root", "r", List.of(), "base")),
                        Map.of(), DEST)));
        assertInstanceOf(TaskMirrorRun.Revalidation.Stop.class, TaskMirroring.revalidation(accepted, original,
                proposal(List.of(doc("base", "b", List.of()), doc("root", "r", List.of())), Map.of(), DEST)));
        assertInstanceOf(TaskMirrorRun.Revalidation.Stop.class, TaskMirroring.revalidation(accepted, original,
                proposal(docs, Map.of(), new Destination("p", "GitHub repository github.com/o/other", null))));
        assertInstanceOf(TaskMirrorRun.Revalidation.Stop.class, TaskMirroring.revalidation(accepted, original,
                proposal(docs, Map.of(TaskId.of("base"), new Disposition.Skip("gh failed")), DEST)));

        TaskMirrorRun.Proposal reused = proposal(
                List.of(doc("base", "b", List.of(ISSUE)), doc("root", "r", List.of(), "base")), Map.of(), DEST);
        assertInstanceOf(TaskMirrorRun.Revalidation.Stop.class,
                TaskMirroring.revalidation(acceptedOf(reused), reused, original));
    }

    @Test
    void theReportKeepsEveryConfirmedAddressAndAdvisesCheckingForUnknownCreates() {
        TaskDocument created = doc("base", "b", List.of());
        TaskMirrorRun.Result result = new TaskMirrorRun.Result(TaskMirrorRun.Status.COMPLETED, "Done.",
                List.of(new TaskMirrorRun.Created(created, ISSUE, Optional.empty(), Optional.of("record failed"))),
                List.of(), List.of(), List.of(new TaskMirrorRun.Failure(TaskMirrorRun.Operation.CREATE,
                        "'T root'", "no answer. Check the tracker before retrying.", true)), List.of());
        String text = TaskMirroring.report(result);
        assertTrue(text.contains(ISSUE));
        assertTrue(text.contains("NOT recorded"));
        assertTrue(text.contains("Check the tracker before retrying"));
        assertEquals(NotificationType.ERROR, TaskMirroring.severity(result));

        TaskMirrorRun.Result clean = new TaskMirrorRun.Result(TaskMirrorRun.Status.COMPLETED, "Done.",
                List.of(new TaskMirrorRun.Created(created, ISSUE, Optional.empty(), Optional.empty())),
                List.of(), List.of(), List.of(), List.of());
        assertEquals(NotificationType.INFORMATION, TaskMirroring.severity(clean));
        TaskMirrorRun.Result partial = new TaskMirrorRun.Result(TaskMirrorRun.Status.COMPLETED, "Done.",
                List.of(new TaskMirrorRun.Created(created, ISSUE, Optional.empty(), Optional.empty())),
                List.of(), List.of(new TaskMirrorRun.Omission(TaskMirrorRun.OmissionKind.TASK, "'x'", "skipped")),
                List.of(), List.of());
        assertEquals(NotificationType.WARNING, TaskMirroring.severity(partial));
    }
}

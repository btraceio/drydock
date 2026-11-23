package app.drydock.agent.api;

import app.drydock.agent.api.RemoteLaunchSupport.ReviewConnection;
import app.drydock.agent.api.RemoteLaunchSupport.WorkflowAccess;
import app.drydock.domain.SshRemote;
import app.drydock.sdlc.WorkflowResourceLease;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLaunchSupportTest {

    private static final Path WT = Path.of("/wt/alpha");
    private static final ReviewConnection CONNECTION =
            new ReviewConnection("/home/u/.drydock/mcp.json", "mcp-1", "launch-7");
    private static final WorkflowAccess ACCESS = new WorkflowAccess(List.of("/home/u/.drydock/wf/ns-1"));
    private static final SshRemote REMOTE = new SshRemote("box", "/home/u");

    private static CreateContext create() {
        return new CreateContext("n", "s", WT, Optional.of(REMOTE), Optional.empty());
    }

    private static ResumeContext resume() {
        return new ResumeContext(Optional.of("a"), Optional.empty(), WT, Optional.of(REMOTE), Optional.empty());
    }

    @Test
    void absentByDefaultThroughEveryArityAndCopier() {
        Optional<SshRemote> none = Optional.empty();
        assertTrue(create().remoteLaunchSupport().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), true).remoteLaunchSupport().isEmpty());
        assertTrue(create().withWorkflowAccess(new LocalWorkflowAccess(List.of(WT), Optional.empty()))
                .remoteLaunchSupport().isEmpty());
        assertTrue(resume().remoteLaunchSupport().isEmpty());
        assertTrue(resume().withStdioMcpServers(List.of()).withLaunchResources(
                new LaunchResources(List.of(), Optional.empty())).remoteLaunchSupport().isEmpty());
    }

    @Test
    void componentsAreIndependentlyOptional() {
        RemoteLaunchSupport review = RemoteLaunchSupport.ofReviewConnection(CONNECTION);
        assertTrue(review.workflowAccess().isEmpty());
        assertEquals(CONNECTION, review.reviewConnection().orElseThrow());
        RemoteLaunchSupport workflow = RemoteLaunchSupport.ofWorkflowAccess(ACCESS);
        assertTrue(workflow.reviewConnection().isEmpty());
        assertEquals(ACCESS, workflow.workflowAccess().orElseThrow());
        RemoteLaunchSupport neither = new RemoteLaunchSupport(Optional.empty(), Optional.empty(), List.of());
        assertTrue(neither.reviewConnection().isEmpty() && neither.workflowAccess().isEmpty());
    }

    @Test
    void referencesAreRemoteTextNeverHostPaths() {
        assertThrows(IllegalArgumentException.class, () -> new ReviewConnection("mcp.json", "p", "i"));
        assertThrows(IllegalArgumentException.class, () -> new ReviewConnection("C:\\u\\mcp.json", "p", "i"));
        assertThrows(IllegalArgumentException.class, () -> new ReviewConnection("/a/mcp.json", " ", "i"));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowAccess(List.of("relative/ns")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowAccess(List.of()));
        assertEquals(List.of("/a", "/b"), new WorkflowAccess(List.of("/a", "/b", "/a")).readReferences());
    }

    @Test
    void contextsCarryTheSupportAndItsLeasesOnlyWithARemote() {
        WorkflowResourceLease lease = new WorkflowResourceLease(() -> CompletableFuture.completedFuture(null));
        RemoteLaunchSupport support =
                new RemoteLaunchSupport(Optional.of(CONNECTION), Optional.of(ACCESS), List.of(lease));
        assertSame(lease, create().withRemoteLaunchSupport(support).remoteLaunchSupport().orElseThrow()
                .leases().get(0));
        assertSame(support, resume().withRemoteLaunchSupport(support).remoteLaunchSupport().orElseThrow());
        // Copiers preserve it.
        assertSame(support, create().withRemoteLaunchSupport(support).withStdioMcpServers(List.of())
                .remoteLaunchSupport().orElseThrow());
        assertSame(support, resume().withRemoteLaunchSupport(support).withLaunchResources(
                new LaunchResources(List.of(), Optional.empty())).remoteLaunchSupport().orElseThrow());
        // Host-side surfaces stay untouched.
        assertTrue(create().withRemoteLaunchSupport(support).mcp().isEmpty());
        assertTrue(create().withRemoteLaunchSupport(support).launchResources().isEmpty());
        CreateContext local = new CreateContext("n", "s", WT, Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> local.withRemoteLaunchSupport(support));
    }
}

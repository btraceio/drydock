package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkflowAccessTest {

    private static final Path WT = Path.of("/wt/alpha");
    private static final Path ROOT = Path.of("/staging/session-1/workflows");
    private static final LocalWorkflowAccess ACCESS =
            new LocalWorkflowAccess(List.of(ROOT), Optional.of("ns-1"));

    private static CreateContext create() {
        return new CreateContext("n", "s", WT, Optional.empty(), Optional.empty());
    }

    private static ResumeContext resume() {
        return new ResumeContext(Optional.of("a"), Optional.empty(), WT, Optional.empty(), Optional.empty());
    }

    @Test
    void everyOldCreateArityDefaultsToAbsentAccess() {
        Optional<SshRemote> none = Optional.empty();
        assertTrue(create().workflowAccess().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), true).workflowAccess().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), false, true).workflowAccess().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), false, false, Optional.of("t"))
                .workflowAccess().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of()).workflowAccess().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of(), Optional.empty()).workflowAccess().isEmpty());
        assertTrue(new CreateContext("n", "s", WT, none, Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty()).workflowAccess().isEmpty());
    }

    @Test
    void everyOldResumeArityDefaultsToAbsentAccess() {
        Optional<SshRemote> none = Optional.empty();
        Optional<String> id = Optional.of("a");
        assertTrue(resume().workflowAccess().isEmpty());
        assertTrue(new ResumeContext(id, id, WT, none, Optional.empty(), true).workflowAccess().isEmpty());
        assertTrue(new ResumeContext(id, id, WT, none, Optional.empty(), false, true).workflowAccess().isEmpty());
        assertTrue(new ResumeContext(id, id, WT, none, Optional.empty(), false, false, List.of())
                .workflowAccess().isEmpty());
        assertTrue(new ResumeContext(id, id, WT, none, Optional.empty(), false, false, List.of(), Optional.empty())
                .workflowAccess().isEmpty());
        assertTrue(new ResumeContext(id, id, WT, none, Optional.empty(), false, false, List.of(), Optional.empty(),
                Optional.empty()).workflowAccess().isEmpty());
        assertTrue(new ResumeContext(id, id, WT, none, Optional.empty(), false, false, List.of(), Optional.empty(),
                Optional.empty(), Optional.empty()).workflowAccess().isEmpty());
    }

    @Test
    void accessNeverAliasesLaunchResources() {
        Path extra = Path.of("/wt/other");
        Path guidance = Path.of("/plugin/guidance");
        LaunchResources resources = new LaunchResources(List.of(extra), Optional.of(guidance));

        CreateContext created = create().withLaunchResources(resources).withWorkflowAccess(ACCESS);
        assertEquals(Optional.of(ACCESS), created.workflowAccess());
        assertSame(resources, created.launchResources().orElseThrow());
        assertEquals(List.of(extra), created.launchResources().orElseThrow().extraDirectories());
        assertEquals(Optional.of(guidance), created.launchResources().orElseThrow().taskGuidance());

        ResumeContext resumed = resume().withWorkflowAccess(ACCESS).withLaunchResources(resources);
        assertEquals(Optional.of(ACCESS), resumed.workflowAccess());
        assertSame(resources, resumed.launchResources().orElseThrow());
        assertEquals(List.of(extra), resumed.launchResources().orElseThrow().extraDirectories());

        // Access alone leaves launch resources empty: nothing is merged across.
        assertTrue(create().withWorkflowAccess(ACCESS).launchResources().isEmpty());
        assertTrue(resume().withWorkflowAccess(ACCESS).launchResources().isEmpty());
    }

    @Test
    void withersKeepAccessAndOtherComponents() {
        CreateContext c = create().withWorkflowAccess(ACCESS)
                .withStdioMcpServers(List.of()).withLaunchResources(new LaunchResources(List.of(), Optional.empty()));
        assertEquals(Optional.of(ACCESS), c.workflowAccess());
        ResumeContext r = resume().withWorkflowAccess(ACCESS)
                .withStdioMcpServers(List.of()).withLaunchResources(new LaunchResources(List.of(), Optional.empty()));
        assertEquals(Optional.of(ACCESS), r.workflowAccess());
        assertNotEquals(create(), create().withWorkflowAccess(ACCESS));
        assertNotEquals(resume(), resume().withWorkflowAccess(ACCESS));
        assertEquals(create().withWorkflowAccess(ACCESS), create().withWorkflowAccess(ACCESS));
    }

    @Test
    void recordCopiesCallerListAndCollapsesDuplicates() {
        List<Path> roots = new ArrayList<>(List.of(ROOT, Path.of("/staging/b"), ROOT));
        LocalWorkflowAccess access = new LocalWorkflowAccess(roots, Optional.empty());
        roots.clear();
        assertEquals(List.of(ROOT, Path.of("/staging/b")), access.readRoots());
        assertThrows(UnsupportedOperationException.class, () -> access.readRoots().add(WT));
    }

    @Test
    void rejectsAmbiguousOrEmptyAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalWorkflowAccess(List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalWorkflowAccess(List.of(Path.of("relative")), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalWorkflowAccess(List.of(ROOT), Optional.of("  ")));
        assertThrows(NullPointerException.class, () -> create().withWorkflowAccess(null));
        assertThrows(NullPointerException.class, () -> resume().withWorkflowAccess(null));
        assertEquals(Optional.of("ns"), new LocalWorkflowAccess(List.of(), Optional.of(" ns ")).evalNamespace());
    }
}

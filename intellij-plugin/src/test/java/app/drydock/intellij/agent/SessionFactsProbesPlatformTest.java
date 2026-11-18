package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live probes against the booted IDE's real {@link DrydockPluginService}
 * (sandboxed catalog, T14 guard) with temp working directories. No provider
 * is launched and no provider store is written: a stopped session with a real
 * transcript needs one under the user's provider directory, so the transcript
 * scan itself is NOT covered here (manual gate T32 stays mandatory).
 * {@code scansResumeCost} cases are helper-only truth-table pins, not end-to-end proof.
 * The running-session path of {@code probe} (no transcript/token estimation) is likewise
 * UNTESTED by automation: a running execution needs a live process handler registered in
 * the global registry, and no seam exists without launching a provider. Manual gate T32 covers it.
 * Likewise UNTESTED by automation: the readiness-failure path of {@code forProject}. The fixture's
 * real {@code DrydockPluginService} always initializes successfully and no failing-initialization
 * plugin can be supplied without a test double; that behaviour is covered only by the T36 loader tests,
 * and the factory composition here is structurally pinned (source pins), not runtime-tested. The
 * transcript-existence failure path is STRUCTURALLY pinned only ({@code testTranscriptExistenceErrorsAreNotSwallowed}, a
 * source-text check); no provider-backed fault injection exists, so a real throwing provider store is manual-only.
 * Loader-level propagation of a throwing probe to {@code FAILED} is runtime-covered by the T36 loader tests.
 */
public class SessionFactsProbesPlatformTest extends BasePlatformTestCase {

    private DrydockPluginService plugin;
    private Path dir;
    private ManagedSessionId id;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        plugin.initialization().get(60, TimeUnit.SECONDS);
        dir = Files.createTempDirectory("drydock-facts-probe");
        id = ManagedSessionId.newId();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            IsolatedCatalog.remove(id);
            Files.deleteIfExists(dir.resolve(".git"));
            Files.deleteIfExists(dir);
        } finally {
            super.tearDown();
        }
    }

    private void seed(Path workingDirectory, Optional<String> agentSessionId) {
        Instant now = Instant.now();
        IsolatedCatalog.seed(new ManagedAgentSession(id, RepositoryId.newId(), "probe",
                AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(agentSessionId),
                new SessionWorkspace(workingDirectory, Optional.empty(), false), SessionStatus.INACTIVE,
                now, now, Optional.empty(), PrLink.none(), false, Optional.empty(), false));
    }

    private SessionPaneFacts.Inputs probe() throws Exception {
        return SessionFactsProbes.probe(getProject(), plugin, id).orElseThrow();
    }

    public void testSessionMissingFromTheProvidedServiceIsEmpty() throws Exception {
        assertTrue(SessionFactsProbes.probe(getProject(), plugin, ManagedSessionId.newId()).isEmpty());
    }

    public void testGitEntryAbsentThenPresentAndCanonicalPath() throws Exception {
        seed(dir, Optional.empty());
        SessionPaneFacts.Inputs bare = probe();
        assertTrue(bare.directoryExists());
        assertFalse("no .git entry", bare.hasGitEntry());
        assertEquals(RepositoryCatalog.canonicalize(dir), bare.canonicalWorkingDirectory());
        assertEquals(RepositoryCatalog.canonicalize(Path.of(getProject().getBasePath())),
                bare.canonicalProjectBase().orElseThrow());
        assertEquals(dir.getFileName().toString(), bare.directoryName());

        Files.createFile(dir.resolve(".git")); // the linked-worktree file form counts
        assertTrue(probe().hasGitEntry());
    }

    public void testMissingDirectoryHasNeitherDirectoryNorGitEntry() throws Exception {
        seed(dir.resolve("gone"), Optional.empty());
        SessionPaneFacts.Inputs gone = probe();
        assertFalse(gone.directoryExists());
        assertFalse(gone.hasGitEntry());
    }

    public void testStoppedSessionWithoutConversationHasNoCostAndIsNotRunning() throws Exception {
        seed(dir, Optional.empty());
        SessionPaneFacts.Inputs in = probe();
        assertFalse(in.running());
        assertFalse(in.hasAgentSessionId());
        assertFalse(in.hasConversation());
        assertTrue(in.contextTokens().isEmpty());
        assertTrue(in.runningPlace().isEmpty());
    }

    public void testResumeCostScanTruthTableHelperOnly() {
        assertTrue(SessionFactsProbes.scansResumeCost(true, false, true));
        assertFalse("running: no scan", SessionFactsProbes.scansResumeCost(true, true, true));
        assertFalse("no conversation: no scan", SessionFactsProbes.scansResumeCost(true, false, false));
        assertFalse("no agent id: no scan", SessionFactsProbes.scansResumeCost(false, false, true));
    }

    public void testForProjectLoadsOffTheEdtThroughRealReadinessAndCachesTheSelection() throws Exception {
        seed(dir, Optional.empty());
        CopyOnWriteArrayList<SessionFactsLoader.Result> results = new CopyOnWriteArrayList<>();
        AtomicBoolean onEdt = new AtomicBoolean(true);
        CountDownLatch settled = new CountDownLatch(1);
        SessionFactsLoader loader = SessionFactsLoader.forProject(getProject(), plugin, result -> {
            onEdt.set(ApplicationManager.getApplication().isDispatchThread());
            results.add(result);
            settled.countDown();
        });
        try {
            assertEquals(SessionPaneFacts.State.LOADING, loader.select(id).facts().state());
            assertTrue(settled.await(30, TimeUnit.SECONDS));
            assertFalse("the probes ran on the executor, not the EDT", onEdt.get());
            assertEquals(SessionPaneFacts.State.KNOWN, results.get(0).facts().state());
            assertSame("a repeated selection is the cached answer", results.get(0), loader.select(id));
            assertEquals("and adds no probe", 1, results.size());
        } finally {
            loader.dispose();
        }
    }

    /** Source pins; a structural check, not runtime proof of the failed-readiness path (T36 covers that). */
    public void testSourceWiringPins() throws Exception {
        String loader = source("SessionFactsLoader.java");
        assertTrue(loader.contains("plugin.initialization(), plugin.executor()"));
        assertTrue(loader.contains("SessionFactsProbes.indexed(project, plugin)"));
        String probes = source("SessionFactsProbes.java");
        assertFalse("no probe-side readiness wait", probes.contains("initialization()"));
        assertFalse(probes.contains(".join("));
        assertFalse(probes.contains("DrydockPluginService.getInstance"));
    }

    /** STRUCTURAL source-contract pin, not runtime proof: the existence read must not be wrapped in a catch. */
    public void testTranscriptExistenceErrorsAreNotSwallowed() throws Exception {
        String probes = source("SessionFactsProbes.java");
        int start = probes.indexOf("private static Optional<Boolean> transcriptExists(");
        assertTrue(start >= 0);
        int end = probes.indexOf("/**", start);
        assertFalse("existence-read errors propagate to the loader's FAILED path",
                probes.substring(start, end).contains("catch"));
        assertEquals("only the cosmetic token estimate swallows",
                probes.indexOf("catch (RuntimeException"), probes.lastIndexOf("catch (RuntimeException"));
        assertTrue(probes.indexOf("catch (RuntimeException") > probes.indexOf("private static OptionalLong resumeTokens("));
    }

    private static String source(String name) throws Exception {
        for (String root : new String[] {"src/platform/java", "intellij-plugin/src/platform/java"}) {
            Path path = Path.of(root, "app/drydock/intellij/agent", name);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        throw new AssertionError("source not found: " + name);
    }
}

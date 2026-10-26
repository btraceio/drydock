package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.review.ReviewScope;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open paths of {@link ReviewTabOpener} over fakes: the placeholder
 * before any platform call (AC18), the dedicated Review window activated
 * by its pinned id (T8), and the link session carried into the mint (Q7).
 * The gutter path ({@code openFinding}) adds the pending-target seam: the
 * target is recorded on the project before activation, so it works with
 * no content in sight, and an ordinary open records nothing.
 */
class ReviewTabOpenerTest {

    /** Records every call of the open paths, in order. */
    private final List<String> calls = new ArrayList<>();

    private final ManagedAgentSession link = session();

    @Test
    void thePlaceholderRunsBeforeTheFirstPlatformCallOfTheOpenPath() {
        opener(scopeSource()).openIn(project("/repo"), Optional.of(link));

        // The placeholder is the first record of the whole open path after
        // the document save; the first window call (the activation) comes
        // after it — the click visibly does something before any
        // background work lands on a platform call (AC18).
        assertTrue(calls.indexOf("placeholder: Loading review…") >= 0, calls::toString);
        assertTrue(calls.indexOf("placeholder: Loading review…")
                        < calls.indexOf("activate: Drydock Review"),
                calls::toString);
    }

    @Test
    void theOpenPathSavesThenShowsProgressThenMintsThenActivatesTheDedicatedWindow() {
        opener(scopeSource()).openIn(project("/repo"), Optional.of(link));

        // The dedicated window has one content, so activation is the
        // whole selection — no tool-window resolution and no
        // ChangesViewContentManager call remain on the path (T7/T8).
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=" + link.id(),
                "activate: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void theOpenPathCarriesTheLinkSessionIntoTheMint() {
        opener(scopeSource()).openIn(project("/repo"), Optional.of(link));

        // The mint the open path triggers is bound to the link session and
        // resolves the project's own checkout (Q7, AC18).
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=" + link.id(),
                "activate: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void aDirectOpenMintsTheScopeUnbound() {
        opener(scopeSource()).openIn(project("/repo"), Optional.empty());

        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=<unbound>",
                "activate: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void aFailedMintStillOpensTheWindow() {
        ReviewTabOpener opener = new ReviewTabOpener(
                host(),
                (checkout, linkSession) -> {
                    calls.add("resolve: " + checkout + " link=" + linkSession.map(s -> s.id().toString())
                            .orElse("<unbound>"));
                    return CompletableFuture.failedFuture(new IllegalStateException("not a git checkout"));
                },
                recordingLoading());

        opener.openIn(project("/nowhere"), Optional.of(link));

        // The open is not lost with the mint: the window opens and its own
        // load path shows the failure in its own states (AC22).
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /nowhere link=" + link.id(),
                "activate: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void theChangesLinkSavesBeforeAnyBackgroundWorkAndReRunsTheDiffAfterActivatingTheWindow() {
        opener(scopeSource()).openIn(project("/repo"), Optional.of(link));

        // Q9: the save is the first call, before the placeholder fronts the
        // background mint; the reload comes only once the window is active.
        assertEquals("saveDocuments", calls.get(0), calls::toString);
        assertTrue(calls.indexOf("saveDocuments") < calls.indexOf("resolve: /repo link=" + link.id()),
                calls::toString);
        assertEquals("reloadTab", calls.get(calls.size() - 1), calls::toString);
        assertTrue(calls.indexOf("activate: Drydock Review") < calls.indexOf("reloadTab"), calls::toString);
    }

    @Test
    void aDisposedProjectOpensNothing() {
        opener(scopeSource()).openIn(disposedProject("/repo"), Optional.of(link));

        assertTrue(calls.isEmpty(), calls::toString);
    }

    // ---- the gutter path: the pending-target seam (AC5, T8) --------------

    /**
     * The gutter click records its target on the project <em>before</em> the
     * window is activated — with no Review content in existence, because
     * activation is what lazily creates it (T7's factory). The recorded
     * target survives for the consumer and is spent exactly once.
     */
    @Test
    void openFindingRecordsTheTargetBeforeActivationAndWithoutAnyContent() {
        Project project = project("/repo");
        opener(scopeSource()).openFindingIn(project, "a.txt", "f1");

        ReviewTabOpener.FindingTarget target = new ReviewTabOpener.FindingTarget("a.txt", "f1");
        // Recorded before the activation, in the same call order the
        // window's lazy content could observe.
        assertTrue(calls.indexOf("record: " + target) >= 0, calls::toString);
        assertTrue(calls.indexOf("record: " + target)
                        < calls.indexOf("activate: Drydock Review"),
                calls::toString);
        // The open path itself is the unbound one — the gutter click has no
        // link session — and keeps the Changes-link ordering (Q9, AC18):
        // the target is recorded first, with no content in sight, then the
        // save, the placeholder, the mint and the activation follow.
        assertEquals(List.of(
                "record: " + target,
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=<unbound>",
                "activate: Drydock Review",
                "reloadTab"), calls);
        // The seam: still recorded (the content never existed to consume
        // it), peekable, and spent exactly once by the take.
        assertEquals(Optional.of(target), ReviewTabOpener.pendingFinding(project));
        assertEquals(Optional.of(target), ReviewTabOpener.takePendingFinding(project));
        assertTrue(ReviewTabOpener.takePendingFinding(project).isEmpty(), "the take is once, only once");
        assertTrue(ReviewTabOpener.pendingFinding(project).isEmpty(), "the take cleared the record");
    }

    /** A malformed gutter target records nothing — but the window still opens. */
    @Test
    void openFindingWithAMalformedTargetRecordsNothingAndStillOpens() {
        opener(scopeSource()).openFindingIn(project("/repo"), "  ", "f1");

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("record: ")), calls::toString);
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=<unbound>",
                "activate: Drydock Review",
                "reloadTab"), calls);
    }

    /** The ordinary Changes link records no target: no navigation, just the open. */
    @Test
    void anOrdinaryOpenRecordsNoPendingTarget() {
        Project project = project("/repo");
        opener(scopeSource()).openIn(project, Optional.of(link));

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("record: ")), calls::toString);
        assertTrue(ReviewTabOpener.pendingFinding(project).isEmpty());
    }

    /**
     * The seam's own semantics, independent of any open path: a later
     * record overwrites an earlier one (the latest navigation is the one
     * that counts), the peek never consumes, and the take gets the
     * recorded target and clears it in one step — spent exactly once.
     */
    @Test
    void thePendingTargetOverwritesAndIsGotAndClearedOnce() {
        Project project = project("/repo");
        ReviewTabOpener.FindingTarget first = new ReviewTabOpener.FindingTarget("a.txt", "f1");
        ReviewTabOpener.FindingTarget second = new ReviewTabOpener.FindingTarget("b.txt", "f2");

        ReviewTabOpener.recordPendingFinding(project, first);
        ReviewTabOpener.recordPendingFinding(project, second);
        assertEquals(Optional.of(second), ReviewTabOpener.pendingFinding(project),
                "the latest record is the one that counts");
        assertEquals(Optional.of(second), ReviewTabOpener.takePendingFinding(project),
                "the take gets and clears in one step");
        assertTrue(ReviewTabOpener.pendingFinding(project).isEmpty(), "the take cleared the record");
        assertTrue(ReviewTabOpener.takePendingFinding(project).isEmpty(), "the take is once, only once");
        assertTrue(ReviewTabOpener.pendingFinding(project).isEmpty());
    }

    /**
     * The same concurrent shape the tree's TreeState carries: when several
     * consumers take the recorded target at the same instant, exactly one
     * of them gets it — the record/peek/take contract the T9 consumer and
     * the public opener rely on, held per project for every interleaving.
     * The takers spin-start on a flag so they enter the take as
     * simultaneously as the JVM allows.
     */
    @Test
    void concurrentTakersSpendThePendingTargetAtMostOnce() throws Exception {
        Project project = concurrentProject("/repo");
        int takers = 4;
        int rounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(takers);
        try {
            for (int round = 0; round < rounds; round++) {
                ReviewTabOpener.FindingTarget target =
                        new ReviewTabOpener.FindingTarget("a.txt", "f" + round);
                ReviewTabOpener.recordPendingFinding(project, target);

                AtomicBoolean go = new AtomicBoolean();
                CountDownLatch ready = new CountDownLatch(takers);
                List<Future<Boolean>> races = new ArrayList<>();
                for (int taker = 0; taker < takers; taker++) {
                    races.add(pool.submit(() -> {
                        ready.countDown();
                        while (!go.get()) {
                            Thread.onSpinWait(); // enter the take as one instant
                        }
                        return ReviewTabOpener.takePendingFinding(project).isPresent();
                    }));
                }
                ready.await(20, TimeUnit.SECONDS);
                go.set(true);

                int spent = 0;
                for (Future<Boolean> race : races) {
                    spent += race.get(20, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertTrue(spent <= 1,
                        "round " + round + " spent its target " + spent + " times");
            }
            assertTrue(ReviewTabOpener.takePendingFinding(project).isEmpty(),
                    "nothing is left after the rounds");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the fakes --------------------------------------------------------

    /** A scope source that records the mint and always succeeds. */
    private ReviewTabOpener.ScopeSource scopeSource() {
        return (checkout, linkSession) -> {
            calls.add("resolve: " + checkout + " link=" + linkSession.map(s -> s.id().toString())
                    .orElse("<unbound>"));
            return CompletableFuture.completedFuture(scope());
        };
    }

    /**
     * The host seam recording its calls. The activation id is whatever the
     * opener passes — the assertions prove it is the dedicated Review id
     * ({@code ReviewToolWindowFactory.TOOL_WINDOW_ID}), never a resolved
     * host (T7/T8).
     */
    private ReviewTabOpener.Host host() {
        return new ReviewTabOpener.Host() {
            @Override
            public void activate(Project project, String toolWindowId) {
                calls.add("activate: " + toolWindowId);
            }

            @Override
            public void saveDocuments(Project project) {
                calls.add("saveDocuments");
            }

            @Override
            public void reloadTab(Project project) {
                calls.add("reloadTab");
            }
        };
    }

    /**
     * The loading seam recording the placeholder — the hook the open path
     * runs before any platform call — then driving the background half and
     * the EDT half, as the production progress task does.
     */
    private ReviewTabOpener.Loading recordingLoading() {
        return (project, text, background, done) -> {
            calls.add("placeholder: " + text);
            background.run();
            done.run();
        };
    }

    private ReviewTabOpener opener(ReviewTabOpener.ScopeSource scopes) {
        return new ReviewTabOpener(host(), scopes, recordingLoading());
    }

    private static ReviewScope scope() {
        return new ReviewScope("scope-1", ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.empty(), "main", "head", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ManagedAgentSession session() {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/repo")), Instant.EPOCH).build();
    }

    /**
     * A live {@link Project} stand-in: it answers {@code getBasePath()} and
     * {@code isDisposed()} — the two project APIs the open paths read — and
     * carries the project user data the pending-target seam lives on,
     * recording every non-null put so the tests can see recording order.
     */
    private Project project(String basePath) {
        Map<Key<?>, Object> userData = new HashMap<>();
        return (Project) Proxy.newProxyInstance(
                ReviewTabOpenerTest.class.getClassLoader(),
                new Class<?>[] {Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "isDisposed" -> false;
                    case "putUserData" -> {
                        userData.put((Key<?>) args[0], args[1]);
                        if (args[1] != null) {
                            calls.add("record: " + args[1]);
                        }
                        yield null;
                    }
                    case "getUserData" -> userData.get(args[0]);
                    case "toString" -> "project@" + basePath;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args[0] == proxy;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * A {@code Project} stand-in for the concurrency test: the same
     * surface as {@link #project(String)} except its user data is a
     * synchronized map (it must take the take path's null clears, which
     * a {@code ConcurrentHashMap} refuses) and it records nothing — the
     * pool threads touch the user data directly, so the recording list
     * must stay single-threaded.
     */
    private static Project concurrentProject(String basePath) {
        Map<Key<?>, Object> userData = Collections.synchronizedMap(new HashMap<>());
        return (Project) Proxy.newProxyInstance(
                ReviewTabOpenerTest.class.getClassLoader(),
                new Class<?>[] {Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "isDisposed" -> false;
                    case "putUserData" -> {
                        userData.put((Key<?>) args[0], args[1]);
                        yield null;
                    }
                    case "getUserData" -> userData.get(args[0]);
                    case "toString" -> "project@" + basePath;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args[0] == proxy;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Project disposedProject(String basePath) {
        return (Project) Proxy.newProxyInstance(
                ReviewTabOpenerTest.class.getClassLoader(),
                new Class<?>[] {Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "isDisposed" -> true;
                    case "toString" -> "project@" + basePath;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args[0] == proxy;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}

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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open path of {@link ReviewTabOpener} over fakes: the placeholder
 * before any platform call (AC18), the host resolved through the seam and
 * never named (AC3), and the link session carried into the mint (Q7).
 */
class ReviewTabOpenerTest {

    /** Records every call of the open path, in order. */
    private final List<String> calls = new ArrayList<>();

    private final ManagedAgentSession link = session();

    @Test
    void thePlaceholderRunsBeforeTheFirstPlatformCallOfTheOpenPath() {
        opener(scopeSource(), "Commit").openIn(projectWithBasePath("/repo", false), Optional.of(link));

        // The placeholder is the first record of the whole open path; the
        // first platform call (the host resolution) comes after it — the
        // click visibly does something before any background work (AC18).
        assertTrue(calls.indexOf("placeholder: Loading review…") >= 0, calls::toString);
        assertTrue(calls.indexOf("placeholder: Loading review…")
                        < calls.indexOf("toolWindowIdFor: Drydock Review"),
                calls::toString);
    }
    @Test
    void theOpenPathResolvesTheHostActivatesItAndSelectsTheTabThroughTheSeam() {
        opener(scopeSource(), "Commit").openIn(projectWithBasePath("/repo", false), Optional.of(link));

        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=" + link.id(),
                "toolWindowIdFor: Drydock Review",
                "activate: Commit",
                "selectContent: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void theOpenPathCarriesTheLinkSessionIntoTheMint() {
        opener(scopeSource(), "Version Control")
                .openIn(projectWithBasePath("/repo", false), Optional.of(link));

        // The mint the open path triggers is bound to the link session and
        // resolves the project's own checkout (Q7, AC18) — the fake host
        // id shows the tab never names its host (AC3).
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=" + link.id(),
                "toolWindowIdFor: Drydock Review",
                "activate: Version Control",
                "selectContent: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void aDirectOpenMintsTheScopeUnbound() {
        opener(scopeSource(), "Commit").openIn(projectWithBasePath("/repo", false), Optional.empty());

        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=<unbound>",
                "toolWindowIdFor: Drydock Review",
                "activate: Commit",
                "selectContent: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void aFailedMintStillOpensTheTab() {
        ReviewTabOpener opener = new ReviewTabOpener(
                host("Commit"),
                (checkout, linkSession) -> {
                    calls.add("resolve: " + checkout + " link=" + linkSession.map(s -> s.id().toString())
                            .orElse("<unbound>"));
                    return CompletableFuture.failedFuture(new IllegalStateException("not a git checkout"));
                },
                recordingLoading());

        opener.openIn(projectWithBasePath("/nowhere", false), Optional.of(link));

        // The open is not lost with the mint: the tab opens and its own
        // load path shows the failure in its own states (AC22).
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /nowhere link=" + link.id(),
                "toolWindowIdFor: Drydock Review",
                "activate: Commit",
                "selectContent: Drydock Review",
                "reloadTab"), calls);
    }

    @Test
    void aHostThatResolvesToNothingSkipsActivationAndSelection() {
        opener(scopeSource(), null).openIn(projectWithBasePath("/repo", false), Optional.of(link));

        // The host is resolved (and found empty), so activation and
        // selection are skipped — the rest of the path ran.
        assertEquals(List.of(
                "saveDocuments",
                "placeholder: Loading review…",
                "resolve: /repo link=" + link.id(),
                "toolWindowIdFor: Drydock Review"), calls);
        assertFalse(calls.contains("activate: Commit"), calls::toString);
    }

    @Test
    void theChangesLinkSavesBeforeAnyBackgroundWorkAndReRunsTheDiffAfterSelectingTheTab() {
        opener(scopeSource(), "Commit").openIn(projectWithBasePath("/repo", false), Optional.of(link));

        // Q9: the save is the first call, before the placeholder fronts the
        // background mint; the reload comes only once the tab is selected.
        assertEquals("saveDocuments", calls.get(0), calls::toString);
        assertTrue(calls.indexOf("saveDocuments") < calls.indexOf("resolve: /repo link=" + link.id()),
                calls::toString);
        assertEquals("reloadTab", calls.get(calls.size() - 1), calls::toString);
        assertTrue(calls.indexOf("selectContent: Drydock Review") < calls.indexOf("reloadTab"), calls::toString);
    }

    @Test
    void aDisposedProjectOpensNothing() {
        opener(scopeSource(), "Commit").openIn(projectWithBasePath("/repo", true), Optional.of(link));

        assertTrue(calls.isEmpty(), calls::toString);
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
     * The host seam recording its calls, answering {@code toolWindowId} —
     * including a fake id unlike the Commit window, so the assertions prove
     * the opener uses whatever the resolution returns (AC3).
     */
    private ReviewTabOpener.Host host(String toolWindowId) {
        return new ReviewTabOpener.Host() {
            @Override
            public String toolWindowIdFor(Project project, String tabName) {
                calls.add("toolWindowIdFor: " + tabName);
                return toolWindowId;
            }

            @Override
            public void activate(Project project, String resolvedToolWindowId) {
                calls.add("activate: " + resolvedToolWindowId);
            }

            @Override
            public void selectContent(Project project, String tabName) {
                calls.add("selectContent: " + tabName);
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

    private ReviewTabOpener opener(ReviewTabOpener.ScopeSource scopes, String toolWindowId) {
        return new ReviewTabOpener(host(toolWindowId), scopes, recordingLoading());
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
     * A {@link Project} that answers only {@code getBasePath()} and
     * {@code isDisposed()} — the two project APIs the open path reads. The
     * same reflective stand-in {@code ProjectRepositoryServiceTest} uses,
     * because there is no cheap platform way to obtain a project rooted at
     * an arbitrary fixture path.
     */
    private static Project projectWithBasePath(String basePath, boolean disposed) {
        return (Project) Proxy.newProxyInstance(
                ReviewTabOpenerTest.class.getClassLoader(),
                new Class<?>[] {Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "isDisposed" -> disposed;
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

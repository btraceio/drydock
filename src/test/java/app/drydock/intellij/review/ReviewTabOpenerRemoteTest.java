package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The remote branch of {@link ReviewTabOpener}: reveal-only, before any local facility (INV-58/59). */
class ReviewTabOpenerRemoteTest {

    private final List<String> calls = new ArrayList<>();
    private final ManagedSessionId session = ManagedSessionId.newId();
    private final ReviewTabOpener.RemoteContext context =
            new ReviewTabOpener.RemoteContext(session, "Remote workspace box-1 · local changes");

    @Test
    void revealShowsTheWorkbenchWithoutAnyLocalFacilityOrInvocation() {
        Project project = project();
        opener().openRemoteIn(project, context);

        // No saveDocuments, placeholder, mint (resolve), reloadTab: only content + workbench.
        assertEquals(List.of("content: Drydock Review", "openWorkbench"), calls);
        assertEquals(Optional.of(context), ReviewTabOpener.remoteContext(project));
    }

    @Test
    void revealWorksDetachedBecauseItNeedsOnlyTheSessionIdentity() {
        // The context is just identity + label: no console, no attachment, no enrollment is consulted.
        Project project = project();
        opener().openIn(project, Optional.empty()); // local first: takes the local path
        calls.clear();
        opener().openRemoteIn(project, context);
        assertEquals(List.of("content: Drydock Review", "openWorkbench"), calls);
    }

    @Test
    void aRemoteContextKeepsLaterOpensRevealOnlyAndNeverStartsAReview() {
        Project project = project();
        opener().openRemoteIn(project, context);
        calls.clear();

        opener().openIn(project, Optional.empty());
        assertEquals(List.of("content: Drydock Review", "openWorkbench"), calls);

        // A review start on a remote scope returns before WorktreeService/DrydockPluginService/dialogs
        // (any of which would throw in this platform-free fixture).
        ReviewTabOpener.startReview(project, Path.of("/repo"), scope(), ReviewTabOpener.ReviewStart.SECTIONS,
                ReviewIntent.Origin.WORKBENCH);
        ReviewTabOpener.startReview(project, Path.of("/repo"), scope(), ReviewTabOpener.ReviewStart.TOUR);
        assertEquals(List.of("content: Drydock Review", "openWorkbench"), calls);
        assertTrue(ReviewTabOpener.remoteContext(project).isPresent());
    }

    @Test
    void clearingTheContextRestoresTheUnchangedLocalPath() {
        Project project = project();
        opener().openRemoteIn(project, context);
        ReviewTabOpener.clearRemoteContext(project);
        calls.clear();

        opener().openIn(project, Optional.empty());
        assertEquals(List.of("saveDocuments", "placeholder", "resolve: /repo", "hasData", "content: Drydock Review",
                "openWorkbench", "reloadTab"), calls);
    }

    private ReviewTabOpener opener() {
        ReviewTabOpener.Host host = new ReviewTabOpener.Host() {
            @Override public void prepareContent(Project project, String id) { calls.add("content: " + id); }
            @Override public void saveDocuments(Project project) { calls.add("saveDocuments"); }
            @Override public void reloadTab(Project project) { calls.add("reloadTab"); }
            @Override public void openWorkbench(Project project) { calls.add("openWorkbench"); }
        };
        ReviewTabOpener.ScopeSource scopes = (checkout, link) -> {
            calls.add("resolve: " + checkout);
            return CompletableFuture.completedFuture(scope());
        };
        ReviewTabOpener.Loading loading = (project, text, background, done) -> {
            calls.add("placeholder");
            background.run();
            done.run();
        };
        ReviewTabOpener.Preparation preparation = new ReviewTabOpener.Preparation() {
            @Override public boolean hasData(ReviewScope scope) { calls.add("hasData"); return true; }
            @Override public boolean offer(Project project, Path checkout, ReviewScope scope) { return false; }
            @Override public void launch(Project project, Path checkout, ReviewScope scope) { }
        };
        return new ReviewTabOpener(host, scopes, loading, preparation);
    }

    private static ReviewScope scope() {
        return new ReviewScope("scope-1", ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.empty(), "main", "head", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Project project() {
        Map<Key<?>, Object> userData = new HashMap<>();
        return (Project) Proxy.newProxyInstance(ReviewTabOpenerRemoteTest.class.getClassLoader(),
                new Class<?>[] {Project.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> "/repo";
                    case "isDisposed" -> false;
                    case "putUserData" -> {
                        userData.put((Key<?>) args[0], args[1]);
                        yield null;
                    }
                    case "getUserData" -> userData.get(args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args[0] == proxy;
                    case "toString" -> "project";
                    default -> null;
                });
    }
}

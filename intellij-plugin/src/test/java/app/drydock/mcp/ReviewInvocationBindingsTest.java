package app.drydock.mcp;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.mcp.ReviewInvocationBindings.Binding;
import app.drydock.mcp.ReviewInvocationBindings.Ticket;
import app.drydock.review.AnnotationStore;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewInvocationBindingsTest {

    @TempDir
    Path temp;

    private final ManagedSessionId alice = ManagedSessionId.newId();
    private final ManagedSessionId bob = ManagedSessionId.newId();
    private ReviewScopeRegistry registry;
    private TourStore tourStore;
    private AnnotationStore annotationStore;
    private WorkspaceMcpSessionContext context;
    private ReviewScope scope;

    @BeforeEach
    void setUp() {
        registry = new ReviewScopeRegistry();
        tourStore = new TourStore(temp.resolve("tours.json"));
        annotationStore = new AnnotationStore(temp.resolve("annotations.json"));
        context = new WorkspaceMcpSessionContext(
                List::of, List::of, annotationStore, tourStore, registry,
                new AgentRegistry(List.of(), null), new DiffService(), new GitStatusService(), new WorktreeService(),
                UserConfig::empty,
                (worktree, prompt) -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                (id, title) -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                (id, newId) -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                (id, draft) -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                (id, draft) -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                new PendingQuestions(null));
        scope = mint(alice, ReviewScope.Kind.BRANCH);
    }

    private ReviewScope mint(ManagedSessionId owner, ReviewScope.Kind kind) {
        return registry.mint(new ReviewScope("spec", kind, temp, Optional.empty(), "main", "topic",
                Optional.empty(), Optional.of(owner), Optional.empty()));
    }

    private ReviewInvocationBindings bindings() {
        return context.invocationBindings();
    }

    private String modeOf(ManagedSessionId caller) {
        return context.reviewIntegration(scope, caller).mode();
    }

    @Test
    void generationsAreOrderedAndALaterInstallSupersedes() {
        Ticket first = bindings().install(alice, scope.id(), "main..topic", ReviewTour.Kind.SECTIONS);
        Ticket second = bindings().install(alice, scope.id(), "main..topic", ReviewTour.Kind.TOUR);
        assertTrue(second.generation() > first.generation());
        assertEquals(second.generation(), bindings().bindingOf(alice, scope.id()).orElseThrow().generation());
        assertEquals("tour", modeOf(alice));
    }

    @Test
    void theBindingDrivesTheIntegrationOverTheStoredTourAndTheTourFallbackApplies() {
        assertEquals("tour", modeOf(alice));
        tourStore.put(storedTour(ReviewTour.Kind.SECTIONS));
        assertEquals("sections", modeOf(alice));
        bindings().install(alice, scope.id(), "t", ReviewTour.Kind.TOUR);
        assertEquals("tour", modeOf(alice));
        assertTrue(context.reviewIntegration(scope, alice).text().contains(scope.id()));
    }

    @Test
    void rollbackRestoresThePreviousBindingButOnlyForItsOwnGeneration() {
        Ticket committed = bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        Ticket noSend = bindings().install(alice, scope.id(), "t", ReviewTour.Kind.TOUR);
        assertTrue(bindings().rollback(noSend));
        assertEquals(committed.generation(), bindings().bindingOf(alice, scope.id()).orElseThrow().generation());
        assertEquals("sections", modeOf(alice));

        Ticket superseded = bindings().install(alice, scope.id(), "t", ReviewTour.Kind.TOUR);
        Ticket latest = bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        assertFalse(bindings().rollback(superseded), "an older request must not undo a later commit");
        assertEquals(latest.generation(), bindings().bindingOf(alice, scope.id()).orElseThrow().generation());

        assertTrue(bindings().rollback(latest));
        assertEquals(superseded.generation(), bindings().bindingOf(alice, scope.id()).orElseThrow().generation());
        assertFalse(bindings().rollback(latest), "a ticket rolls back once");
    }

    @Test
    void aRollbackWithNothingBeforeLeavesNoBinding() {
        Ticket only = bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        assertTrue(bindings().rollback(only));
        assertEquals(Optional.<Binding>empty(), bindings().bindingOf(alice, scope.id()));
    }

    @Test
    void aBindingNeverLeaksToAnotherSession() {
        bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        assertEquals("sections", modeOf(alice));
        assertEquals("tour", modeOf(bob));
        assertEquals(Optional.<Binding>empty(), bindings().bindingOf(bob, scope.id()));
        Ticket bobs = bindings().install(bob, scope.id(), "t", ReviewTour.Kind.TOUR);
        assertNotEquals(Optional.empty(), bindings().bindingOf(alice, scope.id()));
        bindings().rollback(bobs);
        assertEquals("sections", modeOf(alice));
    }

    @Test
    void revokingTheScopeDropsItsBindingsAndNothingElse() {
        ReviewScope other = mint(bob, ReviewScope.Kind.WORKING_TREE);
        bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        bindings().install(bob, other.id(), "t", ReviewTour.Kind.SECTIONS);
        registry.revoke(scope.id());
        assertEquals(Optional.<Binding>empty(), bindings().bindingOf(alice, scope.id()));
        assertTrue(bindings().bindingOf(bob, other.id()).isPresent());
    }

    @Test
    void rebindingTheScopeGrantsNoAddressabilityAndDropsTheOldSessionsBinding() {
        bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        registry.rebind(alice, bob);
        assertTrue(bindings().bindingOf(alice, scope.id()).isEmpty());
        assertFalse(registry.isAddressableBy(scope.id(), alice));
        assertTrue(registry.isAddressableBy(scope.id(), bob));
        assertEquals("tour", modeOf(bob), "the successor inherits no presentation either");
        // Installing a binding is not authorization: an unrelated session stays locked out.
        bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        assertFalse(context.reviewScope(scope.id(), alice).isPresent());
    }

    @AfterEach
    void tearDown() {
        // Both stores' save executors are single virtual threads; an unclosed store can land a
        // pending write after the test ends and make @TempDir teardown fail to delete the file.
        tourStore.close();
        annotationStore.close();
    }

    void sessionRemovalAndCloseDropBindings() {
        bindings().install(alice, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        bindings().install(bob, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        context.forgetSession(alice);
        assertTrue(bindings().bindingOf(alice, scope.id()).isEmpty());
        assertTrue(bindings().bindingOf(bob, scope.id()).isPresent());
        context.close();
        assertTrue(bindings().bindingOf(bob, scope.id()).isEmpty());
        // After close the registry no longer feeds the context.
        bindings().install(bob, scope.id(), "t", ReviewTour.Kind.SECTIONS);
        registry.revoke(scope.id());
        assertTrue(bindings().bindingOf(bob, scope.id()).isPresent());
    }

    private TourRecord storedTour(ReviewTour.Kind kind) {
        ReviewTour tour = new ReviewTour(scope.id(), "fp", List.of(), Optional.empty(), List.of(), kind);
        return new TourRecord(tour, Map.of(), Map.of(), Map.of(), false, false, false);
    }
}

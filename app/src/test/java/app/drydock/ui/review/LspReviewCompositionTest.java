package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.Provenance;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.Severity;
import app.drydock.review.SubmitPlan;
import app.drydock.review.Triage;
import app.drydock.review.UsageProvider;
import app.drydock.review.UsageProvider.Usage;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.search.SessionSearchService;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import app.drydock.ui.nav.ExplorerTrailStore;
import app.drydock.ui.nav.LexicalUsageProvider;
import app.drydock.ui.nav.SymbolPeek;
import app.drydock.ui.nav.SymbolPeekService;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition at the usage seam (usage-resolution design 2026-10-08,
 * §§6–7): the {@link SessionReviewView.Host} factory is the ONE provider
 * construction site -- the default is exactly today's lexical provider, no
 * LSP wiring -- and both of its consumers, the diff column's symbol peek
 * and the step panel's callee resolution, go through it. Plus the seam's
 * compatible shapes: the occurrence constructor's tier derivation, and the
 * peek composed from a provider's answers preserving the lexical peek's
 * rows (text, order, warrant) while upgrading only what a server
 * confirmed.
 */
class LspReviewCompositionTest extends FxTest {

    private static final String FILE_JAVA = "src/guards.java";

    final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final DiffService diffService = new DiffService();

    private Path navRoot;
    private SessionSearchService navSearch;
    private ExplorerTrailStore trails;
    private RecordingHost host;
    private SessionReviewView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            Path storeDir = Files.createTempDirectory("drydock-lsp-composition");
            navRoot = Files.createTempDirectory("drydock-lsp-nav");
            writeNavRepo();
            navSearch = new SessionSearchService();
            trails = new ExplorerTrailStore(storeDir.resolve("trail.json"));
            host = new RecordingHost(new FakeReviewHost(storeDir.resolve("annotations.json")));
            host.real.diff = diff();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @BeforeEach
    void showBoard() throws TimeoutException {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        host.real.tours.put(TourRecord.fresh(tour(scope.id(), host.real.diff), host.real.diff));
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.real.diff));
        FxSync.waitForFxEvents();
        // See ReviewTourFixture#showBoard: the graph build's completion
        // refreshes the board whenever it lands; wait it out once here.
        FxSync.waitFor(10, TimeUnit.SECONDS,
                () -> !ReviewDiagFxThread.call(() -> view.diagGraphBuildPending(scope.id())));
        FxSync.waitForFxEvents();
    }

    @AfterEach
    void tearDown() {
        interact(view::close);
        diffService.close();
        host.real.store.close();
        host.real.tours.close();
        trails.close();
        navSearch.close();
    }

    // ------------------------------------------------------- the board under test

    /** One ADD row calling a name the change does not declare: a callee to resolve. */
    private static UnifiedDiff diff() {
        return new UnifiedDiff(List.of(file(FILE_JAVA, "  helper();")));
    }

    private static ReviewTour tour(String scopeId, UnifiedDiff diff) {
        return new ReviewTour(scopeId, TourFingerprint.of(diff), List.of(
                new TourStep("s1", "Guarded call", "Why the call changes.",
                        List.of(new TourAnchor(FILE_JAVA, "n1", "n1")), List.of(), List.of())));
    }

    /** One ADD row per hunk, hunk {@code i} at new line {@code i * 10 + 1}; see ReviewTourFixture. */
    private static UnifiedDiff.FileDiff file(String path, String... hunkTexts) {
        List<UnifiedDiff.Hunk> hunks = new ArrayList<>();
        for (int i = 0; i < hunkTexts.length; i++) {
            hunks.add(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                    new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                            OptionalInt.of(i * 10 + 1), hunkTexts[i]))));
        }
        return new UnifiedDiff.FileDiff(path, "M", hunkTexts.length, 0, false, false, hunks);
    }

    /** The little Java checkout the navigation reads: what the provider searches. */
    private void writeNavRepo() throws IOException {
        write("src/Target.java", "package a;", "class Target {", "  void target() {}", "}");
        write("src/UseTarget.java", "package a;", "class UseTarget {",
                "  void go(Target t) {", "    t.target();", "  }", "}");
        write("src/Helper.java", "package a;", "class Helper {", "  void helper() {}", "}");
    }

    private void write(String relative, String... lines) throws IOException {
        Path file = navRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    private List<String> panelTexts() {
        return ReviewDiagFxThread.call(() -> texts(view));
    }

    private static List<String> texts(Parent root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Labeled labeled && labeled.getText() != null && node.isVisible()) {
            out.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child.isVisible()) {
                    collect(child, out);
                }
            }
        }
    }

    /** Waits until the step panel's callee resolved, so no factory call can race the baselines below. */
    private void settleCallees() throws TimeoutException {
        try {
            FxSync.waitFor(10, TimeUnit.SECONDS,
                    () -> panelTexts().stream().anyMatch(text -> text.contains("helper → src/Helper.java:3")));
        } catch (TimeoutException e) {
            throw new TimeoutException("the callee never resolved; panel showed " + panelTexts());
        }
    }

    // -------------------------------------------------- both call sites + factory

    @Test
    void resolveCalleesAsksTheHostFactoryAndResolvesThroughWhatItReturned() throws TimeoutException {
        settleCallees();

        assertTrue(ReviewDiagFxThread.call(() -> !host.providerCalls.isEmpty()),
                "the callee resolution built its provider through the Host factory");
        assertFalse(ReviewDiagFxThread.call(() -> host.providerCalls.getFirst().isEmpty()),
                "the factory was handed the diff's changed lines to mark occurrences against");
        assertTrue(panelTexts().stream().anyMatch(text -> text.contains("helper → src/Helper.java:3")),
                "the callee resolved through the provider the factory returned, over the checkout");
    }

    @Test
    void peekAtSymbolAsksTheHostFactoryAndPeeksThroughWhatItReturned() throws TimeoutException {
        settleCallees();
        int before = ReviewDiagFxThread.call(() -> host.providerCalls.size());

        assertTrue(ReviewDiagFxThread.call(() -> view.diagPeekAtSymbol("target")),
                "tour mode with navigation: the peek path opens");
        assertEquals(before + 1, (int) ReviewDiagFxThread.call(() -> host.providerCalls.size()),
                "the peek's provider construction went through the Host factory, exactly once for this peek");

        FxSync.waitFor(10, TimeUnit.SECONDS, () -> ReviewDiagFxThread.call(view::diagPeekOpen));
        assertTrue(ReviewDiagFxThread.call(() -> view.lookupAll(".peek-title").stream()
                        .map(Labeled.class::cast)
                        .anyMatch(label -> label.getText().contains("Target.java"))),
                () -> "the peek landed, centred on the declaration the factory's provider returned; titles were "
                        + panelTexts());
    }

    // -------------------------------------------------------- the factory default

    @Test
    void theHostDefaultFactoryIsExactlyTheLexicalProvider() throws IOException {
        try (SessionSearchService search = new SessionSearchService()) {
            FakeReviewHost plain = new FakeReviewHost(
                    Files.createTempDirectory("drydock-lsp-host-default").resolve("annotations.json"));
            try {
                UsageProvider provider = plain.usageProvider(
                        new SymbolPeekService(Path.of("/repo"), search), Map.of());
                assertTrue(provider instanceof LexicalUsageProvider,
                        "the default reproduces today's lexical construction -- no LSP wiring in it");
            } finally {
                plain.store.close();
                plain.tours.close();
            }
        }
    }

    // --------------------------------------------------- the seam's compatible shapes

    @Test
    void theOccurrenceCompatConstructorMapsBoundToProvenance() {
        Path file = Path.of("src/Side.java");
        SymbolPeek.Occurrence scoped = new SymbolPeek.Occurrence(file, 3, "t();", false, true);
        assertEquals(Provenance.SCOPED, scoped.provenance(), "a bound row is scope-bound");
        assertTrue(scoped.bound(), "bound stays readable for the existing readers");
        assertEquals(Provenance.MEASURED, new SymbolPeek.Occurrence(file, 3, "t();", false, false).provenance());
        assertEquals(Provenance.MEASURED, new SymbolPeek.Occurrence(file, 3, "t();", false).provenance());
    }

    @Test
    void thePeekThroughTheLexicalProviderIsThePeekOfToday(@TempDir Path repo) throws Exception {
        writeRepo(repo);
        try (SessionSearchService search = new SessionSearchService()) {
            SymbolPeekService service = new SymbolPeekService(repo, search);
            UsageProvider lexical = new LexicalUsageProvider(service, Map.of());

            SymbolPeek direct = service.peek("target", Map.of()).get().orElseThrow();
            SymbolPeek composed = service.peek("target", Map.of(), lexical).get().orElseThrow();

            assertEquals(direct.title(), composed.title(), "the same card, not a near miss");
            assertEquals(direct.relativePath(), composed.relativePath());
            assertEquals(direct.startLine(), composed.startLine());
            assertEquals(direct.resolvedDeclaration(), composed.resolvedDeclaration());
            assertEquals(direct.declarationScopeBound(), composed.declarationScopeBound());
            assertEquals(direct.text(), composed.text());
            assertEquals(direct.inDiffCount(), composed.inDiffCount());
            assertEquals(direct.occurrences().size(), composed.occurrences().size());
            for (int i = 0; i < direct.occurrences().size(); i++) {
                SymbolPeek.Occurrence want = direct.occurrences().get(i);
                SymbolPeek.Occurrence got = composed.occurrences().get(i);
                assertEquals(want.relativePath(), got.relativePath(), "row order preserved");
                assertEquals(want.line(), got.line());
                assertEquals(want.text(), got.text(), "row text preserved");
                assertEquals(want.bound(), got.bound());
                assertEquals(want.provenance(), got.provenance());
            }
        }
    }

    @Test
    void resolvedRowsUpgradeTheirOccurrencesAndTheDeclarationRecentres(@TempDir Path repo) throws Exception {
        writeRepo(repo);
        try (SessionSearchService search = new SessionSearchService()) {
            SymbolPeekService service = new SymbolPeekService(repo, search);
            // A canned provider standing in for the tier above the lexical
            // one -- no LSP wiring, just the shape of an answered seam.
            UsageProvider server = new UsageProvider() {
                @Override
                public CompletableFuture<Optional<Usage>> declaration(String symbol) {
                    return CompletableFuture.completedFuture(Optional.of(
                            new Usage("src/Target.java", 3, "  void target() {}", Provenance.RESOLVED, true)));
                }

                @Override
                public CompletableFuture<List<Usage>> usages(String symbol) {
                    return CompletableFuture.completedFuture(List.of(
                            new Usage("src/UseTarget.java", 5, "t.target();", Provenance.RESOLVED, false),
                            new Usage("src/Helper.java", 3, "void helper() {}", Provenance.SCOPED, false),
                            new Usage("src/Notes.java", 9, "target once", Provenance.MEASURED, false)));
                }
            };

            SymbolPeek peek = service.peek("target", Map.of(), server).get().orElseThrow();

            assertEquals(Path.of("src/Target.java"), peek.relativePath(), "the provider's declaration is the centre");
            assertEquals(3, peek.startLine());
            assertTrue(peek.resolvedDeclaration(), "the re-centre carries the resolved warrant");
            assertTrue(peek.title().contains("resolved declaration"), peek.title());
            List<SymbolPeek.Occurrence> rows = peek.occurrences();
            assertEquals(3, rows.size());
            assertEquals(Provenance.RESOLVED, rows.get(0).provenance());
            assertTrue(rows.get(0).bound(), "a resolved row is as bound as a scoped one");
            assertEquals("t.target();", rows.get(0).text(), "row text preserved through the upgrade");
            assertEquals(Provenance.SCOPED, rows.get(1).provenance(), "a scoped row is never downgraded");
            assertEquals(Provenance.MEASURED, rows.get(2).provenance(), "an unconfirmed row stays a name match");
        }
    }

    private void writeRepo(Path repo) throws IOException {
        String[] target = {"package a;", "class Target {", "  void target() {}", "}"};
        String[] use = {"package a;", "class UseTarget {", "  void go(Target t) {", "    t.target();", "  }", "}"};
        String[] helper = {"package a;", "class Helper {", "  void helper() {}", "}"};
        for (String[] file : List.of(target, use, helper)) {
            Path path = repo.resolve("src").resolve(file[1].substring(6, file[1].length() - 1) + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.join("\n", file) + "\n");
        }
    }

    // ------------------------------------------------------------- the recording host

    /**
     * A {@link SessionReviewView.Host} delegating everything to a real
     * {@link FakeReviewHost} (the real stores, so the board is the real
     * thing) except what this test is about: {@code navigation} answers
     * with a real little Java checkout, and {@code usageProvider} -- the
     * factory under test -- records its calls and returns the interface
     * default, so the assertions are about WHERE the provider came from,
     * never about a stub's behaviour standing in for the default.
     */
    private final class RecordingHost implements SessionReviewView.Host {

        final FakeReviewHost real;
        /** One entry per factory call: the changed-lines map it was handed. */
        final List<Map<Path, Set<Integer>>> providerCalls = new ArrayList<>();

        RecordingHost(FakeReviewHost real) {
            this.real = real;
        }

        @Override
        public UsageProvider usageProvider(SymbolPeekService peeks, Map<Path, Set<Integer>> changedLines) {
            providerCalls.add(Map.copyOf(changedLines));
            return SessionReviewView.Host.super.usageProvider(peeks, changedLines);
        }

        @Override
        public Optional<ReviewNavigation> navigation(ReviewScope scope) {
            return Optional.of(new ReviewNavigation(navRoot, navSearch, trails, "lsp-composition-test"));
        }

        @Override
        public Optional<Region> bodyFor(ReviewScope scope) {
            return real.bodyFor(scope);
        }

        @Override
        public Optional<Integer> openFindings(ReviewScope scope) {
            return real.openFindings(scope);
        }

        @Override
        public void showShortcuts() {
            real.showShortcuts();
        }

        @Override
        public boolean openInExplorer(ReviewScope scope, Path file, int line) {
            return real.openInExplorer(scope, file, line);
        }

        @Override
        public List<ReviewAnnotation> findings(ReviewScope scope) {
            return real.findings(scope);
        }

        @Override
        public Optional<ReviewVerdict> verdict(ReviewScope scope, String hunkDigest) {
            return real.verdict(scope, hunkDigest);
        }

        @Override
        public void setVerdict(ReviewScope scope, List<String> hunkDigests,
                               Optional<ReviewVerdict.Decision> decision, boolean blocked) {
            real.setVerdict(scope, hunkDigests, decision, blocked);
        }

        @Override
        public void confirmStillGood(ReviewScope scope, List<String> hunkDigests) {
            real.confirmStillGood(scope, hunkDigests);
        }

        @Override
        public String currentBase(ReviewScope scope) {
            return real.currentBase(scope);
        }

        @Override
        public BaseMove.Delta baseMove(ReviewScope scope, String recordedBase) {
            return real.baseMove(scope, recordedBase);
        }

        @Override
        public boolean assessedAffected(ReviewScope scope, String hunkDigest, String fromBase, String toBase) {
            return real.assessedAffected(scope, hunkDigest, fromBase, toBase);
        }

        @Override
        public boolean dispatchRecheck(ReviewScope scope, String fromBase, String toBase) {
            return real.dispatchRecheck(scope, fromBase, toBase);
        }

        @Override
        public boolean dispatchRiskCheck(ReviewScope scope, String checkId) {
            return real.dispatchRiskCheck(scope, checkId);
        }

        @Override
        public boolean dispatchTourRefresh(ReviewScope scope, List<String> staleStepIds, int uncoveredHunks) {
            return real.dispatchTourRefresh(scope, staleStepIds, uncoveredHunks);
        }

        @Override
        public SessionActivity agentActivity(ReviewScope scope) {
            return real.agentActivity(scope);
        }

        @Override
        public boolean supportsAutomaticRecheck(ReviewScope scope) {
            return real.supportsAutomaticRecheck(scope);
        }

        @Override
        public boolean assessedMove(ReviewScope scope, String fromBase, String toBase) {
            return real.assessedMove(scope, fromBase, toBase);
        }

        @Override
        public void setResolved(ReviewScope scope, ReviewAnnotation finding, boolean resolved) {
            real.setResolved(scope, finding, resolved);
        }

        @Override
        public boolean postMessage(ReviewScope scope, ReviewAnnotation finding, String body) {
            return real.postMessage(scope, finding, body);
        }

        @Override
        public void addComment(ReviewScope scope, ReviewAnnotation annotation) {
            real.addComment(scope, annotation);
        }

        @Override
        public void setPostToPr(ReviewScope scope, ReviewAnnotation finding, boolean post) {
            real.setPostToPr(scope, finding, post);
        }

        @Override
        public void setTriage(ReviewScope scope, ReviewAnnotation finding, Triage triage, Optional<String> reason) {
            real.setTriage(scope, finding, triage, reason);
        }

        @Override
        public void applyPatch(ReviewScope scope, ReviewAnnotation finding) {
            real.applyPatch(scope, finding);
        }

        @Override
        public void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity) {
            real.overrideSeverity(scope, finding, severity);
        }

        @Override
        public boolean askAgentToFix(ReviewScope scope, String subject, List<ReviewAnnotation> findings) {
            return real.askAgentToFix(scope, subject, findings);
        }

        @Override
        public boolean sendFindingsToAuthor(ReviewScope scope, List<ReviewAnnotation> findings) {
            return real.sendFindingsToAuthor(scope, findings);
        }

        @Override
        public void submit(ReviewScope scope, SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions,
                          BiFunction<String, String, Optional<String>> lineText, ReviewSubmitSheet.Unverified unverified) {
            real.submit(scope, index, decisions, lineText, unverified);
        }

        @Override
        public boolean runReview(ReviewScope scope) {
            return real.runReview(scope);
        }

        @Override
        public boolean requestDeepReview(ReviewScope scope) {
            return real.requestDeepReview(scope);
        }

        @Override
        public boolean requestStepAsk(ReviewScope scope, TourStep step) {
            return real.requestStepAsk(scope, step);
        }

        @Override
        public Optional<TourRecord> tour(ReviewScope scope) {
            return real.tour(scope);
        }

        @Override
        public void updateTour(ReviewScope scope, UnaryOperator<TourRecord> transform) {
            real.updateTour(scope, transform);
        }

        @Override
        public void applyTourVerdicts(ReviewScope scope, Map<String, Optional<ReviewVerdict.Decision>> byDigest) {
            real.applyTourVerdicts(scope, byDigest);
        }

        @Override
        public boolean askAgentAboutPeek(ReviewScope scope, SymbolPeek peek) {
            return real.askAgentAboutPeek(scope, peek);
        }
    }
}

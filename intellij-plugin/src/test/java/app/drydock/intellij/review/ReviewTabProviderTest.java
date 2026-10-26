package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.util.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import java.awt.EventQueue;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider's own decisions (T9), headless: the file-change trigger's
 * filter and the AC15 mismatch memo (kept from P2), the pure halves of the
 * new selection pipeline — the titled request context and the inlay
 * decorations that keep CONFIRMED/DISMISSED cards alive — the pending
 * target handoff, the fix-outcome messages, and the card callbacks' wiring
 * over a real store through the injected seams. The provider's Swing
 * assembly (the dedicated window's content) needs the platform; its shape
 * is pinned by the reflection test here and the live gate.
 */
class ReviewTabProviderTest {

    private static final Path ROOT = Path.of("/repo").toAbsolutePath();
    private static final Instant AT = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final ManagedSessionId SESSION =
            ManagedSessionId.of("7cfda2b6-d909-4f3a-a1b2-000000000001");

    @TempDir
    Path temp;

    // ---- the P2 refresh invariants (unchanged) -------------------------------

    @Test
    void aChangeInTheCheckoutTriggersAReload() {
        assertTrue(ReviewTabProvider.underCheckout(ROOT, ROOT.resolve("src/A.java").toString()));
        assertTrue(ReviewTabProvider.underCheckout(ROOT, ROOT.toString()));
    }

    @Test
    void gitsOwnDirectoryAndOtherCheckoutsDoNot() {
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ROOT.resolve(".git/index").toString()),
                "the index the diff itself touches");
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ROOT.resolve("sub/.git/HEAD").toString()));
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ROOT.resolveSibling("repo-other/A.java").toString()),
                "a sibling directory sharing the name prefix");
        assertFalse(ReviewTabProvider.underCheckout(ROOT, ""));
        assertFalse(ReviewTabProvider.underCheckout(ROOT, null));
    }

    @Test
    void twoIdenticalMismatchesScheduleExactlyOneAutomaticReload() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();

        assertTrue(memo.schedulesReload("src/link", "target\n", "working tree\n"),
                "the first mismatch schedules the reload");
        assertFalse(memo.schedulesReload("src/link", "target\n", "working tree\n"),
                "the same file with the same contents on both sides: the memoized failure, no second reload");
    }

    @Test
    void aDifferentFileOrARealContentChangeSchedulesAnotherReload() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();
        assertTrue(memo.schedulesReload("src/link", "target\n", "tree\n"));

        assertTrue(memo.schedulesReload("src/other", "target\n", "tree\n"),
                "a different file is not the memoized failure");
        assertTrue(memo.schedulesReload("src/link", "changed\n", "tree\n"),
                "the left side changed: a real change, not the memoized failure");
        assertTrue(memo.schedulesReload("src/link", "changed\n", "changed too\n"),
                "the right side changed: a real change, not the memoized failure");
    }

    @Test
    void aChangeSignalResetsTheMemo() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();
        assertTrue(memo.schedulesReload("src/link", "target\n", "tree\n"));

        // What a file-change event, a turn end, a manual Refresh, and a
        // fresh matching selection all clear the memo with.
        memo.reset();

        assertTrue(memo.schedulesReload("src/link", "target\n", "tree\n"),
                "after a genuine change signal the same mismatch again schedules a reload");
    }

    // ---- the dedicated window's shape (no embedded preview) -------------------

    /**
     * The P3 surface: header, tree and status only. The P2 content was a
     * splitter hosting an in-panel diff preview behind the tree — the
     * operator rejected both, so neither the platform's changes-content
     * provider interface (the subtab contract) nor any preview/splitter
     * field may survive in the provider. Reflection, because the content
     * itself needs the platform; the live gate covers the rendering.
     */
    @Test
    void theDedicatedWindowContentCarriesNoEmbeddedDiffPreviewOrSplitter() {
        assertTrue(Arrays.stream(ReviewTabProvider.class.getInterfaces())
                        .noneMatch(i -> i.getSimpleName().equals("ChangesViewContentProvider")),
                "the changesViewContent subtab contract is gone with the subtab");
        for (Field field : ReviewTabProvider.class.getDeclaredFields()) {
            String type = field.getType().getTypeName();
            assertFalse(type.contains("Preview"), field.getName() + " must not be a preview field: " + type);
            assertFalse(type.contains("Splitter"), field.getName() + " must not be a splitter field: " + type);
        }
    }

    // ---- the selection pipeline's pure halves ---------------------------------

    @Test
    void theRequestContextIsTitledByTheFileAndScrollsTheFindingToItsKey() {
        UnifiedDiff.FileDiff file = file("src/A.java",
                hunk(ctx(1, 1, "one"), add(2, "two"), ctx(3, 3, "three"), add(4, "four")));
        String left = "one\nthree\n";
        String right = "one\ntwo\nthree\nfour\n";
        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, left, right);

        // A finding row keyed at a row line: its own key is the scroll target.
        ReviewTabModel.FindingRow finding = findingRow("f1", "n4");
        ReviewDiffRequests.RequestContext context = ReviewTabProvider.requestContextFor(
                finding, file, left, right, fragments);
        assertEquals("src/A.java", context.path(), "the file path titles the reusable tab");
        assertEquals(Side.RIGHT, context.plan().scrollToSide());
        assertEquals(3, context.plan().scrollToLine(),
                "the finding key n4 scrolls to its own 0-based line");
        assertEquals(fragments, context.plan().fragments(),
                "the git-authoritative fragments ride the plan unchanged");

        // A malformed persisted key is a value, not a crash: the file's own
        // scroll target — its first added line, n2 — applies instead.
        ReviewTabModel.FindingRow malformed = findingRow("f2", "x9");
        ReviewDiffRequests.RequestContext fallback = ReviewTabProvider.requestContextFor(
                malformed, file, left, right, fragments);
        assertEquals("src/A.java", fallback.path());
        ReviewDiffRequests.Plan filePlan = ReviewDiffRequests.plan(file, left, right, fragments);
        assertEquals(filePlan.scrollToSide(), fallback.plan().scrollToSide());
        assertEquals(filePlan.scrollToLine(), fallback.plan().scrollToLine(),
                "a malformed key falls back to the file's own scroll target");

        // A file row selection: the file's own scroll, the same title.
        ReviewDiffRequests.RequestContext fileRow = ReviewTabProvider.requestContextFor(
                file, file, left, right, fragments);
        assertEquals("src/A.java", fileRow.path());
        assertEquals(filePlan.scrollToLine(), fileRow.plan().scrollToLine());
        assertEquals(filePlan.scrollToSide(), fileRow.plan().scrollToSide());
    }

    @Test
    void theDecorationsCarryEveryUnresolvedFindingOfTheFileWhateverItsTriage() {
        UnifiedDiff.FileDiff file = file("src/A.java",
                hunk(ctx(1, 1, "one"), add(2, "two"), del(3), ctx(4, 3, "last")));
        ReviewAnnotation proposed = finding("f-proposed", "src/A.java", "n2", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        ReviewAnnotation confirmed = finding("f-confirmed", "src/A.java", "n2", Triage.CONFIRMED,
                AnnotationStatus.OPEN);
        ReviewAnnotation dismissed = finding("f-dismissed", "src/A.java", "n2", Triage.DISMISSED,
                AnnotationStatus.OPEN);
        ReviewAnnotation sentConfirmed = finding("f-sent", "src/A.java", "n2", Triage.CONFIRMED,
                AnnotationStatus.SENT);
        ReviewAnnotation resolved = finding("f-resolved", "src/A.java", "n2", Triage.PROPOSED,
                AnnotationStatus.RESOLVED);
        ReviewAnnotation otherFile = finding("f-other", "src/B.java", "n1", Triage.PROPOSED,
                AnnotationStatus.OPEN);

        ReviewTabModel.Snapshot snapshot = ReviewTabModel.snapshot(new ReviewTabModel.Inputs(
                diff(file),
                List.of(proposed, confirmed, dismissed, sentConfirmed, resolved, otherFile),
                List.of(), Optional.empty()));
        ReviewTabModel.FileRow row = snapshot.files().stream()
                .filter(f -> f.path().equals("src/A.java")).findFirst().orElseThrow();

        ReviewDiffInlays.Decorations decorations = ReviewTabProvider.decorationsFor(row,
                List.of(proposed, confirmed, dismissed, sentConfirmed, resolved, otherFile));

        assertEquals("src/A.java", decorations.path());
        assertEquals(Set.of("n1", "n2", "o3", "n3"), decorations.rowKeys(),
                "the current diff's row keys, both sides");
        assertEquals(ReviewFindingPlacement.firstHunkFallback(file), decorations.firstHunkFallback());
        assertEquals(List.of(proposed, confirmed, dismissed, sentConfirmed), decorations.findings(),
                "every unresolved finding of the file — CONFIRMED and DISMISSED keep their cards and "
                        + "their Undo — while resolved findings and other files' findings stay out");
    }

    @Test
    void aPendingFindingTargetSelectsItsFindingAndNoTargetSelectsNothing() {
        List<String[]> selections = new ArrayList<>();
        BiConsumer<String, String> selectFinding = (file, id) -> selections.add(new String[] {file, id});

        ReviewTabProvider.consumeTarget(Optional.empty(), selectFinding);
        assertTrue(selections.isEmpty(), "no recorded target: the selection is unchanged");

        ReviewTabProvider.consumeTarget(
                Optional.of(new ReviewTabOpener.FindingTarget("src/A.java", "f1")), selectFinding);
        assertEquals(1, selections.size(), "the target is consumed once");
        assertEquals("src/A.java", selections.get(0)[0]);
        assertEquals("f1", selections.get(0)[1]);
    }

    // ---- the decoration generation guard --------------------------------------

    /**
     * T9's fix: every asynchronous decoration producer is generation-safe,
     * not just the selection's identity check. The tokens below model the
     * deterministic queue of old and new completions — an older
     * computation mints first, a newer one after it, and the applies are
     * checked in the order the EDT would run them — and the newer
     * file's/state's apply wins while the older one's is always dropped.
     */
    @Test
    void aNewerSelectionsDecorationsWinAndTheOlderFileNeverReapplies() {
        ReviewTabProvider.DecorationGate gate = new ReviewTabProvider.DecorationGate();
        ReviewTabProvider.DecorationGate.Token olderFile = gate.begin("rs_a", "src/A.java");
        // The selection moved to another file before the older file's
        // completion landed on the EDT.
        ReviewTabProvider.DecorationGate.Token newerFile = gate.begin("rs_a", "src/B.java");
        assertTrue(gate.applies(newerFile, "rs_a", "src/B.java"),
                "the newer file's decorations apply");
        assertFalse(gate.applies(olderFile, "rs_a", "src/A.java"),
                "a completion computed for the older file never overwrites the newer file's decorations");
        // The other queueing order too — the older apply queued behind the
        // newer one — still only the newer token is current.
        assertFalse(gate.applies(olderFile, "rs_a", "src/A.java"),
                "the older file's decorations cannot reapply after the newer file's landed");
    }

    @Test
    void staleSamePathAnnotationsCannotReapplyOverTheFresherState() {
        ReviewTabProvider.DecorationGate gate = new ReviewTabProvider.DecorationGate();
        // The selection's build read the file's annotations first…
        ReviewTabProvider.DecorationGate.Token staleRead = gate.begin("rs_a", "src/A.java");
        // …then a store/scope/snapshot refresh re-read the same file: the
        // same path, a fresher read, a newer generation.
        ReviewTabProvider.DecorationGate.Token freshRead = gate.begin("rs_a", "src/A.java");
        assertTrue(gate.applies(freshRead, "rs_a", "src/A.java"),
                "the fresher same-path read applies");
        assertFalse(gate.applies(staleRead, "rs_a", "src/A.java"),
                "the stale same-path annotations cannot reapply over the fresher state's decorations");
    }

    @Test
    void aBareSelectionChangeAScopeChangeAndAPathChangeEachDropAnInFlightApply() {
        ReviewTabProvider.DecorationGate gate = new ReviewTabProvider.DecorationGate();
        ReviewTabProvider.DecorationGate.Token inFlight = gate.begin("rs_a", "src/A.java");
        // The selection changed to a row nothing is scheduled for (the
        // file left the snapshot, say): no newer computation will come —
        // the generation bump alone retires the in-flight one.
        gate.invalidate();
        assertFalse(gate.applies(inFlight, "rs_a", "src/A.java"),
                "a selection change invalidates every in-flight decoration computation");

        // A scope re-resolved: an in-flight computation for the old scope
        // is stale even at the newest generation.
        ReviewTabProvider.DecorationGate.Token oldScope = gate.begin("rs_a", "src/A.java");
        assertFalse(gate.applies(oldScope, "rs_b", "src/A.java"),
                "decorations computed for an older scope never apply to the current one");
        assertTrue(gate.applies(oldScope, "rs_a", "src/A.java"),
                "the scope it was computed for is still current: it applies");

        // The selected file changed without a new generation: the path
        // check alone retires the in-flight computation.
        ReviewTabProvider.DecorationGate.Token otherPath = gate.begin("rs_b", "src/B.java");
        assertFalse(gate.applies(otherPath, "rs_b", "src/A.java"),
                "decorations computed for the previously selected path never apply");
        assertTrue(gate.applies(otherPath, "rs_b", "src/B.java"));
    }

    // ---- the batched decisions hand-off (pure pieces) ---------------------------

    /**
     * The bar's counts: only unresolved, decided, not-yet-SENT findings
     * count — exactly what {@code sendDecisions} will include, so the bar
     * never advertises a batch the send would refuse.
     */
    @Test
    void pendingDecisionsCountsOnlyUndecidedUnsentFindings() {
        List<ReviewAnnotation> findings = List.of(
                finding("a1", "src/A.java", "n1", Triage.CONFIRMED, AnnotationStatus.OPEN),
                finding("a2", "src/A.java", "n2", Triage.DISMISSED, AnnotationStatus.OPEN),
                finding("a3", "src/A.java", "n3", Triage.PROPOSED, AnnotationStatus.OPEN),
                finding("a4", "src/A.java", "n4", Triage.CONFIRMED, AnnotationStatus.SENT),
                finding("a5", "src/A.java", "n5", Triage.CONFIRMED, AnnotationStatus.RESOLVED));

        ReviewTabProvider.PendingDecisions pending = ReviewTabProvider.pendingDecisions(findings);

        assertEquals(1, pending.toFix());
        assertEquals(1, pending.dismissed());
        assertEquals(2, pending.total());
        assertEquals(0, ReviewTabProvider.pendingDecisions(List.of()).total());
    }

    /** The hand-off's subject: the pull request when the scope carries one, else the checkout's name. */
    @Test
    void theHandoffSubjectNamesThePullRequestOrTheCheckout() {
        ReviewScope withPr = new ReviewScope("rs_a", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.empty(), "base", "head",
                Optional.of(new ReviewScope.PullRequestRef(858, Optional.empty())),
                Optional.empty(), Optional.empty());
        ReviewScope withoutPr = new ReviewScope("rs_a", ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.empty(), "base", "head", Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals("PR #858", ReviewTabProvider.subjectOf(withPr));
        assertEquals("repo", ReviewTabProvider.subjectOf(withoutPr));
    }

    // ---- the fix outcome messages ----------------------------------------------

    /**
     * The never-pretend rule, one message per outcome: a delivered prompt
     * needs none (the store change rebuild shows SENT), and no-session, an
     * empty hand-off, a refusal and a failed write are four distinct,
     * never-blank messages — only the failed write is an error.
     */
    @Test
    void everyNonDeliveredFixOutcomeSurfacesADistinctMessage() {
        assertNull(ReviewTabProvider.TriageCards.fixOutcome(
                ReviewTriageActions.FixOutcome.State.DELIVERED));

        List<ReviewTabProvider.TriageCards.Surfaced> surfaced = Arrays.stream(
                        ReviewTriageActions.FixOutcome.State.values())
                .filter(state -> state != ReviewTriageActions.FixOutcome.State.DELIVERED)
                .map(ReviewTabProvider.TriageCards::fixOutcome)
                .toList();
        assertEquals(4, surfaced.size());
        assertEquals(4, surfaced.stream().map(ReviewTabProvider.TriageCards.Surfaced::text).distinct().count(),
                "each outcome carries its own message");
        assertTrue(surfaced.stream().allMatch(message ->
                        !message.text().isBlank() && message.text().endsWith(".")));
        assertEquals(1, surfaced.stream()
                        .filter(message -> message.notice() == ReviewTabProvider.TriageCards.Notice.ERROR)
                        .count(),
                "only the failed write is an error; the rest are states the human can act on");
        assertTrue(surfaced.stream().anyMatch(message ->
                        message.notice() == ReviewTabProvider.TriageCards.Notice.WARNING
                                && message.text().contains("No agent session")));
    }

    // ---- the card callbacks' wiring over a real store ---------------------------

    private AnnotationStore store;
    private ReviewTriageActions actions;
    private ReviewTabProvider.TriageCards cards;
    private final List<ReviewTabProvider.TriageCards.Notice> notices = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private PromptOutcome answer = PromptOutcome.DELIVERED;

    @BeforeEach
    void setUpCards() {
        store = new AnnotationStore(temp.resolve("annotations.json"));
        prompts.clear();
        answer = PromptOutcome.DELIVERED;
        actions = new ReviewTriageActions(store, Runnable::run,
                (session, prompt) -> {
                    prompts.add(prompt);
                    if (answer == null) {
                        throw new IllegalStateException("boom: the prompt write threw");
                    }
                    return answer;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        notices.clear();
        messages.clear();
        cards = new ReviewTabProvider.TriageCards(() -> actions,
                () -> scope(Optional.of(SESSION)), () -> store,
                (notice, text) -> {
                    notices.add(notice);
                    messages.add(text);
                });
    }

    @AfterEach
    void tearDownCards() {
        try {
            store.flushPendingSaves();
        } catch (RuntimeException expectedWhenClosed) {
            // not closed by these tests
        }
        store.close();
    }

    @Test
    void confirmDismissUndoAndCommentRoundTripThroughTheStoreAndClearTheCard() {
        ReviewAnnotation finding = finding("f1", "src/Main.java", "n10", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        store.upsert(finding);
        ReviewAnnotation.Key key = finding.key();
        FindingInlayCard triageCard = onEdtGet(() -> new FindingInlayCard(finding, Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC), new FindingInlayCard.Callbacks() {
            @Override public void confirm() { }
            @Override public void dismiss() { }
            @Override public void undo() { }
            @Override public void comment(String text) { }
        }));

        onEdt(() -> cards.confirm(key, triageCard));
        assertEquals(Triage.CONFIRMED, store.byKey(key).orElseThrow().triage());
        assertFalse(triageCard.isBusy(), "the card's progress cleared on completion");
        assertTrue(notices.isEmpty() && messages.isEmpty(), "a successful update needs no message");

        onEdt(() -> cards.comment(key, triageCard, "Please re-check this"));
        ReviewAnnotation commented = store.byKey(key).orElseThrow();
        assertEquals(2, commented.thread().size(), "the agent's message plus the human's comment");
        assertEquals("You", commented.thread().get(1).author());
        assertEquals("Please re-check this", commented.thread().get(1).text());
        assertFalse(triageCard.isBusy());
        assertTrue(messages.isEmpty());

        onEdt(() -> cards.dismiss(key, triageCard));
        assertEquals(Triage.DISMISSED, store.byKey(key).orElseThrow().triage());
        assertFalse(triageCard.isBusy());

        onEdt(() -> cards.undo(key, triageCard));
        assertEquals(Triage.PROPOSED, store.byKey(key).orElseThrow().triage());
        assertFalse(triageCard.isBusy());
        assertTrue(messages.isEmpty(), "no outcome was surfaced along the whole round trip");
    }

    @Test
    void aMissingFindingSurfacesDistinctlyForBothTriageAndComment() {
        ReviewAnnotation gone = finding("gone", "src/Main.java", "n10", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        ReviewAnnotation.Key missing = gone.key(); // never upserted: no finding lives under it
        FindingInlayCard card = card(gone);

        onEdt(() -> cards.comment(missing, card, "hello?"));
        assertFalse(card.isBusy());
        assertEquals(List.of(ReviewTabProvider.TriageCards.Notice.WARNING), notices);
        assertTrue(messages.get(0).contains("no longer exists"), messages.get(0));
        assertTrue(messages.get(0).contains("comment was not saved"), messages.get(0));

        notices.clear();
        messages.clear();
        onEdt(() -> cards.confirm(missing, card));
        assertFalse(card.isBusy());
        assertEquals(1, messages.size(), "a stale triage write says the finding is gone, once");
        assertTrue(messages.get(0).contains("no longer exists"), messages.get(0));

    }

    // ---- synchronous scheduling failures clear the card too ---------------------

    /**
     * A synchronous failure while an action's work is being scheduled —
     * the triage executor rejecting, the one case no future ever sees,
     * because {@code ReviewTriageActions.onExecutor} throws before the
     * future exists — completes the card and surfaces the ERROR with the
     * root cause, for every card action; nothing is ever written.
     */
    @Test
    void aSynchronouslyRejectedActionExecutorClearsTheCardAndSurfacesTheRootCause() {
        ReviewAnnotation finding = finding("f1", "src/Main.java", "n10", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        store.upsert(finding);
        ReviewTriageActions rejecting = new ReviewTriageActions(store, task -> {
            throw new RejectedExecutionException("executor is shut down");
        }, (session, prompt) -> {
            prompts.add(prompt);
            return answer;
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        ReviewTabProvider.TriageCards rejectingCards = new ReviewTabProvider.TriageCards(
                () -> rejecting, () -> scope(Optional.of(SESSION)), () -> store,
                (notice, text) -> {
                    notices.add(notice);
                    messages.add(text);
                });

        FindingInlayCard confirming = card(finding);
        onEdt(() -> rejectingCards.confirm(finding.key(), confirming));
        assertFalse(confirming.isBusy(), "the card's progress cleared on the synchronous rejection");
        assertEquals(List.of(ReviewTabProvider.TriageCards.Notice.ERROR), notices);
        assertTrue(messages.get(0).startsWith("Updating the finding failed: "), messages.get(0));
        assertTrue(messages.get(0).endsWith("executor is shut down"), messages.get(0));
        assertEquals(Triage.PROPOSED, store.byKey(finding.key()).orElseThrow().triage(),
                "nothing was written");

        notices.clear();
        messages.clear();
        FindingInlayCard dismissing = card(finding);
        onEdt(() -> rejectingCards.dismiss(finding.key(), dismissing));
        assertFalse(dismissing.isBusy());
        assertEquals(List.of(ReviewTabProvider.TriageCards.Notice.ERROR), notices);
        assertTrue(messages.get(0).startsWith("Updating the finding failed: "), messages.get(0));

        notices.clear();
        messages.clear();
        FindingInlayCard undoing = card(finding);
        onEdt(() -> rejectingCards.undo(finding.key(), undoing));
        assertFalse(undoing.isBusy());
        assertEquals(List.of(ReviewTabProvider.TriageCards.Notice.ERROR), notices);
        assertTrue(messages.get(0).startsWith("Updating the finding failed: "), messages.get(0));

        notices.clear();
        messages.clear();
        FindingInlayCard commenting = card(finding);
        onEdt(() -> rejectingCards.comment(finding.key(), commenting, "still there?"));
        assertFalse(commenting.isBusy());
        assertEquals(List.of(ReviewTabProvider.TriageCards.Notice.ERROR), notices);
        assertTrue(messages.get(0).startsWith("Saving the comment failed: "), messages.get(0));
        assertTrue(messages.get(0).endsWith("executor is shut down"), messages.get(0));
        assertEquals(1, store.byKey(finding.key()).orElseThrow().thread().size(),
                "the comment was not written");
    }

    /**
     * The catch split the checker demanded (T9): a synchronous
     * {@link RuntimeException} that is not a rejection — a plain failure
     * thrown while the work is being scheduled — is still an operational
     * failure, surfaced as the ERROR dialog with the root cause.
     */
    @Test
    void aSynchronousRuntimeExceptionIsStillSurfacedAsAnErrorDialog() {
        ReviewAnnotation finding = finding("f1", "src/Main.java", "n10", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        store.upsert(finding);
        ReviewTriageActions throwing = new ReviewTriageActions(store, task -> {
            throw new IllegalStateException("boom: not a rejection");
        }, (session, prompt) -> {
            prompts.add(prompt);
            return answer;
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        ReviewTabProvider.TriageCards throwingCards = new ReviewTabProvider.TriageCards(
                () -> throwing, () -> scope(Optional.of(SESSION)), () -> store,
                (notice, text) -> {
                    notices.add(notice);
                    messages.add(text);
                });

        FindingInlayCard confirming = card(finding);
        onEdt(() -> throwingCards.confirm(finding.key(), confirming));

        assertFalse(confirming.isBusy(), "the card's progress cleared on the synchronous RuntimeException");
        assertEquals(List.of(ReviewTabProvider.TriageCards.Notice.ERROR), notices);
        assertTrue(messages.get(0).startsWith("Updating the finding failed: "), messages.get(0));
        assertTrue(messages.get(0).endsWith("boom: not a rejection"), messages.get(0));
        assertTrue(prompts.isEmpty(), "nothing was submitted");
    }

    /**
     * The Error half of the split (T9): an {@link Error} is never
     * converted into a dialog — surfacing it as one swallows it. On the
     * triage action's own scheduling path the card clears and the Error is
     * rethrown, so it propagates instead of dying in the UI.
     */
    @Test
    void aSynchronousErrorClearsTheCardAndIsRethrownNeverBecomingADialog() {
        ReviewAnnotation finding = finding("f1", "src/Main.java", "n10", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        store.upsert(finding);
        ReviewTriageActions failing = new ReviewTriageActions(store, task -> {
            throw new AssertionError("linkage died");
        }, (session, prompt) -> {
            prompts.add(prompt);
            return answer;
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        ReviewTabProvider.TriageCards errorCards = new ReviewTabProvider.TriageCards(
                () -> failing, () -> scope(Optional.of(SESSION)), () -> store,
                (notice, text) -> {
                    notices.add(notice);
                    messages.add(text);
                });

        // The Error from the triage action's own scheduling: rethrown, not dialogued.
        FindingInlayCard confirming = card(finding);
        Throwable[] fromConfirm = new Throwable[1];
        onEdt(() -> {
            try {
                errorCards.confirm(finding.key(), confirming);
            } catch (Error e) {
                fromConfirm[0] = e;
            }
        });
        assertTrue(fromConfirm[0] instanceof AssertionError,
                "the Error is rethrown, not converted into a dialog: " + fromConfirm[0]);
        assertFalse(confirming.isBusy(), "the card's progress cleared before the rethrow");

        assertTrue(notices.isEmpty() && messages.isEmpty(), "no dialog was raised for any Error");
        assertTrue(prompts.isEmpty(), "nothing was submitted");
        assertEquals(AnnotationStatus.OPEN, store.byKey(finding.key()).orElseThrow().status(),
                "nothing was marked");
    }

    // ---- fixtures ---------------------------------------------------------------

    private static UnifiedDiff.Line ctx(int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line add(int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line del(int oldLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), "gone " + oldLine);
    }

    private static UnifiedDiff.Hunk hunk(UnifiedDiff.Line... lines) {
        return new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(lines));
    }

    private static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 1, false, false, List.of(hunks));
    }

    private static UnifiedDiff diff(UnifiedDiff.FileDiff... files) {
        return new UnifiedDiff(List.of(files));
    }

    private static ReviewTabModel.FindingRow findingRow(String id, String startKey) {
        ReviewAnnotation annotation = finding(id, "src/A.java", startKey, Triage.PROPOSED,
                AnnotationStatus.OPEN);
        return new ReviewTabModel.FindingRow(id, Severity.QUESTION, annotation.displayTitle(),
                startKey, Optional.empty(), Optional.empty(), annotation);
    }

    private static ReviewAnnotation finding(String id, String file, String startKey, Triage triage,
                                            AnnotationStatus status) {
        return new ReviewAnnotation("rs_a", id, file, startKey, startKey,
                Severity.QUESTION, Confidence.HIGH, Optional.of("Title " + id), "Claude", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", AT, "body of " + id)),
                Optional.empty(), status, Optional.empty(), false,
                triage, Optional.empty());
    }

    private static ReviewScope scope(Optional<ManagedSessionId> session) {
        return new ReviewScope("rs_a", ReviewScope.Kind.WORKTREE, Path.of("/repo"),
                Optional.of(Path.of("/repo/.wt/a")), "base0", "head0",
                Optional.empty(), session, Optional.empty());
    }

    private static FindingInlayCard card(ReviewAnnotation finding) {
        return onEdtGet(() -> new FindingInlayCard(finding, Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC), new FindingInlayCard.Callbacks() {
            @Override public void confirm() { }
            @Override public void dismiss() { }
            @Override public void undo() { }
            @Override public void comment(String text) { }
        }));
    }

    private static void onEdt(Runnable op) {
        if (EventQueue.isDispatchThread()) {
            op.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the EDT", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("the EDT runnable failed", e.getCause());
        }
    }

    private static <T> T onEdtGet(Supplier<T> op) {
        Object[] out = new Object[1];
        onEdt(() -> out[0] = op.get());
        @SuppressWarnings("unchecked")
        T value = (T) out[0];
        return value;
    }

}

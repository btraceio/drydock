package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.InboxModel;
import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.Confidence;
import app.drydock.review.DiffSelection;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import com.intellij.diff.fragments.LineFragment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.function.Function;
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

    @Test
    void aHunkThatChangedSinceShowsTheNoticeEveryTimeAndReloadsOnceUntilTheMemoResets() {
        ReviewTabProvider.MismatchMemo memo = new ReviewTabProvider.MismatchMemo();
        List<String> calls = new ArrayList<>();
        Runnable notice = () -> calls.add("notice");
        Runnable reload = () -> calls.add("reload");

        ReviewTabProvider.changedSince(memo, "src/A.java", "left\n", "right\n", notice, reload);
        ReviewTabProvider.changedSince(memo, "src/A.java", "left\n", "right\n", notice, reload);
        assertEquals(List.of("notice", "reload", "notice"), calls, "the repeat mismatch reloads nothing");

        memo.reset();
        ReviewTabProvider.changedSince(memo, "src/A.java", "left\n", "right\n", notice, reload);
        assertEquals(List.of("notice", "reload", "notice", "notice", "reload"), calls);
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

    @Test
    void aRegistryChangeToTheShownScopeAdoptsTheRegistrysCopy() {
        ReviewScope shown = scope(Optional.empty());
        ReviewScope bound = scope(Optional.of(ManagedSessionId.newId()));
        List<String> asked = new ArrayList<>();
        Function<String, Optional<ReviewScope>> byId = id -> {
            asked.add(id);
            return id.equals(shown.id()) ? Optional.of(bound) : Optional.empty();
        };

        assertEquals(Optional.of(bound), ReviewTabProvider.adopted(shown, shown.id(), byId),
                "a session bound after the content resolved its scope: the registry's copy wins");
        assertEquals(Optional.empty(), ReviewTabProvider.adopted(shown, "rs_other", byId),
                "another scope's change adopts nothing");
        assertEquals(Optional.empty(), ReviewTabProvider.adopted(null, shown.id(), byId),
                "before a scope is loaded there is nothing to adopt");
        assertEquals(List.of(shown.id()), asked, "the registry is read only for the shown scope");
    }

    @Test
    void aBackToReviewTargetNamesItsFileAtItsLineAndAGoneFileNothing() {
        UnifiedDiff.FileDiff file = file("src/A.java", hunk(ctx(1, 1, "one"), add(2, "two")));
        ReviewTabModel.Snapshot rows = ReviewTabModel.snapshot(new ReviewTabModel.Inputs(diff(file), List.of(),
                List.of(), Optional.empty()));

        ReviewTabProvider.FileAt at = ReviewTabProvider.fileAt(rows,
                new ReviewTabOpener.FileTarget("src/A.java", "n2")).orElseThrow();
        assertEquals("src/A.java", at.file().path());
        assertEquals("n2", at.scrollKey());
        assertEquals(Optional.empty(), ReviewTabProvider.fileAt(rows,
                new ReviewTabOpener.FileTarget("src/Gone.java", "n2")));
    }

    @Test
    void aHunkDiffCarriesEveryUnresolvedFindingOnItsRowsWhateverItsTriage() {
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

        ReviewDiffInlays.Decorations decorations = ReviewTabProvider.hunkDecorations(row, 0,
                List.of(proposed, confirmed, dismissed, sentConfirmed, resolved, otherFile));

        assertEquals("src/A.java", decorations.path());
        assertEquals(Set.of("n1", "n2", "o3", "n3"), decorations.rowKeys(),
                "the current diff's row keys, both sides");
        assertEquals(ReviewFindingPlacement.firstHunkFallback(file), decorations.firstHunkFallback());
        assertEquals(List.of(proposed, confirmed, dismissed, sentConfirmed), decorations.findings(),
                "every unresolved finding on the hunk — CONFIRMED and DISMISSED keep their cards and "
                        + "their Undo — while resolved findings and other files' findings stay out");
    }

    @Test
    void aHunkDiffsFragmentsAreItsOwnHunksAtTheirAbsoluteLines() {
        UnifiedDiff.Hunk first = hunk(ctx(1, 1, "one"), add(2, "two"), add(3, "three"), ctx(2, 4, "four"));
        UnifiedDiff.Hunk second = hunk(ctx(9, 11, "nine"), del(10), ctx(11, 12, "eleven"));
        UnifiedDiff.FileDiff file = file("src/A.java", first, second);

        UnifiedDiff.FileDiff onlySecond = ReviewTabProvider.onlyHunk(file, 1);

        assertEquals(List.of(second), onlySecond.hunks());
        assertEquals(List.of(), ReviewTabProvider.onlyHunk(file, 2).hunks(), "an index past the end: no hunk");
        List<LineFragment> fragments = ReviewFragments.fragmentsFor(onlySecond,
                "one\nfour\nl3\nl4\nl5\nl6\nl7\nl8\nnine\nten\neleven\n",
                "one\ntwo\nthree\nfour\nl3\nl4\nl5\nl6\nl7\nl8\nnine\neleven\n");
        assertEquals(1, fragments.size(), "the first hunk is not a change in the hunk diff");
        assertEquals(9, fragments.getFirst().getStartLine1(), "the deletion at old line 10 (0-based 9)");
        assertEquals(10, fragments.getFirst().getEndLine1());
        assertEquals(11, fragments.getFirst().getStartLine2(), "paired after the omitted hunk's two insertions");
        assertEquals(11, fragments.getFirst().getEndLine2());
    }

    @Test
    void aHunkDiffsDecorationsAreTheFindingsOnItsHunksRows() {
        UnifiedDiff.Hunk first = hunk(ctx(1, 1, "one"), add(2, "two"), ctx(2, 3, "three"));
        UnifiedDiff.Hunk second = hunk(ctx(9, 10, "nine"), del(10), ctx(11, 11, "eleven"));
        UnifiedDiff.FileDiff file = file("src/A.java", first, second);
        ReviewAnnotation onFirst = finding("f-first", "src/A.java", "n2", Triage.PROPOSED, AnnotationStatus.OPEN);
        ReviewAnnotation onSecond = finding("f-second", "src/A.java", "o10", Triage.PROPOSED,
                AnnotationStatus.OPEN);
        ReviewTabModel.FileRow row = ReviewTabModel.snapshot(new ReviewTabModel.Inputs(diff(file),
                List.of(onFirst, onSecond), List.of(), Optional.empty())).files().getFirst();

        ReviewDiffInlays.Decorations decorations = ReviewTabProvider.hunkDecorations(row, 1,
                List.of(onFirst, onSecond));

        assertEquals(Set.of("n10", "o10", "n11"), decorations.rowKeys(), "only the second hunk's rows");
        assertEquals(List.of(onSecond), decorations.findings(), "the first hunk's finding stays off the hunk diff");
        assertEquals(ReviewFindingPlacement.firstHunkFallback(ReviewTabProvider.onlyHunk(file, 1)),
                decorations.firstHunkFallback());
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
        // The bar and the send share one membership test: the counts can
        // never advertise a batch sendDecisions would refuse.
        assertEquals((int) findings.stream()
                        .filter(ReviewTriageActions::isPendingDecision).count(),
                pending.total());
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


    @Test
    void aScopeCarryingAPullRequestIsReviewedAsThePullRequest() {
        ReviewScope local = new ReviewScope("rs_1", ReviewScope.Kind.WORKTREE, Path.of("/r"), Optional.of(Path.of("/w")),
                "main", "task", Optional.empty(), Optional.empty(), Optional.empty());
        ReviewScope pr = new ReviewScope("rs_2", ReviewScope.Kind.WORKTREE, Path.of("/r"), Optional.of(Path.of("/w")),
                "main", "pr-12", Optional.of(new ReviewScope.PullRequestRef(12, Optional.empty())), Optional.empty(),
                Optional.empty());
        assertEquals(WorkbenchState.Mode.LOCAL, ReviewTabProvider.modeOf(local));
        assertEquals(WorkbenchState.Mode.PR, ReviewTabProvider.modeOf(pr));
        assertEquals("PR #12", ReviewTabProvider.subjectOf(pr));
    }

    @Test
    void aHunkCommentWaitsForThePrInAPullRequestReviewAndGoesToTheSessionInALocalOne() {
        ReviewScope local = scope(Optional.of(SESSION));
        ReviewScope pr = new ReviewScope("rs_a", ReviewScope.Kind.WORKTREE, Path.of("/repo"),
                Optional.of(Path.of("/repo/.wt/a")), "base0", "head0",
                Optional.of(new ReviewScope.PullRequestRef(12, Optional.empty())), Optional.of(SESSION),
                Optional.empty());
        DiffSelection.Range range = new DiffSelection.Range("src/A.java", "n2", "n3", false);

        ReviewAnnotation inPr = ReviewTabProvider.humanComment(pr, range, "Rename it", Severity.QUESTION, AT);
        OutboxModel.Snapshot prLane = OutboxModel.snapshot(List.of(inPr), true, true);
        assertEquals(List.of(inPr), prLane.forPr().stream().map(OutboxModel.Item::finding).toList());
        assertTrue(prLane.toFix().isEmpty(), "a PR review's comment is not sent to the session");

        ReviewAnnotation inLocal = ReviewTabProvider.humanComment(local, range, "Rename it", Severity.QUESTION, AT);
        OutboxModel.Snapshot localLane = OutboxModel.snapshot(List.of(inLocal), true, false);
        assertEquals(List.of(inLocal), localLane.toFix().stream().map(OutboxModel.Item::finding).toList());
        assertFalse(inLocal.postToPr());
        assertEquals(List.of(new ReviewAnnotation.Message("You", AT, "Rename it")), inLocal.thread());
    }

    @Test
    void theInboxFileScopeFollowsTheHunkTheFlowShows() {
        FlowModel.Hunk onB = new FlowModel.Hunk("h", "src/B.java", 0, "d", "1", "n1", 0, 1, Set.of("n1"), 0, false);
        assertEquals(Optional.of("src/B.java"), ReviewTabProvider.followedFile(Optional.of(onB), "src/A.java"));
        assertEquals(Optional.of("src/B.java"), ReviewTabProvider.followedFile(Optional.of(onB), null));
        assertEquals(Optional.empty(), ReviewTabProvider.followedFile(Optional.of(onB), "src/B.java"),
                "the same file rebuilds nothing");
        assertEquals(Optional.empty(), ReviewTabProvider.followedFile(Optional.empty(), "src/A.java"),
                "the finish screen keeps the last file");

        ReviewAnnotation onA = finding("a", "src/A.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN);
        ReviewAnnotation onBFinding = finding("b", "src/B.java", "n1", Triage.PROPOSED, AnnotationStatus.OPEN);
        WorkbenchState state = WorkbenchState.of("repo", WorkbenchState.Mode.LOCAL,
                new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL,
                ReviewTabProvider.followedFile(Optional.of(onB), "src/A.java"), WorkbenchState.View.CHANGES_ONLY,
                List.of(onA, onBFinding),
                new WorkbenchState.InboxChoice(InboxModel.Tab.TO_TRIAGE, InboxModel.Scope.FILE));
        assertEquals(List.of(onBFinding), state.inbox().cards().stream().map(InboxModel.Card::finding).toList());
    }
}

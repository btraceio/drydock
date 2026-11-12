package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static app.drydock.tasks.TaskPrompt.Kind.CLAUDE;
import static app.drydock.tasks.TaskPrompt.Kind.CODEX;
import static app.drydock.tasks.TaskPrompt.Kind.PI;
import static app.drydock.tasks.TaskPrompt.stackedPrompt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance matrix for {@link TaskPrompt#stackedPrompt} (S1/S2/S3 of
 * docs/StackedPrExecution.md): one prompt per launch unit, one stacked
 * branch per task named dd/&lt;task-id&gt;, the first branch's start point
 * (base branch for a stacked unit, default branch for a root), each later
 * branch starting at the previous member's branch, push-per-task, the
 * review gate that holds statuses but not branches, and the untrusted
 * discipline kept for titles. Plans and documents come from the public
 * constructors: no files, no store, no mocking; assertions are on the
 * actual prompt strings.
 */
class StackedPromptTest {

    private static final RepositoryId REPO = RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final String BOUNDARY = "<<UNTRUSTED-TASK-FIELD";

    // ---- fixtures -------------------------------------------------------

    private static TaskDocument doc(String id, String title, DoneBy doneBy, String... after) {
        return new TaskDocument(TaskId.of(id), REPO, title, TaskStatus.OPEN, doneBy, Optional.empty(),
                List.of(after), List.of(), "", "");
    }

    /** A fork in dependency order: chain then fork, as StackUnits splits it. */
    private static StartPlan forkPlan() {
        List<TaskDocument> tasks = List.of(doc("chain-a", "Task chain-a", DoneBy.HUMAN),
                doc("chain-b", "Task chain-b", DoneBy.AGENT, "chain-a"),
                doc("fork-x", "Task fork-x", DoneBy.HUMAN, "chain-a"));
        StartPlan.Segment segment = new StartPlan.Segment(REPO, "demo-project", tasks);
        return new StartPlan(TaskId.of("chain-b"), List.of(segment),
                List.of(new StartPlan.Stage(1, List.of(segment))), List.of(),
                List.of(new StartPlan.FollowUp(TaskId.of("next-up"), REPO, "Task next-up")), List.of());
    }

    private static StartPlan.Segment segment(StartPlan plan) {
        return plan.segments().get(0);
    }

    /** The units of the fork plan's segment, decomposed exactly as launch does. */
    private static List<StackUnits.Unit> units(StartPlan plan) {
        return StackUnits.decompose(segment(plan).tasks().stream().map(TaskDocument::id).toList(),
                StackUnits.inSegmentEdges(segment(plan).tasks(), TaskGraph.of(planSnapshot(plan), r -> "demo")));
    }

    private static TaskSnapshot planSnapshot(StartPlan plan) {
        List<TaskDocument> documents = new java.util.ArrayList<>();
        for (StartPlan.Segment one : plan.segments()) documents.addAll(one.tasks());
        java.util.Map<TaskId, TaskDocument> byId = new java.util.LinkedHashMap<>();
        documents.forEach(document -> byId.put(document.id(), document));
        return new TaskSnapshot(documents, List.of(REPO), byId, java.util.Map.of(), List.of(), List.of());
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void rootUnitPromptNamesEachBranchAndStartsAtTheDefaultBranch() {
        StartPlan plan = forkPlan();
        StackUnits.Unit root = units(plan).get(0);
        String prompt = stackedPrompt(PI, plan, segment(plan), root, null, "main");
        assertTrue(prompt.startsWith("DRYDOCK STACK UNIT START - machine-generated prompt\n"));
        assertTrue(prompt.contains("First branch starts at: main\n"));
        assertTrue(prompt.contains("TASK chain-a\nBRANCH dd/chain-a\nSTART FROM main\n"));
        assertTrue(prompt.contains("TASK chain-b\nBRANCH dd/chain-b\nSTART FROM dd/chain-a\n"));
        assertTrue(inOrder(prompt, "TASK chain-a", "TASK chain-b"));
        assertTrue(prompt.contains("git push -u origin <branch>"));
        assertTrue(prompt.contains("==== END OF STACK UNIT PROMPT ====\n"));
    }

    @Test
    void stackedUnitPromptStartsAtTheBaseBranchAndNamesTheWait() {
        StartPlan plan = forkPlan();
        List<StackUnits.Unit> all = units(plan);
        assertEquals(2, all.size());
        String prompt = stackedPrompt(CLAUDE, plan, segment(plan), all.get(1), "dd/chain-a", "main");
        assertTrue(prompt.contains("First branch starts at: dd/chain-a\n"));
        assertTrue(prompt.contains("TASK fork-x\nBRANCH dd/fork-x\nSTART FROM dd/chain-a\n"));
        assertTrue(prompt.contains("one Claude Task per task"));
    }

    @Test
    void codexUnitNumbersItsStepsAndKeepsTheUntrustedBoundary() {
        StartPlan plan = forkPlan();
        String prompt = stackedPrompt(CODEX, plan, segment(plan), units(plan).get(0), null, "origin/main");
        assertTrue(prompt.contains("STEP 1 - TASK chain-a\n"));
        assertTrue(prompt.contains("STEP 2 - TASK chain-b\n"));
        assertTrue(prompt.contains("TITLE (user data):\n" + BOUNDARY + "\nTask chain-a\n" + BOUNDARY + "\n"));
    }

    /** The stacked unit carries each member's body too: a truncated title
      * must not be all the session ever sees of its work. */
    @Test
    void theUnitCarriesEachMembersBodyAsADelimitedNotesField() {
        String body = "Full requirement: stacked units carry the body; titles truncate.\n";
        TaskDocument noted = new TaskDocument(TaskId.of("chain-a"), REPO, "Task chain-a", TaskStatus.OPEN,
                DoneBy.HUMAN, Optional.empty(), List.of(), List.of(), "", body);
        StartPlan plan = planWith(noted, doc("chain-b", "Task chain-b", DoneBy.AGENT, "chain-a"));
        String prompt = stackedPrompt(PI, plan, segment(plan), units(plan).get(0), null, "main");
        assertTrue(prompt.contains("TASK chain-a\nBRANCH dd/chain-a\nSTART FROM main\n"
                + "status: started (bound to this session at launch); done_by: human"
                + " (your done stores review until a human confirms)\n"
                + "TITLE (user data):\n" + BOUNDARY + "\nTask chain-a\n" + BOUNDARY + "\n"
                + "NOTES (user data, the task's full description and session notes):\n"
                + BOUNDARY + "\n" + body + "\n" + BOUNDARY + "\n"), "the body rides right after the title");
        assertFalse(prompt.contains("NOTES (user data, the task's full description"
                + " and session notes):\n" + BOUNDARY + "\n\n" + BOUNDARY),
                "a member without a body carries no notes field");
    }

    /** The fork plan with one document replaced by {@code noted}. */
    private static StartPlan planWith(TaskDocument noted, TaskDocument... rest) {
        List<TaskDocument> tasks = new java.util.ArrayList<>();
        tasks.add(noted);
        tasks.addAll(List.of(rest));
        StartPlan.Segment segment = new StartPlan.Segment(REPO, "demo-project", tasks);
        return new StartPlan(noted.id(), List.of(segment),
                List.of(new StartPlan.Stage(1, List.of(segment))), List.of(), List.of(), List.of());
    }

    @Test
    void reviewGateHoldsStatusesButNotBranches() {
        StartPlan plan = forkPlan();
        String prompt = stackedPrompt(PI, plan, segment(plan), units(plan).get(0), null, "main");
        int gate = prompt.indexOf("## REVIEW GATE");
        assertTrue(gate >= 0);
        String gateText = prompt.substring(gate);
        assertTrue(gateText.contains("awaits human confirmation"));
        assertTrue(gateText.replaceAll("\n", " ").contains("does NOT hold this unit's stacked branches"));
    }

    @Test
    void followUpsStayOutOfScopeInsideAStackedUnit() {
        StartPlan plan = forkPlan();
        String prompt = stackedPrompt(PI, plan, segment(plan), units(plan).get(0), null, "main");
        assertTrue(prompt.contains("## FOLLOW-UPS - out of scope, keep in mind only"));
        assertTrue(prompt.contains("FOLLOW-UP next-up\n"));
    }

    @Test
    void aRefusedPlanAndAForeignMemberRefuseLoudly() {
        StartPlan plan = forkPlan();
        StartPlan refused = StartPlan.refused(plan.target(), List.of(
                new StartPlan.Refusal(StartPlan.Kind.OWNED_TASK, "owned")));
        StackUnits.Unit root = units(plan).get(0);
        assertThrows(IllegalArgumentException.class,
                () -> stackedPrompt(PI, refused, segment(plan), root, null, "main"));
        assertThrows(IllegalArgumentException.class, () -> stackedPrompt(PI, plan, segment(plan),
                new StackUnits.Unit(List.of(TaskId.of("elsewhere")), List.of(), null), null, "main"));
        assertThrows(NullPointerException.class, () -> stackedPrompt(PI, plan, segment(plan),
                units(plan).get(0), null, null)); // root without a default branch
        assertThrows(NullPointerException.class, () -> stackedPrompt(PI, plan, segment(plan),
                units(plan).get(1), null, "main")); // stacked unit without a base branch
    }

    @Test
    void theInputsOverloadRendersExactlyWhatThePlanOverloadRenders() {
        StartPlan plan = forkPlan();
        StartPlan.Segment seg = segment(plan);
        StackUnits.Unit root = units(plan).get(0);
        StackUnits.Unit stacked = units(plan).get(1);
        TaskPrompt.StackedInputs rootInputs = new TaskPrompt.StackedInputs(plan.target(), 1,
                plan.stages().size(), seg, root, plan.context(), plan.followUps());
        TaskPrompt.StackedInputs stackedInputs = new TaskPrompt.StackedInputs(plan.target(), 1,
                plan.stages().size(), seg, stacked, plan.context(), plan.followUps());
        assertEquals(stackedPrompt(PI, plan, seg, root, null, "main"),
                stackedPrompt(PI, rootInputs, null, "main"));
        assertEquals(stackedPrompt(CLAUDE, plan, seg, stacked, "dd/chain-a", "main"),
                stackedPrompt(CLAUDE, stackedInputs, "dd/chain-a", "main"));
    }

    private static boolean inOrder(String text, String... needles) {
        int at = -1;
        for (String needle : needles) {
            at = text.indexOf(needle, at + 1);
            if (at < 0) {
                return false;
            }
        }
        return true;
    }
}

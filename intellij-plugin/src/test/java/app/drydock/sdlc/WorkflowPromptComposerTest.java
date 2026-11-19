package app.drydock.sdlc;

import app.drydock.agent.api.CreateContext;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryId;
import app.drydock.sdlc.WorkflowPromptComposer.Input;
import app.drydock.sdlc.WorkflowPromptComposer.Origin;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPromptComposerTest {

    private static final String CLARIFY = "BUILTIN-CLARIFY\n\n";
    private static final String TITLE = "TITLE-FRAMING\n\n";
    private static final String SEP = CreateContext.TASK_SEPARATOR + "\n";
    private static final String DEFAULT_METHOD = "BUILTIN-STEPS quality-gates definition-of-done roles";

    private static SelectedWorkflow wf(WorkflowTarget target, String text) {
        return new SelectedWorkflow(RepositoryId.newId(), target,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty()),
                text, WorkflowBundle.empty(), "d");
    }

    private static Input free(Optional<SelectedWorkflow> w, String task, Optional<ModelTiers> tiers, boolean sub) {
        return new Input(Origin.FREE_FORM, w, CLARIFY, "", task, Optional.empty(), Optional.empty(),
                Optional.of(TITLE), Optional.empty(), tiers, sub);
    }

    private static Input issue(Optional<SelectedWorkflow> w, Optional<String> edited, Optional<String> prev) {
        return new Input(Origin.ISSUE, w, DEFAULT_METHOD, "Issue #7: title", "", edited, prev,
                Optional.of(TITLE), Optional.empty(), Optional.empty(), false);
    }

    @Test
    void freeFormReplacesOnlyClarifyAndKeepsTaskBytesAfterSeparator() {
        String task = "  do it\r\n\tkeep  bytes \n";
        String out = WorkflowPromptComposer.compose(free(
                Optional.of(wf(WorkflowTarget.FREE_FORM_PRE_PROMPT, "MY-POLICY\n")), task, Optional.empty(), false))
                .initialPrompt().orElseThrow();
        assertEquals("MY-POLICY\n\n" + TITLE + SEP + task, out);
        assertFalse(out.contains("BUILTIN-CLARIFY"));
    }

    @Test
    void unconfiguredFreeFormKeepsDefaultFraming() {
        String out = WorkflowPromptComposer.compose(free(Optional.empty(), "t", Optional.empty(), false))
                .initialPrompt().orElseThrow();
        assertEquals(CLARIFY + TITLE + SEP + "t", out);
    }

    @Test
    void blankSendsNothingAndSlashCommandIsUnframed() {
        var w = Optional.of(wf(WorkflowTarget.FREE_FORM_PRE_PROMPT, "P"));
        assertEquals(Optional.empty(), WorkflowPromptComposer.compose(free(w, "  \n", Optional.empty(), false)).initialPrompt());
        assertEquals(Optional.of("/review now"),
                WorkflowPromptComposer.compose(free(w, "/review now", Optional.empty(), false)).initialPrompt());
    }

    @Test
    void issueReplacesMethodologyKeepsIdentityTitleAndSeparator() {
        String out = WorkflowPromptComposer.compose(issue(
                Optional.of(wf(WorkflowTarget.ISSUE_IMPLEMENT, "MY-METHOD")), Optional.empty(), Optional.empty()))
                .initialPrompt().orElseThrow();
        assertEquals(TITLE + SEP + "Issue #7: title\n\nMY-METHOD", out);
        assertFalse(out.contains("BUILTIN"));
        assertFalse(out.contains("quality-gates"));
    }

    @Test
    void tiersAreRoleNeutralConditionalAndOnlyOnSubagentHarness() {
        var tiers = Optional.of(new ModelTiers(Optional.of("big"), Optional.empty(), Optional.of("small")));
        var w = Optional.of(wf(WorkflowTarget.FREE_FORM_PRE_PROMPT, "P"));
        String sub = WorkflowPromptComposer.compose(free(w, "t", tiers, true)).initialPrompt().orElseThrow();
        assertTrue(sub.contains("If you choose to dispatch subagents"));
        assertTrue(sub.contains("strong: `big`") && sub.contains("low: `small`"));
        assertFalse(sub.contains("MUST"));
        String plain = WorkflowPromptComposer.compose(free(w, "t", tiers, false)).initialPrompt().orElseThrow();
        assertFalse(plain.contains("subagent"));
    }

    @Test
    void editedMethodologySurvivesFallbackAndSeedRecomposesOnlyWhenUnedited() {
        var configured = Optional.of(wf(WorkflowTarget.ISSUE_TRIAGE, "SEED-A"));
        ComposedPrompt first = WorkflowPromptComposer.compose(issue(configured, Optional.empty(), Optional.empty()));
        assertFalse(first.edited());
        assertTrue(first.provenance().startsWith("project workflow k"));

        // unedited editor text equal to the old seed: fallback recomposes to the default seed
        ComposedPrompt fallback = WorkflowPromptComposer.compose(
                issue(Optional.empty(), Optional.of("SEED-A"), Optional.of("SEED-A")));
        assertFalse(fallback.edited());
        assertEquals(DEFAULT_METHOD, fallback.effectiveMethodology());
        assertEquals("built-in default", fallback.provenance());

        // edited text survives the same fallback; the indicator says so
        ComposedPrompt kept = WorkflowPromptComposer.compose(
                issue(Optional.empty(), Optional.of("MY-EDIT"), Optional.of("SEED-A")));
        assertTrue(kept.edited());
        assertEquals("MY-EDIT", kept.effectiveMethodology());
        assertEquals(DEFAULT_METHOD, kept.generatedSeed());
        assertEquals("Edited for this invocation; based on built-in default", kept.provenance());
        String text = kept.initialPrompt().orElseThrow();
        assertTrue(text.endsWith("MY-EDIT"));
        assertFalse(text.contains("BUILTIN"));
    }

    @Test
    void excludedTargetsNeverCompose() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowPromptComposer.compose(free(
                Optional.of(wf(WorkflowTarget.PULL_REQUEST_REVIEW, "x")), "t", Optional.empty(), false)));
        assertThrows(IllegalArgumentException.class, () -> WorkflowPromptComposer.compose(issue(
                Optional.of(wf(WorkflowTarget.FREE_FORM_PRE_PROMPT, "x")), Optional.empty(), Optional.empty())));
    }
}

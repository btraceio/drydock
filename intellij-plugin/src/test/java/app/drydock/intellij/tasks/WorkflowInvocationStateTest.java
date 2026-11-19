package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.WorkflowInvocationState.Accepted;
import app.drydock.intellij.tasks.WorkflowInvocationState.Input;
import app.drydock.intellij.tasks.WorkflowInvocationState.Phase;
import app.drydock.intellij.tasks.WorkflowSourceLabel.Provenance;
import app.drydock.sdlc.SelectedWorkflow.SourceDescriptor;
import app.drydock.sdlc.SelectedWorkflow.SourceKind;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowInvocationStateTest {

    private static final Input IN = new Input("task", "claude");

    private static WorkflowInvocationState<String> fresh() {
        return new WorkflowInvocationState<>(IN);
    }

    @Test
    void happyPathPreparingReadyAcceptedCommitted() {
        var s = fresh();
        long g = 1;
        assertEquals(Phase.PREPARING, s.phase());
        assertTrue(s.ready(g, "wf", "seed"));
        Accepted<String> a = s.accept(g).orElseThrow();
        assertEquals("wf", a.selection());
        assertEquals("seed", a.text());
        assertEquals(IN, a.input());
        assertTrue(s.commit(g));
        assertEquals(Phase.COMMITTED, s.phase());
        assertFalse(s.cancel(), "a committed prompt cannot be claimed as not sent");
    }

    @Test
    void staleGenerationIsRejectedEverywhere() {
        var s = fresh();
        long old = 1;
        long cur = s.begin(new Input("task", "codex"));
        assertFalse(s.isCurrent(old));
        assertFalse(s.ready(old, "late", "late-seed"));
        assertFalse(s.waitForFallback(old));
        assertTrue(s.ready(cur, "wf", "seed"));
        assertTrue(s.accept(old).isEmpty());
        assertEquals("seed", s.text());
        assertEquals(Phase.READY, s.phase());
    }

    @Test
    void cancelFencesPendingCompletionsAndIsTerminal() {
        var s = fresh();
        assertTrue(s.cancel());
        assertEquals(Phase.CANCELLED, s.phase());
        assertFalse(s.ready(1, "wf", "x"));
        assertFalse(s.isCurrent(1));
        assertFalse(s.isCurrent(2));
        assertFalse(s.edit("typed"));
        assertThrows(IllegalStateException.class, () -> s.begin(IN));
        assertTrue(s.cancel());
    }

    @Test
    void cancelAfterAcceptanceBlocksCommit() {
        var s = fresh();
        s.ready(1, "wf", "seed");
        s.accept(1).orElseThrow();
        assertTrue(s.cancel());
        assertFalse(s.commit(1));
        assertEquals(Phase.CANCELLED, s.phase());
    }

    @Test
    void fallbackWaitsForExplicitDefaultOnceAndDoesNotPersist() {
        var s = fresh();
        assertTrue(s.waitForFallback(1));
        assertTrue(s.accept(1).isEmpty(), "invalid source cannot be accepted without a choice");
        assertTrue(s.chooseDefaultOnce(1, "default-seed"));
        Accepted<String> a = s.accept(1).orElseThrow();
        assertTrue(a.defaultOnce());
        assertEquals("default-seed", a.text());
        long next = new WorkflowInvocationState<String>(IN).begin(IN);
        assertEquals(2, next);
        var again = fresh();
        again.ready(1, "wf", "s");
        assertFalse(again.accept(1).orElseThrow().defaultOnce());
    }

    @Test
    void retryAfterFallbackClearsDefaultOnceForTheNewGeneration() {
        var s = fresh();
        s.waitForFallback(1);
        s.chooseDefaultOnce(1, "d");
        long g = s.begin(IN);
        assertFalse(s.chooseDefaultOnce(g, "d2"));
        s.ready(g, "wf", "seed");
        assertFalse(s.accept(g).orElseThrow().defaultOnce());
    }

    @Test
    void editedTextSurvivesLatePreparationFallbackAndHarnessSwitch() {
        var s = fresh();
        assertTrue(s.edit("my edits"));
        assertTrue(s.edited());
        assertTrue(s.ready(1, "wf", "generated"));
        assertEquals("my edits", s.text());
        long g = s.begin(new Input("task", "codex"));
        assertTrue(s.waitForFallback(g));
        assertTrue(s.chooseDefaultOnce(g, "default"));
        Accepted<String> a = s.accept(g).orElseThrow();
        assertEquals("my edits", a.text());
        assertTrue(a.edited());
        assertFalse(s.edit("after accept"));
    }

    @Test
    void uneditedSeedFollowsNewPreparation() {
        var s = fresh();
        s.ready(1, "a", "seed1");
        long g = s.begin(new Input("task", "codex"));
        s.ready(g, "b", "seed2");
        assertEquals("seed2", s.text());
        assertFalse(s.edited());
    }

    @Test
    void acceptedInputIsFrozenAgainstLaterBegin() {
        var s = fresh();
        s.ready(1, "wf", "seed");
        Accepted<String> a = s.accept(1).orElseThrow();
        assertThrows(IllegalStateException.class, () -> s.begin(new Input("other", "codex")));
        assertEquals("task", a.input().target());
    }

    @Test
    void labelsForEverySourceAndProvenance() {
        var file = new SourceDescriptor(SourceKind.FILE, "k", Optional.of(".sdlc/impl.md"));
        var inline = new SourceDescriptor(SourceKind.INLINE, "k", Optional.empty());
        assertEquals("Drydock default", WorkflowSourceLabel.of(Provenance.DEFAULT, null, null));
        assertEquals("Project repo: .sdlc/impl.md", WorkflowSourceLabel.of(Provenance.PROJECT, "repo", file));
        assertEquals("Project repo: inline text", WorkflowSourceLabel.of(Provenance.PROJECT, "repo", inline));
        assertEquals("Drydock default (this time only)", WorkflowSourceLabel.of(Provenance.DEFAULT_ONCE, "repo", file));
        assertEquals("Drydock default (project not registered)",
                WorkflowSourceLabel.of(Provenance.NOT_REGISTERED, "repo", null));
        assertEquals("Edited for this invocation; based on Project repo: .sdlc/impl.md",
                WorkflowSourceLabel.edited(Provenance.PROJECT, "repo", file));
        assertEquals("Edited for this invocation; based on Drydock default",
                WorkflowSourceLabel.edited(Provenance.DEFAULT, null, null));
    }

    @Test
    void labelNeverShowsCredentialsOrRemoteCoordinates() {
        var leak = new SourceDescriptor(SourceKind.FILE, "k", Optional.of("https://user:tok@host.example/x/wf.md"));
        String label = WorkflowSourceLabel.of(Provenance.PROJECT, "/Users/me/work/repo", leak);
        assertEquals("Project repo: wf.md", label);
        assertFalse(WorkflowSourceLabel.of(Provenance.PROJECT, "git@github.com:org/repo", leak).contains("@"));
        assertEquals("Project (unnamed): inline text",
                WorkflowSourceLabel.of(Provenance.PROJECT, "", new SourceDescriptor(SourceKind.INLINE, "k", Optional.empty())));
    }
}

package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent strip's copy. These are the assertions worth having without an
 * IDE: the chip must not promise an enforcement the agent does not have, and
 * an unknown count must print nothing rather than a zero that reads as a
 * measurement.
 */
class AgentStripTextTest {

    private static ManagedAgentSession session(SessionMode mode) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix the parser",
                AgentBinding.unlaunched(AgentKind.CLAUDE, mode),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")),
                Instant.EPOCH)
                .status(SessionStatus.RUNNING)
                .build();
    }

    @Test
    void aWritingSessionHasNoChip() {
        assertTrue(AgentStripText.readOnlyChip(session(SessionMode.WRITES), true).isEmpty());
        assertTrue(AgentStripText.readOnlyChip(session(SessionMode.WRITES), false).isEmpty());
    }

    @Test
    void anEnforcedReadOnlySessionSaysItWillNotEdit() {
        assertEquals(Optional.of("Read-only · won't edit files"),
                AgentStripText.readOnlyChip(session(SessionMode.READ_ONLY), true));
    }

    @Test
    void anUnenforcedReadOnlySessionSaysSoRatherThanPromising() {
        // pi has no read-only mode. The chip still appears — the mode is a
        // real statement of intent — but it must not claim an enforcement
        // the CLI cannot deliver.
        Optional<String> chip = AgentStripText.readOnlyChip(session(SessionMode.READ_ONLY), false);

        assertEquals(Optional.of("Read-only · not enforced"), chip);
        assertFalse(chip.orElseThrow().contains("won't edit"));
    }

    @Test
    void anUnknownChangeCountIsOmittedRatherThanPrintedAsZero() {
        String text = AgentStripText.secondary("Claude", Duration.ofMinutes(4), Optional.empty(), true, false);

        assertEquals("Claude · 4m", text);
        assertFalse(text.contains("files changed"));
    }

    @Test
    void oneChangedFileIsSingular() {
        assertEquals("Claude · 4m · 1 file changed",
                AgentStripText.secondary("Claude", Duration.ofMinutes(4), Optional.of(1), true, false));
        assertEquals("Claude · 4m · 3 files changed",
                AgentStripText.secondary("Claude", Duration.ofMinutes(4), Optional.of(3), true, false));
    }

    @Test
    void dumbModeIsAnnouncedBecauseTheAgentStartedWithoutWaitingForIt() {
        assertEquals("Codex · 2s · IDE still indexing",
                AgentStripText.secondary("Codex", Duration.ofSeconds(2), Optional.empty(), true, true));
    }

    @Test
    void aStoppedSessionNeverMentionsIndexing() {
        // The note means "the agent started before the IDE was ready". With
        // nothing running it explains nothing, and under the words "Not
        // running" it reads as the reason the agent is not running.
        String text = AgentStripText.secondary("Codex", Duration.ofSeconds(2), Optional.empty(), false, true);

        assertEquals("Codex · 2s", text);
        assertFalse(text.contains("indexing"), text);
    }

    @Test
    void aRowStuckInTheRunningBandDisplaysAsNotRunning() {
        // The window is the authority on what is alive in it. A row whose idle
        // transition was lost (window closed while its message bus was going
        // away) otherwise shows a live dot, the word "Running", and a Stop
        // button that stops nothing.
        assertEquals(SessionStatus.INACTIVE,
                AgentStripText.displayStatus(SessionStatus.RUNNING, false));
        assertEquals(SessionStatus.INACTIVE,
                AgentStripText.displayStatus(SessionStatus.STARTING, false));
        // A live process keeps its status untouched...
        assertEquals(SessionStatus.RUNNING,
                AgentStripText.displayStatus(SessionStatus.RUNNING, true));
        // ...and nothing outside the running band is rewritten either way: an
        // error status must survive, not be flattened to "not running".
        assertEquals(SessionStatus.FAILED,
                AgentStripText.displayStatus(SessionStatus.FAILED, false));
        assertEquals(SessionStatus.EXITED,
                AgentStripText.displayStatus(SessionStatus.EXITED, false));
    }

    @Test
    void theStoppedFooterNeverClaimsAnAgentIsRunning() {
        // The footer only renders when nothing is running here, so a RUNNING
        // status reaching it is stale. "it is running" contradicted the very
        // panel the line sits in.
        String footer = AgentStripText.stoppedFooter(
                SessionStatus.RUNNING, Optional.empty(), Instant.parse("2026-10-18T12:00:00Z"));

        assertFalse(footer.contains("it is running"), footer);
        assertTrue(footer.contains("no agent is running here"), footer);
    }

    @Test
    void theStoppedFooterNamesWhyAndPromisesNoRestart() {
        Instant now = Instant.parse("2026-10-18T12:00:00Z");
        Instant stopped = now.minus(Duration.ofMinutes(5));

        assertEquals("— stopped 5m ago · the agent exited · nothing restarts on its own —",
                AgentStripText.stoppedFooter(SessionStatus.EXITED, Optional.of(stopped), now));
        assertEquals("— stopped 5m ago · Drydock stopped it · nothing restarts on its own —",
                AgentStripText.stoppedFooter(SessionStatus.INACTIVE, Optional.of(stopped), now));
        assertEquals("— stopped earlier · the last launch failed · nothing restarts on its own —",
                AgentStripText.stoppedFooter(SessionStatus.FAILED, Optional.empty(), now));
    }

    @Test
    void anUnknownResumeCostIsABareResume() {
        // "~0k tokens" would read as "this costs nothing", which is the
        // opposite of what an absent estimate means.
        assertEquals("Resume", AgentStripText.primaryLabel(true, Optional.empty()));
        assertEquals("Resume · ~12k tokens", AgentStripText.primaryLabel(true, Optional.of(12_400)));
        assertEquals("Resume · ~13k tokens", AgentStripText.primaryLabel(true, Optional.of(12_600)));
        // Never "~0k": a short conversation still costs something.
        assertEquals("Resume · <1k tokens", AgentStripText.primaryLabel(true, Optional.of(400)));
    }

    @Test
    void aSessionWithNothingToResumeOffersToStartInstead() {
        // An agent that never wrote a session record — stopped before it
        // completed a turn — has no conversation to resume into, and "Resume"
        // could only land the user in the provider's own picker.
        assertEquals("Start", AgentStripText.primaryLabel(false, Optional.empty()));
        // Not even when an estimate happens to exist: there is still nothing
        // to resume, so the cost of resuming it is not a thing to show.
        assertEquals("Start", AgentStripText.primaryLabel(false, Optional.of(12_400)));
    }

    @Test
    void ageStaysOneUnitWideAtEveryScale() {
        assertEquals("0s", AgentStripText.relativeAge(Duration.ZERO));
        assertEquals("0s", AgentStripText.relativeAge(Duration.ofSeconds(-30)));
        assertEquals("59s", AgentStripText.relativeAge(Duration.ofSeconds(59)));
        assertEquals("1m", AgentStripText.relativeAge(Duration.ofSeconds(60)));
        assertEquals("59m", AgentStripText.relativeAge(Duration.ofMinutes(59)));
        assertEquals("1h", AgentStripText.relativeAge(Duration.ofHours(1)));
        assertEquals("23h", AgentStripText.relativeAge(Duration.ofHours(23)));
        assertEquals("1d", AgentStripText.relativeAge(Duration.ofDays(1)));
        assertEquals("30d", AgentStripText.relativeAge(Duration.ofDays(30)));
    }

    @Test
    void aSessionWithoutARecordedTranscriptStartsInsteadOfResuming() {
        // Claude gets its id at launch; stopped before its first turn it has
        // an id but nothing on disk, and --resume answers "No conversation found".
        assertFalse(AgentStripText.resumable(true, Optional.of(false)));
        assertTrue(AgentStripText.resumable(true, Optional.of(true)));
        assertTrue(AgentStripText.resumable(true, Optional.empty()), "a provider that cannot tell keeps Resume");
        assertFalse(AgentStripText.resumable(false, Optional.empty()));
    }
}

package app.drydock.intellij.agent;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tab hosted from another worktree, in the handoff's words (frame F2). */
class HostedTabTest {

    private static final SessionPlace DD_JMC = new SessionPlace("dd-jmc", Optional.of("fix/chunk_memory"));

    @Test
    void aSessionFromThisWindowsWorktreeIsNotHosted() {
        assertFalse(HostedTab.isHosted(Optional.of(Path.of("/w/a")), Path.of("/w/a")));
    }

    @Test
    void aSessionFromAnotherWorktreeIsHosted() {
        assertTrue(HostedTab.isHosted(Optional.of(Path.of("/w/a")), Path.of("/w/b")));
    }

    @Test
    void aWindowWithoutABaseHostsNothing() {
        assertFalse(HostedTab.isHosted(Optional.empty(), Path.of("/w/b")));
    }

    @Test
    void theTitleCarriesTheRepoChip() {
        assertEquals("dd-jmc · Fix chunk memory growth", HostedTab.title("dd-jmc", "Fix chunk memory growth"));
    }

    @Test
    void theTooltipSaysHosted() {
        assertEquals("dd-jmc · fix/chunk_memory · hosted", HostedTab.tooltip(DD_JMC));
    }

    @Test
    void provenanceNamesRepoAndBranch() {
        assertEquals("from dd-jmc ⑂ fix/chunk_memory", DD_JMC.provenance());
        assertEquals("from dd-jmc", new SessionPlace("dd-jmc", Optional.empty()).provenance());
    }

    @Test
    void factsNameTheirWindowByLocationNotByReference() {
        // Kept on an application-lifetime file: a Project reference would
        // keep a closed window alive.
        HostedTab.Facts facts = new HostedTab.Facts("abc123", DD_JMC);

        assertTrue(facts.isFor("abc123"));
        assertFalse(facts.isFor("def456"));
        for (var component : HostedTab.Facts.class.getRecordComponents()) {
            assertFalse(Project.class.isAssignableFrom(component.getType()), component.getName());
        }
    }
}

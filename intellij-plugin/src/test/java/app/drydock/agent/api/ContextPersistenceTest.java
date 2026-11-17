package app.drydock.agent.api;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPersistenceTest {

    private static final Path ALPHA = Path.of("/wt/alpha");
    private static final SshRemote BOX = new SshRemote("workspace-box", "/home/user/repo");

    private static RemotePersistence persistence() {
        return new RemotePersistence(new ManagedSessionId(UUID.fromString(
                        "00000000-0000-0000-0000-000000000001")),
                Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:4000"));
    }

    @Test
    void delegatingCreateConstructorsPassEmptyPersistence() {
        CreateContext canonical = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of(), Optional.empty(), Optional.empty());
        assertTrue(canonical.persistence().isEmpty());

        CreateContext oldCanonical = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of(), Optional.empty());
        assertTrue(oldCanonical.persistence().isEmpty());

        CreateContext noResources = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of());
        assertTrue(noResources.persistence().isEmpty());

        CreateContext legacy = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), false, false, Optional.of("do it"));
        assertTrue(legacy.persistence().isEmpty());

        CreateContext bare = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty());
        assertTrue(bare.persistence().isEmpty());
    }

    @Test
    void delegatingResumeConstructorsPassEmptyPersistence() {
        ResumeContext canonical = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(),
                Optional.empty(), Optional.empty());
        assertTrue(canonical.persistence().isEmpty());

        ResumeContext oldCanonical = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(),
                Optional.of("continue"), Optional.empty());
        assertTrue(oldCanonical.persistence().isEmpty());

        ResumeContext noResources = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(),
                Optional.of("continue"));
        assertTrue(noResources.persistence().isEmpty());

        ResumeContext bare = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty());
        assertTrue(bare.persistence().isEmpty());
    }

    @Test
    void createWithPersistenceRoundTrips() {
        CreateContext original = new CreateContext("name", "session-1", ALPHA,
                Optional.of(BOX), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of(),
                Optional.of(new LaunchResources(List.of(ALPHA), Optional.empty())));
        RemotePersistence p = persistence();
        CreateContext swapped = original.withPersistence(p);
        assertSame(p, swapped.persistence().orElseThrow());
        assertEquals(Optional.of(BOX), swapped.remote());
        assertEquals("do it", swapped.initialPrompt().orElseThrow());
        assertEquals(List.of(ALPHA), swapped.launchResources().orElseThrow().extraDirectories());
        // The original is untouched: withPersistence copies, never mutates.
        assertTrue(original.persistence().isEmpty());
    }

    @Test
    void resumeWithPersistenceRoundTrips() {
        ResumeContext original = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.of(BOX), Optional.empty(), false, false, List.of(),
                Optional.of("continue"));
        RemotePersistence p = persistence();
        ResumeContext swapped = original.withPersistence(p);
        assertSame(p, swapped.persistence().orElseThrow());
        assertEquals(Optional.of(BOX), swapped.remote());
        assertEquals("continue", swapped.resumePrompt().orElseThrow());
        assertTrue(original.persistence().isEmpty());
    }

    @Test
    void persistenceWithoutRemoteIsRejected() {
        RemotePersistence p = persistence();
        assertThrows(IllegalArgumentException.class, () -> new CreateContext("name", "session-1",
                ALPHA, Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of(), Optional.empty(), Optional.of(p)));
        assertThrows(IllegalArgumentException.class, () -> new ResumeContext(Optional.of("convo"),
                Optional.empty(), ALPHA, Optional.empty(), Optional.empty(), false, false,
                List.of(), Optional.empty(), Optional.empty(), Optional.of(p)));
    }

    @Test
    void remoteWithEmptyPersistenceStaysALegalLaunch() {
        // Today's remote-without-persistence branch: a remote is present and
        // persistence is empty. That combination must keep constructing.
        CreateContext create = new CreateContext("name", "session-1", ALPHA,
                Optional.of(BOX), Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of(), Optional.empty());
        assertTrue(create.persistence().isEmpty());

        ResumeContext resume = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.of(BOX), Optional.empty(), false, false, List.of(),
                Optional.empty(), Optional.empty());
        assertTrue(resume.persistence().isEmpty());
    }

    @Test
    void environmentMapIsCopied() {
        Map<String, String> source = new HashMap<>();
        source.put("ANTHROPIC_BASE_URL", "http://127.0.0.1:4000");
        source.put("CLAUDE_CODE_NO_FLICKER", "1");
        RemotePersistence p = new RemotePersistence(new ManagedSessionId(UUID.randomUUID()), source);
        // Mutating the caller's map after construction changes nothing.
        source.put("INJECTED", "nope");
        assertEquals(Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:4000",
                "CLAUDE_CODE_NO_FLICKER", "1"), p.agentEnvironment());
        // The copy is unmodifiable: nobody can retune the launch after the
        // fact either.
        assertThrows(UnsupportedOperationException.class,
                () -> p.agentEnvironment().put("INJECTED", "nope"));
    }

    @Test
    void environmentRendersInSortedKeyOrder() {
        // Insertion order and hash order deliberately differ from key order.
        Map<String, String> source = new HashMap<>();
        source.put("zz_last", "3");
        source.put("ANTHROPIC_BASE_URL", "1");
        source.put("middle", "2");
        RemotePersistence p = new RemotePersistence(new ManagedSessionId(UUID.randomUUID()), source);
        String expected = "RemotePersistence[managedSessionId=" + p.managedSessionId()
                + ", agentEnvironment=" + new TreeMap<>(source) + "]";
        assertEquals(expected, p.toString());
        // The rendering is deterministic: the same map renders identically
        // no matter which map instance carried it in.
        RemotePersistence again = new RemotePersistence(p.managedSessionId(), new HashMap<>(source));
        assertEquals(p.toString(), again.toString());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new RemotePersistence(null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> new RemotePersistence(new ManagedSessionId(UUID.randomUUID()), null));
    }
}

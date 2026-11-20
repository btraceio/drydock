package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchResourcesTest {

    private static final Path ALPHA = Path.of("/wt/alpha");
    private static final Path BETA = Path.of("/wt/beta");
    private static final StdioMcpServer IDE = new StdioMcpServer("ide", "mcp",
            List.of("--stdio"), java.util.Map.of());

    @Test
    void oldCreateConstructorsCarryNoLaunchResources() {
        CreateContext full = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of());
        assertTrue(full.launchResources().isEmpty());
        assertNull(full.model().orElse(null));

        CreateContext legacy = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), false, false, Optional.of("do it"));
        assertTrue(legacy.launchResources().isEmpty());
        assertEquals("do it", legacy.initialPrompt().orElseThrow());

        CreateContext bare = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty());
        assertTrue(bare.launchResources().isEmpty());
    }

    @Test
    void oldResumeConstructorsCarryNoLaunchResources() {
        ResumeContext full = new ResumeContext(Optional.of("convo"), Optional.of("title"),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(IDE),
                Optional.of("continue"));
        assertTrue(full.launchResources().isEmpty());
        assertEquals("continue", full.resumePrompt().orElseThrow());

        ResumeContext noPrompt = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(IDE));
        assertTrue(noPrompt.launchResources().isEmpty());

        ResumeContext bare = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty());
        assertTrue(bare.launchResources().isEmpty());
    }

    @Test
    void extraDirectoriesAreDeduplicatedInFirstSeenOrder() {
        LaunchResources resources = new LaunchResources(
                List.of(ALPHA, BETA, ALPHA, BETA), Optional.empty());
        assertEquals(List.of(ALPHA, BETA), resources.extraDirectories());
    }

    @Test
    void extraDirectoriesAreImmutable() {
        LaunchResources resources = new LaunchResources(List.of(ALPHA), Optional.empty());
        assertThrows(UnsupportedOperationException.class,
                () -> resources.extraDirectories().add(BETA));
    }

    @Test
    void taskGuidanceTravelsThrough() {
        Path guidance = Path.of("/state/drydock/guidance/1");
        LaunchResources resources = new LaunchResources(List.of(ALPHA), Optional.of(guidance));
        assertEquals(guidance, resources.taskGuidance().orElseThrow());
        assertEquals(List.of(ALPHA), resources.extraDirectories());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new LaunchResources(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new LaunchResources(List.of(), null));
        assertThrows(NullPointerException.class, () -> new LaunchResources(List.of(ALPHA), null));
    }

    @Test
    void createWithStdioMcpServersRetainsResourcesAndPrompt() {
        CreateContext original = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of(),
                Optional.of(new LaunchResources(List.of(BETA), Optional.empty())));
        CreateContext copied = original.withStdioMcpServers(List.of(IDE));
        assertEquals(List.of(IDE), copied.stdioMcpServers());
        assertEquals(List.of(BETA), copied.launchResources().orElseThrow().extraDirectories());
        assertEquals("do it", copied.initialPrompt().orElseThrow());
    }

    @Test
    void resumeWithStdioMcpServersRetainsResourcesAndPrompt() {
        ResumeContext original = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(),
                Optional.of("continue"),
                Optional.of(new LaunchResources(List.of(BETA), Optional.empty())));
        ResumeContext copied = original.withStdioMcpServers(List.of(IDE));
        assertEquals(List.of(IDE), copied.stdioMcpServers());
        assertEquals(List.of(BETA), copied.launchResources().orElseThrow().extraDirectories());
        assertEquals("continue", copied.resumePrompt().orElseThrow());
    }

    @Test
    void createWithLaunchResourcesRetainsServersAndPrompt() {
        CreateContext original = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.of("do it"), List.of(IDE),
                Optional.of(new LaunchResources(List.of(BETA), Optional.empty())));
        LaunchResources replacement = new LaunchResources(List.of(), Optional.of(Path.of("/g")));
        CreateContext swapped = original.withLaunchResources(replacement);
        assertSame(replacement, swapped.launchResources().orElseThrow());
        assertEquals(List.of(IDE), swapped.stdioMcpServers());
        assertEquals("do it", swapped.initialPrompt().orElseThrow());
        // The original is untouched: resources are copied, not mutated in place.
        assertEquals(List.of(BETA), original.launchResources().orElseThrow().extraDirectories());
    }

    @Test
    void resumeWithLaunchResourcesRetainsServersAndPrompt() {
        ResumeContext original = new ResumeContext(Optional.of("convo"), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(IDE),
                Optional.of("continue"));
        LaunchResources replacement = new LaunchResources(List.of(BETA), Optional.empty());
        ResumeContext swapped = original.withLaunchResources(replacement);
        assertSame(replacement, swapped.launchResources().orElseThrow());
        assertEquals(List.of(IDE), swapped.stdioMcpServers());
        assertEquals("continue", swapped.resumePrompt().orElseThrow());
    }

    @Test
    void createAndResumeCarryIndependentResources() {
        LaunchResources createOnly = new LaunchResources(List.of(ALPHA), Optional.empty());
        CreateContext create = new CreateContext("name", "session-1", ALPHA,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of(), Optional.of(createOnly));
        ResumeContext resume = new ResumeContext(Optional.empty(), Optional.empty(),
                ALPHA, Optional.empty(), Optional.empty(), false, false, List.of(),
                Optional.empty());
        assertEquals(createOnly, create.launchResources().orElseThrow());
        assertTrue(resume.launchResources().isEmpty());

        ResumeContext resumedWithResources = resume.withLaunchResources(
                new LaunchResources(List.of(BETA), Optional.empty()));
        assertEquals(List.of(BETA),
                resumedWithResources.launchResources().orElseThrow().extraDirectories());
        // The create context keeps its own resources; the two never share state.
        assertEquals(List.of(ALPHA), create.launchResources().orElseThrow().extraDirectories());
    }
}

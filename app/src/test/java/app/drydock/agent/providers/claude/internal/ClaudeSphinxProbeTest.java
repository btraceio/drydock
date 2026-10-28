package app.drydock.agent.providers.claude.internal;

import app.drydock.agent.api.SphinxReviewSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parse tests against fixture {@code installed_plugins.json} files; the file
 * is Claude Code's own plugin registry, so the fixtures pin the schema shape
 * the probe depends on (top-level {@code plugins} keyed
 * {@code <name>@<marketplace>}).
 */
class ClaudeSphinxProbeTest {

    @TempDir
    Path dir;

    private Path write(String json) throws IOException {
        Path file = dir.resolve("installed_plugins.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void missingFileYieldsEmpty() {
        assertEquals(Optional.empty(), ClaudeSphinxProbe.probe(dir.resolve("absent.json")));
    }

    @Test
    void registryWithoutSphinxYieldsEmpty() throws IOException {
        Path file = write("""
                {
                  "version": 2,
                  "plugins": {
                    "atlassian@claude-plugins-official": [
                      { "scope": "user", "installPath": "/x/atlassian/1" }
                    ]
                  }
                }
                """);
        assertEquals(Optional.empty(), ClaudeSphinxProbe.probe(file));
    }

    @Test
    void sphinxEntryOnAnyMarketplaceIsDetected() throws IOException {
        Path file = write("""
                {
                  "version": 2,
                  "plugins": {
                    "amos@profiling-rt-ai-plugins": [
                      { "scope": "user", "installPath": "/x/amos/1" }
                    ],
                    "sphinx@profiling-rt-ai-plugins": [
                      { "scope": "user", "installPath": "/x/sphinx/1",
                        "gitCommitSha": "5b0caec7bbf44f5fc890e5e58b640a2243541466" }
                    ]
                  }
                }
                """);
        Optional<SphinxReviewSupport> probed = ClaudeSphinxProbe.probe(file);
        assertTrue(probed.isPresent());
        assertEquals(SphinxReviewSupport.Invocation.SLASH_COMMAND, probed.get().invocation());
    }

    @Test
    void malformedJsonYieldsEmptyNotAThrow() throws IOException {
        Path file = write("{ \"version\": 2, \"plugins\": {");
        assertEquals(Optional.empty(), ClaudeSphinxProbe.probe(file));
    }

    @Test
    void registryWithoutAPluginsObjectYieldsEmpty() throws IOException {
        Path file = write("{ \"version\": 2 }");
        assertEquals(Optional.empty(), ClaudeSphinxProbe.probe(file));
    }
}

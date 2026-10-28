package app.drydock.agent.providers.pi.internal;

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
 * Parse tests against fixture pi agent directories ({@code settings.json}
 * {@code packages} plus the skills dir layout), pinning both places pi loads
 * skills from and the two package-entry forms (npm ref, filesystem path).
 */
class PiSphinxProbeTest {

    @TempDir
    Path agentDir;

    private void writeSettings(String json) throws IOException {
        Files.writeString(agentDir.resolve("settings.json"), json);
    }

    @Test
    void missingSettingsAndSkillsYieldEmpty() {
        assertEquals(Optional.empty(), PiSphinxProbe.probe(agentDir));
    }

    @Test
    void personalSkillDirIsDetectedWithoutPackages() throws IOException {
        Files.createDirectories(agentDir.resolve(Path.of("skills", "sphinx")));
        Files.writeString(agentDir.resolve(Path.of("skills", "sphinx", "SKILL.md")), "---\nname: sphinx\n---\n");
        Optional<SphinxReviewSupport> probed = PiSphinxProbe.probe(agentDir);
        assertTrue(probed.isPresent());
        assertEquals(SphinxReviewSupport.Invocation.SKILL_REQUEST, probed.get().invocation());
    }

    @Test
    void pathPackageRelativeSettingsEntryIsResolvedAgainstTheAgentDir() throws IOException {
        // The real shape on this machine's install: a relative path out of
        // ~/.pi/agent to a marketplace checkout.
        Path packageRoot = agentDir.resolve(Path.of("..", "sphinx-pi")).normalize();
        Files.createDirectories(packageRoot.resolve(Path.of("skills", "sphinx")));
        Files.writeString(packageRoot.resolve(Path.of("skills", "sphinx", "SKILL.md")), "skill");
        writeSettings("""
                {
                  "packages": [
                    "npm:@plannotator/pi-extension",
                    "../sphinx-pi"
                  ]
                }
                """);
        Optional<SphinxReviewSupport> probed = PiSphinxProbe.probe(agentDir);
        assertTrue(probed.isPresent());
        assertEquals(SphinxReviewSupport.Invocation.SKILL_REQUEST, probed.get().invocation());
    }

    @Test
    void npmPackageEntryResolvesUnderNodeModules() throws IOException {
        Path packageRoot = agentDir.resolve(Path.of("npm", "node_modules", "@datadog", "sphinx-pi"));
        Files.createDirectories(packageRoot.resolve(Path.of("skills", "sphinx")));
        Files.writeString(packageRoot.resolve(Path.of("skills", "sphinx", "SKILL.md")), "skill");
        writeSettings("""
                {
                  "packages": [
                    "npm:@datadog/sphinx-pi"
                  ]
                }
                """);
        assertTrue(PiSphinxProbe.probe(agentDir).isPresent());
    }

    @Test
    void packagesWithoutTheSphinxSkillYieldEmpty() throws IOException {
        Path packageRoot = agentDir.resolve("some-package");
        Files.createDirectories(packageRoot.resolve(Path.of("skills", "other")));
        Files.writeString(packageRoot.resolve(Path.of("skills", "other", "SKILL.md")), "skill");
        writeSettings("""
                {
                  "packages": [
                    "some-package"
                  ]
                }
                """);
        assertEquals(Optional.empty(), PiSphinxProbe.probe(agentDir));
    }

    @Test
    void malformedSettingsYieldEmptyNotAThrow() throws IOException {
        writeSettings("{ \"packages\": [");
        assertEquals(Optional.empty(), PiSphinxProbe.probe(agentDir));
    }

    @Test
    void npmEntryRefersIntoNodeModulesNotTheFilesystemRoot() {
        // A leading-slash-free npm ref must never be treated as an absolute
        // path: "@datadog/sphinx-pi" resolving against / would read whatever
        // happens to sit at /@datadog on the host.
        Path resolved = PiSphinxProbe.resolvePackageRoot(Path.of("/agent"), "npm:@datadog/sphinx-pi");
        assertEquals(Path.of("/agent", "npm", "node_modules", "@datadog", "sphinx-pi"), resolved);
    }
}

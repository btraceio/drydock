package app.drydock.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed port of the T25a acceptance smoke for {@link TaskGuidance}:
 * byte parity with the bundled classpath resources, the concurrent-install
 * race converging on one coherent versioned layout, read-only preview and
 * lookups, path stability and verify-only re-install within a version, and
 * the injected-root constructor leaving the user home untouched.
 *
 * <p>Honest coverage limits, stated rather than papered over: the
 * platform-hostile branches stay smoke-covered, not unit-tested here --
 * (a) a real cross-device {@code ATOMIC_MOVE} refusal is unreachable by
 * construction in-process (staging and target are siblings under the same
 * injected state root, so they share a filesystem), and (b) there is no
 * production seam that forces an fsync ({@code channel.force}) failure, so
 * the failed-write discard path is only proven by code reading plus the
 * staged smoke, not by this class. The race test maximizes overlap with a
 * barrier but cannot force the scheduler to interleave; what it asserts is
 * the documented end-state contract (the loser is classified by the live
 * target state, not the exception type), which holds whether or not the
 * moves actually collide.
 */
class TaskGuidanceTest {

    /** The bundled skill resource the plugin build carries (main resources, tasks/SKILL.md). */
    private static final String SKILL_RESOURCE = "/tasks/SKILL.md";

    @TempDir
    Path stateRoot;

    /** The manifest bytes TaskGuidance renders for a version, traced to its manifest template. */
    private static String manifestFor(String version) {
        return "{\n"
                + "  \"name\": \"drydock-tasks\",\n"
                + "  \"version\": \"" + version + "\",\n"
                + "  \"description\": \"Drydock delayed tasks: the four task tools, "
                + "dependency-aware planning and the review pause.\"\n"
                + "}\n";
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = TaskGuidanceTest.class.getResourceAsStream(name)) {
            assertTrue(in != null, "classpath resource must exist: " + name);
            return in.readAllBytes();
        }
    }

    /**
     * The state tree as relative-path to mtime, for every file and directory
     * under {@code root} (the root itself maps to {@code ""}); used as the
     * zero-write proof: any write under the root appears as a new entry or
     * a changed mtime.
     */
    private static Map<String, FileTime> treeSnapshot(Path root) throws IOException {
        Map<String, FileTime> snapshot = new TreeMap<>();
        if (!Files.exists(root)) {
            return snapshot;
        }
        try (var paths = Files.walk(root)) {
            paths.forEach(path -> {
                try {
                    snapshot.put(root.relativize(path).toString(),
                            Files.getLastModifiedTime(path));
                } catch (IOException readFailure) {
                    throw new UncheckedIOException(readFailure);
                }
            });
        }
        return snapshot;
    }

    private static List<String> topLevelNames(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** Matrix 1: the materialized layout is byte-identical to the bundled resources. */
    @Test
    void installedLayoutIsByteIdenticalToBundledResources() throws Exception {
        TaskGuidance guidance = new TaskGuidance(stateRoot);
        assertFalse(guidance.isInstalled(), "absent install answers false, builds nothing");

        Path dir = guidance.install();
        assertEquals(stateRoot.resolve("tasks-guidance").resolve("2"), dir);
        assertTrue(guidance.isInstalled());

        assertArrayEquals(resource(SKILL_RESOURCE), Files.readAllBytes(guidance.piSkillFile()));
        assertArrayEquals(manifestFor("2").getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(dir.resolve(".claude-plugin").resolve("plugin.json")));
        assertEquals(new String(resource(SKILL_RESOURCE), StandardCharsets.UTF_8),
                guidance.bundledSkillText(), "preview equals the bundled skill text");

        // exactly the two layout files, nothing else in the version tree
        try (var entries = Files.walk(dir)) {
            List<String> files = entries.filter(Files::isRegularFile)
                    .map(path -> dir.relativize(path).toString())
                    .sorted()
                    .toList();
            assertEquals(List.of(".claude-plugin/plugin.json",
                    "skills/drydock-tasks/SKILL.md"), files);
        }
    }

    /** Matrix 2: two concurrent installs end with one coherent versioned layout. */
    @Test
    void concurrentInstallsConvergeOnOneCoherentLayout() throws Exception {
        TaskGuidance guidance = new TaskGuidance(stateRoot);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Path>> installs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                installs.add(pool.submit((Callable<Path>) () -> {
                    start.await();
                    return guidance.install();
                }));
            }
            Path first = installs.get(0).get(60, TimeUnit.SECONDS);
            Path second = installs.get(1).get(60, TimeUnit.SECONDS);
            assertEquals(guidance.guidanceDirectory(), first);
            assertEquals(first, second, "both racers return the shared directory");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(guidance.isInstalled());
        assertArrayEquals(resource(SKILL_RESOURCE), Files.readAllBytes(guidance.piSkillFile()));
        assertArrayEquals(manifestFor("2").getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(guidance.guidanceDirectory()
                        .resolve(".claude-plugin").resolve("plugin.json")));
        // no staging leftovers: the loser discards its tree, so only the version dir remains
        try (var entries = Files.list(stateRoot.resolve("tasks-guidance"))) {
            assertEquals(List.of("2"), entries
                    .map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    /** Matrix 3: lookups and the preview are pure -- zero writes, path and mtime. */
    @Test
    void lookupsAndPreviewNeverWrite() throws Exception {
        TaskGuidance guidance = new TaskGuidance(stateRoot);
        Map<String, FileTime> emptyState = treeSnapshot(stateRoot);

        assertFalse(guidance.isInstalled());
        String preview = guidance.bundledSkillText();
        assertArrayEquals(resource(SKILL_RESOURCE), preview.getBytes(StandardCharsets.UTF_8));
        assertEquals(stateRoot.resolve("tasks-guidance").resolve("2"),
                guidance.guidanceDirectory());
        assertEquals(guidance.guidanceDirectory(), guidance.claudePluginDirectory());
        assertEquals(guidance.guidanceDirectory()
                        .resolve("skills").resolve("drydock-tasks").resolve("SKILL.md"),
                guidance.piSkillFile());
        assertEquals(emptyState, treeSnapshot(stateRoot), "queries wrote nothing anywhere");

        guidance.install();
        Map<String, FileTime> installed = treeSnapshot(stateRoot);
        assertTrue(guidance.isInstalled());
        assertEquals(new String(resource(SKILL_RESOURCE), StandardCharsets.UTF_8),
                guidance.bundledSkillText(), "preview still reads the bundle, not the disk");
        assertEquals(installed, treeSnapshot(stateRoot), "reads of installed state write nothing");
    }

    /** Matrix 4: stable paths within a version; re-install verifies and never rewrites. */
    @Test
    void pathIsStableAndReinstallVerifiesWithoutRewriting() throws Exception {
        TaskGuidance guidance = new TaskGuidance(stateRoot);
        Path dir = guidance.guidanceDirectory();
        assertEquals(dir, guidance.guidanceDirectory());
        assertEquals(dir, guidance.claudePluginDirectory());
        Path skill = guidance.piSkillFile();
        assertEquals(skill, guidance.piSkillFile());

        guidance.install();
        Path manifest = dir.resolve(".claude-plugin").resolve("plugin.json");
        FileTime skillMtime = Files.getLastModifiedTime(skill);
        FileTime manifestMtime = Files.getLastModifiedTime(manifest);

        assertEquals(dir, guidance.install(), "verify-only re-install returns the same path");
        assertArrayEquals(resource(SKILL_RESOURCE), Files.readAllBytes(skill),
                "re-install is byte-stable, not a rewrite");
        assertArrayEquals(manifestFor("2").getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(manifest));
        assertEquals(skillMtime, Files.getLastModifiedTime(skill), "skill bytes not rewritten");
        assertEquals(manifestMtime, Files.getLastModifiedTime(manifest),
                "manifest bytes not rewritten");
        assertTrue(guidance.isInstalled());

        // the T25b seam: a second version installs alongside without churning version 1
        TaskGuidance second = new TaskGuidance(stateRoot, "2", SKILL_RESOURCE);
        assertEquals(stateRoot.resolve("tasks-guidance").resolve("2"), second.install());
        assertTrue(second.isInstalled());
        assertArrayEquals(manifestFor("2").getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(stateRoot.resolve("tasks-guidance").resolve("2")
                        .resolve(".claude-plugin").resolve("plugin.json")));
        assertEquals(skillMtime, Files.getLastModifiedTime(skill),
                "version 1 keeps its mtimes across a version 2 install");
        assertTrue(guidance.isInstalled());

        // drifted bytes fail visibly and are never silently rewritten
        Files.write(skill, "tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(guidance.isInstalled());
        assertThrows(IOException.class, guidance::install);
        assertArrayEquals("tampered".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(skill), "the mismatch is not rewritten away");
    }

    /**
     * Matrix 5: the injected-root constructor writes nothing outside the
     * state directory it is given. The evidence is a before/after snapshot
     * of the user home's top-level names plus the absence of a
     * {@code tasks-guidance} entry there; the state tree itself is the
     * positive control.
     */
    @Test
    void injectedRootLeavesTheUserHomeUntouched() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        List<String> before = topLevelNames(home);

        TaskGuidance guidance = new TaskGuidance(stateRoot);
        assertEquals(guidance.guidanceDirectory(), guidance.install());
        assertEquals(guidance.guidanceDirectory(), guidance.install());

        assertFalse(Files.exists(home.resolve("tasks-guidance")),
                "no guidance root appeared directly under the user home");
        assertTrue(Files.isDirectory(stateRoot.resolve("tasks-guidance").resolve("2")),
                "the positive control: the injected root got the install");
        assertEquals(before, topLevelNames(home),
                "the user home's top level is unchanged by install and re-install");
    }
}

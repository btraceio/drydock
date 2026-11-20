package app.drydock.intellij;

import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.projects.SharedProjectsFile;

import com.intellij.openapi.application.PathManager;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The catalog-isolation guard for platform tests, and the only sanctioned
 * write path into the loaded catalog (T14).
 *
 * <p>A platform test that seeds the catalog must never touch the user's real
 * Drydock state: the plugin persists to {@code PathManager.getConfigPath()/
 * drydock/state.json} and follows a shared projects list the build points
 * somewhere else. The Gradle test task sandboxes both — but "the build
 * configures it" is not evidence, so every catalog-using test class calls
 * {@link #assertSandboxed()} in {@code setUp()} <em>before</em> {@code
 * super.setUp()}, before anything this test does can initialize the plugin,
 * and the write paths re-check it before every write.</p>
 *
 * <p>The guard fails closed. The accepted layout is exactly the observed
 * Gradle test sandbox of this build (IntelliJ Platform Gradle Plugin 2.19):
 * {@code <plugin>/.intellijPlatform/sandbox/<product>/<build>/config-test}
 * for the IDE config path — NOT a bare "anything under the checkout" — plus
 * the build's own {@code <plugin>/build/test-projects/projects.json} for the
 * shared projects list. The plan expected the config path under
 * {@code intellij-plugin/build}; the real test sandbox lives under
 * {@code .intellijPlatform/sandbox} instead (runtime evidence: the earlier
 * platform tests' state.json), and the parent approved this corrected,
 * still-strict guard. Anything else — the user's real IDE config, the
 * runIde sandbox ({@code config}, no {@code -test} suffix), a missing
 * property — is rejected.</p>
 */
public final class IsolatedCatalog {

    /** The test sandbox's config directory name, as the Gradle test task lays it out. */
    static final String TEST_CONFIG_DIR = "config-test";

    private IsolatedCatalog() {
    }

    /**
     * Asserts this test JVM's Drydock state is sandboxed. Touches no
     * service — only {@link PathManager} and the projects-file system
     * property — so it is safe before the application exists and before
     * the plugin initializes.
     */
    public static void assertSandboxed() {
        assertSandboxed(Path.of(PathManager.getConfigPath()),
                System.getProperty(SharedProjectsFile.OVERRIDE_PROPERTY));
    }

    /**
     * The guard over given values, so tests can prove it rejects the
     * layouts it must reject. Throws {@link AssertionError} listing every
     * violation when the layout is not the test sandbox.
     */
    public static void assertSandboxed(Path configPath, String projectsFileProperty) {
        List<String> violations = violations(configPath, projectsFileProperty);
        if (!violations.isEmpty()) {
            throw new AssertionError("the Drydock test catalog is not sandboxed:\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    /**
     * Every way the observed paths could fail to be the test sandbox.
     * Pure: no PathManager, no service, no disk.
     */
    static List<String> violations(Path configPath, String projectsFileProperty) {
        List<String> violations = new ArrayList<>();
        if (projectsFileProperty == null || projectsFileProperty.isBlank()) {
            violations.add("the " + SharedProjectsFile.OVERRIDE_PROPERTY
                    + " system property is not set; the Gradle test task must set it to "
                    + "<plugin>/build/test-projects/projects.json");
            return violations;
        }
        Path projects = Path.of(projectsFileProperty).toAbsolutePath().normalize();
        // <plugin>/build/test-projects/projects.json -> <plugin>
        Path pluginRoot = ancestor(projects, 3);
        if (pluginRoot == null) {
            violations.add("the shared projects file is not under <plugin>/build/test-projects: "
                    + projects);
            return violations;
        }
        Path expectedProjects = pluginRoot.resolve("build").resolve("test-projects")
                .resolve(projects.getFileName());
        if (!projects.equals(expectedProjects)) {
            violations.add("the shared projects file must be " + expectedProjects + " but is " + projects);
        }
        if (configPath == null) {
            violations.add("the IDE config path is null");
            return violations;
        }
        Path config = configPath.toAbsolutePath().normalize();
        Path sandbox = pluginRoot.resolve(".intellijPlatform").resolve("sandbox");
        if (!config.startsWith(sandbox) || !TEST_CONFIG_DIR.equals(config.getFileName().toString())) {
            violations.add("the IDE config path must be the test sandbox "
                    + sandbox.resolve("<product>").resolve(TEST_CONFIG_DIR) + " (or a sibling of it) "
                    + "but is " + config);
        }
        return violations;
    }

    /** {@code levels} directories up from {@code path}, or null when there are fewer. */
    private static Path ancestor(Path path, int levels) {
        Path result = path;
        for (int i = 0; i < levels && result != null; i++) {
            result = result.getParent();
        }
        return result;
    }

    /**
     * Seeds {@code session} into the loaded catalog. Re-guarded: the
     * sandbox paths are re-checked, the plugin's initialization awaited,
     * and the store's base directory verified to be exactly the sandboxed
     * config path's {@code drydock} before the single write.
     */
    public static void seed(ManagedAgentSession session) {
        store().update(state -> {
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            // A fresh id can never be present; guarding anyway keeps a
            // re-seed idempotent instead of duplicating the entry.
            boolean present = sessions.stream().anyMatch(existing -> existing.id().equals(session.id()));
            if (!present) {
                sessions.add(session);
            }
            return state.withSessions(sessions);
        });
    }

    /**
     * Filters {@code ids} back out of the catalog and flushes the write,
     * so a test's seeded sessions never survive it in the persistent
     * sandbox state. Re-guarded like every write.
     */
    public static void remove(ManagedSessionId... ids) {
        List<ManagedSessionId> toRemove = List.of(ids);
        ApplicationStateStore store = store();
        store.update(state -> {
            List<ManagedAgentSession> kept = new ArrayList<>(state.sessions().size());
            for (ManagedAgentSession existing : state.sessions()) {
                if (!toRemove.contains(existing.id())) {
                    kept.add(existing);
                }
            }
            return state.withSessions(kept);
        });
        store.flush();
    }

    /** The guarded store: sandbox re-checked, initialization awaited, base directory verified. */
    private static ApplicationStateStore store() {
        assertSandboxed();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try {
            plugin.initialization().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting Drydock initialization", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Drydock initialization did not complete within 60 s", e);
        }
        Path expectedBase = Path.of(PathManager.getConfigPath()).toAbsolutePath().normalize()
                .resolve("drydock");
        Path base = plugin.baseDir().toAbsolutePath().normalize();
        if (!base.equals(expectedBase)) {
            throw new AssertionError("the Drydock state must live at the sandboxed " + expectedBase
                    + " but lives at " + base);
        }
        return plugin.stateStore();
    }
}

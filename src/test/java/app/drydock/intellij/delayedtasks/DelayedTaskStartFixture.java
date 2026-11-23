package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.*;
import app.drydock.domain.*;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.tasks.*;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The shared booted-IDE fixture of the T31b matrix's two files (a supervisor-granted
 * split, the T05c/T11b precedent; the matrix items live wholly in exactly one of
 * DelayedTaskStartTest and DelayedTaskStartBindingTest, whose javadocs carry the full
 * 7-item mapping): the real Gradle-sandbox task store (zero mocking), the direct-write
 * task fixture (the T20b shape), the production planOf/claim/in-flight seams reached
 * reflectively where no seam exists (T22b/T28b precedent, pinned to the exact
 * production branch each enters), and the pump-park and settle-then-decode waits.
 * Nothing matrix-specific is covered here.
 */
abstract class DelayedTaskStartFixture extends BasePlatformTestCase {

    DrydockPluginService plugin;
    Path storeRoot;
    final List<Path> repoDirs = new ArrayList<>();
    final List<TaskId> written = new ArrayList<>();
    final List<Disposable> dialogs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); } catch (Exception e) { fail("init: " + e); }
        storeRoot = plugin.taskService().root().toAbsolutePath().normalize();
        assertTrue("the store root must be the Gradle test sandbox: " + storeRoot,
                !storeRoot.startsWith(Path.of(System.getProperty("user.home"), ".drydock")));
    }

    @Override
    protected void tearDown() throws Exception {
        for (Disposable dialog : dialogs) {
            try { Disposer.dispose(dialog); } catch (RuntimeException ignored) { }
        }
        for (TaskId id : written) {
            try { Files.deleteIfExists(storeRoot.resolve("runs").resolve(id.text() + ".run")); }
            catch (IOException ignored) { }
        }
        for (Path dir : repoDirs) delete(dir);
        super.tearDown();
    }

    /** One startable one-segment fixture: an open prerequisite, the target after it. */
    Chain chain(String prefix, String title) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        TaskId open = write(prefix + "-open", "Write the tests", List.of(), repository);
        TaskId ship = write(prefix + "-ship", title, List.of(open.text()), repository);
        return new Chain(open, ship, planOf(ship));
    }

    record Chain(TaskId open, TaskId ship, StartPlan plan) { }

    /** One task file written directly under the store root (the T20b fixture shape). */
    TaskId write(String slug, String title, List<String> after, RepositoryId repository)
            throws IOException {
        TaskId id = TaskId.of(slug);
        Path dir = storeRoot.resolve(repository.toString());
        repoDirs.add(dir);
        Files.createDirectories(dir);
        String refs = after.isEmpty() ? "" : "'" + String.join("', '", after) + "'";
        Files.writeString(dir.resolve(id.text() + ".md"), "---\ntitle: \"" + title + "\"\nstatus: open\n"
                + "done_by: human\ncreated: " + Instant.now() + "\nafter: [" + refs + "]\n"
                + "sessions: []\n---\n\nFixture body.\n");
        written.add(id);
        return id;
    }

    /** The production planOf over the service's own fresh snapshot; the booted state registers
      * no repository, so the production slug mapping yields null for the fixture's, and the
      * planner's own per-repository fallback applies, exactly as in the live flow. */
    StartPlan planOf(TaskId target) throws Exception {
        DelayedTaskService service = plugin.taskService();
        return (StartPlan) seam(DelayedTaskStart.class, "planOf",
                new Class<?>[] {TaskId.class, TaskSnapshot.class, Function.class, Set.class}, target,
                service.snapshot().get(30, TimeUnit.SECONDS),
                (Function<RepositoryId, String>) repository -> null, service.liveSessionIds());
    }

    /** The production claim seam: the whole segment's work, or null when in flight elsewhere. */
    List<TaskId> claim(StartPlan.Segment segment) {
        return (List<TaskId>) seam(DelayedTaskStart.class, "claim",
                new Class<?>[] {StartPlan.Segment.class}, segment);
    }

    /** The production in-flight set (the duplicate-click and start-elsewhere guard). */
    static Set<TaskId> inFlight() {
        return (Set<TaskId>) staticField(DelayedTaskStart.class, "IN_FLIGHT");
    }

    /** One production package-private static seam, reached reflectively where no seam exists. */
    static Object seam(Class<?> owner, String name, Class<?>[] types, Object... arguments) {
        try { Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true);
            return method.invoke(null, arguments); }
        catch (ReflectiveOperationException e) { throw new AssertionError("no seam " + name, e); }
    }

    static Object staticField(Class<?> owner, String name) {
        try { Field found = owner.getDeclaredField(name); found.setAccessible(true);
            return found.get(null); }
        catch (ReflectiveOperationException e) { throw new AssertionError("no static field " + name, e); }
    }

    /** Spins (never sleeps) until the condition holds, pumping the EDT queue between polls. */
    void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            pump();
        }
    }

    static void pump() {
        for (int i = 0; i < 3; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }

    /** Settled-then-decoded store content: never race a pending write (the T20b fix). */
    Map<String, String> settledTree() throws IOException {
        Map<String, String> tree = decoded();
        for (int i = 0; i < 100; i++) {
            pump();
            if (decoded().equals(tree)) { return tree; }
            tree = decoded();
        }
        return tree;
    }

    private Map<String, String> decoded() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(storeRoot)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    out.put(storeRoot.relativize(path).toString(), Files.readString(path));
                }
            }
        }
        return out;
    }

    static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}

package app.drydock.ui;

import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The harness-scoping of {@link ModelSelector}'s suggestion list, on the FX
 * thread it actually runs on: switching harnesses swaps the popup's list to
 * the selected harness's own catalog -- never a merge of everything loaded so
 * far (Pi's whole catalog used to survive under Claude's toggle) -- and
 * returning to a harness shows its list again without a second load.
 */
class ModelSelectorFxTest extends FxTest {

    private static final List<String> PI_CATALOG = List.of("pi-model-a", "pi-model-b", "pi-model-c");
    private static final List<String> CLAUDE_CATALOG = List.of("sonnet");
    /** Two catalog sources listing the same model: the repeated id must show once. */
    private static final List<String> CODEX_CATALOG = List.of("gpt-1", "gpt-1", "gpt-2");

    private final Map<AgentKind, AtomicInteger> calls = new EnumMap<>(AgentKind.class);

    private ModelSelector selector;

    /** Holds CLAUDE's probe until released; count 0 (the default) never blocks. */
    private volatile CountDownLatch claudeGate = new CountDownLatch(0);

    @Override
    public void start(Stage stage) {
        for (AgentKind kind : AgentKind.values()) {
            calls.put(kind, new AtomicInteger());
        }
        AgentContext ctx = new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                java.util.concurrent.ForkJoinPool.commonPool());
        AgentRegistry registry = new AgentRegistry(
                List.of(new FakeProvider(AgentKind.PI, PI_CATALOG),
                        new FakeProvider(AgentKind.CLAUDE, CLAUDE_CATALOG),
                        new FakeProvider(AgentKind.CODEX, CODEX_CATALOG)), ctx);
        selector = new ModelSelector(registry, kind -> Optional.empty());
        TestStages.show(stage, new Scene(new StackPane(selector), 420, 120));
    }

    @Test
    void switchingHarnessSwapsTheListInsteadOfMerging() throws Exception {
        interact(() -> selector.setAgent(AgentKind.PI));
        awaitCatalog(AgentKind.PI, PI_CATALOG.size());

        interact(() -> selector.setAgent(AgentKind.CLAUDE));
        awaitCatalog(AgentKind.CLAUDE, CLAUDE_CATALOG.size());

        ObservableList<String> shown = items();
        assertEquals(CLAUDE_CATALOG, shown, "the list is the selected harness's own catalog");
        for (String piModel : PI_CATALOG) {
            assertFalse(shown.contains(piModel), "another harness's ids must not survive the switch");
        }
    }

    @Test
    void returningToAHarnessShowsItsListAgainWithoutASecondLoad() throws Exception {
        interact(() -> selector.setAgent(AgentKind.PI));
        awaitCatalog(AgentKind.PI, PI_CATALOG.size());
        interact(() -> selector.setAgent(AgentKind.CLAUDE));
        awaitCatalog(AgentKind.CLAUDE, CLAUDE_CATALOG.size());

        interact(() -> selector.setAgent(AgentKind.PI));
        FxSync.waitForFxEvents();

        assertEquals(PI_CATALOG, items());
        assertEquals(1, calls.get(AgentKind.PI).get(), "the catalog is kept per harness");
        assertEquals(1, calls.get(AgentKind.CLAUDE).get());
    }

    @Test
    void aRepeatedCatalogIdAppearsOnceOnLoadAndAfterReturning() throws Exception {
        interact(() -> selector.setAgent(AgentKind.CODEX));
        awaitAtLeast(2);
        FxSync.waitForFxEvents();
        assertEquals(List.of("gpt-1", "gpt-2"), items(), "a repeated id must appear once on load");

        interact(() -> selector.setAgent(AgentKind.PI));
        awaitCatalog(AgentKind.PI, PI_CATALOG.size());

        interact(() -> selector.setAgent(AgentKind.CODEX));
        FxSync.waitForFxEvents();
        assertEquals(List.of("gpt-1", "gpt-2"), items(), "the cache replay must show it once too");
        assertEquals(1, calls.get(AgentKind.CODEX).get());
    }

    @Test
    void aLateLoadForAnUnselectedHarnessIsStoredButNotShown() throws Exception {
        claudeGate = new CountDownLatch(1);
        try {
            interact(() -> selector.setAgent(AgentKind.PI));
            awaitCatalog(AgentKind.PI, PI_CATALOG.size());
            // CLAUDE's load starts and blocks in the probe.
            interact(() -> selector.setAgent(AgentKind.CLAUDE));
            // PI is served from the selector's cache, not the (busy) registry.
            interact(() -> selector.setAgent(AgentKind.PI));
            FxSync.waitForFxEvents();
            assertEquals(PI_CATALOG, items());

            claudeGate.countDown();
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (calls.get(AgentKind.CLAUDE).get() < 1) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("CLAUDE's probe never returned");
                }
                Thread.sleep(20);
            }
            Thread.sleep(200);
            FxSync.waitForFxEvents();
            assertEquals(PI_CATALOG, items(),
                    "a late load for a harness no longer selected must not replace the list");

            // ...but it was stored: CLAUDE shows its list with no second load.
            interact(() -> selector.setAgent(AgentKind.CLAUDE));
            FxSync.waitForFxEvents();
            assertEquals(CLAUDE_CATALOG, items());
            assertEquals(1, calls.get(AgentKind.CLAUDE).get());
        } finally {
            claudeGate.countDown();
        }
    }

    private ObservableList<String> items() {
        // The combo's items list is the filtered view; with an empty editor
        // query it equals the source list.
        javafx.scene.control.ComboBox<?> combo = (javafx.scene.control.ComboBox<?>) selector.getChildren().get(1);
        @SuppressWarnings("unchecked")
        ObservableList<String> filteredList = (ObservableList<String>) (ObservableList<?>) combo.getItems();
        return filteredList;
    }

    /** Polls until the named harness's catalog has landed in the picker. */
    private void awaitCatalog(AgentKind kind, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] ready = new boolean[1];
            interact(() -> ready[0] = items().size() == expected);
            if (ready[0]) {
                FxSync.waitForFxEvents();
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException(kind + "'s catalog never arrived");
    }

    /** Polls until the picker shows at least {@code expected} items (so an excess fails an assertion, not a timeout). */
    private void awaitAtLeast(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] ready = new boolean[1];
            interact(() -> ready[0] = items().size() >= expected);
            if (ready[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the catalog never arrived");
    }

    /** A provider whose only interesting answer is a counted, fixed suggestion list. */
    private final class FakeProvider implements AgentProvider {

        private final AgentKind kind;
        private final List<String> suggestions;

        FakeProvider(AgentKind kind, List<String> suggestions) {
            this.kind = kind;
            this.suggestions = suggestions;
        }

        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return kind.persistedName(); }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/sh")); }
        @Override public String describeSearched() { return "test fake"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(false, false, "test"); }
        @Override public boolean supportsRemote() { return false; }
        @Override public McpDelivery mcpDelivery() { return McpDelivery.NONE; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.unsupported(); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.unsupported(); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public java.util.Optional<app.drydock.agent.api.ConversationSource> conversations() {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<app.drydock.agent.api.ActivityReporter> activity() {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<app.drydock.agent.api.SessionIdDiscovery> idDiscovery() {
            return java.util.Optional.empty();
        }
        @Override public List<String> modelSuggestions() {
            if (kind == AgentKind.CLAUDE) {
                try {
                    claudeGate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // After the gate: a count of 1 means the probe has returned.
            calls.get(kind).incrementAndGet();
            return suggestions;
        }
    }
}

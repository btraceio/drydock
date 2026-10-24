package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComboBox;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The {@link ModelField} combo on the EDT: filtering over the retained
 * catalog, editor preservation, and the harness-switch re-filter. The field
 * is never shown, so the popup is never touched (it cannot anchor headless).
 */
public class ModelFieldPlatformTest extends BasePlatformTestCase {

    private static final List<String> CATALOG = List.of("gpt-1", "gpt-2", "claude-x");

    public void testTypingNarrowsWithoutRewritingTheEditor() throws Exception {
        ModelField field = loaded(new AtomicInteger());
        JComboBox<?> combo = combo(field);

        type(field, "g");
        type(field, "p");

        assertEquals("gp", editor(field).getText());
        assertEquals(2, combo.getItemCount());
        assertEquals(Optional.of("gp"), field.selected());
        assertEquals("gp", combo.getSelectedItem());
    }

    public void testClearingTheQueryRestoresTheFullCatalog() throws Exception {
        ModelField field = loaded(new AtomicInteger());
        JComboBox<?> combo = combo(field);
        type(field, "gp");
        assertEquals(2, combo.getItemCount());

        clear(field);
        assertEquals(3, combo.getItemCount());
        assertEquals("", editor(field).getText());
        assertEquals(Optional.empty(), field.selected());

        type(field, "c");
        assertEquals(1, combo.getItemCount());
        assertEquals("claude-x", combo.getItemAt(0));
    }

    public void testTrailingSpaceIsKept() throws Exception {
        ModelField field = loaded(new AtomicInteger());
        JComboBox<?> combo = combo(field);

        type(field, "gp");
        type(field, " ");

        assertEquals("gp ", editor(field).getText());
        assertEquals(2, combo.getItemCount());
        assertEquals(Optional.of("gp"), field.selected());
    }

    public void testSuggestionsLandingOnAnEmptyEditorSelectNothing() throws Exception {
        ModelField field = loaded(new AtomicInteger());
        JComboBox<?> combo = combo(field);

        assertEquals("", editor(field).getText());
        assertEquals(Optional.empty(), field.selected());
        assertNull(combo.getSelectedItem());
    }

    public void testSuggestionsLandingAfterATypedQueryAreFiltered() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ModelField field = new ModelField(kind -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CATALOG;
        });
        JComboBox<?> combo = combo(field);
        field.setAgent(AgentKind.PI, k -> Optional.empty());

        type(field, "cl");
        assertEquals(0, combo.getItemCount());
        release.countDown();
        waitFor(() -> combo.getItemCount() == 1);

        assertEquals("claude-x", combo.getItemAt(0));
        assertEquals("cl", editor(field).getText());
    }

    public void testHarnessSwitchRefilters() throws Exception {
        Map<AgentKind, AtomicInteger> calls = new EnumMap<>(AgentKind.class);
        ModelField field = new ModelField(counting(calls, CATALOG));
        JComboBox<?> combo = combo(field);
        field.setAgent(AgentKind.PI, k -> Optional.empty());
        waitFor(() -> combo.getItemCount() == 3);
        type(field, "gp");
        assertEquals(2, combo.getItemCount());

        field.setAgent(AgentKind.CLAUDE, k -> Optional.empty());
        assertEquals("", editor(field).getText());
        assertEquals(3, combo.getItemCount());

        field.setAgent(AgentKind.PI, k -> Optional.empty());
        assertEquals("gp", editor(field).getText());
        assertEquals(2, combo.getItemCount());

        // Let the CLAUDE load land (and be dropped by the kind guard) inside this test.
        waitFor(() -> count(calls, AgentKind.CLAUDE) == 1);
        pump();
        assertEquals(2, combo.getItemCount());
    }

    public void testSuggestionsAreDedupedAndLoadedOncePerHarness() throws Exception {
        Map<AgentKind, AtomicInteger> calls = new EnumMap<>(AgentKind.class);
        ModelField field = new ModelField(counting(calls, List.of("gpt-1", "gpt-1", "gpt-2")));
        JComboBox<?> combo = combo(field);
        field.setAgent(AgentKind.PI, k -> Optional.empty());
        waitFor(() -> combo.getItemCount() == 2);

        field.setAgent(AgentKind.CLAUDE, k -> Optional.empty());
        field.setAgent(AgentKind.PI, k -> Optional.empty());
        waitFor(() -> count(calls, AgentKind.CLAUDE) == 1);
        pump();

        assertEquals(1, count(calls, AgentKind.PI));
        assertEquals(2, combo.getItemCount());
    }

    public void testRememberedModelSeedsTheEditorButNotTheList() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelField field = new ModelField(kind -> {
            calls.incrementAndGet();
            return CATALOG;
        });
        JComboBox<?> combo = combo(field);
        field.setAgent(AgentKind.PI, k -> Optional.of("my-custom"));
        waitFor(() -> calls.get() == 1);
        pump();

        assertEquals("my-custom", editor(field).getText());
        assertEquals(0, combo.getItemCount());

        clear(field);
        assertEquals(3, combo.getItemCount());
        for (int i = 0; i < combo.getItemCount(); i++) {
            assertFalse("my-custom".equals(combo.getItemAt(i)));
        }
    }

    /** A field on PI whose catalog has landed (three items, empty editor). */
    private static ModelField loaded(AtomicInteger calls) throws InterruptedException {
        ModelField field = new ModelField(kind -> {
            calls.incrementAndGet();
            return CATALOG;
        });
        JComboBox<?> combo = combo(field);
        field.setAgent(AgentKind.PI, k -> Optional.empty());
        waitFor(() -> combo.getItemCount() == 3);
        return field;
    }

    private static Function<AgentKind, List<String>> counting(Map<AgentKind, AtomicInteger> calls,
                                                              List<String> catalog) {
        for (AgentKind kind : AgentKind.values()) {
            calls.put(kind, new AtomicInteger());
        }
        return kind -> {
            calls.get(kind).incrementAndGet();
            return catalog;
        };
    }

    private static int count(Map<AgentKind, AtomicInteger> calls, AgentKind kind) {
        return calls.get(kind).get();
    }

    private static JComboBox<?> combo(ModelField field) {
        return (JComboBox<?>) field.getComponent(0);
    }

    private static JTextComponent editor(ModelField field) {
        return (JTextComponent) combo(field).getEditor().getEditorComponent();
    }

    private static void type(ModelField field, String text) throws Exception {
        Document document = editor(field).getDocument();
        document.insertString(document.getLength(), text, null);
    }

    private static void clear(ModelField field) throws Exception {
        Document document = editor(field).getDocument();
        document.remove(0, document.getLength());
    }

    /** Drains a few rounds of the EDT queue so pending invokeLater merges run. */
    private static void pump() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    /** Polls, pumping the EDT (the test runs on it), for up to ten seconds. */
    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within 10 s");
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }
}

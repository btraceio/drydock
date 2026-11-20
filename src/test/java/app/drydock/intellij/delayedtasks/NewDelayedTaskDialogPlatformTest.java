package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.NewDelayedTaskDialog;
import app.drydock.delayedtasks.NewDelayedTaskDialog.Result;
import app.drydock.delayedtasks.TaskPrerequisitePicker;
import app.drydock.delayedtasks.TaskPrerequisitePicker.Index;
import app.drydock.delayedtasks.TaskPrerequisitePicker.Match;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskStore;

import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.TimeoutUtil;

import org.junit.Assert;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.text.JTextComponent;

import java.awt.Component;
import java.awt.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The T18b acceptance matrix for the new-task dialog and its picker in a
 * booted IDE. Stated plainly: the fixture boots real Swing, but
 * DialogWrapper's headless peer (unit-test mode) drops the window, so the
 * dialog is never SHOWN - the matrix drives its real component tree
 * instead, through the public getPreferredFocusedComponent seam plus the
 * parent chain: search field, coalescing timer, buttons, radios, reason
 * label, validation and OK/cancel actions are all production. Only real
 * keystrokes to a focused window cannot run headless: Enter goes
 * through the OK button's own action (what the default-button Enter
 * fires), Esc through the public doCancelAction (the exact method the
 * root-pane Esc binding calls). Broken/ambiguous refs cannot be picked
 * through the picker's UI, so their validation runs over its public
 * setReferences seam - what doValidate composes. Waits pump the EDT with a
 * deadline; zero-write proofs compare the whole temp-root tree.
 */
public class NewDelayedTaskDialogPlatformTest extends BasePlatformTestCase {

    private Path root;
    private Index index;
    private RepositoryId alpha, beta, unnamed, alphaToo;
    private TaskId login, docs, ship, clash;
    private final List<NewDelayedTaskDialog> dialogs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("drydock-new-task-store");
        alpha = RepositoryId.newId();
        beta = RepositoryId.newId();
        unnamed = RepositoryId.newId();
        alphaToo = RepositoryId.newId();
        TaskStore store = new TaskStore(root);
        login = store.create(alpha, "Fix login redirect", List.of()).id();
        docs = store.create(beta, "Write user docs", List.of()).id();
        store.create(alphaToo, "The duplicate slug task", List.of());
        store.create(beta, "Let the agent finish", List.of());
        ship = store.create(unnamed, "Ship the release", List.of()).id();
        clash = store.create(alpha, "Clashing fixture", List.of()).id();
        store.flush();
        store.close();
        Files.copy(root.resolve(alpha.toString()).resolve(clash.text() + ".md"),
                root.resolve(unnamed.toString()).resolve(clash.text() + ".md"));
        index = Index.of(TaskFiles.scan(root), repository -> alpha.equals(repository)
                || alphaToo.equals(repository) ? "alpha" : beta.equals(repository) ? "beta" : null);
    }

    @Override
    protected void tearDown() throws Exception {
        dialogs.forEach(NewDelayedTaskDialog::disposeIfNeeded);
        super.tearDown();
        delete(root);
    }

    // 1. A blank title blocks OK with a visible reason.
    public void testABlankTitleBlocksOkWithAVisibleReason() {
        NewDelayedTaskDialog dialog = newDialog();
        JTextComponent title = titleField(dialog);
        JLabel reason = byText(dialog, JLabel.class, JLabel::getText, "A task needs a title.");
        title.setText("   ");
        assertTrue("a blank title shows the visible reason under the field", reason.isVisible());
        List<ValidationInfo> refused = dialog.performValidateAll();
        assertTrue("exactly the title refusal, on the refusing field", refused.size() == 1
                && "Give the task a title.".equals(refused.get(0).message)
                && refused.get(0).component == title);
        control(dialog, "Create task").doClick();
        assertTrue("a blank title cannot be confirmed and accepts no result", !dialog.isOK());
        Assert.assertThrows(IllegalStateException.class, dialog::result);
        title.setText("Fix the release notes");
        assertTrue("the reason disappears and doValidate passes for a real title",
                !reason.isVisible() && dialog.performValidateAll().isEmpty());
    }

    // 2. Cross-project search, canonical refs, ambiguous/broken reasons.
    public void testPickerSearchFindsCrossProjectHitsAndBuildsCanonicalRefs() {
        List<Match> across = index.search("the", 50);
        assertTrue("cross-project hits show project, title and id", across.size() == 3
                && across.stream().map(Match::project).distinct().count() == 3
                && across.stream().allMatch(hit -> hit.display()
                        .equals((hit.project() == null ? "(no project)" : hit.project()) + " - "
                                + hit.title() + "  [" + hit.id().text() + "]")));
        assertTrue("a blank query answers no hits and a collided task is never offered",
                index.search("   ", 50).isEmpty() && index.search("clashing", 50).isEmpty());
        assertTrue("canonical refs: an ambiguous slug and a slugless repo stay bare, a unique slug qualifies",
                login.text().equals(index.canonicalReference(doc(login)))
                        && ("beta:" + docs.text()).equals(index.canonicalReference(doc(docs)))
                        && ship.text().equals(index.canonicalReference(doc(ship))));
        TaskPrerequisitePicker picker = new TaskPrerequisitePicker(index);
        picker.setReferences(List.of("ghost:x-1", "missing-id", "beta:" + login.text(), clash.text()));
        List<String> broken = picker.refusals();
        assertTrue("every broken ref is reported, in order, with the graph's exact reasons", broken.size() == 4
                && broken.get(0).contains("no repository with project slug 'ghost'")
                && broken.get(1).contains("no task with id 'missing-id'")
                && broken.get(2).contains("no task with id '" + login.text()
                + "' in project 'beta' (the id is in project 'alpha')")
                && broken.get(3).contains("ambiguous id '" + clash.text() + "'")
                && broken.get(3).contains("claimed by 2 files"));
        picker.setReferences(List.of("beta:" + docs.text(), login.text(), ship.text()));
        assertTrue("valid canonical refs answer no refusal", picker.refusals().isEmpty());
    }

    // 3. done_by defaults HUMAN; the immutable Result round-trips.
    public void testDoneByDefaultsHumanAndTheResultIsImmutable() {
        Result defaulted = new Result(null, null, null, null);
        assertTrue(defaulted.title().isEmpty() && defaulted.body().isEmpty()
                && defaulted.after().isEmpty() && defaulted.doneBy() == DoneBy.HUMAN);
        List<String> source = new ArrayList<>(List.of("after-b"));
        Result copied = new Result("  Round trip  ", "Body prose", source, DoneBy.AGENT);
        source.add("after-c");
        assertTrue("the title is stripped and the refs defensively copied",
                "Round trip".equals(copied.title()) && List.of("after-b").equals(copied.after()));
        Assert.assertThrows(UnsupportedOperationException.class, () -> copied.after().add("late"));
        NewDelayedTaskDialog human = newDialog();
        assertTrue("the done_by control defaults HUMAN", control(human, "Human confirms done").isSelected()
                && !control(human, "Agent may set done").isSelected());
        components(human, JTextArea.class).get(0).setText("The human body round-trips.");
        titleField(human).setText("Human confirms");
        control(human, "Create task").doClick();
        assertTrue("the default policy and the body reach the result",
                human.result().doneBy() == DoneBy.HUMAN
                        && "The human body round-trips.".equals(human.result().body()));
        NewDelayedTaskDialog agent = newDialog();
        control(agent, "Agent may set done").doClick();
        byText(agent, JLabel.class, JLabel::getText,
                "An agent may set this task done; dropping or reopening it stays human-only.");
        titleField(agent).setText("Agent confirms");
        control(agent, "Create task").doClick();
        assertEquals("the chosen policy reaches the result", DoneBy.AGENT, agent.result().doneBy());
    }

    // 4. Enter confirms, Esc cancels; zero writes.
    public void testEnterConfirmsAndEscCancelsWithZeroWrites() throws IOException {
        Map<Path, Long> untouched = tree(root);
        NewDelayedTaskDialog dialog = newDialog();
        titleField(dialog).setText("Confirmed by keyboard");
        AbstractButton ok = control(dialog, "Create task");
        AbstractButton cancel = control(dialog, "Cancel");
        assertTrue("OK and Cancel are real enabled focusable buttons",
                ok.isEnabled() && ok.isFocusable() && cancel.isEnabled() && cancel.isFocusable());
        ok.doClick();
        assertEquals("a valid form confirms with the typed title",
                "Confirmed by keyboard", dialog.result().title());
        NewDelayedTaskDialog cancelled = newDialog();
        titleField(cancelled).setText("Abandoned midway");
        cancelled.doCancelAction();
        assertFalse("Esc cancels without confirming", cancelled.isOK());
        Assert.assertThrows(IllegalStateException.class, cancelled::result);
        assertEquals("neither confirm nor cancel writes anything: the temp root is untouched",
                untouched, tree(root));
    }

    // 5. The whole flow - title, body, pick, policy, Enter - writes nothing.
    public void testCompletingTheWholeDialogWritesNothingAnywhere() throws IOException {
        Map<Path, Long> untouched = tree(root);
        NewDelayedTaskDialog dialog = newDialog();
        titleField(dialog).setText("The full creation flow");
        components(dialog, JTextArea.class).get(0).setText("The body round-trips too.");
        searchField(dialog).setText("docs");
        JList<?> hits = list(dialog, 0);
        await(() -> hits.getModel().getSize() == 1, "the coalesced refill of the hits list");
        hits.setSelectedIndex(0);
        control(dialog, "Add").doClick();
        control(dialog, "Agent may set done").doClick();
        control(dialog, "Create task").doClick();
        Result result = dialog.result();
        assertTrue(dialog.isOK() && List.of("beta:" + docs.text()).equals(result.after())
                && result.doneBy() == DoneBy.AGENT);
        assertEquals("the whole flow wrote nothing: the temp root is untouched", untouched, tree(root));
    }

    private NewDelayedTaskDialog newDialog() {
        NewDelayedTaskDialog dialog = new NewDelayedTaskDialog(index);
        dialogs.add(dialog);
        return dialog;
    }

    private TaskDocument doc(TaskId id) {
        return index.snapshot().byId().get(id);
    }

    private static JTextComponent titleField(NewDelayedTaskDialog dialog) {
        Component focus = dialog.getPreferredFocusedComponent();
        assertTrue(focus instanceof JTextComponent field && field.isFocusable());
        return (JTextComponent) focus;
    }

    private static JTextComponent searchField(NewDelayedTaskDialog dialog) {
        List<JBTextField> fields = components(dialog, JBTextField.class);
        return fields.get(0) == titleField(dialog) ? fields.get(1) : fields.get(0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JList list(NewDelayedTaskDialog dialog, int which) {
        List<JList> lists = components(dialog, JList.class);
        assertEquals("hits and chosen lists", 2, lists.size());
        return lists.get(which);
    }

    private static AbstractButton control(NewDelayedTaskDialog dialog, String text) {
        return byText(dialog, AbstractButton.class, AbstractButton::getText, text);
    }

    private static <T extends Component> T byText(
            NewDelayedTaskDialog dialog, Class<T> type, Function<T, String> reader, String text) {
        return components(dialog, type).stream().filter(item -> text.equals(reader.apply(item))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " reading " + text));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends Component> List<T> components(NewDelayedTaskDialog dialog, Class<T> type) {
        Container root = titleField(dialog);
        while (root.getParent() != null) root = root.getParent();
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collect(Container parent, Class type, List found) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) collect(container, type, found);
        }
    }

    /** Spins, pumping the EDT (for the 150 ms timer), until the condition holds. */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            assertTrue("Timed out waiting for " + what, System.nanoTime() < deadline);
            for (int i = 0; i < 3; i++) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                TimeoutUtil.sleep(20);
            }
        }
    }

    private static Map<Path, Long> tree(Path root) throws IOException {
        Map<Path, Long> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) out.put(root.relativize(path),
                    Files.getLastModifiedTime(path).toMillis());
        }
        return out;
    }

    private static void delete(Path directory) {
        if (directory == null) return;
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}

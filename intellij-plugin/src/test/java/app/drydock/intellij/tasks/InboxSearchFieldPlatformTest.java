package app.drydock.intellij.tasks;

import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.Suggestion;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link InboxSearchField} in a booted IDE: what text it reports, the
 * {@code project:} suggestions and their keyboard picking, and the chip's
 * removal by Backspace. Headless: the popup is never shown, so every
 * gesture goes through the keys.
 */
public class InboxSearchFieldPlatformTest extends BasePlatformTestCase {

    private static final ProjectRef ALPHA = new ProjectRef(Path.of("/repos/alpha").toAbsolutePath(), "alpha");
    private static final ProjectRef ALPACA = new ProjectRef(Path.of("/repos/alpaca").toAbsolutePath(), "alpaca");
    private static final ProjectRef BETA = new ProjectRef(Path.of("/repos/beta").toAbsolutePath(), "beta");
    private static final List<Suggestion> PROJECTS = List.of(new Suggestion(ALPACA, 0), new Suggestion(ALPHA, 3),
            new Suggestion(BETA, 1));

    private final List<String> texts = new ArrayList<>();
    private final List<ProjectRef> picked = new ArrayList<>();
    private int cleared;
    private InboxSearchField field;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        field = new InboxSearchField(new InboxSearchField.Listener() {
            @Override
            public void textChanged(String effectiveText) {
                texts.add(effectiveText);
            }

            @Override
            public void projectPicked(ProjectRef project) {
                picked.add(project);
            }

            @Override
            public void projectCleared() {
                cleared++;
            }
        }, text -> PROJECTS.stream()
                .filter(s -> s.project().name().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))
                .toList());
    }

    /** Delivers a key press to the field's listeners: headless, AWT retargets dispatched keys to a focus owner. */
    private void press(int keyCode) {
        KeyEvent event = new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode,
                KeyEvent.CHAR_UNDEFINED);
        for (KeyListener listener : field.getKeyListeners()) {
            listener.keyPressed(event);
        }
    }

    private List<String> shownNames() {
        return field.suggestionsShown().stream().map(s -> s.project().name()).toList();
    }

    public void testPlainTextIsReportedAndAProjectPrefixIsNot() {
        field.setText("parser");
        assertEquals("parser", texts.getLast());
        field.setText("project:al");
        assertEquals("while project: is typed the inbox gets no text", "", texts.getLast());
        assertEquals("", InboxSearchField.effectiveText("  PROJECT:x"));
        assertEquals("is:idle", InboxSearchField.effectiveText("is:idle"));
    }

    public void testProjectColonSuggestsAndTheKeysPick() {
        field.setText("project:al");
        assertEquals(List.of("alpaca", "alpha"), shownNames());
        assertEquals(Optional.of("alpaca"), field.highlighted().map(s -> s.project().name()));
        press(KeyEvent.VK_DOWN);
        press(KeyEvent.VK_DOWN);
        assertEquals("the highlight stops at the last suggestion",
                Optional.of("alpha"), field.highlighted().map(s -> s.project().name()));
        press(KeyEvent.VK_ENTER);
        assertEquals(List.of(ALPHA), picked);
        assertEquals("picking clears the text", "", field.getText());
        assertEquals(List.of(), shownNames());
    }

    public void testNoMatchSuggestsNothingAndEnterPicksNothing() {
        field.setText("project:zzz");
        assertEquals(List.of(), shownNames());
        press(KeyEvent.VK_ENTER);
        assertEquals(List.of(), picked);
    }

    public void testTheChipChangesThePlaceholderAndBackspaceRemovesIt() {
        assertEquals("Search, or type project:", field.getEmptyText().getText());
        field.showProject(Optional.of(BETA));
        assertEquals("Search in project", field.getEmptyText().getText());
        assertTrue("the chip is an extension before the text",
                field.getExtensions().stream().filter(e -> e.isIconBeforeText()).count() == 2);
        field.setText("x");
        press(KeyEvent.VK_BACK_SPACE);
        assertEquals("Backspace in a non-empty field edits text", 0, cleared);
        field.setText("");
        press(KeyEvent.VK_BACK_SPACE);
        assertEquals(1, cleared);
    }

    public void testEscapeClearsTheText() {
        field.setText("project:be");
        press(KeyEvent.VK_ESCAPE);
        assertEquals("", field.getText());
        assertEquals(List.of(), shownNames());
    }
}

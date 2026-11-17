package app.drydock.intellij.tasks;

import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.tasks.AddProjectBanner.Added;
import app.drydock.intellij.tasks.AddProjectBanner.Hidden;
import app.drydock.intellij.tasks.AddProjectBanner.Offer;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.InlineBanner;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link AddProjectBanner}: which state the rules pick, and what each state puts on screen. */
public class AddProjectBannerPlatformTest extends BasePlatformTestCase {

    private static final Path ROOT = Path.of("/repos/async-profiler").toAbsolutePath();
    private static final AddCurrentProject UNREGISTERED = new AddCurrentProject(ROOT, "async-profiler");
    private static final Added ADDED = new Added(ROOT, "async-profiler");

    private final List<String> calls = new ArrayList<>();
    private AddProjectBanner banner;

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        banner = new AddProjectBanner(new AddProjectBanner.Actions() {
            @Override
            public void add(AddCurrentProject project) {
                calls.add("add " + project.repoName());
            }

            @Override
            public void dismiss() {
                calls.add("dismiss");
            }

            @Override
            public void openInProjects() {
                calls.add("open");
            }

            @Override
            public void closeAdded() {
                calls.add("close");
            }
        });
    }

    public void testTheStateRules() {
        assertEquals(new Offer(UNREGISTERED), AddProjectBanner.state(Optional.of(UNREGISTERED), false, Optional.empty()));
        assertEquals("a dismissed offer stays away", new Hidden(),
                AddProjectBanner.state(Optional.of(UNREGISTERED), true, Optional.empty()));
        assertEquals("a registered project offers nothing", new Hidden(),
                AddProjectBanner.state(Optional.empty(), false, Optional.empty()));
        assertEquals("a just-added project is confirmed, whatever else", ADDED,
                AddProjectBanner.state(Optional.empty(), true, Optional.of(ADDED)));
    }

    public void testEachStateShowsItsBanner() {
        assertFalse(banner.isVisible());
        banner.show(new Offer(UNREGISTERED));
        assertTrue(banner.isVisible());
        InlineBanner offer = (InlineBanner) banner.getComponent(0);
        assertEquals(EditorNotificationPanel.Status.Info, offer.getStatus());

        banner.show(new Offer(UNREGISTERED));
        assertSame("an equal state rebuilds nothing", offer, banner.getComponent(0));

        banner.show(ADDED);
        InlineBanner added = (InlineBanner) banner.getComponent(0);
        assertEquals(EditorNotificationPanel.Status.Success, added.getStatus());

        banner.show(new Hidden());
        assertFalse(banner.isVisible());
        assertEquals(0, banner.getComponentCount());
    }

    public void testTheOfferNamesTheProjectAndOffersItsTwoActions() {
        banner.show(new Offer(new AddCurrentProject(ROOT, "a<b")));
        List<String> texts = new ArrayList<>();
        collectTexts(banner, texts);
        assertTrue("the name is escaped into the message: " + texts,
                texts.stream().anyMatch(text -> text.contains("<b>a&lt;b</b>")));
        assertTrue(texts.contains("Add project"));
        assertTrue(texts.contains("Not now"));
    }

    private static void collectTexts(Component component, List<String> out) {
        if (component instanceof JLabel label && label.getText() != null) {
            out.add(label.getText());
        } else if (component instanceof JTextComponent text && text.getText() != null) {
            out.add(text.getText());
        } else if (component instanceof AbstractButton button && button.getText() != null) {
            out.add(button.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectTexts(child, out);
            }
        }
    }
}

package app.drydock.intellij.review;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AC1 registration half and AC2's static half, on the plugin descriptor
 * as the test classpath serves it: exactly one {@code changesViewContent}
 * tab named "Drydock Review", registered for the Commit tool window with
 * the provider and the visibility predicate, and no {@code <module>}
 * element naming either diff module — the diff modules stay compile-only
 * (a plugin declaring an internal-visibility module would be refused at
 * load; {@code kb/find-263-diff-api-internal}). The spike-absence half of
 * AC1 is asserted here too: T16 deleted the spike package, so the
 * descriptor registers no spike tab, extension or action.
 */
class ReviewTabPluginXmlTest {

    private static final String TAB_NAME = "Drydock Review";

    @Test
    void registersExactlyOneDrydockReviewTabForTheCommitToolWindow() throws Exception {
        List<Element> drydockReview = changesViewContentTabsNamed(descriptor(), TAB_NAME);

        assertEquals(1, drydockReview.size(),
                "exactly one changesViewContent tab named \"" + TAB_NAME + "\" must be registered");
        Element tab = drydockReview.get(0);
        assertEquals("app.drydock.intellij.review.ReviewTabProvider", attribute(tab, "className"));
        assertEquals("app.drydock.intellij.review.ReviewTabVisibility", attribute(tab, "predicateClassName"));
        assertEquals("true", attribute(tab, "isInCommitToolWindow"),
                "the tab must register for the Commit tool window");
    }

    @Test
    void declaresNoDiffModuleDependency() throws Exception {
        List<String> moduleNames = new ArrayList<>();
        NodeList modules = descriptor().getElementsByTagName("module");
        for (int i = 0; i < modules.getLength(); i++) {
            moduleNames.add(attribute((Element) modules.item(i), "name"));
        }

        // AC2's static half: the diff product modules are internal-visibility
        // on 2026.3 and are bundledModule(...) compile-only dependencies in
        // build.gradle.kts; declaring them here would refuse the plugin at load.
        assertFalse(moduleNames.contains("intellij.platform.diff"),
                "intellij.platform.diff must stay compile-only, not a declared module: " + moduleNames);
        assertFalse(moduleNames.contains("intellij.platform.diff.impl"),
                "intellij.platform.diff.impl must stay compile-only, not a declared module: " + moduleNames);
    }

    /**
     * T16's half of AC1: the spike package and its three plugin.xml entries
     * (the "Drydock Review (Spike)" tab, the diff.DiffExtension, the
     * Drydock.SpikeReviewDiff action) are gone, leaving no trace.
     */
    @Test
    void descriptorRegistersNoSpikeEntries() throws Exception {
        String xml;
        try (InputStream in = ReviewTabPluginXmlTest.class.getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull(in, "plugin.xml must be on the test classpath");
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(xml.toLowerCase(Locale.ROOT).contains("spike"),
                "the descriptor must contain no spike entry: the spike package and its registrations were deleted");
    }

    /** The plugin descriptor as the test classpath serves it (main's resources output). */
    private static Document descriptor() throws Exception {
        InputStream descriptor = ReviewTabPluginXmlTest.class.getResourceAsStream("/META-INF/plugin.xml");
        assertNotNull(descriptor, "plugin.xml must be on the test classpath");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The descriptor is plugin-controlled, but the parser is still hardened.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream in = descriptor) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static List<Element> changesViewContentTabsNamed(Document descriptor, String tabName) {
        List<Element> matches = new ArrayList<>();
        NodeList tabs = descriptor.getElementsByTagName("changesViewContent");
        for (int i = 0; i < tabs.getLength(); i++) {
            Element tab = (Element) tabs.item(i);
            if (tabName.equals(attribute(tab, "tabName"))) {
                matches.add(tab);
            }
        }
        return matches;
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        assertTrue(value != null && !value.isBlank(), "the " + name + " attribute must be present and set");
        return value;
    }
}

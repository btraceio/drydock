package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DrydockToolWindowFactoryTest {

    @Test
    void toolWindowIdMatchesItsPluginDescriptorRegistration() throws Exception {
        List<Element> registrations = registrationsForFactory(descriptor());

        assertEquals(1, registrations.size(),
                "the Drydock factory must have exactly one tool-window registration");
        assertEquals(DrydockToolWindowFactory.TOOL_WINDOW_ID, registrations.get(0).getAttribute("id"),
                "the Java lookup id must match the plugin descriptor");
    }

    private static List<Element> registrationsForFactory(Document descriptor) {
        List<Element> matches = new ArrayList<>();
        NodeList windows = descriptor.getElementsByTagName("toolWindow");
        for (int i = 0; i < windows.getLength(); i++) {
            Element window = (Element) windows.item(i);
            if (DrydockToolWindowFactory.class.getName().equals(window.getAttribute("factoryClass"))) {
                matches.add(window);
            }
        }
        return matches;
    }

    private static Document descriptor() throws Exception {
        InputStream descriptor = DrydockToolWindowFactoryTest.class.getResourceAsStream("/META-INF/plugin.xml");
        assertNotNull(descriptor, "plugin.xml must be on the test classpath");
        try (InputStream in = descriptor) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(in);
        }
    }
}

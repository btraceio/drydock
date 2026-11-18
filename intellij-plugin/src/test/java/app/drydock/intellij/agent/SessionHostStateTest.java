package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SessionHostState}: only the selected id is stored, and bad text is no selection, never a failure. */
class SessionHostStateTest {

    @Test
    void selectedIdRoundTripsThroughTheSerializedXml() {
        ManagedSessionId id = ManagedSessionId.newId();
        SessionHostState state = new SessionHostState();
        state.setSelectedSession(Optional.of(id));

        Element xml = XmlSerializer.serialize(state.getState());
        SessionHostState reloaded = new SessionHostState();
        reloaded.loadState(XmlSerializer.deserialize(xml, SessionHostState.Stored.class));

        assertEquals(Optional.of(id), reloaded.selectedSession());
        reloaded.setSelectedSession(Optional.empty());
        assertEquals(Optional.empty(), reloaded.selectedSession(), "forgetting clears it");
    }

    @Test
    void absentOrMalformedIdIsNoSelection() {
        SessionHostState state = new SessionHostState();
        assertEquals(Optional.empty(), state.selectedSession(), "a fresh upgrade has nothing remembered");

        for (String bad : List.of("", "   ", "not-a-uuid", "12345678-1234-1234-1234")) {
            SessionHostState.Stored stored = new SessionHostState.Stored();
            stored.selectedSessionId = bad;
            state.loadState(stored);
            assertEquals(Optional.empty(), state.selectedSession(), "'" + bad + "' is not an id");
        }
    }

    @Test
    void nothingButTheSelectedIdIsPersisted() {
        SessionHostState state = new SessionHostState();
        state.setSelectedSession(Optional.of(ManagedSessionId.newId()));

        Element xml = XmlSerializer.serialize(state.getState());
        assertEquals(1, xml.getChildren().size(), "one stored option: " + xml.getChildren());
        assertEquals("selectedSessionId", xml.getChildren().get(0).getAttributeValue("name"));
        assertTrue(SessionHostState.Stored.class.getFields().length == 1, "no held/previous fields exist");
    }
}

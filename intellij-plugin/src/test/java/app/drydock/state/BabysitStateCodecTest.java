package app.drydock.state;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BabysitStateCodecTest {

    private static final String BABYSITTER = "11111111-2222-3333-4444-555555555555";
    private static final String WATCHED = "22222222-3333-4444-5555-666666666666";
    private static final String REPO = "33333333-4444-5555-6666-777777777777";
    private static final String ESCALATION = "44444444-5555-6666-7777-888888888888";

    private static BabysitPairing pairing(BabysitState state) {
        return new BabysitPairing(ManagedSessionId.newId(), ManagedSessionId.newId(), RepositoryId.newId(), 42,
                state);
    }

    private static Escalation escalation(boolean resolved) {
        return new Escalation(UUID.randomUUID(), ManagedSessionId.newId(), ManagedSessionId.newId(), 42,
                "Rebase needs a decision on the parser rewrite", Instant.parse("2026-11-18T10:15:30Z"), resolved);
    }

    private static ApplicationState decode(String json) {
        return ApplicationStateCodec.fromJson(JsonParser.parse(json));
    }

    private static String document(String pairingsJson, String escalationsJson) {
        return """
                {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{},
                 "pairings":%s,"escalations":%s}""".formatted(pairingsJson, escalationsJson);
    }

    private static String pairingJson(String babysitter, String watched, String prNumber, String state) {
        return """
                {"babysitterId":"%s","watchedId":"%s","repositoryId":"%s","prNumber":%s,"state":"%s"}"""
                .formatted(babysitter, watched, REPO, prNumber, state);
    }

    @Test
    void pairingsAndEscalationsRoundTrip() {
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(pairing(BabysitState.WATCHING), pairing(BabysitState.ESCALATED)))
                .withEscalations(List.of(escalation(false), escalation(true)));

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertEquals(state.pairings(), decoded.pairings());
        assertEquals(state.escalations(), decoded.escalations());
    }

    @Test
    void emptyListsAreWrittenAndDecodeEmpty() {
        JsonObject json = (JsonObject) ApplicationStateCodec.toJson(ApplicationState.empty());

        assertEquals(List.of(), ((JsonArray) json.get("pairings")).elements());
        assertEquals(List.of(), ((JsonArray) json.get("escalations")).elements());
        assertEquals(ApplicationState.empty(), ApplicationStateCodec.fromJson(json));
    }

    @Test
    void stateWrittenBeforeBabysittingExistedDecodesToNone() {
        ApplicationState decoded = decode("""
                {"schemaVersion":2,"repositories":[],"sessions":[],"ui":{},"handoffBriefs":[]}""");

        assertEquals(List.of(), decoded.pairings());
        assertEquals(List.of(), decoded.escalations());
    }

    @Test
    void nonArrayMembersDecodeToNone() {
        ApplicationState decoded = decode(document("{}", "\"nope\""));

        assertEquals(List.of(), decoded.pairings());
        assertEquals(List.of(), decoded.escalations());
    }

    @Test
    void malformedPairingsAreDroppedWithoutCostingTheGoodOne() {
        String pairings = "[" + String.join(",",
                pairingJson("not-a-uuid", WATCHED, "42", "WATCHING"),
                pairingJson(BABYSITTER, WATCHED, "0", "WATCHING"),
                pairingJson(BABYSITTER, WATCHED, "\"42\"", "WATCHING"),
                pairingJson(BABYSITTER, WATCHED, "42", "DOZING"),
                pairingJson(BABYSITTER, BABYSITTER, "42", "WATCHING"),
                "\"not-an-object\"",
                pairingJson(BABYSITTER, WATCHED, "7", "ACTING")) + "]";

        List<BabysitPairing> decoded = decode(document(pairings, "[]")).pairings();

        assertEquals(List.of(new BabysitPairing(ManagedSessionId.of(BABYSITTER), ManagedSessionId.of(WATCHED),
                RepositoryId.of(REPO), 7, BabysitState.ACTING)), decoded);
    }

    @Test
    void aPairingWhoseSessionsNoLongerExistIsKept() {
        // Neither id names a session in this document; ending the engagement
        // is the monitor's call, not the decoder's.
        List<BabysitPairing> decoded = decode(document(
                "[" + pairingJson(BABYSITTER, WATCHED, "42", "WATCHING") + "]", "[]")).pairings();

        assertEquals(1, decoded.size());
        assertEquals(ManagedSessionId.of(WATCHED), decoded.get(0).watchedId());
    }

    @Test
    void malformedEscalationsAreDroppedWithoutCostingTheGoodOne() {
        String good = """
                {"id":"%s","watchedId":"%s","babysitterId":"%s","prNumber":42,
                 "message":"kept","createdAt":"2026-11-18T10:15:30Z","resolved":true}"""
                .formatted(ESCALATION, WATCHED, BABYSITTER);
        String badTime = good.replace("2026-11-18T10:15:30Z", "yesterday").replace("kept", "bad time");
        String blank = good.replace("\"kept\"", "\"  \"");
        String noId = good.replace("\"id\":\"" + ESCALATION + "\",", "");

        List<Escalation> decoded = decode(document("[]",
                "[" + String.join(",", badTime, blank, noId, "42", good) + "]")).escalations();

        assertEquals(1, decoded.size());
        assertEquals("kept", decoded.get(0).message());
        assertTrue(decoded.get(0).resolved());
    }

    @Test
    void anEscalationWithoutResolvedDecodesUnresolved() {
        String noResolved = """
                {"id":"%s","watchedId":"%s","babysitterId":"%s","prNumber":42,
                 "message":"m","createdAt":"2026-11-18T10:15:30Z"}""".formatted(ESCALATION, WATCHED, BABYSITTER);

        List<Escalation> decoded = decode(document("[]", "[" + noResolved + "]")).escalations();

        assertEquals(1, decoded.size());
        assertFalse(decoded.get(0).resolved());
    }

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void aKnownHeadRoundTripsAndAnAbsentOneIsEmpty() {
        BabysitPairing withHead = pairing(BabysitState.WATCHING).withKnownHead(HEAD);
        BabysitPairing without = pairing(BabysitState.ACTING);
        ApplicationState state = ApplicationState.empty().withPairings(List.of(withHead, without));

        ApplicationState decoded = ApplicationStateCodec.fromJson(ApplicationStateCodec.toJson(state));

        assertEquals(state.pairings(), decoded.pairings());
        assertEquals(Optional.of(HEAD), decoded.pairings().get(0).knownHead());
        assertEquals(Optional.empty(), decoded.pairings().get(1).knownHead());
        // P1-era entries carry no knownHead member at all.
        assertEquals(Optional.empty(), decode(document(
                "[" + pairingJson(BABYSITTER, WATCHED, "42", "WATCHING") + "]", "[]")).pairings().get(0).knownHead());
    }

    @Test
    void aBadKnownHeadIsDroppedAndThePairingKept() {
        String base = pairingJson(BABYSITTER, WATCHED, "42", "WATCHING");
        List<String> bad = List.of("\"knownHead\":null", "\"knownHead\":42", "\"knownHead\":\"main\"",
                "\"knownHead\":\"" + HEAD.toUpperCase(Locale.ROOT) + "\"",
                "\"knownHead\":\"--upload-pack=x\"", "\"knownHead\":\"" + HEAD + "0\"");
        for (String member : bad) {
            String entry = base.substring(0, base.lastIndexOf('}')) + "," + member + "}";

            List<BabysitPairing> decoded = decode(document("[" + entry + "]", "[]")).pairings();

            assertEquals(1, decoded.size(), member);
            assertEquals(Optional.empty(), decoded.get(0).knownHead(), member);
            assertEquals(BabysitState.WATCHING, decoded.get(0).state(), member);
        }
    }

    @Test
    void aPairingRefusesAMalformedKnownHead() {
        BabysitPairing pairing = pairing(BabysitState.WATCHING);
        assertThrows(IllegalArgumentException.class, () -> pairing.withKnownHead("HEAD"));
        assertEquals(Optional.of(HEAD), pairing.withKnownHead(HEAD).withState(BabysitState.STOPPED).knownHead());
    }
}

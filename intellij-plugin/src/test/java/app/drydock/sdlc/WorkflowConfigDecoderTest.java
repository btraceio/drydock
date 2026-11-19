package app.drydock.sdlc;

import app.drydock.sdlc.WorkflowConfigDecoder.Decoded;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WorkflowConfigDecoderTest {

    private static final WorkflowTarget TRIAGE = WorkflowTarget.ISSUE_TRIAGE;

    private static ProblemCategory invalid(String json, WorkflowTarget t) {
        return assertInstanceOf(Decoded.Invalid.class, WorkflowConfigDecoder.decode(json, t)).problem().category();
    }

    private static String triage(String leaf) {
        return "{\"version\":1,\"actions\":{\"issue\":{\"triage\":" + leaf + "}}}";
    }

    @Test
    void validLeafForEachSourceKindIncludingUnicodeAndIncludes() {
        var d = assertInstanceOf(Decoded.Leaf.class, WorkflowConfigDecoder.decode(
                triage("{\"text\":\"h\u00e9llo \\ud83d\\ude00 \u65e5\u672c\",\"include\":[\"a/b\",\"c\"]}"), TRIAGE)).definition();
        assertEquals(WorkflowConfigDecoder.SourceType.TEXT, d.type());
        assertEquals("h\u00e9llo \uD83D\uDE00 \u65e5\u672c", d.value());
        assertEquals(List.of("a/b", "c"), d.include());
        assertEquals("actions.issue.triage", d.configKey());
        var f = assertInstanceOf(Decoded.Leaf.class, WorkflowConfigDecoder.decode(
                triage("{\"file\":\".sdlc/t.md\",\"include\":[]}"), TRIAGE)).definition();
        assertEquals(WorkflowConfigDecoder.SourceType.FILE, f.type());
        var s = assertInstanceOf(Decoded.Leaf.class, WorkflowConfigDecoder.decode(
                triage("{\"skill\":\"skills/x/SKILL.md\"}"), TRIAGE)).definition();
        assertEquals(WorkflowConfigDecoder.SourceType.SKILL, s.type());
    }

    @Test
    void omittedContainerOrLeafIsMissingAndTargetsAreIndependent() {
        String cfg = "{\"version\":1,\"actions\":{\"pullRequest\":{\"review\":{\"text\":\"pr\"}}}}";
        assertInstanceOf(Decoded.Leaf.class, WorkflowConfigDecoder.decode(cfg, WorkflowTarget.PULL_REQUEST_REVIEW));
        assertInstanceOf(Decoded.Missing.class, WorkflowConfigDecoder.decode(cfg, WorkflowTarget.LOCAL_CHANGES_REVIEW));
        assertInstanceOf(Decoded.Missing.class, WorkflowConfigDecoder.decode(cfg, WorkflowTarget.FREE_FORM_PRE_PROMPT));
        assertInstanceOf(Decoded.Missing.class, WorkflowConfigDecoder.decode("{\"version\":1}", TRIAGE));
        assertInstanceOf(Decoded.Missing.class, WorkflowConfigDecoder.decode("{\"version\":1,\"actions\":{}}", TRIAGE));
    }

    @Test
    void versionMatrix() {
        assertEquals(ProblemCategory.VERSION, invalid("{}", TRIAGE));
        assertEquals(ProblemCategory.VERSION, invalid("{\"version\":2}", TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid("{\"version\":\"1\"}", TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid("{\"version\":1.0}", TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid("{\"version\":null}", TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid("[1]", TRIAGE));
    }

    @Test
    void unknownKeysAndGenericActionsAreRejected() {
        assertEquals(ProblemCategory.UNKNOWN_FIELD, invalid("{\"version\":1,\"extra\":1}", TRIAGE));
        assertEquals(ProblemCategory.UNKNOWN_FIELD,
                invalid("{\"version\":1,\"actions\":{\"triage\":{\"text\":\"x\"}}}", TRIAGE));
        assertEquals(ProblemCategory.UNKNOWN_FIELD,
                invalid("{\"version\":1,\"actions\":{\"implement\":{\"text\":\"x\"}}}", WorkflowTarget.ISSUE_IMPLEMENT));
        assertEquals(ProblemCategory.UNKNOWN_FIELD,
                invalid("{\"version\":1,\"actions\":{\"issue\":{\"review\":{\"text\":\"x\"}}}}", TRIAGE));
        assertEquals(ProblemCategory.UNKNOWN_FIELD, invalid(triage("{\"text\":\"x\",\"mode\":1}"), TRIAGE));
    }

    @Test
    void leafSchemaMatrix() {
        assertEquals(ProblemCategory.SCHEMA, invalid(triage("{}"), TRIAGE));
        assertEquals(ProblemCategory.SCHEMA, invalid(triage("{\"text\":\"a\",\"file\":\"b\"}"), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("null"), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("\"x\""), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("{\"text\":1}"), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("{\"text\":null}"), TRIAGE));
        assertEquals(ProblemCategory.BLANK, invalid(triage("{\"text\":\" \\n\"}"), TRIAGE));
        assertEquals(ProblemCategory.BLANK, invalid(triage("{\"file\":\"\"}"), TRIAGE));
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, invalid(triage("{\"skill\":\"skills/x\"}"), TRIAGE));
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, invalid(triage("{\"skill\":\"skills/xSKILL.md\"}"), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("{\"text\":\"x\",\"include\":\"a\"}"), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("{\"text\":\"x\",\"include\":[1]}"), TRIAGE));
        assertEquals(ProblemCategory.BLANK, invalid(triage("{\"text\":\"x\",\"include\":[\"a\",\" \"]}"), TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid(triage("{\"text\":\"x\",\"include\":null}"), TRIAGE));
    }

    @Test
    void nullOrWrongTypeContainersAreInvalidNotMissing() {
        assertEquals(ProblemCategory.TYPE, invalid("{\"version\":1,\"actions\":null}", TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid("{\"version\":1,\"actions\":{\"issue\":[]}}", TRIAGE));
        assertEquals(ProblemCategory.TYPE, invalid("{\"version\":1,\"newTask\":null}", TRIAGE));
    }

    @Test
    void duplicateMembersAndMalformedRootInvalidateEveryTarget() {
        for (WorkflowTarget t : WorkflowTarget.values()) {
            assertEquals(ProblemCategory.DUPLICATE, invalid("{\"version\":1,\"version\":1}", t));
            assertEquals(ProblemCategory.SCHEMA, invalid("{\"version\":1,", t));
            assertEquals(ProblemCategory.SCHEMA, invalid("", t));
            assertEquals(ProblemCategory.SCHEMA, invalid("{\"version\":1} x", t));
        }
        assertEquals(ProblemCategory.DUPLICATE,
                invalid(triage("{\"text\":\"a\",\"text\":\"b\"}"), TRIAGE));
        assertEquals(ProblemCategory.SCHEMA, invalid("{\"version\":1,\"newTask\":{\"prePrompt\":{\"text\":\"\ud800\"}}}", TRIAGE));
    }

    @Test
    void unrelatedInvalidLeafIsIgnoredButSelectedOneIsNot() {
        String cfg = "{\"version\":1,\"actions\":{\"issue\":{\"triage\":{\"text\":\"ok\"},"
                + "\"implement\":{\"text\":\"a\",\"file\":\"b\"}},\"localChanges\":{\"review\":null}}}";
        assertInstanceOf(Decoded.Leaf.class, WorkflowConfigDecoder.decode(cfg, TRIAGE));
        assertEquals(ProblemCategory.SCHEMA, invalid(cfg, WorkflowTarget.ISSUE_IMPLEMENT));
        assertEquals(ProblemCategory.TYPE, invalid(cfg, WorkflowTarget.LOCAL_CHANGES_REVIEW));
    }
}

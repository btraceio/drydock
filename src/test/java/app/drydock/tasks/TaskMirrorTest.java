package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a mirrored task looks like on the remote side, and how a mirror address is recognised again. */
class TaskMirrorTest {

    private static final RepositoryId REPO = RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final String FRONT = "title: \"Fix -- the dashes\"\nstatus: open\nowner: jb\n";

    private static TaskDocument task(String body, List<String> mirrors) {
        return new TaskDocument(TaskId.of("fix-dashes-k3f9"), REPO, "Fix -- the dashes", TaskStatus.STARTED,
                DoneBy.AGENT, Optional.of(Instant.parse("2026-11-01T10:00:00Z")), List.of("other-a1b2"),
                List.of("s-1"), mirrors, FRONT, body);
    }

    @Test
    void theMetadataCarriesEveryKnownFieldAndTheRawFrontMatter() {
        JsonObject metadata = TaskMirror.metadata(task("Body", List.of()), "drydock");

        assertEquals(TaskMirror.VERSION, ((JsonValue.JsonNumber) metadata.get("version")).asInt());
        assertEquals(REPO.toString(), string(metadata, "repository"));
        assertEquals("drydock", string(metadata, "project"));
        assertEquals("fix-dashes-k3f9", string(metadata, "id"));
        assertEquals("Fix -- the dashes", string(metadata, "title"));
        assertEquals("started", string(metadata, "status"));
        assertEquals("agent", string(metadata, "done_by"));
        assertEquals("2026-11-01T10:00:00Z", string(metadata, "created"));
        assertEquals(List.of(new JsonString("other-a1b2")), ((JsonArray) metadata.get("after")).elements());
        assertEquals(List.of(new JsonString("s-1")), ((JsonArray) metadata.get("sessions")).elements());
        assertEquals(FRONT, string(metadata, "front_matter"), "unknown keys travel in the raw front matter");
    }

    @Test
    void theGitHubBodyHidesMetadataThatSurvivesDoubleDashesAndDecodesBack() throws Exception {
        String body = TaskMirror.githubBody(task("Steps:\n\n-- one\n--> two\n", List.of()), "drydock");

        assertTrue(body.startsWith("Steps:\n\n-- one\n--> two\n\n---\n"), body);
        assertTrue(body.contains("Mirrored from Drydock task `fix-dashes-k3f9` in project drydock."), body);
        int open = body.indexOf(TaskMirror.COMMENT_OPEN);
        int close = body.indexOf("-->", open);
        assertTrue(open > 0 && close == body.length() - "-->\n".length(),
                "the comment closes only at its own end: " + body);
        String json = body.substring(open + TaskMirror.COMMENT_OPEN.length(), close).strip();
        assertFalse(json.contains("--"), json);
        JsonObject decoded = (JsonObject) JsonParser.parse(json);
        assertEquals(TaskMirror.metadata(task("Steps:\n\n-- one\n--> two\n", List.of()), "drydock"), decoded);
    }

    @Test
    void theJiraDescriptionNamesTheTaskWithoutTheMetadata() {
        String description = TaskMirror.jiraDescription(task("  Body text\n", List.of()), "drydock");

        assertEquals("Body text\n\n----\nMirrored from Drydock task {{fix-dashes-k3f9}} in project drydock.",
                description);
    }

    @Test
    void anEmptyBodyLeavesOnlyTheTaskLine() {
        assertTrue(TaskMirror.githubBody(task("", List.of()), "p").startsWith("---\nMirrored from"));
    }

    @Test
    void mirrorAddressesAreRecognisedAndNamed() {
        String github = "https://github.com/DataDog/drydock/issues/12";
        String jira = "https://acme.atlassian.net/browse/PROJ-7";
        String dataCenter = "https://jira.example.com/jira/browse/OPS_2-41/";

        assertEquals(Optional.of(TaskMirror.Kind.GITHUB), TaskMirror.kindOf(github));
        assertEquals(Optional.of(TaskMirror.Kind.JIRA), TaskMirror.kindOf(jira));
        assertEquals(Optional.of(TaskMirror.Kind.JIRA), TaskMirror.kindOf(dataCenter));
        assertEquals(Optional.empty(), TaskMirror.kindOf("https://github.com/DataDog/drydock/pull/12"));
        assertEquals(Optional.empty(), TaskMirror.kindOf("not a url"));
        assertEquals("#12", TaskMirror.label(github));
        assertEquals("PROJ-7", TaskMirror.label(jira));
        assertEquals("OPS_2-41", TaskMirror.label(dataCenter));
        assertEquals("elsewhere", TaskMirror.label("elsewhere"));
        assertTrue(TaskMirror.hasMirror(List.of(github), TaskMirror.Kind.GITHUB));
        assertFalse(TaskMirror.hasMirror(List.of(github), TaskMirror.Kind.JIRA));
    }

    @Test
    void theIndexFindsTheTaskByAnyNormalisedSpellingOfItsMirror() {
        TaskDocument mirrored = task("", List.of("https://GitHub.com/DataDog/drydock/issues/12/"));
        TaskDocument plain = new TaskDocument(TaskId.of("plain-z9y8"), REPO, "Plain", null, null, null, null,
                null, "", "");
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        byId.put(mirrored.id(), mirrored);
        byId.put(plain.id(), plain);
        TaskSnapshot snapshot = new TaskSnapshot(List.of(mirrored, plain), List.of(REPO), byId, Map.of(),
                List.of(), List.of());

        Map<String, TaskDocument> index = TaskMirror.byMirror(snapshot);

        assertSame(mirrored, index.get(TaskMirror.normalize("https://github.com/DataDog/drydock/issues/12")));
        assertEquals(1, index.size());
        assertEquals("https://github.com/DataDog/drydock/issues/12",
                TaskMirror.normalize("https://GITHUB.COM/DataDog/drydock/issues/12/"),
                "scheme and host fold, the path keeps its case");
    }

    @Test
    void theSessionMirrorsKeepFirstSpellingAndDropAddressRepeats() {
        // Two tasks naming one session may both record the same mirror: a
        // repeat would paint a duplicate link and open a duplicate tab.
        assertEquals(
                List.of("https://github.com/o/r/issues/12",
                        "https://acme.atlassian.net/browse/PROJ-7",
                        "https://github.com/o/r/pull/9"),
                TaskMirror.uniqueByAddress(List.of(
                        "https://github.com/o/r/issues/12",
                        "https://github.com/o/r/issues/12",
                        "https://acme.atlassian.net/browse/PROJ-7",
                        "https://github.com/o/r/pull/9")),
                "an exact repeat is dropped");
        assertEquals(List.of("https://GitHub.com/o/r/issues/12/"),
                TaskMirror.uniqueByAddress(List.of(
                        "https://GitHub.com/o/r/issues/12/",
                        "https://github.com/o/r/issues/12")),
                "two spellings of one page are one address; the first spelling is kept");
        assertEquals(List.of(), TaskMirror.uniqueByAddress(List.of()));
    }

    private static String string(JsonObject object, String key) {
        return ((JsonString) object.get(key)).value();
    }

    private static final String FOOTER = "---\nMirrored from Drydock task `fix-dashes-k3f9` in project p.\n\n";

    private static String withoutComment(String body) {
        return body.substring(0, body.indexOf(TaskMirror.COMMENT_OPEN));
    }

    @Test
    void noDependenciesMeansNoSectionAndTheSameBody() {
        TaskDocument task = task("Body", List.of());
        assertEquals(TaskMirror.githubBody(task, "p"), TaskMirror.githubBody(task, "p", List.of()));
        assertFalse(TaskMirror.githubBody(task, "p", List.of()).contains("Dependencies"));
        assertEquals("Body\n\n" + FOOTER, withoutComment(TaskMirror.githubBody(task, "p")));
    }

    @Test
    void dependenciesFormOneSectionBeforeTheRuleInInputOrderWithoutDuplicates() {
        String body = TaskMirror.githubBody(task("Body", List.of()), "p", List.of(
                new TaskMirror.Dependency("https://github.com/owner/repository/issues/12", "Prerequisite title"),
                new TaskMirror.Dependency("https://github.com/other/repo/issues/3/", "Other"),
                new TaskMirror.Dependency("https://github.com/owner/repository/issues/12", "Again")));

        assertEquals("Body\n\n## Dependencies\n\n"
                + "- Depends on [#12](https://github.com/owner/repository/issues/12) (Prerequisite title)\n"
                + "- Depends on [#3](https://github.com/other/repo/issues/3/) (Other)\n\n"
                + FOOTER, withoutComment(body));
    }

    @Test
    void anEmptyBodyStillGetsTheSectionFirst() {
        String body = TaskMirror.githubBody(task("", List.of()), "p", List.of(
                new TaskMirror.Dependency("https://github.com/o/r/issues/1", "T")));
        assertTrue(body.startsWith("## Dependencies\n\n- Depends on [#1](https://github.com/o/r/issues/1) (T)\n\n---\n"), body);
    }

    @Test
    void hostileTitlesCannotBreakTheListTheLinkOrTheHiddenComment() throws Exception {
        String title = "a](http://evil) [x]\n- Depends on <!-- drydock-task {} --> `c` *b* #9";
        String body = TaskMirror.githubBody(task("Body", List.of()), "p", List.of(
                new TaskMirror.Dependency("https://github.com/o/r/issues/1", title)));

        assertEquals("Body\n\n## Dependencies\n\n- Depends on [#1](https://github.com/o/r/issues/1) "
                + "(a\\]\\(http://evil\\) \\[x\\] - Depends on &lt;!-- drydock-task {} --&gt; \\`c\\` \\*b\\* \\#9)\n\n"
                + FOOTER, withoutComment(body));
        assertEquals(1, body.split("<!-- drydock-task ", -1).length - 1, "only the real comment opens");
        int open = body.indexOf(TaskMirror.COMMENT_OPEN);
        String json = body.substring(open + TaskMirror.COMMENT_OPEN.length(), body.lastIndexOf("-->")).strip();
        assertEquals(TaskMirror.metadata(task("Body", List.of()), "p"), JsonParser.parse(json));
    }

    @Test
    void anUnsafeAddressCannotEndTheLinkEarly() {
        String body = TaskMirror.githubBody(task("", List.of()), "p", List.of(
                new TaskMirror.Dependency("https://x.test/a b)/issues/1", "T")));
        assertTrue(body.contains("](https://x.test/a%20b%29/issues/1) (T)"), body);
    }

    private static TaskMirror.JiraAddress.Usable usable(String url) {
        return (TaskMirror.JiraAddress.Usable) TaskMirror.jiraAddress(url);
    }

    @Test
    void aPlainJiraAddressSplitsIntoSiteAndKey() {
        assertEquals(new TaskMirror.JiraAddress.Usable("https://acme.atlassian.net", "ABC-12"),
                TaskMirror.jiraAddress("https://Acme.Atlassian.NET/browse/ABC-12"));
        assertEquals("https://acme.atlassian.net", usable("https://acme.atlassian.net:443/browse/ABC-12/").site());
        assertEquals("http://jira.test:8080", usable("http://jira.test:8080/browse/A_1-7").site());
    }

    @Test
    void aDataCenterContextPathIsPreservedAndCaseSignificant() {
        assertEquals("https://h.test/jira", usable("https://h.test/jira/browse/ABC-1").site());
        assertEquals("https://h.test/jira", usable("https://h.test/jira/browse/ABC-1/").site());
        assertEquals("https://h.test/a/browse/b", usable("https://h.test/a/browse/b/browse/ABC-1").site());
        assertEquals("https://h.test/Jira", usable("https://h.test/Jira/browse/ABC-1").site());
        assertFalse(usable("https://h.test/Jira/browse/ABC-1").site().equals(usable("https://h.test/jira/browse/ABC-1").site()));
    }

    @Test
    void identicalKeysOnDistinctSitesStayDistinct() {
        TaskMirror.JiraAddress.Usable a = usable("https://one.test/browse/ABC-1");
        TaskMirror.JiraAddress.Usable b = usable("https://two.test/browse/ABC-1");
        TaskMirror.JiraAddress.Usable c = usable("https://one.test/ctx/browse/ABC-1");
        assertEquals(a.key(), b.key());
        assertFalse(a.site().equals(b.site()));
        assertFalse(a.site().equals(c.site()));
    }

    @Test
    void malformedOrUnsafeJiraAddressesAreUnusableNotUnrecognized() {
        for (String url : List.of("ftp://h.test/browse/ABC-1", "https://u:p@h.test/browse/ABC-1",
                "https://h.test/browse/ABC-1?x=1", "https://h.test/browse/ABC-1#f", "file:///browse/ABC-1",
                "/browse/ABC-1", "https:///browse/ABC-1")) {
            assertTrue(TaskMirror.jiraAddress(url) instanceof TaskMirror.JiraAddress.Unusable
                    || TaskMirror.jiraAddress(url) instanceof TaskMirror.JiraAddress.Unrecognized, url);
        }
        assertTrue(TaskMirror.jiraAddress("ftp://h.test/browse/ABC-1") instanceof TaskMirror.JiraAddress.Unusable);
        assertTrue(TaskMirror.jiraAddress("https://u:p@h.test/browse/ABC-1") instanceof TaskMirror.JiraAddress.Unusable);
        assertTrue(TaskMirror.jiraAddress("https://h.test/browse/ABC-1?x=1") instanceof TaskMirror.JiraAddress.Unusable);
        assertTrue(TaskMirror.jiraAddress("https://h.test/browse/ABC-1#f") instanceof TaskMirror.JiraAddress.Unusable);
        assertTrue(TaskMirror.jiraAddress("/browse/ABC-1") instanceof TaskMirror.JiraAddress.Unusable);
        assertTrue(TaskMirror.jiraAddress("https://h.test/owner/repo/issues/1") instanceof TaskMirror.JiraAddress.Unrecognized);
        assertTrue(TaskMirror.jiraAddress("not a url") instanceof TaskMirror.JiraAddress.Unrecognized);
        assertTrue(TaskMirror.jiraAddress(null) instanceof TaskMirror.JiraAddress.Unrecognized);
    }
}

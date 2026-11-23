package app.drydock.sdlc;

import app.drydock.sdlc.WorkflowPathSelector.Root;
import app.drydock.sdlc.WorkflowPathSelector.SelectionException;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowSelectionPlan.DirectoryEntry;
import app.drydock.sdlc.WorkflowSelectionPlan.Entry;
import app.drydock.sdlc.WorkflowSelectionPlan.FileEntry;
import app.drydock.sdlc.WorkflowSelectionPlan.LinkEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static containment fixtures only; nothing here claims immunity to a hostile concurrent writer. */
class WorkflowPathSelectorTest {

    @TempDir
    Path tmp;
    Path repo;

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectory(tmp.resolve("repo"));
    }

    private Path file(String rel, String content) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content);
    }

    private void link(String rel, String text) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.createSymbolicLink(p, Path.of(text));
    }

    private WorkflowSelectionPlan dir(String path) throws SelectionException {
        return WorkflowPathSelector.select(repo, List.of(new Root(path, true)));
    }

    private ProblemCategory rejected(Root... roots) {
        return assertThrows(SelectionException.class, () -> WorkflowPathSelector.select(repo, List.of(roots))).category();
    }

    private static Set<String> paths(WorkflowSelectionPlan plan) {
        return plan.entries().stream().map(Entry::path).collect(Collectors.toSet());
    }

    @Test
    void skillDirectoryIsSelectedCompletelyWithExecutableBitAndWithoutReadingBytes() throws Exception {
        file("skills/s/SKILL.md", "x");
        file("skills/s/sub/data.bin", "y");
        Path run = file("skills/s/run.sh", "#!/bin/sh");
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path unreadable = file("skills/s/secret.txt", "z");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        WorkflowSelectionPlan plan = dir("skills/s");
        assertEquals(Set.of("skills", "skills/s", "skills/s/SKILL.md", "skills/s/sub", "skills/s/sub/data.bin",
                "skills/s/run.sh", "skills/s/secret.txt"), paths(plan));
        assertTrue(plan.entries().contains(new FileEntry("skills/s/run.sh", true)));
        assertTrue(plan.entries().contains(new FileEntry("skills/s/SKILL.md", false)));
        assertEquals(4, plan.count(FileEntry.class));
        assertEquals(3, plan.count(DirectoryEntry.class));
    }

    @Test
    void lexicalEscapesAbsoluteUrlExpansionAndGitAreRejectedBeforeTouchingDisk() throws Exception {
        file("a/f", "x");
        for (String bad : List.of("../x", "a/../../x", "/etc/passwd", "http://x/y", "C:/x", "~/x", "$HOME/x",
                "a/${X}", "a\\b", "a/`id`", "%HOME%/x")) {
            assertEquals(ProblemCategory.ESCAPES_ROOT, rejected(new Root(bad, false)), bad);
            if (bad.startsWith("/")) {
                assertTrue(!assertThrows(SelectionException.class, () -> WorkflowPathSelector.select(repo,
                        List.of(new Root(bad, false)))).getMessage().contains(bad));
            }
        }
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, rejected(new Root("a/\u0001", false)));
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, rejected(new Root("a/..", true)));
        assertEquals(ProblemCategory.SPECIAL_FILE, rejected(new Root(".git/config", false)));
        assertEquals(ProblemCategory.SPECIAL_FILE, rejected(new Root("a/.git", true)));
        assertEquals(List.of("a", "a/f"), List.copyOf(paths(WorkflowPathSelector.select(repo,
                List.of(new Root("a/./x/../f", false))))).stream().sorted().toList());
    }

    @Test
    void containedLinksAreKeptAsRelativeLinkEntriesAndAbsoluteContainedTextIsRewritten() throws Exception {
        file("s/a", "a");
        link("s/l", "a");
        link("s/abs", repo.resolve("s/a").toRealPath().toString());
        link("s/chain", "l");
        link("s/self", "../s");
        WorkflowSelectionPlan plan = dir("s");
        assertTrue(plan.entries().contains(new LinkEntry("s/l", "a")));
        assertTrue(plan.entries().contains(new LinkEntry("s/abs", "a")));
        assertTrue(plan.entries().contains(new LinkEntry("s/chain", "a")));
        assertTrue(plan.entries().contains(new LinkEntry("s/self", ".")));
        WorkflowSelectionPlan single = WorkflowPathSelector.select(repo, List.of(new Root("s/l", false)));
        assertEquals("s/a", single.logicalToCanonical().get("s/l"));
        assertTrue(single.entries().contains(new LinkEntry("s/l", "a")));
        assertTrue(single.entries().contains(new FileEntry("s/a", false)));
    }

    @Test
    void linkEscapesOutsideTextAndPrefixTricksAreRejected() throws Exception {
        file("s/a", "a");
        Files.writeString(tmp.resolve("outside"), "x");
        Files.createDirectories(tmp.resolve("repo2"));
        Files.writeString(tmp.resolve("repo2/f"), "x");
        String[][] cases = {{"../../outside", "ESCAPES_ROOT"}, {tmp.resolve("outside").toRealPath().toString(), "ESCAPES_ROOT"},
                {tmp.resolve("repo2/f").toRealPath().toString(), "ESCAPES_ROOT"}, {"../.git", "SPECIAL_FILE"}};
        for (String[] c : cases) {
            link("s/bad", c[0]);
            assertEquals(ProblemCategory.valueOf(c[1]), rejected(new Root("s", true)), c[0]);
            if (c[0].startsWith("/")) {
                String msg = assertThrows(SelectionException.class, () -> dir("s")).getMessage();
                assertTrue(!msg.contains(c[0]) && !msg.contains(tmp.toString()), msg);
            }
            Files.delete(repo.resolve("s/bad"));
        }
    }

    @Test
    void brokenCyclicRootAndTooLongLinkChainsAreRejected() throws Exception {
        file("s/a", "a");
        link("s/broken", "missing");
        assertEquals(ProblemCategory.SOURCE_UNREADABLE, rejected(new Root("s", true)));
        Files.delete(repo.resolve("s/broken"));
        link("s/x", "y");
        link("s/y", "x");
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, rejected(new Root("s", true)));
        Files.delete(repo.resolve("s/x"));
        Files.delete(repo.resolve("s/y"));
        link("s/rootlink", "..");
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, rejected(new Root("s", true)));
        Files.delete(repo.resolve("s/rootlink"));
        // a chain of exactly TRAVERSAL_DEPTH links resolves; one more is rejected
        int n = WorkflowLimits.TRAVERSAL_DEPTH;
        for (int i = 0; i < n; i++) {
            link("c/l" + i, i == n - 1 ? "../s/a" : "l" + (i + 1));
        }
        assertDoesNotThrow(() -> WorkflowPathSelector.select(repo, List.of(new Root("c/l0", false), new Root("s/a", false))));
        Files.delete(repo.resolve("c/l" + (n - 1)));
        link("c/l" + (n - 1), "l" + n);
        link("c/l" + n, "../s/a");
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, rejected(new Root("c/l0", false), new Root("s/a", false)));
    }

    @Test
    void linkDestinationMustBeInTheSelectedSet() throws Exception {
        file("s/a", "a");
        file("other/b", "b");
        link("s/l", "../other/b");
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, rejected(new Root("s", true)));
        assertDoesNotThrow(() -> WorkflowPathSelector.select(repo, List.of(new Root("s", true), new Root("other/b", false))));
    }

    @Test
    void linkedParentIsKeptAsLinkAndMapsLogicalToCanonical() throws Exception {
        file("real/sub/f", "f");
        link("top", "real/sub");
        WorkflowSelectionPlan plan = WorkflowPathSelector.select(repo, List.of(new Root("top/f", false)));
        assertEquals("real/sub/f", plan.logicalToCanonical().get("top/f"));
        assertEquals(Set.of("real", "real/sub", "real/sub/f", "top"), paths(plan));
        assertTrue(plan.entries().contains(new LinkEntry("top", "real/sub")));
        assertTrue(plan.entries().contains(new FileEntry("real/sub/f", false)));
    }

    @Test
    void entryCountExactAndPlusOne() throws Exception {
        for (int i = 0; i < WorkflowLimits.BUNDLE_ENTRIES - 1; i++) {
            file("d/f" + i, "");
        }
        assertEquals(WorkflowLimits.BUNDLE_ENTRIES, dir("d").entries().size());
        file("d/extra", "");
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, rejected(new Root("d", true)));
    }

    @Test
    void pathLengthExactAndPlusOne() throws Exception {
        // PATH_MAX (1,024 on macOS) forbids a real 1,024-byte entry under a temp root, so the boundary
        // is pinned on the production check applied to every entry (putOne).
        WorkflowPathSelector.checkPathLength("x".repeat(WorkflowLimits.PATH_UTF8_BYTES));
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, assertThrows(SelectionException.class,
                () -> WorkflowPathSelector.checkPathLength("x".repeat(WorkflowLimits.PATH_UTF8_BYTES + 1))).category());
        // multi-byte: 512 two-byte characters are exactly 1,024 bytes; one more byte fails
        WorkflowPathSelector.checkPathLength("\u00e9".repeat(512));
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, assertThrows(SelectionException.class,
                () -> WorkflowPathSelector.checkPathLength("\u00e9".repeat(512) + "a")).category());
    }

    @Test
    void traversalDepthExactAndPlusOne() throws Exception {
        file("ok" + "/d".repeat(WorkflowLimits.TRAVERSAL_DEPTH) + "/f", "");
        assertDoesNotThrow(() -> dir("ok"));
        file("deep" + "/d".repeat(WorkflowLimits.TRAVERSAL_DEPTH + 1) + "/f", "");
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, rejected(new Root("deep", true)));
    }

    @Test
    void specialFilesAndWrongTypesAreRejected() throws Exception {
        file("s/a", "a");
        Process mk = new ProcessBuilder("mkfifo", repo.resolve("s/pipe").toString()).start();
        assertEquals(0, mk.waitFor(), "mkfifo must be available on the test host");
        assertEquals(ProblemCategory.SPECIAL_FILE, rejected(new Root("s", true)));
        assertEquals(ProblemCategory.SPECIAL_FILE, rejected(new Root("s/pipe", false)));
        Files.delete(repo.resolve("s/pipe"));
        assertEquals(ProblemCategory.SOURCE_UNREADABLE, rejected(new Root("s", false)));
        assertEquals(ProblemCategory.SOURCE_UNREADABLE, rejected(new Root("s/a", true)));
    }
}

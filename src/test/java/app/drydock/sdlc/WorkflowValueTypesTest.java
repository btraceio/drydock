package app.drydock.sdlc;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowValueTypesTest {

    private static WorkflowBundle.File file(String path, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return new WorkflowBundle.File(path, b, WorkflowBundle.sha256Hex(b), false);
    }

    private static SelectedWorkflow selected(RepositoryId id, WorkflowBundle bundle) {
        return new SelectedWorkflow(id, WorkflowTarget.ISSUE_TRIAGE,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.FILE, "issueTriage",
                        Optional.of(".sdlc/triage.md")),
                "prompt", bundle, "digest1");
    }

    @Test
    void targetHasFiveLeaves() {
        assertEquals(5, WorkflowTarget.values().length);
    }

    @Test
    void invalidIsADistinctVariantFromMissingAndNeverEmpty() {
        var problem = new WorkflowResolution.WorkflowProblem("issueTriage", Optional.of(".sdlc/x.md"),
                WorkflowResolution.ProblemCategory.SOURCE_UNREADABLE, "unreadable");
        WorkflowResolution invalid = new WorkflowResolution.Invalid(problem);
        WorkflowResolution missing = new WorkflowResolution.Missing("no config");
        assertNotEquals(invalid, missing);
        String kind = switch (invalid) {
            case WorkflowResolution.Missing m -> "missing";
            case WorkflowResolution.Selected s -> "selected";
            case WorkflowResolution.Invalid i -> "invalid:" + i.problem().configKey();
        };
        assertEquals("invalid:issueTriage", kind);
        assertThrows(NullPointerException.class, () -> new WorkflowResolution.Invalid(null));
    }

    @Test
    void bundleCopiesBytesAndEntries() {
        byte[] src = {1, 2, 3};
        var f = new WorkflowBundle.File("a/b.txt", src, WorkflowBundle.sha256Hex(src), true);
        src[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, f.content());
        f.content()[0] = 7;
        assertArrayEquals(new byte[] {1, 2, 3}, f.content());
        List<WorkflowBundle.Entry> list = new ArrayList<>(List.of(f));
        var bundle = WorkflowBundle.of(list);
        list.clear();
        assertEquals(1, bundle.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> bundle.entries().clear());
    }

    @Test
    void bundleValueSemanticsAndMeasuredCounts() {
        List<WorkflowBundle.Entry> entries = List.of(file("a.txt", "xx"), new WorkflowBundle.Directory("d"),
                new WorkflowBundle.Link("d/l", "../a.txt"));
        var one = WorkflowBundle.of(entries);
        var two = WorkflowBundle.of(entries.stream().toList());
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertEquals(1, one.fileCount());
        assertEquals(1, one.directoryCount());
        assertEquals(1, one.linkCount());
        assertEquals(2, one.totalBytes());
        assertNotEquals(one, WorkflowBundle.of(List.of(file("a.txt", "xy"))));
        assertEquals(WorkflowBundle.empty(), WorkflowBundle.of(List.of()));
    }

    @Test
    void bundleRejectsAbsoluteTraversingAndAdminPaths() {
        for (String bad : new String[] {"/etc/passwd", "../x", "a/../b", "a//b", "./a", ".git/config",
                "a/.git/x", "C:/x", "a\\b", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new WorkflowBundle.Directory(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new WorkflowBundle.Link("a", "/abs"));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowBundle.Link("a", "../out"));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowBundle.Link("d/a", "../../out"));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowBundle.Link("a", "C:/x"));
        assertTrue(WorkflowBundle.of(List.of(new WorkflowBundle.Link("d/a", "../b"))).linkCount() == 1);
    }

    @Test
    void selectedWorkflowSnapshotIsStableAndValueEqual() {
        var id = RepositoryId.newId();
        List<WorkflowBundle.Entry> list = new ArrayList<>(List.of(file("a.txt", "xx")));
        var s1 = selected(id, WorkflowBundle.of(list));
        list.add(file("b.txt", "yy"));
        assertEquals(1, s1.bundle().entries().size());
        assertEquals(s1, selected(id, WorkflowBundle.of(List.of(file("a.txt", "xx")))));
        assertNotEquals(s1, selected(RepositoryId.newId(), s1.bundle()));
        assertEquals(new WorkflowResolution.Selected(s1), new WorkflowResolution.Selected(s1));
    }

    @Test
    void selectedWorkflowRejectsNullsBlankDigestAndAbsoluteSource() {
        var id = RepositoryId.newId();
        var src = new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new SelectedWorkflow(id, WorkflowTarget.ISSUE_IMPLEMENT,
                src, "p", WorkflowBundle.empty(), " "));
        assertThrows(NullPointerException.class, () -> new SelectedWorkflow(null, WorkflowTarget.ISSUE_IMPLEMENT,
                src, "p", WorkflowBundle.empty(), "d"));
        assertThrows(IllegalArgumentException.class, () -> new SelectedWorkflow.SourceDescriptor(
                SelectedWorkflow.SourceKind.FILE, "k", Optional.of("/abs/x.md")));
        assertThrows(IllegalArgumentException.class, () -> new SelectedWorkflow.SourceDescriptor(
                SelectedWorkflow.SourceKind.FILE, "k", Optional.empty()));
    }

    @Test
    void noAbsolutePathOrPathTypeInBundleModel() {
        for (var c : new Class<?>[] {WorkflowBundle.class, WorkflowBundle.File.class, WorkflowBundle.Link.class,
                WorkflowBundle.Directory.class, SelectedWorkflow.class}) {
            for (var rc : c.getRecordComponents()) {
                assertNotEquals(Path.class, rc.getType(), c + "." + rc.getName());
            }
        }
    }
}

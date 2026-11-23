package app.drydock.intellij.remote;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.remote.RemoteWorkflowPublication.Failed;
import app.drydock.intellij.remote.RemoteWorkflowPublication.Outcome;
import app.drydock.intellij.remote.RemoteWorkflowPublication.Published;
import app.drydock.sdlc.MaterializedWorkflow.RemoteReference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.WorkflowArchiveEncoder;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowLimits;
import app.drydock.workspace.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real {@code WorkflowArchiveEncoder} archive is transferred to a temporary "box" home; a fake {@code CommandRunner} turns ssh into a local {@code sh -c} (HOME pointed at the
 * box) and scp into a file copy, so faults can be injected at the transfer.
 */
class RemoteWorkflowPublicationTest {
    private static final String STAGER = Path.of("src/main/resources/drydock/remote/workflow_stage.py")
            .toAbsolutePath().toString();
    private final ManagedSessionId session = ManagedSessionId.newId();
    private final List<List<String>> argvs = new ArrayList<>();
    private Path home;
    private volatile Consumer<Path> afterCopy = p -> { };
    private volatile Runnable afterStage = () -> { };

    private final CommandRunner box = (argv, options, cap) -> {
        argvs.add(argv);
        if (argv.get(0).equals("scp")) {
            Path dest = Path.of(argv.get(argv.size() - 1).substring("box:".length()));
            Files.copy(Path.of(argv.get(argv.size() - 2)), dest);
            afterCopy.accept(dest);
            return new ProcessResult(0, "", "");
        }
        ProcessResult r = ProcessRunner.run(List.of("sh", "-c", argv.get(argv.size() - 1)),
                new ProcessRunner.Options(null, options.timeout(), true, Map.of("HOME", home.toString())), cap);
        if (argv.get(argv.size() - 1).contains("workflow_stage.py")) {
            afterStage.run();
        }
        return r;
    };

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
    }

    private static WorkflowBundle bundle() {
        byte[] run = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);
        byte[] doc = "# doc\n".getBytes(StandardCharsets.UTF_8);
        return WorkflowBundle.of(List.of(new WorkflowBundle.Directory("skill"),
                new WorkflowBundle.File("skill/run.sh", run, WorkflowBundle.sha256Hex(run), true),
                new WorkflowBundle.File("skill/doc.md", doc, WorkflowBundle.sha256Hex(doc), false),
                new WorkflowBundle.Link("skill/latest.md", "doc.md")));
    }

    private Outcome publish(Cancellation cancel) throws Exception {
        return new RemoteWorkflowPublication(box, STAGER).publish("box", "inst1", session, "inv-abc", bundle(), cancel);
    }

    private Path pendingRoot() {
        return home.resolve(".local/share/drydock/inst1/sessions/" + session + "/pending");
    }

    private boolean pendingEmpty() throws Exception {
        try (var s = Files.list(pendingRoot())) {
            return s.findAny().isEmpty();
        }
    }

    private static String zipMember(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    void transferredArchiveHasTheEncodersHashLayoutExecBitAndLink() throws Exception {
        byte[][] seen = new byte[1][];
        Object[] mode = new Object[1];
        afterCopy = dest -> {
            try {
                seen[0] = Files.readAllBytes(dest);
                mode[0] = Files.getPosixFilePermissions(dest.getParent());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        Published p = assertInstanceOf(Published.class, publish(Cancellation.none()));
        Path tree = Path.of(p.remoteRoot());
        assertEquals(home.resolve(".local/share/drydock/inst1/sessions/" + session + "/workflows/inv-abc"), tree);
        assertEquals("#!/bin/sh\necho hi\n", Files.readString(tree.resolve("skill/run.sh")));
        assertTrue(Files.isExecutable(tree.resolve("skill/run.sh")));
        assertFalse(Files.isExecutable(tree.resolve("skill/doc.md")));
        assertEquals("doc.md", Files.readSymbolicLink(tree.resolve("skill/latest.md")).toString());
        assertEquals(List.of(new RemoteReference(Role.RESOURCE, p.remoteRoot())), p.references());
        assertFalse(p.toString().contains("drydock-wf-"), "no local scratch path in the result");
        assertEquals(WorkflowArchiveEncoder.encode(bundle()).sha256(), WorkflowBundle.sha256Hex(seen[0]));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), mode[0],
                "private 0700 pending directory");
        assertEquals("#!/bin/sh\necho hi\n", zipMember(seen[0], "skill/run.sh"));
        String manifest = zipMember(seen[0], ".drydock-manifest.json");
        assertTrue(manifest.contains("\"path\":\"skill/run.sh\"") && manifest.contains("\"executable\":true"));
        assertTrue(manifest.contains("\"type\":\"link\",\"target\":\"doc.md\""));
        assertTrue(manifest.contains("\"type\":\"dir\""));
        assertTrue(pendingEmpty(), "pending archive and directory are removed");
        assertTrue(argvs.stream().flatMap(List::stream).noneMatch(a -> a.contains("drydock-wf-") && !a.startsWith("/")));
    }

    @Test
    void partialCopyIsRejectedByHashBeforeAnyStaging() throws Exception {
        afterCopy = dest -> {
            try {
                Files.write(dest, Arrays.copyOf(Files.readAllBytes(dest), 40));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        Failed f = assertInstanceOf(Failed.class, publish(Cancellation.none()));
        assertEquals("archive hash mismatch after transfer", f.reason());
        assertTrue(pendingEmpty());
        assertTrue(argvs.stream().noneMatch(a -> a.get(a.size() - 1).contains("workflow_stage.py")), "stager never ran");
    }

    @Test
    void cancelAfterStageRemovesThePublishedTree() throws Exception {
        Cancellation cancel = Cancellation.none();
        afterStage = cancel::cancel;
        assertInstanceOf(RemoteWorkflowPublication.Cancelled.class, publish(cancel));
        assertFalse(Files.exists(home.resolve(".local/share/drydock/inst1/sessions/" + session + "/workflows/inv-abc")));
        assertTrue(pendingEmpty());
    }

    @Test
    void cancelBetweenVerifiedCopyAndStageNeverRunsTheStager() throws Exception {
        Cancellation cancel = Cancellation.none();
        afterCopy = dest -> cancel.cancel();
        assertInstanceOf(RemoteWorkflowPublication.Cancelled.class, publish(cancel));
        assertTrue(argvs.stream().noneMatch(a -> a.get(a.size() - 1).contains("workflow_stage.py")));
        assertTrue(pendingEmpty());
    }

    @Test
    void cancelBeforeAnyRemoteCommandRunsNothing() throws Exception {
        Cancellation cancel = Cancellation.none();
        cancel.cancel();
        assertInstanceOf(RemoteWorkflowPublication.Cancelled.class, publish(cancel));
        assertTrue(argvs.isEmpty());
    }

    @Test
    void bundleOverTheEntryLimitByOneIsRefusedBeforeAnyRemoteCommand() throws Exception {
        List<WorkflowBundle.Entry> entries = new ArrayList<>();
        for (int i = 0; i <= WorkflowLimits.BUNDLE_ENTRIES; i++) {
            entries.add(new WorkflowBundle.Directory("d" + i));
        }
        Outcome o = new RemoteWorkflowPublication(box, STAGER)
                .publish("box", "inst1", session, "inv-abc", WorkflowBundle.of(entries), Cancellation.none());
        assertInstanceOf(Failed.class, o);
        assertTrue(argvs.isEmpty());
    }

    @Test
    void exhaustedOverallBudgetPublishesNothing() throws Exception {
        Outcome o = new RemoteWorkflowPublication(box, STAGER, Duration.ZERO)
                .publish("box", "inst1", session, "inv-abc", bundle(), Cancellation.none());
        assertInstanceOf(Failed.class, o);
        assertFalse(Files.exists(home.resolve(".local")), "no remote directory was created");
    }
}

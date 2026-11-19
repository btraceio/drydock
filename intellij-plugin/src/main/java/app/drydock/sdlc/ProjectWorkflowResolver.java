package app.drydock.sdlc;

import app.drydock.projects.SharedProject;
import app.drydock.sdlc.WorkflowConfigDecoder.Decoded;
import app.drydock.sdlc.WorkflowConfigDecoder.Definition;
import app.drydock.sdlc.WorkflowConfigDecoder.SourceType;
import app.drydock.sdlc.WorkflowPathSelector.Root;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowResolution.WorkflowProblem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking, headless resolution of one target's workflow from the registered project's own
 * checkout. Only {@code <canonical root>/.sdlc/config.json} is read: no session, PR, remote,
 * parent or user-global lookup. Every call resolves afresh; the returned snapshot holds bytes
 * only, so later source edits never change it. The work runs on a daemon thread bounded by the
 * deadline and polled for cancellation; on expiry or cancel the worker is interrupted and any late
 * result is discarded.
 */
public final class ProjectWorkflowResolver {

    static final String CONFIG = ".sdlc/config.json";

    private final long deadlineMillis;
    private final BoundedWorkflowCapture.Probe probe;

    public ProjectWorkflowResolver() {
        this(WorkflowLimits.DEADLINE_MILLIS, null);
    }

    /** Test seam: shorter deadline and a probe run after each captured file read. */
    ProjectWorkflowResolver(long deadlineMillis, BoundedWorkflowCapture.Probe probe) {
        this.deadlineMillis = deadlineMillis;
        this.probe = probe;
    }

    public WorkflowResolution resolve(SharedProject project, WorkflowTarget target, Cancellation cancel) {
        String key = WorkflowConfigDecoder.configKey(target);
        if (project.isRemote()) {
            return invalid(key, null, ProblemCategory.UNSUPPORTED_SOURCE, "a remote project has no local checkout to read");
        }
        AtomicReference<WorkflowResolution> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            WorkflowResolution r = resolveBlocking(project, target, key);
            // a worker that was abandoned (deadline/cancel) must not publish
            if (!Thread.currentThread().isInterrupted()) {
                result.compareAndSet(null, r);
            }
        }, "sdlc-workflow-resolve");
        worker.setDaemon(true);
        worker.start();
        long end = System.nanoTime() + deadlineMillis * 1_000_000L;
        try {
            while (worker.isAlive()) {
                if (cancel.isCancelled()) {
                    return abandon(worker, key, ProblemCategory.CANCELLED, "workflow resolution was cancelled");
                }
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return abandon(worker, key, ProblemCategory.LIMIT_EXCEEDED, "workflow resolution exceeded " + deadlineMillis + " ms");
                }
                worker.join(Math.max(1, Math.min(20, left / 1_000_000L)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return abandon(worker, key, ProblemCategory.CANCELLED, "workflow resolution was interrupted");
        }
        WorkflowResolution r = result.get();
        return r != null ? r : invalid(key, null, ProblemCategory.SOURCE_UNREADABLE, "workflow resolution failed");
    }

    private static WorkflowResolution abandon(Thread worker, String key, ProblemCategory c, String message) {
        worker.interrupt();
        return invalid(key, null, c, message);
    }

    private WorkflowResolution resolveBlocking(SharedProject project, WorkflowTarget target, String key) {
        Path root;
        try {
            root = project.root().toRealPath();
        } catch (IOException e) {
            return invalid(key, null, ProblemCategory.SOURCE_UNREADABLE, "registered root is not readable");
        }
        String text;
        try {
            Optional<String> config = readConfig(root);
            if (config.isEmpty()) {
                return new WorkflowResolution.Missing(CONFIG + " is not present");
            }
            text = config.get();
        } catch (ConfigProblem p) {
            return invalid(key, CONFIG, p.category, p.getMessage());
        }
        return switch (WorkflowConfigDecoder.decode(text, target)) {
            case Decoded.Missing m -> new WorkflowResolution.Missing(m.reason());
            case Decoded.Invalid i -> new WorkflowResolution.Invalid(i.problem());
            case Decoded.Leaf l -> select(project, target, root, l.definition());
        };
    }

    private WorkflowResolution select(SharedProject project, WorkflowTarget target, Path root, Definition def) {
        String key = def.configKey();
        String source = def.type() == SourceType.TEXT ? null : def.value();
        List<Root> roots = new ArrayList<>();
        if (def.type() == SourceType.FILE) {
            roots.add(new Root(source, false));
        } else if (def.type() == SourceType.SKILL) {
            // a skill selects its complete containing directory; the file root first so the selector
            // rejects absolute/expansion/escaping spellings before the directory is derived
            roots.add(new Root(source, false));
            String norm = normalizeOrNull(source);
            int slash = norm == null ? -1 : norm.lastIndexOf('/');
            if (slash < 0) {
                return invalid(key, source, ProblemCategory.UNSUPPORTED_SOURCE, "a skill must live in a repository subdirectory");
            }
            roots.add(new Root(norm.substring(0, slash), true));
        }
        for (String inc : def.include()) {
            Path p = root.resolve(inc).normalize();
            roots.add(new Root(inc, p.startsWith(root) && Files.isDirectory(p)));
        }
        try {
            WorkflowSelectionPlan plan = WorkflowPathSelector.select(root, roots);
            WorkflowBundle bundle = BoundedWorkflowCapture.capture(root, plan, probe);
            String prompt = def.value();
            if (source != null) {
                prompt = prompt(bundle, plan.logicalToCanonical().get(normalizeOrNull(source)));
            }
            if (prompt.isBlank()) {
                return invalid(key, source, ProblemCategory.BLANK, "prompt must not be blank");
            }
            if (prompt.getBytes(StandardCharsets.UTF_8).length > WorkflowLimits.PROMPT_BYTES) {
                return invalid(key, source, ProblemCategory.LIMIT_EXCEEDED, "prompt exceeds " + WorkflowLimits.PROMPT_BYTES + " bytes");
            }
            SelectedWorkflow.SourceDescriptor descriptor = source == null
                    ? new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, key, Optional.empty())
                    : new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.FILE, key, Optional.of(source));
            return new WorkflowResolution.Selected(new SelectedWorkflow(project.id(), target, descriptor, prompt, bundle,
                    digest(project, target, descriptor, prompt, bundle)));
        } catch (WorkflowPathSelector.SelectionException e) {
            return invalid(key, source, e.category(), e.getMessage());
        } catch (BoundedWorkflowCapture.CaptureException e) {
            return invalid(key, source, e.category(), e.getMessage());
        } catch (ConfigProblem e) {
            return invalid(key, source, e.category, e.getMessage());
        } catch (IllegalArgumentException e) {
            return invalid(key, null, ProblemCategory.UNSUPPORTED_SOURCE, "source is not a repository-relative path");
        }
    }

    /** Same lexical normalization as the selector's logical key; null when it would climb above the root. */
    private static String normalizeOrNull(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.equals("..")) {
                if (out.isEmpty()) {
                    return null;
                }
                out.remove(out.size() - 1);
            } else if (!seg.isEmpty() && !seg.equals(".")) {
                out.add(seg);
            }
        }
        return String.join("/", out);
    }

    private static String prompt(WorkflowBundle bundle, String canonical) throws ConfigProblem {
        for (WorkflowBundle.Entry e : bundle.entries()) {
            if (e instanceof WorkflowBundle.File f && f.path().equals(canonical)) {
                return strictUtf8(f.content(), "prompt");
            }
        }
        throw new ConfigProblem(ProblemCategory.SPECIAL_FILE, "prompt source is not a regular file");
    }

    private static String strictUtf8(byte[] bytes, String what) throws ConfigProblem {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ConfigProblem(ProblemCategory.ENCODING, what + " is not strict UTF-8");
        }
    }

    private static String digest(SharedProject project, WorkflowTarget target, SelectedWorkflow.SourceDescriptor source,
                                 String prompt, WorkflowBundle bundle) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            update(md, project.id().toString(), target.name(), source.kind().name(), source.configKey(),
                    source.relativeSource().orElse(""), prompt);
            for (WorkflowBundle.Entry e : bundle.entries()) {
                switch (e) {
                    case WorkflowBundle.File f -> update(md, "F", f.path(), f.sha256(), String.valueOf(f.executable()));
                    case WorkflowBundle.Directory d -> update(md, "D", d.path());
                    case WorkflowBundle.Link l -> update(md, "L", l.path(), l.target());
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest md, String... parts) {
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            md.update(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(b);
        }
    }

    /** Reads the registered root's config only: absent is empty, anything unusable is a {@link ConfigProblem}. */
    private static Optional<String> readConfig(Path root) throws ConfigProblem {
        Path cfg = root.resolve(".sdlc").resolve("config.json");
        Path real;
        try {
            real = cfg.toRealPath();
        } catch (NoSuchFileException e) {
            if (Files.isSymbolicLink(cfg) || Files.isSymbolicLink(cfg.getParent())) {
                throw new ConfigProblem(ProblemCategory.SOURCE_UNREADABLE, "config link is dangling");
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new ConfigProblem(ProblemCategory.SOURCE_UNREADABLE, "config is not readable");
        }
        if (!real.startsWith(root)) {
            throw new ConfigProblem(ProblemCategory.ESCAPES_ROOT, "config resolves outside the repository");
        }
        try {
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigProblem(ProblemCategory.SPECIAL_FILE, "config is not a regular file");
            }
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (SeekableByteChannel ch = Files.newByteChannel(real, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(16 * 1024);
                while (ch.read(buf) >= 0) {
                    sink.write(buf.array(), 0, buf.position());
                    if (sink.size() > WorkflowLimits.CONFIG_BYTES) {
                        throw new ConfigProblem(ProblemCategory.LIMIT_EXCEEDED, "config exceeds " + WorkflowLimits.CONFIG_BYTES + " bytes");
                    }
                    buf.clear();
                }
            }
            return Optional.of(strictUtf8(sink.toByteArray(), "config"));
        } catch (IOException e) {
            throw new ConfigProblem(ProblemCategory.SOURCE_UNREADABLE, "config is not readable");
        }
    }

    private static final class ConfigProblem extends Exception {
        final ProblemCategory category;

        ConfigProblem(ProblemCategory category, String message) {
            super(message);
            this.category = category;
        }
    }

    private static WorkflowResolution invalid(String key, String source, ProblemCategory c, String message) {
        return new WorkflowResolution.Invalid(new WorkflowProblem(key, Optional.ofNullable(source), c, message));
    }
}

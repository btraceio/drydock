package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitReviewQueryRules;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.workspace.CommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Remote backend behind {@link ReviewDataSource}: every read is a bounded ssh command (Git argv from
 * {@link RemoteReviewQueryBuilder}, or the fixed {@code review_git.py} capture helper) run through
 * {@link CommandRunner}; no local Path/Git branch. A capture pins HEAD/base/merge-base object ids and
 * reads sources at those ids (working-scope right sides via the no-follow helper, hash/size validated), and
 * captures again; a difference is retried once, then {@link Result.Stale}.
 */
public final class RemoteReviewDataSource implements ReviewDataSource {
    /** Remote paths of the bundled {@code review_git.py} and {@code review_data.py}. */
    public record Helpers(String git, String data) { }

    static final int MAX_DIFF_FILES = 2048;
    static final long MAX_SOURCE_BYTES = 8L << 20;
    static final long MAX_SNAPSHOT_SOURCE_BYTES = 64L << 20;
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(30);
    private static final int SMALL_CAP = 1 << 20;
    private static final int CAPTURE_CAP = 32 << 20;
    private static final int READ_CAP = 12 << 20;
    private static final int CONTEXT_LINES = DiffService.REVIEW_CONTEXT_LINES;
    private static final String[] IDENTITY = {"head", "refs_sha256", "index_sha256", "status_b64",
            "staged_b64", "untracked_b64", "diff_b64"};

    private final SshRemote remote;
    private final CommandRunner runner;
    private final ReviewScopeRegistry registry;
    private final RemoteSnapshotCache cache;
    private final Helpers helpers;
    private final String checkoutId;
    private final long generation;
    private final Executor executor;
    private final Duration overall;
    private final RemoteReviewQueryBuilder query;

    public RemoteReviewDataSource(SshRemote remote, CommandRunner runner, ReviewScopeRegistry registry,
                                  RemoteSnapshotCache cache, Helpers helpers, String checkoutId,
                                  long generation, Executor executor) {
        this(remote, runner, registry, cache, helpers, checkoutId, generation, executor, OVERALL_TIMEOUT);
    }

    RemoteReviewDataSource(SshRemote remote, CommandRunner runner, ReviewScopeRegistry registry,
                           RemoteSnapshotCache cache, Helpers helpers, String checkoutId, long generation,
                           Executor executor, Duration overall) {
        this.remote = Objects.requireNonNull(remote, "remote");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.helpers = Objects.requireNonNull(helpers, "helpers");
        this.checkoutId = Objects.requireNonNull(checkoutId, "checkoutId");
        this.generation = generation;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.overall = overall;
        this.query = new RemoteReviewQueryBuilder(remote);
    }

    /** A failed step: unavailable (default) or stale; carries no data. */
    private static final class Abort extends Exception {
        final boolean stale;

        Abort(String reason, boolean stale) {
            super(reason, null, false, false);
            this.stale = stale;
        }
    }

    private record Capture(JsonObject json, String head, String left, String base) { }

    @Override
    public Backend backend() {
        return Backend.REMOTE;
    }

    @Override
    public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId session, String selection) {
        Result<ReviewScope> r = registry.isAddressableBy(selection, session)
                ? registry.byId(selection).<Result<ReviewScope>>map(Result.Ok::new)
                        .orElseGet(() -> new Result.Refused<>("scope is not addressable by this session"))
                : new Result.Refused<>("scope is not addressable by this session");
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope scope, BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            if (scope.sessionId().isEmpty()) {
                return new Result.Refused<ReviewDataSnapshot>("scope has no owning session");
            }
            long deadline = System.nanoTime() + overall.toNanos();
            try {
                ReviewDataSnapshot snap = captureWithRetry(scope, deadline, cancelled);
                return switch (cache.put(scope.sessionId().get().toString(), snap)) {
                    case STORED -> new Result.Ok<>(snap);
                    case TOO_LARGE -> new Result.Unavailable<>("snapshot exceeds the cache size ceiling");
                    case NO_CAPACITY -> new Result.Unavailable<>("no snapshot cache capacity (held by readers)");
                };
            } catch (Abort a) {
                return a.stale ? new Result.Stale<ReviewDataSnapshot>(a.getMessage())
                        : new Result.Unavailable<>(a.getMessage());
            } catch (RuntimeException e) {
                return new Result.Unavailable<>("remote capture failed: " + e.getClass().getSimpleName());
            }
        }, executor);
    }

    private ReviewDataSnapshot captureWithRetry(ReviewScope scope, long deadline, BooleanSupplier cancelled)
            throws Abort {
        for (int attempt = 0; ; attempt++) {
            Capture before = capture(scope, deadline);
            Map<String, ReviewDataSnapshot.SourceFile> sources = sources(scope, before, deadline, cancelled);
            Capture after = capture(scope, deadline);
            if (identity(before.json()).equals(identity(after.json()))
                    && before.head().equals(after.head())) {
                return snapshot(scope, before, sources);
            }
            if (attempt == 1) {
                throw new Abort("remote checkout changed during the capture", true);
            }
        }
    }

    private Capture capture(ReviewScope scope, long deadline) throws Abort {
        String head = oid(gitText(query.headCommand(), deadline));
        DiffScope diffScope = scope.diffScope();
        String left = head;
        String baseOid = head;
        if (diffScope != DiffScope.WORKING_TREE) {
            baseOid = oid(gitText(query.resolveCommand(scope.base()), deadline));
            left = oid(gitText(query.mergeBaseCommand(baseOid, head), deadline));
        }
        String mergeBase = left;
        List<String> rules = GitReviewQueryRules.diffCommand("git", Path.of("/"), diffScope, baseOid,
                CONTEXT_LINES, () -> mergeBase);
        String range = rules.get(rules.size() - 1);
        range = range.endsWith("HEAD") ? range.substring(0, range.length() - 4) + head : range;
        JsonObject json = helper(List.of("python3", helpers.git(), "capture", remote.remotePath(), range,
                String.valueOf(CONTEXT_LINES), GitReviewQueryRules.includesWorkingTree(diffScope) ? "1" : "0"),
                deadline, CAPTURE_CAP);
        if (!head.equals(text(json, "head"))) {
            throw new Abort("remote HEAD moved during the capture", true);
        }
        return new Capture(json, head, left, baseOid);
    }

    private static String identity(JsonObject json) {
        StringBuilder id = new StringBuilder();
        for (String key : IDENTITY) {
            id.append(key).append('=').append(json.has(key) ? text(json, key) : "").append('\n');
        }
        return id.toString();
    }

    private Map<String, ReviewDataSnapshot.SourceFile> sources(ReviewScope scope, Capture cap, long deadline,
                                                              BooleanSupplier cancelled) throws Abort {
        UnifiedDiff diff = diff(cap.json());
        if (diff.files().size() > MAX_DIFF_FILES) {
            throw new Abort("diff has more than " + MAX_DIFF_FILES + " files", false);
        }
        boolean working = GitReviewQueryRules.includesWorkingTree(scope.diffScope());
        Map<String, ReviewDataSnapshot.SourceFile> out = new LinkedHashMap<>();
        long[] total = {0};
        String[] rootId = {null};
        for (UnifiedDiff.FileDiff f : diff.files()) {
            if (cancelled.getAsBoolean()) {
                throw new Abort("refresh cancelled", false);
            }
            var l = "A".equals(f.kind()) ? new ReviewDataSnapshot.Content.Absent()
                    : blob(cap.left(), f.oldPath().orElse(f.path()), total, deadline);
            var r = "D".equals(f.kind()) ? new ReviewDataSnapshot.Content.Absent()
                    : working ? working(f.path(), total, rootId, deadline)
                    : blob(cap.head(), f.path(), total, deadline);
            out.put(f.path(), new ReviewDataSnapshot.SourceFile(l, r));
        }
        return out;
    }

    private ReviewDataSnapshot.Content blob(String oid, String path, long[] total, long deadline) throws Abort {
        try {
            List<String> sizeCmd = SshCommandBuilder.remoteGitCommand(remote,
                    List.of("cat-file", "-s", "--end-of-options", oid + ":" + path));
            long size = Long.parseLong(gitText(sizeCmd, deadline));
            if (size > MAX_SOURCE_BYTES) {
                throw new Abort("source exceeds " + MAX_SOURCE_BYTES + " bytes: " + path, false);
            }
            ProcessResult r = run(query.blobCommand(oid, path), deadline, (int) (MAX_SOURCE_BYTES + 1));
            byte[] bytes = r.stdout().getBytes(StandardCharsets.UTF_8);
            if (r.exitCode() != 0 || bytes.length != size || r.stdout().indexOf('\uFFFD') >= 0) {
                throw new Abort("git object is not transferable as text: " + path, false);
            }
            return account(ReviewDataSnapshot.Content.of(bytes), bytes.length, total);
        } catch (IllegalArgumentException e) {
            throw new Abort("invalid source path or size: " + path, false);
        }
    }

    private ReviewDataSnapshot.Content working(String path, long[] total, String[] rootId, long deadline)
            throws Abort {
        JsonObject json = helper(List.of("python3", helpers.data(), "read", remote.remotePath(), path),
                deadline, READ_CAP);
        byte[] bytes = decode(text(json, "b64"));
        if (bytes.length > MAX_SOURCE_BYTES || !sha256(bytes).equals(text(json, "sha256"))
                || bytes.length != Long.parseLong(num(json, "size"))) {
            throw new Abort("working source failed size/hash validation: " + path, false);
        }
        String root = text(json, "root_id");
        if (rootId[0] != null && !rootId[0].equals(root)) {
            throw new Abort("remote checkout root was replaced", true);
        }
        rootId[0] = root;
        return account(ReviewDataSnapshot.Content.of(bytes), bytes.length, total);
    }

    private static ReviewDataSnapshot.Content account(ReviewDataSnapshot.Content c, int n, long[] total)
            throws Abort {
        total[0] += n;
        if (total[0] > MAX_SNAPSHOT_SOURCE_BYTES) {
            throw new Abort("captured sources exceed " + MAX_SNAPSHOT_SOURCE_BYTES + " bytes", false);
        }
        return c;
    }

    private static UnifiedDiff diff(JsonObject json) throws Abort {
        Set<String> staged = GitReviewQueryRules.parseNulPaths(raw(json, "staged_b64"));
        Set<String> untracked = GitReviewQueryRules.parseNulPaths(raw(json, "untracked_b64"));
        if (Boolean.TRUE.equals(flag(json, "untracked_omitted"))) {
            throw new Abort("untracked files exceed the 2000-file review threshold", false);
        }
        return GitReviewQueryRules.parse(raw(json, "diff_b64"), staged, untracked);
    }

    private ReviewDataSnapshot snapshot(ReviewScope scope, Capture cap,
                                        Map<String, ReviewDataSnapshot.SourceFile> sources) throws Abort {
        String fingerprint = sha256(identity(cap.json()).getBytes(StandardCharsets.UTF_8));
        return new ReviewDataSnapshot(scope.id(), Backend.REMOTE, checkoutId, generation, scope.diffScope(),
                new ReviewBase(scope.base(), scope.baseOrigin().orElse(ReviewBase.Origin.DEFAULT_UNMEASURED)),
                cap.base(), cap.head(), cap.left(), fingerprint, diff(cap.json()), sources, Instant.now());
    }

    @Override
    public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
            ReviewDataSnapshot snapshot, String file, Side side) {
        var sides = snapshot.backend() == Backend.REMOTE ? snapshot.sources().get(file) : null;
        return CompletableFuture.completedFuture(sides == null
                ? new Result.Refused<>("file is not part of this remote snapshot")
                : new Result.Ok<>(side == Side.LEFT ? sides.left() : sides.right()));
    }

    @Override
    public CompletableFuture<Result<Excerpt>> excerpt(
            ManagedSessionId session, String relativeFile, int line, int context) {
        if (line < 1 || context < 0 || context > 20) {
            return CompletableFuture.completedFuture(new Result.Refused<>("line or context out of range"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject json = helper(List.of("python3", helpers.data(), "excerpt", remote.remotePath(),
                        relativeFile, String.valueOf(line), String.valueOf(context)),
                        System.nanoTime() + overall.toNanos(), READ_CAP);
                String window = new String(decode(text(json, "b64")), StandardCharsets.UTF_8);
                List<String> lines = new ArrayList<>(List.of(window.split("\n", -1)));
                if (window.endsWith("\n")) {
                    lines.remove(lines.size() - 1);
                }
                return new Result.Ok<>(new Excerpt(relativeFile, Integer.parseInt(num(json, "start")), lines));
            } catch (Abort a) {
                return a.stale ? new Result.Stale<Excerpt>(a.getMessage()) : new Result.Unavailable<>(a.getMessage());
            } catch (RuntimeException e) {
                return new Result.Unavailable<>("remote excerpt failed");
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot snapshot) {
        Optional<ReviewScope> scope = registry.byId(snapshot.scopeId());
        if (scope.isEmpty()) {
            return CompletableFuture.completedFuture(new Result.Refused<>("scope is not registered"));
        }
        return refresh(scope.get(), () -> false).thenApply(r -> switch (r) {
            case Result.Ok<ReviewDataSnapshot> ok -> ok.value().fingerprint().equals(snapshot.fingerprint())
                    ? new Result.Ok<>(snapshot)
                    : new Result.Stale<>("remote checkout changed since the snapshot was captured");
            default -> r;
        });
    }

    private String gitText(List<String> argv, long deadline) throws Abort {
        ProcessResult r = run(argv, deadline, SMALL_CAP);
        if (r.exitCode() != 0) {
            throw new Abort("remote git command failed (exit " + r.exitCode() + ")", false);
        }
        return r.stdout().strip();
    }

    private JsonObject helper(List<String> remoteArgs, long deadline, int cap) throws Abort {
        ProcessResult r = run(SshCommandBuilder.remoteCommand(remote.host(), remoteArgs), deadline, cap);
        try {
            if (JsonParser.parseStrict(r.stdout().strip(), 32) instanceof JsonObject o) {
                if (o.get("ok") instanceof JsonValue.JsonBoolean b && b.value()) {
                    return o;
                }
                String code = o.has("code") ? text(o, "code") : "unknown";
                throw new Abort("remote helper refused: " + code, code.equals("stale") || code.equals("root-replaced"));
            }
        } catch (RuntimeException e) {
            // fall through: malformed helper output
        }
        throw new Abort("remote helper returned malformed output (exit " + r.exitCode() + ")", false);
    }

    private ProcessResult run(List<String> argv, long deadline, int cap) throws Abort {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new Abort("remote capture exceeded its overall time ceiling", false);
        }
        Duration timeout = Duration.ofNanos(Math.min(left, COMMAND_TIMEOUT.toNanos()));
        try {
            return runner.run(argv, new ProcessRunner.Options(null, timeout, true, Map.of()), cap);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Abort("remote read cancelled", false);
        } catch (IOException | RuntimeException e) {
            throw new Abort("remote command failed or exceeded its bound", false);
        }
    }

    private static String oid(String s) throws Abort {
        if (!s.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new Abort("remote git did not return an object id", false);
        }
        return s;
    }

    private static String text(JsonObject o, String key) {
        return o.get(key) instanceof JsonString s ? s.value() : o.get(key) instanceof JsonValue.JsonNumber n
                ? n.literal() : "";
    }

    private static String num(JsonObject o, String key) {
        return text(o, key);
    }

    private static Boolean flag(JsonObject o, String key) {
        return o.get(key) instanceof JsonValue.JsonBoolean b ? b.value() : null;
    }

    private static byte[] decode(String b64) throws Abort {
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new Abort("remote helper returned invalid base64", false);
        }
    }

    private static String raw(JsonObject o, String key) throws Abort {
        return new String(decode(text(o, key)), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

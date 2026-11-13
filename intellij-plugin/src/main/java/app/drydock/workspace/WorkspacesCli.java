package app.drydock.workspace;

import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The `workspaces` CLI seam for workspace boxes. The class can express
 * exactly two subcommands — the read-only `list` and the mutating `create`
 * — so `create` is the only mutating subcommand this class can express
 * (INV-11): there is deliberately no method that could build a `restart`
 * or `delete` argv, because `workspace-persistent-sessions/find-restart-wake-refuted`
 * showed `workspaces restart` does not wake a stopped box; a stopped box is
 * left stopped and re-creation is decided upstream.
 *
 * <p>Every run goes through the {@link CommandRunner} seam: an argument
 * list (never a shell string), an explicit timeout, stdin from
 * {@code /dev/null}, and a hard output cap (INV-5, INV-20). `list` is
 * bounded to 256 KiB and 30 s. `create` is clone-scale — ≈2.5 min
 * measured end-to-end on a real box
 * (`workspace-persistent-sessions/find-box-provisioning-facts`) — so it
 * carries a 10-minute timeout. The box name handed to {@link #create} has
 * passed {@link WorkspaceBoxNames#validate} upstream; the method
 * re-asserts that guard and never `--`-escapes the name (INV-6).</p>
 *
 * <p>Failure modes are typed and never conflated (INV-9, AC-20):
 * not-installed, non-zero exit, timeout, and cap refusal are distinct
 * outcomes, and each is logged as its own WARNING carrying a
 * {@link ProcessRunner#excerpt} of what the CLI said. An unparseable
 * `list` table is likewise a typed, logged failure — never an empty
 * listing — because the table format is observed rather than a contract
 * (ASM-8): drift must surface as a failure so the lifecycle decision
 * upstream lands in transient-error, never in a wrong classification.</p>
 */
public final class WorkspacesCli {

    private static final Logger LOG = System.getLogger(WorkspacesCli.class.getName());

    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Create is clone-scale: ≈2.5 min measured end-to-end, plus ssh-config
     * and secrets upload (find-box-provisioning-facts); 10 min leaves room
     * for a slow region without hanging a launch forever.
     */
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(10);
    private static final int LIST_MAX_OUTPUT_BYTES = 256 * 1024;
    /** Create prints minutes of provisioning progress, so its cap is looser than list's. */
    private static final int CREATE_MAX_OUTPUT_BYTES = 1024 * 1024;
    /** The bounded half of the table parse (INV-20): a listing this long is not a listing this plugin trusts. */
    private static final int MAX_TABLE_LINES = 4096;
    /** The observed stopped status (find-box-provisioning-facts, ASM-8): the only code interpreted. */
    private static final String STOPPED_STATUS = "8";
    /**
     * {@link ProcessRunner} refuses output beyond the caller's cap with an
     * IOException carrying exactly this documented message prefix; the
     * {@link CommandRunner} seam cannot express the refusal more
     * specifically, so the prefix is the contract used to keep a cap
     * refusal distinct from "the binary could not be launched".
     */
    private static final String OUTPUT_CAP_PREFIX = "Process output exceeded";

    private final HostBinaryLocator binary;
    private final CommandRunner runner;

    /** The production shape: the standard binary locations plus the given runner seam. */
    public WorkspacesCli(CommandRunner runner) {
        this(standardLocator(), runner);
    }

    /** For tests: inject the binary locator (found, or forced missing) and the runner. */
    public WorkspacesCli(HostBinaryLocator binary, CommandRunner runner) {
        this.binary = Objects.requireNonNull(binary, "binary");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * The standard `workspaces` lookup: the live PATH first, then the known
     * install locations (the same fallback convention as the ddtool
     * locator, homebrew dirs leading and `/opt/dogbrew/bin` covering the
     * internal package manager path).
     */
    private static HostBinaryLocator standardLocator() {
        String home = System.getProperty("user.home", "");
        return new HostBinaryLocator("workspaces", List.of(
                Path.of("/opt/homebrew/bin/workspaces"),
                Path.of("/usr/local/bin/workspaces"),
                Path.of(home, ".local", "bin", "workspaces"),
                Path.of("/opt/dogbrew/bin/workspaces")));
    }

    /**
     * The lifecycle-relevant status of one box name: status `8` is the
     * observed stopped code; any other listed status is something else
     * (running, provisioning, error — deliberately not interpreted
     * further, ASM-8); an unlisted name means the box does not exist.
     */
    public enum BoxStatus { STOPPED, OTHER, ABSENT }

    /** The typed outcome of {@link #list()}: a parsed table, or one of the distinct failure modes. */
    public sealed interface ListResult permits Listed, NotInstalled, Failed, TimedOut, OutputTooLarge, Unparseable {
    }

    /** The typed outcome of {@link #create(String, String, Path)}: success, or one of the distinct failure modes. */
    public sealed interface CreateResult permits Created, NotInstalled, Failed, TimedOut, OutputTooLarge {
    }

    /** The CLI binary was not found (nowhere to launch, or the spawn itself failed). */
    public record NotInstalled(String detail) implements ListResult, CreateResult {
    }

    /** The CLI ran and exited non-zero; {@code excerpt} is the capped stderr. */
    public record Failed(int exitCode, String excerpt) implements ListResult, CreateResult {
    }

    /** The CLI hung past its timeout and was forcibly destroyed. */
    public record TimedOut(String excerpt) implements ListResult, CreateResult {
    }

    /** The CLI produced more output than the cap; nothing was trusted from it. */
    public record OutputTooLarge(String excerpt) implements ListResult, CreateResult {
    }

    /** The CLI exited zero but its table could not be parsed (INV-20: malformed input is typed, never an empty listing). */
    public record Unparseable(String reason) implements ListResult {
    }

    /** `workspaces create` succeeded. */
    public record Created() implements CreateResult {
    }

    /** A parsed `workspaces list` table: box name to the raw STATUS column value, in table order. */
    public record Listed(Map<String, String> statusesByName) implements ListResult {

        public Listed {
            statusesByName = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(statusesByName, "statusesByName")));
        }

        /** The names the table listed, in table order. */
        public Set<String> names() {
            return statusesByName.keySet();
        }

        /** The classification: `8` → STOPPED, any other listed status → OTHER, an unlisted name → ABSENT. */
        public BoxStatus statusOf(String name) {
            Objects.requireNonNull(name, "name");
            String raw = statusesByName.get(name);
            if (raw == null) {
                return BoxStatus.ABSENT;
            }
            return STOPPED_STATUS.equals(raw) ? BoxStatus.STOPPED : BoxStatus.OTHER;
        }
    }

    /**
     * Runs `workspaces list --no-color` (30 s, stdin from {@code /dev/null},
     * 256 KiB cap) and parses the whitespace table: a header row starting
     * with `NAME` that has a `STATUS` column, data rows aligned by
     * whitespace, and one footnote line starting with `*` skipped. The
     * parse is bounded in lines as well as bytes.
     *
     * @throws InterruptedException if the caller was cancelled (the child is already destroyed)
     */
    public ListResult list() throws InterruptedException {
        Optional<Path> located = binary.locate();
        if (located.isEmpty()) {
            return notInstalled("workspaces list",
                    "no `workspaces` executable on the PATH or the known install locations");
        }
        List<String> argv = List.of(located.get().toString(), "list", "--no-color");
        ProcessResult result;
        try {
            result = runner.run(argv,
                    new ProcessRunner.Options(null, LIST_TIMEOUT, true, Map.of()),
                    LIST_MAX_OUTPUT_BYTES);
        } catch (IOException e) {
            if (isCapRefusal(e)) {
                return outputTooLarge("workspaces list", LIST_MAX_OUTPUT_BYTES, e);
            }
            return notInstalled("workspaces list", e.getMessage());
        } catch (ProcessTimeoutException e) {
            return timedOut("workspaces list", e);
        }
        if (result.exitCode() != 0) {
            return failed("workspaces list", result.exitCode(), result.stderr());
        }
        return parseTable(result.stdout());
    }

    /**
     * Runs `workspaces create <name> -R <repoName> -s bash -y --no-color`
     * with {@code localRepoRoot} as the child's working directory (the CLI
     * derives the box's repo context from the cwd checkout), 10-minute
     * timeout, stdin from {@code /dev/null}. The name has passed
     * {@link WorkspaceBoxNames#validate} upstream; the guard is re-asserted
     * here so an option-looking name can never reach the argv, and the name
     * is never `--`-escaped (INV-6).
     *
     * @throws IllegalArgumentException if {@code name} fails {@link WorkspaceBoxNames#validate}
     * @throws InterruptedException if the caller was cancelled (the child is already destroyed)
     */
    public CreateResult create(String name, String repoName, Path localRepoRoot) throws InterruptedException {
        if (!WorkspaceBoxNames.validate(name)) {
            throw new IllegalArgumentException("not a valid box name: " + name);
        }
        Objects.requireNonNull(repoName, "repoName");
        Objects.requireNonNull(localRepoRoot, "localRepoRoot");
        Optional<Path> located = binary.locate();
        if (located.isEmpty()) {
            return notInstalled("workspaces create",
                    "no `workspaces` executable on the PATH or the known install locations");
        }
        List<String> argv = List.of(located.get().toString(), "create", name,
                "-R", repoName, "-s", "bash", "-y", "--no-color");
        ProcessResult result;
        try {
            result = runner.run(argv,
                    new ProcessRunner.Options(localRepoRoot, CREATE_TIMEOUT, true, Map.of()),
                    CREATE_MAX_OUTPUT_BYTES);
        } catch (IOException e) {
            if (isCapRefusal(e)) {
                return outputTooLarge("workspaces create", CREATE_MAX_OUTPUT_BYTES, e);
            }
            return notInstalled("workspaces create", e.getMessage());
        } catch (ProcessTimeoutException e) {
            return timedOut("workspaces create", e);
        }
        if (result.exitCode() != 0) {
            return failed("workspaces create", result.exitCode(), result.stderr());
        }
        return new Created();
    }

    /**
     * Parses the observed whitespace table: the first non-blank line must
     * be a `NAME … STATUS …` header, every later `*`-prefixed line is the
     * TTL footnote and is skipped, and every other non-blank row must carry
     * a token at the header's STATUS column (columns to the right of STATUS
     * — the devcontainer repo, age, TTL — are not read, so a repo value
     * containing spaces cannot shift the columns this class depends on).
     */
    private ListResult parseTable(String stdout) {
        List<String> lines = stdout.lines().toList();
        if (lines.size() > MAX_TABLE_LINES) {
            return unparseable("the table has more than " + MAX_TABLE_LINES + " lines");
        }
        LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
        int statusColumn = -1;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            List<String> fields = List.of(line.split("\\s+"));
            if (statusColumn < 0) {
                if (!"NAME".equals(fields.get(0)) || !fields.contains("STATUS")) {
                    return unparseable("expected a NAME header with a STATUS column, first line: " + line);
                }
                statusColumn = fields.indexOf("STATUS");
                continue;
            }
            if (line.startsWith("*")) {
                continue; // the TTL footnote line
            }
            if (fields.size() <= statusColumn) {
                return unparseable("a row without a STATUS column: " + line);
            }
            statuses.put(fields.get(0), fields.get(statusColumn));
        }
        if (statusColumn < 0) {
            return unparseable("empty output: no header row");
        }
        return new Listed(statuses);
    }

    private static ListResult unparseable(String reason) {
        Unparseable outcome = new Unparseable(reason);
        LOG.log(Level.WARNING, () -> "workspaces list printed an unparseable table: "
                + ProcessRunner.excerpt(outcome.reason()));
        return outcome;
    }

    private static NotInstalled notInstalled(String what, String detail) {
        NotInstalled outcome = new NotInstalled(detail == null ? "" : detail);
        LOG.log(Level.WARNING, () -> "The workspaces CLI is not installed, so " + what
                + " was not run: " + ProcessRunner.excerpt(outcome.detail()));
        return outcome;
    }

    private static Failed failed(String what, int exitCode, String stderr) {
        Failed outcome = new Failed(exitCode, ProcessRunner.excerpt(stderr));
        LOG.log(Level.WARNING, () -> what + " failed with exit code " + exitCode + ": " + outcome.excerpt());
        return outcome;
    }

    private static TimedOut timedOut(String what, ProcessTimeoutException e) {
        TimedOut outcome = new TimedOut(ProcessRunner.excerpt(e.getMessage()));
        LOG.log(Level.WARNING, () -> what + " timed out and was killed: " + outcome.excerpt());
        return outcome;
    }

    private static OutputTooLarge outputTooLarge(String what, int cap, IOException e) {
        OutputTooLarge outcome = new OutputTooLarge(ProcessRunner.excerpt(e.getMessage()));
        LOG.log(Level.WARNING, () -> what + " produced more than the " + cap
                + "-byte output cap and was refused: " + outcome.excerpt());
        return outcome;
    }

    private static boolean isCapRefusal(IOException e) {
        return e.getMessage() != null && e.getMessage().startsWith(OUTPUT_CAP_PREFIX);
    }
}

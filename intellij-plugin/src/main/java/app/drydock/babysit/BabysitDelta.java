package app.drydock.babysit;

import app.drydock.babysit.BabysitWriteGuard.Verdict;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Review;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A delta file: what the monitor tells a babysitter when it wakes it. The
 * wake prompt itself is one line pointing here ({@link #pointer}); this
 * file carries what happened, the PR as just read, whether the babysitter
 * may write the branch right now, and the rerun and escalation rules.
 *
 * <p>Delta files sit beside the instructions file ({@link
 * BabysitInstructions#fileFor}), named {@code
 * <watchedId>-pr<n>-delta-<epochMillis, 13 digits>.md} so names sort by
 * time; only the newest {@link #KEEP} of a pairing are kept. Strings that
 * come from GitHub (branch and check names, URLs) are flattened to one line,
 * stripped of backticks and capped before they reach the file.</p>
 *
 * <p>{@link #render} and {@link #pruneable} are pure; the file methods
 * block.</p>
 */
public final class BabysitDelta {

    /** How many delta files of one pairing survive a prune. */
    public static final int KEEP = 5;

    /** How many failing checks a delta lists by name. */
    static final int MAX_LISTED_CHECKS = 30;

    private static final int MAX_INLINE = 200;

    private BabysitDelta() {
    }

    /** Whether the babysitter may write the branch with this delta, from the write guard's verdict. */
    public static boolean grantsWrite(Verdict verdict) {
        return verdict instanceof Verdict.Allowed;
    }

    /** {@code <watchedId>-pr<n>-delta-}: the name prefix of one pairing's delta files. */
    public static String prefix(ManagedSessionId watchedId, int prNumber) {
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
        }
        return watchedId + "-pr" + prNumber + "-delta-";
    }

    /** The file name of a delta written at {@code epochMillis}. */
    public static String fileName(ManagedSessionId watchedId, int prNumber, long epochMillis) {
        return prefix(watchedId, prNumber) + String.format(Locale.ROOT, "%013d", epochMillis) + ".md";
    }

    /** The directory delta (and instructions) files live in. */
    public static Path directory(Path baseDir) {
        return BabysitStateFile.defaultFile(baseDir).getParent();
    }

    /** The single-line wake prompt pointing the babysitter at {@code deltaFile}. */
    public static String pointer(int prNumber, Path deltaFile) {
        return "Drydock babysitting update for PR #" + prNumber + ": read "
                + deltaFile.toAbsolutePath().normalize() + " and act only as it allows.";
    }

    /**
     * The delta's markdown.
     *
     * @param verdict      the write guard's verdict this delta carries; only
     *                     {@link Verdict.Allowed} grants a write
     * @param instructions the babysitter's standing instructions file
     */
    public static String render(BabysitPairing pairing, Snapshot snapshot, List<BabysitEvent> events,
                                Verdict verdict, Path instructions, Instant at) {
        return render(pairing, snapshot, events, verdict, instructions, at, Optional.empty());
    }

    /**
     * How a babysitter woken headlessly while the IDE is closed reaches
     * Drydock instead of the MCP tools: request files in {@code dropBox}
     * ({@link BabysitDropBox}). {@code results} are one line each about the
     * requests of its earlier runs, reported once.
     */
    public record Detached(Path dropBox, List<String> results) {
        public Detached {
            Objects.requireNonNull(dropBox, "dropBox");
            results = List.copyOf(results);
        }
    }

    /**
     * As {@link #render(BabysitPairing, Snapshot, List, Verdict, Path, Instant)};
     * with {@code detached} present, the rerun and escalation sections name
     * the drop box instead of the MCP tools, which a headless run does not
     * have, and say so overriding the standing instructions.
     */
    public static String render(BabysitPairing pairing, Snapshot snapshot, List<BabysitEvent> events,
                                Verdict verdict, Path instructions, Instant at, Optional<Detached> detached) {
        Objects.requireNonNull(detached, "detached");
        Objects.requireNonNull(pairing, "pairing");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(at, "at");
        String branch = code(snapshot.headRefName());
        String base = code(snapshot.baseRefName());
        StringBuilder out = new StringBuilder(2048);
        out.append("# Drydock babysitting update: PR #").append(pairing.prNumber()).append("\n\n");
        out.append("Written ").append(at).append(" for the babysitter ").append(pairing.babysitterId())
                .append(" of session ").append(pairing.watchedId()).append(".\n\n");

        out.append("## What happened\n\n");
        for (BabysitEvent event : events) {
            out.append("- ").append(event.describe()).append(": ");
            switch (event.kind()) {
                case CONFLICT_APPEARED -> out.append("the branch ").append(branch)
                        .append(" no longer merges cleanly into ").append(base).append('.');
                case CONFLICT_CLEARED -> out.append("the branch merges cleanly into ").append(base)
                        .append(" again; nothing is left to resolve.");
                case CHECKS_FAILED -> out.append(String.join(", ", event.subjects().stream()
                        .limit(MAX_LISTED_CHECKS).map(BabysitDelta::code).toList()))
                        .append(event.subjects().size() > MAX_LISTED_CHECKS
                                ? " and " + (event.subjects().size() - MAX_LISTED_CHECKS) + " more" : "")
                        .append('.');
                case CHECKS_RECOVERED -> out.append("every check has concluded and none is failing;"
                        + " nothing is left to fix or rerun.");
                case REVIEW_ARRIVED -> {
                    out.append(String.join("\n\n", event.subjects().stream()
                            .limit(MAX_LISTED_CHECKS).toList()));
                    if (event.subjects().size() > MAX_LISTED_CHECKS) {
                        out.append("\n\nand ").append(event.subjects().size() - MAX_LISTED_CHECKS).append(" more.");
                    }
                    out.append("\n\nRead the review, and escalate it to the watched session; do not act on"
                            + " review feedback yourself.");
                }
                case FIRST_STATUS -> out.append("this is the first update for this engagement: the pull request"
                        + " as it stands, and nothing has changed since babysitting began.");
            }
            out.append('\n');
        }

        out.append("\n## The pull request now\n\n");
        out.append("- State: ").append(snapshot.lifecycle()).append(snapshot.draft() ? " (draft)" : "").append('\n');
        out.append("- Base ").append(base).append("; head ").append(branch).append(" at `")
                .append(snapshot.headRefOid()).append("`\n");
        out.append("- Mergeable: ").append(snapshot.mergeable()).append(" (merge state ")
                .append(code(snapshot.mergeStateStatus())).append(")\n");
        List<Check> failing = snapshot.checks().stream().filter(BabysitMonitorRules::failing).toList();
        long open = snapshot.checks().stream().filter(check -> !BabysitMonitorRules.concluded(check)).count();
        out.append("- Checks: ").append(snapshot.checks().size()).append(" reported, ").append(failing.size())
                .append(" failing, ").append(open).append(" not concluded\n");
        List<Review> reviews = snapshot.reviews();
        long approved = reviews.stream().filter(review -> "APPROVED".equals(review.state())).count();
        long requestingChanges = reviews.stream().filter(review -> "CHANGES_REQUESTED".equals(review.state())).count();
        long commenting = reviews.stream().filter(review -> "COMMENTED".equals(review.state())).count();
        out.append("- Reviews: ").append(reviews.size()).append(" actionable (").append(approved)
                .append(" approved, ").append(requestingChanges).append(" requesting changes, ").append(commenting)
                .append(" commenting)\n");
        for (Check check : failing.stream().limit(MAX_LISTED_CHECKS).toList()) {
            out.append("  - ").append(code(BabysitMonitorRules.checkKey(check))).append(": ")
                    .append(code(check.conclusion().isEmpty() ? check.status() : check.conclusion()));
            check.runId().ifPresent(runId -> out.append(", run ").append(runId));
            check.detailsUrl().ifPresent(url -> out.append(", ").append(code(url)));
            out.append('\n');
        }
        if (failing.size() > MAX_LISTED_CHECKS) {
            out.append("  - and ").append(failing.size() - MAX_LISTED_CHECKS).append(" more\n");
        }

        out.append("\n## What you may do now\n\n");
        if (verdict instanceof Verdict.Allowed allowed) {
            String tip = allowed.remoteTip();
            out.append("You MAY write the pull request's branch: the watched session has no running agent and"
                    + " the remote branch is at `").append(tip).append("`, the head Drydock last read.\n\n");
            out.append("1. Start from exactly that commit: `git fetch origin ").append(plain(snapshot.headRefName()))
                    .append("`, then `git checkout --detach ").append(tip).append("`.\n");
            out.append("2. Fix only what your instructions allow: a mechanical conflict resolution by rebasing"
                    + " onto `origin/").append(plain(snapshot.baseRefName())).append("`, or a small mechanical fix"
                    + " inside the PR's own changes. Anything else: escalate instead.\n");
            out.append("3. Push with `git push --force-with-lease=").append(plain(snapshot.headRefName()))
                    .append(':').append(tip).append(" origin HEAD:").append(plain(snapshot.headRefName()))
                    .append("`. If the push is rejected, someone else pushed: stop, and wait for the next update.\n");
        } else {
            out.append("You may NOT write the pull request's branch now: ").append(readOnlyReason(verdict))
                    .append(". Do not commit to, rebase or push the branch, and do not rerun CI on the strength"
                            + " of this update. You may read logs and plan. Drydock checks again and sends"
                            + " a new update when you may act.\n");
        }

        if (detached.isPresent()) {
            appendDetached(out, detached.get());
        } else {
            out.append("\n## CI reruns\n\n");
            out.append("Judge a failure by its log first: `gh run view <runId> --log-failed`. Only an environment"
                    + " or flaky failure (timeouts, network, runner infrastructure, no relation to the PR's changes)"
                    + " is rerun, with the `ci_rerun` tool (`runId`, optional `checkName`). At most ")
                    .append(BabysitStateFile.MAX_RERUNS_PER_RUN)
                    .append(" reruns per run; when `ci_rerun` refuses at the cap, do not retry: escalate with"
                            + " `babysit_escalate`. A real failure you cannot fix mechanically is escalated too.\n");

            out.append("\n## Escalate\n\n");
            out.append("Design decisions, deep changes, non-mechanical conflicts and real test failures go back to"
                    + " the watched session through `babysit_escalate`: what happened, what you already did, and"
                    + " what the main session needs to decide or do.\n\n");
        }
        out.append("Your standing instructions: ").append(instructions.toAbsolutePath().normalize())
                .append(". When you are done with this update, stop and wait for the next one.\n");
        return out.toString();
    }

    private static void appendDetached(StringBuilder out, Detached detached) {
        Path box = detached.dropBox().toAbsolutePath().normalize();
        int cap = BabysitStateFile.MAX_RERUNS_PER_RUN;
        out.append("\n## The IDE is closed: no Drydock tools in this run\n\n");
        out.append("This update comes from Drydock's detached watcher. The `ci_rerun` and `babysit_escalate`"
                + " tools do NOT exist in this run, whatever your standing instructions say; for this update,"
                + " the two request files below replace them. Write them with your file-writing tool into ")
                .append(box).append(". Drydock reads them when this run ends.\n");
        out.append("\n## CI reruns\n\n");
        out.append("Judge a failure by its log first: `gh run view <runId> --log-failed`. Only an environment"
                + " or flaky failure (timeouts, network, runner infrastructure, no relation to the PR's changes)"
                + " is rerun: create the file `").append(box).append("/rerun-<runId>` (the workflow run id,"
                + " digits only), with the check's name on its first line if you know it. Drydock checks that"
                + " the run belongs to the pull request's head, counts it against the cap of ").append(cap)
                .append(" reruns per run, and reruns the run's failed jobs; at the cap it escalates for you."
                        + " Never rerun with `gh run rerun` or the API yourself. The results come with your next"
                        + " update. A real failure you cannot fix mechanically is escalated.\n");
        out.append("\n## Escalate\n\n");
        out.append("Design decisions, deep changes, non-mechanical conflicts and real test failures go back to"
                + " the watched session: create a new file `").append(box).append("/escalation-<short-name>.md`"
                + " saying what happened, what you already did, and what the main session needs to decide or"
                + " do. One file per problem; at most ").append(BabysitDropBox.MAX_ESCALATION_CHARS)
                .append(" characters are kept.\n");
        if (!detached.results().isEmpty()) {
            out.append("\n## Your earlier requests\n\n");
            for (String result : detached.results()) {
                out.append("- ").append(plain(result)).append('\n');
            }
        }
        out.append('\n');
    }

    private static String readOnlyReason(Verdict verdict) {
        return switch (verdict) {
            case Verdict.HeadMoved moved -> "the remote branch is at `" + plain(moved.remoteTip())
                    + "`, not at `" + plain(moved.knownBase()) + "` that Drydock last read -- someone pushed,"
                    + " and Drydock reads the pull request again before you may write";
            case Verdict.WatchedBusy busy -> "the watched session has a running agent (or one is starting),"
                    + " and its work must not be raced";
            case Verdict.GitFailed failed -> "Drydock could not confirm the remote branch tip ("
                    + plain(failed.message()) + ")";
            case Verdict.Allowed allowed -> throw new IllegalArgumentException("Allowed is not read-only");
        };
    }

    /** {@code value} as inline code: one line, no backticks, capped. */
    private static String code(String value) {
        return "`" + plain(value) + "`";
    }

    /** {@code value} flattened to one line with no control characters or backticks, capped. */
    static String plain(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_INLINE + 1));
        for (int i = 0; i < value.length(); i++) {
            if (out.length() >= MAX_INLINE) {
                out.append('…');
                break;
            }
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c == '`' ? '\'' : c);
        }
        return out.toString();
    }

    /**
     * The names among {@code names} to delete so that only the newest {@code
     * keep} delta files with {@code prefix} remain; names that are not such
     * delta files are never returned. Oldest first.
     */
    public static List<String> pruneable(List<String> names, String prefix, int keep) {
        if (keep < 0) {
            throw new IllegalArgumentException("keep must not be negative: " + keep);
        }
        Pattern ours = Pattern.compile(Pattern.quote(prefix) + "\\d{13,18}\\.md");
        List<String> deltas = new ArrayList<>();
        for (String name : names) {
            if (ours.matcher(name).matches()) {
                deltas.add(name);
            }
        }
        deltas.sort(null);
        return List.copyOf(deltas.subList(0, Math.max(0, deltas.size() - keep)));
    }

    /**
     * Writes {@code content} as a new delta file of the pairing, named for
     * {@code at} (a later millisecond when that name is taken or a newer
     * delta of the pairing exists), through a
     * temp file renamed into place so the babysitter never reads half a
     * delta. Blocking.
     */
    public static Path write(Path baseDir, ManagedSessionId watchedId, int prNumber, Instant at, String content)
            throws IOException {
        Path dir = directory(baseDir);
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "delta", ".md.tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            // Never before the pairing's newest delta, even when the clock is
            // behind it: names must sort in write order for the prune.
            long millis = Math.max(at.toEpochMilli(), newestMillis(dir, prefix(watchedId, prNumber)) + 1);
            for (int attempt = 0; attempt < 100; attempt++) {
                Path target = dir.resolve(fileName(watchedId, prNumber, millis + attempt));
                try {
                    Files.move(temp, target);
                    return target;
                } catch (FileAlreadyExistsException taken) {
                    // the next millisecond
                }
            }
            throw new IOException("No free delta file name near " + at + " in " + dir);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Deletes the pairing's delta files beyond the newest {@code keep}. Blocking. */
    public static void prune(Path baseDir, ManagedSessionId watchedId, int prNumber, int keep) throws IOException {
        Path dir = directory(baseDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = prefix(watchedId, prNumber);
        for (String name : pruneable(namesIn(dir, prefix), prefix, keep)) {
            Files.deleteIfExists(dir.resolve(name));
        }
    }

    private static List<String> namesIn(Path dir, String prefix) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, prefix + "*.md")) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        }
        return names;
    }

    /** The millisecond of the pairing's newest delta file in {@code dir}; -1 when it has none. */
    private static long newestMillis(Path dir, String prefix) throws IOException {
        List<String> deltas = pruneable(namesIn(dir, prefix), prefix, 0);
        if (deltas.isEmpty()) {
            return -1;
        }
        String newest = deltas.get(deltas.size() - 1);
        return Long.parseLong(newest, prefix.length(), newest.length() - ".md".length(), 10);
    }

    /** Deletes every delta file of the pairing and its instructions file: the engagement has ended. Blocking. */
    public static void deleteAll(Path baseDir, ManagedSessionId watchedId, int prNumber) throws IOException {
        prune(baseDir, watchedId, prNumber, 0);
        Files.deleteIfExists(BabysitInstructions.fileFor(baseDir, watchedId, prNumber));
    }
}

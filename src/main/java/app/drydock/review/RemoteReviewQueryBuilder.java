package app.drydock.review;

import app.drydock.domain.SshRemote;
import app.drydock.git.DiffScope;
import app.drydock.git.GitReviewQueryRules;
import app.drydock.process.SshCommandBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * Builds the read-only Git argv lists a remote review runs over SSH. The
 * Git arguments come from {@link GitReviewQueryRules} (the rules the local
 * diff uses) and are wrapped by {@link SshCommandBuilder#remoteGitCommand},
 * which quotes every positional for the remote shell. Every revision/path
 * positional follows {@code --end-of-options}, so a hostile branch or path
 * beginning with {@code -} reaches Git as an operand. Only query
 * subcommands are produced: never fetch, checkout, reset or an index write.
 */
public final class RemoteReviewQueryBuilder {
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    /** Placeholder root so the shared rules can be reused; the remote path is applied by {@code -C}. */
    private static final Path RULES_ROOT = Path.of("/");
    private static final int RULES_PREFIX = 3; // git -C <root>

    private final SshRemote remote;

    public RemoteReviewQueryBuilder(SshRemote remote) {
        this.remote = remote;
    }

    /** The pinned HEAD commit; later queries use its id, never the moving name {@code HEAD}. */
    public List<String> headCommand() {
        return wrap(List.of("rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"));
    }

    /** Resolves {@code ref} to a commit, or fails (non-zero exit) when it names none. */
    public List<String> resolveCommand(String ref) {
        return wrap(List.of("rev-parse", "--verify", "--end-of-options", ref + "^{commit}"));
    }

    public List<String> mergeBaseCommand(String baseBranch, String headOid) {
        requireOid(headOid);
        List<String> args = new ArrayList<>(GitReviewQueryRules.mergeBaseCommand("git", RULES_ROOT, baseBranch)
                .subList(RULES_PREFIX, 7));
        args.set(args.size() - 1, headOid);
        return wrap(args);
    }

    /**
     * The diff for {@code scope}, its right side pinned to {@code headOid}.
     * {@code mergeBaseOid} is required for {@link DiffScope#BRANCH_WORKING_TREE}
     * (the worktree is then compared against the fork point, not the base tip).
     */
    public List<String> diffCommand(DiffScope scope, String baseBranch, int contextLines, String headOid,
                                    Optional<String> mergeBaseOid) {
        requireOid(headOid);
        Supplier<String> mergeBase = () -> {
            String oid = mergeBaseOid.orElseThrow(() -> new IllegalArgumentException("merge base required"));
            requireOid(oid);
            return oid;
        };
        List<String> rules = GitReviewQueryRules.diffCommand("git", RULES_ROOT, scope, baseBranch,
                contextLines, mergeBase);
        List<String> args = new ArrayList<>(rules.subList(RULES_PREFIX, rules.size()));
        int last = args.size() - 1;
        String range = args.get(last);
        if (range.equals("HEAD") || range.endsWith("...HEAD")) {
            args.set(last, range.substring(0, range.length() - "HEAD".length()) + headOid);
        }
        return wrap(args);
    }

    public List<String> stagedCommand() {
        return wrap(GitReviewQueryRules.stagedCommand("git", RULES_ROOT).subList(RULES_PREFIX, 7));
    }

    public List<String> untrackedCommand() {
        return wrap(GitReviewQueryRules.untrackedCommand("git", RULES_ROOT).subList(RULES_PREFIX, 7));
    }

    public List<String> commitsAheadCommand(String base, String headOid) {
        requireOid(headOid);
        List<String> args = new ArrayList<>(GitReviewQueryRules.commitsAheadArguments(base));
        args.set(args.size() - 1, base + ".." + headOid);
        return wrap(args);
    }

    /** The left-side path of a changed file: a rename's pre-image path, else the path itself. */
    public static String leftPath(String path, Optional<String> oldPath) {
        return oldPath.orElse(path);
    }

    /** Bytes of {@code path} at commit {@code oid}; {@code oid:path} is one operand after {@code --end-of-options}. */
    public List<String> blobCommand(String oid, String path) {
        requireOid(oid);
        if (path.isEmpty() || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid path");
        }
        return wrap(List.of("cat-file", "--end-of-options", "blob", oid + ":" + path));
    }

    private List<String> wrap(List<String> gitArgs) {
        return SshCommandBuilder.remoteGitCommand(remote, gitArgs);
    }

    private static void requireOid(String oid) {
        if (!OBJECT_ID.matcher(oid).matches()) {
            throw new IllegalArgumentException("not a Git object id: " + oid);
        }
    }
}

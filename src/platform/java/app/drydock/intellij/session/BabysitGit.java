package app.drydock.intellij.session;

import app.drydock.config.UserConfig;
import app.drydock.git.GitStatusService;
import app.drydock.git.GitStatusService.DetachedWorktree;
import app.drydock.git.GitStatusService.RemoteBranchTip;
import app.drydock.git.WorktreeNaming;
import app.drydock.intellij.settings.DrydockConfigurable;

import java.nio.file.Path;

/**
 * The git and filesystem half of babysitting that {@link DrydockSessionService}
 * needs: where a babysitter's worktree goes, creating and undoing it, and
 * reading a PR branch's remote tip. Every method blocks (a config read, a
 * fetch, a checkout); callers run them on the plugin executor, never the EDT.
 * A seam so the service's babysitting flow is testable without a network.
 */
interface BabysitGit {

    /** The babysitter worktree directory for {@code prNumber} in the repository named {@code repositoryName}. */
    Path directoryFor(String repositoryName, int prNumber);

    /** See {@link GitStatusService#addDetachedWorktreeAtRemoteBranchBlocking}. */
    DetachedWorktree addDetached(Path repositoryRoot, Path directory, String branch);

    /** See {@link GitStatusService#removeWorktreeQuietly}. */
    void removeQuietly(Path repositoryRoot, Path directory);

    /** See {@link GitStatusService#pruneWorktreesQuietly}. */
    void pruneQuietly(Path repositoryRoot);

    /** See {@link GitStatusService#remoteBranchTipBlocking}. */
    RemoteBranchTip remoteTip(Path repositoryRoot, String branch);

    /**
     * The real thing: the user's configured worktree base (the same one new
     * tasks use), and a {@link GitStatusService} per call, closed with it --
     * the StackLaunch idiom, so nothing here outlives the call.
     */
    BabysitGit PRODUCTION = new BabysitGit() {
        @Override
        public Path directoryFor(String repositoryName, int prNumber) {
            Path base = DrydockConfigurable.effectiveBase(UserConfig.load().worktreesDirectory(),
                    Path.of(System.getProperty("user.home")));
            return WorktreeNaming.babysitterDirectory(base, repositoryName, prNumber);
        }

        @Override
        public DetachedWorktree addDetached(Path repositoryRoot, Path directory, String branch) {
            try (GitStatusService git = new GitStatusService()) {
                return git.addDetachedWorktreeAtRemoteBranchBlocking(repositoryRoot, directory, branch);
            }
        }

        @Override
        public void removeQuietly(Path repositoryRoot, Path directory) {
            try (GitStatusService git = new GitStatusService()) {
                git.removeWorktreeQuietly(repositoryRoot, directory);
            }
        }

        @Override
        public void pruneQuietly(Path repositoryRoot) {
            try (GitStatusService git = new GitStatusService()) {
                git.pruneWorktreesQuietly(repositoryRoot);
            }
        }

        @Override
        public RemoteBranchTip remoteTip(Path repositoryRoot, String branch) {
            try (GitStatusService git = new GitStatusService()) {
                return git.remoteBranchTipBlocking(repositoryRoot, branch);
            }
        }
    };
}

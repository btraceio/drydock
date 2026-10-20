package app.drydock.git;

import app.drydock.domain.Repository;
import app.drydock.domain.SshRemote;

import java.nio.file.Path;

/**
 * Where a git query should execute: a local working tree, or a repository
 * on a remote host over SSH. Introduced (spec: SSH remote repositories)
 * because {@link GitStatusService} was {@link Path}-keyed and a remote
 * repo's placeholder root must never reach the local git.
 */
public sealed interface GitTarget {

    record Local(Path root) implements GitTarget { }

    record Remote(SshRemote remote) implements GitTarget { }

    static GitTarget of(Repository repository) {
        return repository.isRemote() ? new Remote(repository.remote()) : new Local(repository.root());
    }
}

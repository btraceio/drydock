package app.drydock.domain;

import app.drydock.workspace.WorkspaceBoxNames;

import java.util.Objects;

/**
 * The Datadog workspace box a session runs on: the row's single source of
 * remote truth. A row is remote iff this binding is present (see {@link
 * ManagedAgentSession#workspaceBox()}).
 *
 * <p>{@code boxName} is the name the {@code workspaces} CLI knows the box
 * under. It must pass {@link WorkspaceBoxNames#validate} — the box-name
 * guard of INV-6: a validated name is the alternative to {@code --}
 * escaping, because a name matching {@code ^[a-z0-9][a-z0-9-]{0,62}$} can
 * never parse as an option flag. This record is that guard's one call site
 * (the contract's interface list), so no persisted or CLI-bound box name
 * can bypass it.</p>
 *
 * <p>{@code boxWorkingPath} is the repo clone on the box, an absolute
 * remote path kept as a {@link String} like {@link SshRemote#remotePath()}
 * — remote paths are not local paths and must never be resolved against
 * the local filesystem.</p>
 */
public record WorkspaceBoxBinding(String boxName, String boxWorkingPath) {

    public WorkspaceBoxBinding {
        Objects.requireNonNull(boxName, "boxName");
        Objects.requireNonNull(boxWorkingPath, "boxWorkingPath");
        if (!WorkspaceBoxNames.validate(boxName)) {
            throw new IllegalArgumentException(
                    "WorkspaceBoxBinding boxName is not a valid box name: " + boxName);
        }
        if (boxWorkingPath.isBlank() || !boxWorkingPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "WorkspaceBoxBinding boxWorkingPath must be absolute: " + boxWorkingPath);
        }
    }

    /**
     * The box as an {@link SshRemote}: the SSH alias {@code workspaces
     * create} writes for the box (ASM-9, resolved through {@code
     * ~/.ssh/workspaces/*}), with the box clone as the remote path.
     */
    public SshRemote sshRemote() {
        return new SshRemote("workspace-" + boxName, boxWorkingPath);
    }
}

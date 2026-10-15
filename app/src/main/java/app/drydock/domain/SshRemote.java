package app.drydock.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A repository that lives on a remote machine reached over SSH (spec:
 * docs/superpowers/specs/2026-07-21-ssh-remote-repos-design.md).
 *
 * <p>{@code host} is handed to {@code ssh} as its destination — an
 * {@code ~/.ssh/config} alias or {@code user@hostname} — so ports, keys and
 * jump hosts all come from the user's own SSH config. A leading {@code -}
 * is rejected here (not just at the UI) because a host that parses as an
 * ssh <em>option</em> is an argument-injection vector; the command builder
 * additionally always places {@code --} before the destination.</p>
 *
 * <p>{@code remotePath} is the resolved repo toplevel on that host, kept as
 * a {@link String}: remote paths are not local {@link Path}s and must never
 * be resolved against the local filesystem.</p>
 */
public record SshRemote(String host, String remotePath) {

    public SshRemote {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(remotePath, "remotePath");
        if (host.isBlank()) {
            throw new IllegalArgumentException("SSH host must not be blank");
        }
        if (host.startsWith("-")) {
            throw new IllegalArgumentException("SSH host must not start with '-': " + host);
        }
        if (remotePath.isBlank() || !remotePath.startsWith("/")) {
            throw new IllegalArgumentException("Remote path must be absolute: " + remotePath);
        }
    }

    /**
     * The deterministic virtual local {@link Path} standing in for this
     * remote repo's {@code Repository.root}: unique per (host, remotePath),
     * absolute, normalized, and under a {@code .drydock-remote} prefix no
     * real checkout plausibly occupies. Percent-encoding the host keeps it
     * a single path element, so a hostile alias cannot escape the prefix.
     * The existing canonical-root duplicate detection then works unchanged
     * (nonexistent paths compare by normalized string).
     *
     * <p>Anchored at the user's drive root: a bare {@code "/.drydock-remote"}
     * has no root on Windows (a drive-relative path), which the domain's
     * absolute-path contract rejects. The user home's root keeps the anchor
     * stable per machine regardless of the app's working directory, and on
     * unix it is {@code /}, so the macOS spelling is unchanged.</p>
     */
    public Path placeholderRoot() {
        String encodedHost = URLEncoder.encode(host, StandardCharsets.UTF_8);
        Path anchor = Path.of(System.getProperty("user.home")).toAbsolutePath().getRoot();
        return anchor.resolve(".drydock-remote").resolve(encodedHost).resolve(remotePath.substring(1)).normalize();
    }
}

package app.drydock.git;

/**
 * The ssh transport itself failed (exit code 255: DNS, connect, auth, host
 * key) — as opposed to git failing on the far side. The sidebar maps this
 * to a quiet "unreachable" state rather than an error dialog; the add flow
 * classifies {@link #stderr()} into a specific user-facing message.
 */
public final class SshUnreachableException extends GitException {

    private final String host;
    private final String stderr;

    public SshUnreachableException(String host, String stderr) {
        super("SSH host unreachable: " + host + (stderr.isBlank() ? "" : " — " + stderr));
        this.host = host;
        this.stderr = stderr;
    }

    public String host() {
        return host;
    }

    public String stderr() {
        return stderr;
    }
}

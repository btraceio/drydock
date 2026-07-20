package app.cpm.process;

/**
 * The captured outcome of one finished child process run by
 * {@link ProcessRunner}: its exit code plus fully drained stdout and
 * stderr, decoded as UTF-8.
 */
public record ProcessResult(int exitCode, String stdout, String stderr) {
}

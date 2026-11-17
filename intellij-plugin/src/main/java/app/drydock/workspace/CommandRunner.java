package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.util.List;

/**
 * The box-independent seam for every external command the workspace-box
 * machinery runs (ssh, scp, the workspaces CLI, git): one run-to-completion
 * call carrying an argument list (never a shell string), an explicit
 * timeout, and a bounded output cap, per the child-process rules in the
 * root AGENTS.md. Everything downstream in this package is written against
 * the interface, so tests inject fakes (a lambda or a small class) and no
 * test ever spawns a child.
 *
 * <p>Production callers use {@link #PRODUCTION}, whose single entry point
 * delegates unchanged to {@link ProcessRunner#run(List, ProcessRunner.Options, int)}.</p>
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Runs {@code argv} to completion and returns its exit code and drained
     * output, refusing output larger than {@code maxOutputBytes} bytes.
     * Blocking — never call on the EDT. Failure modes propagate for the
     * caller to translate into its own typed, logged outcome: {@link IOException}
     * (could not launch, or output exceeded the cap), {@link InterruptedException}
     * (caller cancelled; the child is already destroyed), or {@link ProcessTimeoutException}
     * (hung past the timeout and forcibly destroyed). A failed run is never
     * silently an empty result.
     */
    ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
            throws IOException, InterruptedException;

    /** The production seam, shared by every caller: pure delegation to {@link ProcessRunner}. */
    CommandRunner PRODUCTION = new Production();

    /**
     * The production implementation. The {@link ProcessRunner} call sits
     * behind the protected {@link #processRun} seam so tests can pin the
     * delegation contract — argv list, options, cap, result and exceptions
     * pass through unchanged — without spawning a child process.
     */
    class Production implements CommandRunner {

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            return processRun(argv, options, maxOutputBytes);
        }

        /** The one {@link ProcessRunner} entry point this seam ever uses. */
        protected ProcessResult processRun(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            return ProcessRunner.run(argv, options, maxOutputBytes);
        }
    }
}

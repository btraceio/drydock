package app.drydock.intellij.editorqa;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.drydock.editorqa.EditorAskLimits;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.WorktreeService;

/**
 * Resolves which checkout owns a file, off the EDT, before any question is
 * accepted.
 *
 * <p>The Git top level is asked from the file's own enclosing directory
 * ({@code GitStatusService.resolveRepositoryRoot}); the top level and the
 * file are then canonicalized with {@code toRealPath} and containment is
 * required, so a symlink spelling of the same checkout is the same checkout
 * and a file whose real location is outside the working tree is refused. The
 * relative path is derived from the two canonical paths, never typed.</p>
 *
 * <p>The <em>owning</em> checkout is the canonical top level: two linked
 * worktrees that share a directory name are two checkouts. The main checkout
 * ({@code WorktreeService.checkoutOf(..).mainCheckout()}) is only the
 * catalog association of the managed row. {@code checkoutOf} cannot name a
 * main checkout for a bare or separate-git-dir repository nor for an
 * unborn HEAD, and that is no proof that a valid local working tree is
 * remote or absent: when git ran and simply had no association to report,
 * the verified top level is registered as its own catalog root
 * ({@link Resolution#topLevelIsCatalogRoot()}). A failure that is not a git
 * verdict (timeout, launch failure, interrupt) is a refusal, as is any
 * ambiguity -- an association whose directory is gone, or a file that moved
 * while resolving -- because sending to another checkout is worse than not
 * sending.</p>
 *
 * <p>Blocking: never call from the EDT.</p>
 */
final class EditorAskCheckoutResolver {

    /** How long one git question may take before the ask is refused. */
    static final long PROBE_TIMEOUT_SECONDS = 20;

    /** The two git questions, narrow so tests can answer or block them. */
    interface GitProbe {

        /** The working-tree top level enclosing {@code directory}; empty when it is not in a working tree. */
        Optional<Path> topLevel(Path directory) throws IOException;

        /**
         * The main checkout of the repository {@code topLevel} belongs to;
         * empty when git ran but could name none (bare, separate git dir,
         * unborn HEAD).
         */
        Optional<Path> mainCheckout(Path topLevel) throws IOException;
    }

    /**
     * The verified owner of a file.
     *
     * @param checkout canonical working-tree top level: the identity key
     * @param catalogRoot canonical root the managed row is associated with
     *        (the main checkout, or {@code checkout} itself)
     * @param topLevelIsCatalogRoot true when no main-checkout association
     *        existed and the top level must be registered as the catalog root
     * @param relativePath {@code /}-separated path of the file under {@code checkout}
     */
    record Resolution(Path checkout, Path catalogRoot, boolean topLevelIsCatalogRoot, String relativePath) {
        Resolution {
            Objects.requireNonNull(checkout, "checkout");
            Objects.requireNonNull(catalogRoot, "catalogRoot");
            Objects.requireNonNull(relativePath, "relativePath");
        }
    }

    /** Resolved owner, or the reason there is none. */
    sealed interface Outcome permits Resolved, Refused {
    }

    record Resolved(Resolution resolution) implements Outcome {
    }

    record Refused(String reason) implements Outcome {
    }

    private final GitProbe git;

    EditorAskCheckoutResolver(GitProbe git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    /** The production resolver, backed by the real git services. */
    static EditorAskCheckoutResolver production() {
        return new EditorAskCheckoutResolver(new CliProbe());
    }

    Outcome resolve(Path file) {
        try {
            Path spelled = file.toAbsolutePath().normalize();
            Path directory = spelled.getParent();
            if (directory == null) {
                return new Refused("The file has no enclosing directory");
            }
            Path real = spelled.toRealPath();
            Optional<Path> top = git.topLevel(directory);
            if (top.isEmpty()) {
                return new Refused("The file is not in a Git working tree");
            }
            Path realTop = top.get().toRealPath();
            if (!real.startsWith(realTop) || real.equals(realTop)) {
                return new Refused("The file resolves outside its Git working tree");
            }
            Optional<Path> main = git.mainCheckout(realTop);
            Path catalog = main.isPresent() ? main.get().toRealPath() : realTop;
            String relative = relativize(realTop, real);
            EditorAskLimits.checkRoot(realTop.toString());
            EditorAskLimits.checkRoot(catalog.toString());
            EditorAskLimits.checkRelativePath(relative);
            if (!spelled.toRealPath().equals(real)) {
                return new Refused("The file changed location while its checkout was being resolved");
            }
            return new Resolved(new Resolution(realTop, catalog, main.isEmpty(), relative));
        } catch (EditorAskLimits.RejectedInputException e) {
            return new Refused(e.getMessage());
        } catch (IOException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Refused("Could not determine the file's checkout: " + detail);
        }
    }

    private static String relativize(Path root, Path file) {
        Path relative = root.relativize(file);
        StringBuilder joined = new StringBuilder();
        for (Path part : relative) {
            if (!joined.isEmpty()) {
                joined.append('/');
            }
            joined.append(part);
        }
        return joined.toString();
    }

    /**
     * The real probe: one short-lived service per question (the repository's
     * usual shape), bounded by {@link #PROBE_TIMEOUT_SECONDS}. A git verdict
     * becomes an empty result; anything that is not a verdict becomes an
     * {@link IOException}.
     */
    private static final class CliProbe implements GitProbe {

        @Override
        public Optional<Path> topLevel(Path directory) throws IOException {
            try (GitStatusService service = new GitStatusService()) {
                return Optional.of(service.resolveRepositoryRoot(directory)
                        .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NotAGitRepositoryException) {
                    return Optional.empty();
                }
                if (isVerdict(cause)) {
                    return Optional.empty();
                }
                throw failure("git rev-parse", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while asking git", e);
            } catch (TimeoutException e) {
                throw new IOException("git did not answer in " + PROBE_TIMEOUT_SECONDS + "s", e);
            }
        }

        @Override
        public Optional<Path> mainCheckout(Path topLevel) throws IOException {
            try (WorktreeService service = new WorktreeService()) {
                return Optional.of(service.checkoutOf(topLevel)
                        .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).mainCheckout());
            } catch (ExecutionException e) {
                if (isVerdict(e.getCause())) {
                    return Optional.empty();
                }
                throw failure("git worktree association", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while asking git", e);
            } catch (TimeoutException e) {
                throw new IOException("git did not answer in " + PROBE_TIMEOUT_SECONDS + "s", e);
            }
        }

        /** Git ran to an answer (any real exit code); -1 is timeout, kill or launch failure. */
        private static boolean isVerdict(Throwable cause) {
            return cause instanceof GitCommandFailedException failed && failed.exitCode() >= 0;
        }

        private static IOException failure(String what, Throwable cause) {
            String detail = cause == null || cause.getMessage() == null ? "unknown failure"
                    : cause.getMessage().lines().findFirst().orElse("unknown failure");
            return new IOException(what + " failed: " + detail, cause);
        }
    }
}

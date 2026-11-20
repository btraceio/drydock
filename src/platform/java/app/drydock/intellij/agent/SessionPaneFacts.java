package app.drydock.intellij.agent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a session pane knows about its session, with the honest state of that
 * knowledge. Replaces the editor's mutable fields and its one
 * {@code Starting…} placeholder, which a failed or never-found load left on
 * screen forever.
 *
 * <p>Only a pure mapping lives here, over values the caller already gathered
 * off the EDT: no filesystem, canonicalization, transcript or registry
 * access, so nothing here can block. The probes (git-entry stat, transcript
 * scan, path canonicalization, registry/state reads) belong to the loader.</p>
 */
public record SessionPaneFacts(
        State state,
        String message,
        Optional<Integer> resumeTokens,
        boolean resumable,
        Optional<SessionPlace> hostedFrom,
        boolean folderMissing,
        boolean checkoutGone) {

    /** How much the pane can say. Exactly one holds; never a fabricated session. */
    public enum State {
        /** The load has not finished: the one state that may read "Loading". */
        LOADING,
        /** The session resolved and the fact fields are meaningful. */
        KNOWN,
        /** The load itself failed; {@code message} says why. */
        FAILED,
        /** The session was resolved before and the catalog dropped it since. */
        DELETED,
        /** The load finished and the catalog never had this session. */
        UNKNOWN
    }

    public SessionPaneFacts {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(resumeTokens, "resumeTokens");
        Objects.requireNonNull(hostedFrom, "hostedFrom");
        if (state != State.KNOWN) {
            // Facts of a session that did not resolve would be claims
            // about nothing: a stale token figure under "deleted".
            if (resumeTokens.isPresent() || resumable || hostedFrom.isPresent() || folderMissing || checkoutGone) {
                throw new IllegalArgumentException("facts only exist for a known session: " + state);
            }
        }
    }

    /**
     * Values already read off the EDT for a session found in the catalog.
     *
     * @param canonicalProjectBase the window's canonical base path, if it has one
     * @param canonicalWorkingDirectory the session's canonical working directory
     * @param hasAgentSessionId whether the row records a conversation to resume
     * @param running whether a live process serves the session
     * @param contextTokens the transcript scan's answer, if it produced one
     * @param hasConversation whether the provider holds a resumable conversation
     * @param runningPlace the live agent's own place, if running
     * @param catalogRepositoryName the row's repository display name, if the state lists it
     * @param directoryName the working directory's last segment, the fallback name
     * @param directoryExists whether the working directory is a directory
     * @param hasGitEntry whether it carries a {@code .git} entry (file or directory)
     */
    public record Inputs(
            Optional<Path> canonicalProjectBase,
            Path canonicalWorkingDirectory,
            boolean hasAgentSessionId,
            boolean running,
            OptionalLong contextTokens,
            boolean hasConversation,
            Optional<SessionPlace> runningPlace,
            Optional<String> catalogRepositoryName,
            String directoryName,
            boolean directoryExists,
            boolean hasGitEntry) {
        public Inputs {
            Objects.requireNonNull(canonicalProjectBase, "canonicalProjectBase");
            Objects.requireNonNull(canonicalWorkingDirectory, "canonicalWorkingDirectory");
            Objects.requireNonNull(contextTokens, "contextTokens");
            Objects.requireNonNull(runningPlace, "runningPlace");
            Objects.requireNonNull(catalogRepositoryName, "catalogRepositoryName");
            Objects.requireNonNull(directoryName, "directoryName");
        }
    }

    public static SessionPaneFacts loading() {
        return empty(State.LOADING, "Loading session…");
    }

    public static SessionPaneFacts failed(String reason) {
        return empty(State.FAILED, "Could not load this session: " + reason);
    }

    public static SessionPaneFacts deleted() {
        return empty(State.DELETED, "This session is no longer in Drydock's catalog.");
    }

    public static SessionPaneFacts unknown() {
        return empty(State.UNKNOWN, "Drydock has no record of this session.");
    }

    /**
     * The state for a finished-or-pending load. Precedence: an unfinished load
     * is loading whatever else is said; a failure beats a catalog answer
     * (the catalog could not be trusted); a found session is known; a missing
     * one is deleted if it had resolved before, else unknown. Never loading
     * for a finished load, so no placeholder outlives its answer.
     */
    public static State resolveState(
            boolean loadFinished, Optional<String> failure, boolean foundInCatalog, boolean resolvedBefore) {
        Objects.requireNonNull(failure, "failure");
        if (!loadFinished) {
            return State.LOADING;
        }
        if (failure.isPresent()) {
            return State.FAILED;
        }
        if (foundInCatalog) {
            return State.KNOWN;
        }
        return resolvedBefore ? State.DELETED : State.UNKNOWN;
    }

    /** The facts for a session the catalog returned. */
    public static SessionPaneFacts known(Inputs in) {
        Objects.requireNonNull(in, "in");
        Optional<SessionPlace> hosted = hostedPlace(in);
        return new SessionPaneFacts(
                State.KNOWN,
                "",
                resumeTokens(in),
                in.hasConversation(),
                hosted,
                // A hosted session whose folder vanished; a local one is
                // the checkout-gone case, kept apart on purpose.
                hosted.isPresent() && HostedLifecycle.folderGone(true, in.directoryExists()),
                !(in.directoryExists() && in.hasGitEntry()));
    }

    /** {@code Resume · ~Nk tokens} only for a resumable, not-running conversation, clamped to int. */
    static Optional<Integer> resumeTokens(Inputs in) {
        if (!in.hasAgentSessionId() || in.running() || in.contextTokens().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((int) Math.min(Integer.MAX_VALUE, in.contextTokens().getAsLong()));
    }

    /** The live agent's place if hosted here, else the row's repository with no branch. */
    static Optional<SessionPlace> hostedPlace(Inputs in) {
        if (!HostedTab.isHosted(in.canonicalProjectBase(), in.canonicalWorkingDirectory())) {
            return Optional.empty();
        }
        if (in.runningPlace().isPresent()) {
            return in.runningPlace();
        }
        return Optional.of(new SessionPlace(
                in.catalogRepositoryName().orElse(in.directoryName()), Optional.empty()));
    }

    /** Whether the pane has a session to show; the others show {@link #message()}. */
    public boolean isKnown() {
        return state == State.KNOWN;
    }

    private static SessionPaneFacts empty(State state, String message) {
        return new SessionPaneFacts(state, message, Optional.empty(), false, Optional.empty(), false, false);
    }
}

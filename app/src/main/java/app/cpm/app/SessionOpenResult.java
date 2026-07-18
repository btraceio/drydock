package app.cpm.app;

import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.terminal.ghostty.GhosttySurface;

/**
 * Outcome of {@link SessionManager#createSession} / {@link
 * SessionManager#resumeSession} (plan section 11). A real launch failure
 * (e.g. no {@code claude} executable) is reported by the returned {@code
 * CompletableFuture} completing exceptionally instead -- these three
 * variants are all "successful" outcomes in the sense that nothing went
 * wrong, but a caller (the terminal-tabs UI) still needs to react
 * differently to each:
 *
 * <ul>
 *   <li>{@link Opened} -- a new {@code GhosttySurface} was created and is
 *       running; show it in a new tab.</li>
 *   <li>{@link AlreadyOpen} -- plan section 11.3: focus {@link
 *       #activeSurface()}'s existing tab instead of opening a second one.</li>
 *   <li>{@link MissingWorkingDirectory} -- plan section 11.2: the session's
 *       stored working directory no longer exists; no surface was created.
 *       Offer the user a replacement directory and call {@link
 *       SessionManager#reassignWorkingDirectory} before retrying.</li>
 *   <li>{@link MissingConversation} -- the session is pinned to a Claude
 *       conversation id whose transcript no longer exists on disk (claude
 *       would exit immediately with "No conversation found"); no surface
 *       was created. Offer to start a fresh conversation under the same
 *       name ({@link SessionManager#startFreshConversation}) or delete the
 *       session.</li>
 * </ul>
 */
public sealed interface SessionOpenResult {

    ManagedClaudeSession session();

    record Opened(ManagedClaudeSession session, GhosttySurface surface) implements SessionOpenResult {
    }

    record AlreadyOpen(ManagedClaudeSession session, ManagedSessionId activeSessionId,
                        GhosttySurface activeSurface) implements SessionOpenResult {
    }

    record MissingWorkingDirectory(ManagedClaudeSession session) implements SessionOpenResult {
    }

    record MissingConversation(ManagedClaudeSession session) implements SessionOpenResult {
    }
}

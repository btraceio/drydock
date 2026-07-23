package app.drydock.app;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.terminal.api.TerminalSurface;

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
 *   <li>{@link Opened} -- a new {@code TerminalSurface} was created and is
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
 *   <li>{@link UnsupportedAgent} -- the session was persisted with {@link
 *       app.drydock.domain.SessionStatus#UNSUPPORTED_AGENT} (its {@code
 *       agentKind} decoded from an unrecognized raw name and is only a
 *       placeholder); no surface was created, since launching it would
 *       silently run the wrong agent.</li>
 * </ul>
 */
public sealed interface SessionOpenResult {

    ManagedAgentSession session();

    record Opened(ManagedAgentSession session, TerminalSurface surface) implements SessionOpenResult {
    }

    record AlreadyOpen(ManagedAgentSession session, ManagedSessionId activeSessionId,
                        TerminalSurface activeSurface) implements SessionOpenResult {
    }

    record MissingWorkingDirectory(ManagedAgentSession session) implements SessionOpenResult {
    }

    record MissingConversation(ManagedAgentSession session) implements SessionOpenResult {
    }

    record UnsupportedAgent(ManagedAgentSession session) implements SessionOpenResult {
    }
}

package app.drydock.babysit;

import app.drydock.domain.SessionActivity;

import java.util.Optional;

/**
 * How the monitor wakes a babysitter with a pointer prompt, decided from
 * what the IDE knows about the babysitter right now. Pure.
 *
 * <p>A running agent is an interactive TUI that stays alive at its prompt
 * after a turn, so it can only be reached by typing into it
 * ({@code submitPrompt}); a resume would refuse because the session is
 * live. That write has no busy check: pasted mid-turn it is queued or
 * garbled by the TUI, and pasted into a permission prompt it answers the
 * prompt. So only a reported {@link SessionActivity#IDLE} is safe. Codex
 * reports no activity at all, so an empty activity skips too: the monitor
 * retries on its next tick, and the wake lands once the agent has ended or
 * a reporter says it is idle.</p>
 *
 * <p>A stopped agent is resumed with the prompt, which every provider
 * renders only when the conversation id is bound; without one the resume
 * would be the provider's interactive picker, so an unbound babysitter is
 * skipped rather than woken into a picker.</p>
 */
public final class BabysitWakePolicy {

    public enum Decision {
        /** The agent is idle at its prompt: type the pointer into it. */
        WAKE_VIA_SUBMIT,
        /** The agent is not running and its conversation is bound: resume it with the pointer. */
        WAKE_VIA_RESUME,
        /** A start is in flight, or the agent may be mid-turn or waiting on a permission prompt. */
        SKIP_BUSY,
        /** Not running and no conversation id yet (Codex/Pi bind late): nothing to resume into. */
        SKIP_UNBOUND
    }

    private BabysitWakePolicy() {
    }

    /**
     * @param running            the babysitter has a live process in this IDE
     * @param startingOrClaimed  a start, resume or removal of it is in flight
     * @param activity           its last reported activity; empty when none is reported
     * @param conversationBound  its row carries an agent session id
     */
    public static Decision decide(boolean running, boolean startingOrClaimed, Optional<SessionActivity> activity,
                                  boolean conversationBound) {
        if (startingOrClaimed) {
            return Decision.SKIP_BUSY;
        }
        if (running) {
            return activity.filter(a -> a == SessionActivity.IDLE).isPresent()
                    ? Decision.WAKE_VIA_SUBMIT
                    : Decision.SKIP_BUSY;
        }
        return conversationBound ? Decision.WAKE_VIA_RESUME : Decision.SKIP_UNBOUND;
    }

    /**
     * Whether {@code prompt} can be a wake pointer on both paths: non-blank,
     * one line, and free of control characters ({@code submitPrompt} refuses
     * a newline, a carriage return and ESC; the rest have no business in a
     * pointer either).
     */
    public static boolean isWakePointer(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        for (int i = 0; i < prompt.length(); i++) {
            if (Character.isISOControl(prompt.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

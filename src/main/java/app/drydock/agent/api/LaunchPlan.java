package app.drydock.agent.api;

import java.util.Objects;

/**
 * The result of a provider building a launch command. {@code supported} is
 * false when a provider declines a context it cannot serve (e.g. a remote
 * context for a provider without remote support); callers must not launch it.
 */
public record LaunchPlan(String command, boolean sessionIdUsed, boolean supported) {

    public LaunchPlan {
        Objects.requireNonNull(command, "command");
    }

    public static LaunchPlan of(String command, boolean sessionIdUsed) {
        return new LaunchPlan(command, sessionIdUsed, true);
    }

    public static LaunchPlan unsupported() {
        return new LaunchPlan("", false, false);
    }
}

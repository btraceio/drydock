package app.drydock.agent.providers.pi.internal;

/**
 * Whether the installed {@code pi} can host drydock's bridge extension.
 *
 * <p>Unlike {@code ClaudeCapabilities}, which parses {@code claude --help}
 * "rather than assumed from the version string, since flag availability does
 * not necessarily track a simple version comparison", this compares versions.
 * The difference is deliberate: there is no {@code pi --help} line that says
 * "extension tools work", so there is nothing to grep for.</p>
 *
 * <p>The floor is <strong>0.80.3</strong>. The APIs phase 1 actually uses bind
 * a floor nearer 0.44.0, and the bridge has been observed working on 0.79.10 —
 * the floor is higher because 0.80.3 is where {@code session_info_changed}
 * arrives, which phase 2 needs, and because supporting a range nothing in CI
 * exercises would claim a compatibility drydock cannot back. Anything
 * unparseable, including {@code PiVersionProbe}'s literal {@code "unknown"},
 * is below the floor: a version we could not read is not one we can trust.</p>
 *
 * <p>{@link #versionParsed()} exists so a caller can tell "read the version,
 * and it is genuinely too old" (worth remembering: re-probing cannot change
 * it) apart from "could not make sense of what the probe returned" (not
 * worth remembering: the next probe might do better). Both cases still leave
 * {@link #supportsBridge()} false -- an unparseable version is never trusted
 * enough to unlock the bridge. It is a derived question, not a stored field:
 * {@code version} is already carried verbatim, so re-parsing it on demand
 * keeps the record's public shape at the two fields callers actually
 * construct rather than adding a third that only restates what
 * {@code version} already says.</p>
 */
public record PiCapabilities(String version, boolean supportsBridge) {

    private static final int[] MINIMUM = {0, 80, 3};

    private static final int[] NATIVE_MCP = {0, 99, 0};

    public static PiCapabilities of(String version) {
        String reported = version == null || version.isBlank() ? "unknown" : version.strip();
        int[] components = parseComponents(reported);
        return new PiCapabilities(reported, components != null && meetsMinimum(components, MINIMUM));
    }

    /**
     * Whether this pi connects MCP servers itself, so an extension can hand it
     * one with {@code pi.registerMcpServer}: built-in MCP support arrived in
     * <strong>0.99.0</strong> (pi's CHANGELOG). Derived like
     * {@link #versionParsed()}, and false for anything unparseable.
     */
    public boolean supportsMcpServers() {
        int[] components = parseComponents(version);
        return components != null && meetsMinimum(components, NATIVE_MCP);
    }

    /** Whether {@link #version()} was itself readable as a dotted version, independent of the floor. */
    public boolean versionParsed() {
        return parseComponents(version) != null;
    }

    /** Drop any pre-release/build suffix ("0.84.1-beta.2" compares as 0.84.1), then parse each dotted component. */
    private static int[] parseComponents(String version) {
        String core = version.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length < MINIMUM.length) {
            return null;
        }
        int[] components = new int[MINIMUM.length];
        for (int i = 0; i < MINIMUM.length; i++) {
            try {
                components[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return components;
    }

    private static boolean meetsMinimum(int[] components, int[] floor) {
        for (int i = 0; i < floor.length; i++) {
            if (components[i] != floor[i]) {
                return components[i] > floor[i];
            }
        }
        return true;
    }
}

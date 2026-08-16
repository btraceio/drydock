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
 */
public record PiCapabilities(String version, boolean supportsBridge) {

    private static final int[] MINIMUM = {0, 80, 3};

    public static PiCapabilities of(String version) {
        String reported = version == null || version.isBlank() ? "unknown" : version.strip();
        return new PiCapabilities(reported, meetsMinimum(reported));
    }

    private static boolean meetsMinimum(String version) {
        // Drop any pre-release/build suffix: "0.84.1-beta.2" compares as 0.84.1.
        String core = version.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length < MINIMUM.length) {
            return false;
        }
        for (int i = 0; i < MINIMUM.length; i++) {
            int component;
            try {
                component = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (component != MINIMUM[i]) {
                return component > MINIMUM[i];
            }
        }
        return true;
    }
}

package app.drydock.agent.providers.pi.internal;

/** The installed Pi version and whether Drydock supports it. */
public record PiCapabilities(String version, boolean supported) {

    /** Pi 1.x is required; it includes the native MCP client Drydock relies on. */
    private static final int[] MINIMUM = {1, 0, 0};

    public static PiCapabilities of(String version) {
        String reported = version == null || version.isBlank() ? "unknown" : version.strip();
        int[] components = parseComponents(reported);
        return new PiCapabilities(reported, components != null && meetsMinimum(components, MINIMUM));
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

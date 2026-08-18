package app.drydock.terminal;

/**
 * Launch indirection for {@link NodeTerminalSpike}; see {@link
 * JediTermFxSpikeLauncher}'s Javadoc for why an {@code Application} subclass
 * cannot be the JVM's direct main class under a classpath (no module path)
 * launch.
 */
public final class NodeTerminalSpikeLauncher {
    private NodeTerminalSpikeLauncher() {
    }

    public static void main(String[] args) {
        NodeTerminalSpike.main(args);
    }
}
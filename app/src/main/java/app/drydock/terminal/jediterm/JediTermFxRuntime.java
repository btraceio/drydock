package app.drydock.terminal.jediterm;

import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import com.techsenger.jeditermfx.ui.settings.SettingsProvider;
import javafx.scene.Node;
import javafx.scene.text.Font;

import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The JediTermFX {@link TerminalRuntime}: the per-tab terminal that owns a
 * {@link JediTermFxWidget} over a pty4j {@link PtyProcess}. Selected by
 * {@code TerminalFactory} on Windows (and by the {@code
 * app.drydock.terminal.backend=jediterm} override for testing on any host).
 *
 * <p>The runtime is constructed in {@code MainWorkspace.createOpenSessionTab}
 * (before the session's command is known) and {@link #openSurface} is called
 * later by {@code SessionManager} once the agent command + working directory
 * are resolved -- so the PTY + widget are created in {@link #openSurface},
 * not the constructor. {@link #tick} is a no-op: JediTermFX pumps its own
 * emulator/read thread; the native path's tick-and-draw loop has no analogue
 * here. {@link #updateConfig} (runtime re-theming) is deferred to a follow-up:
 * JediTermFX theming goes through a {@link SettingsProvider} and is not a hot
 * file reload like libghostty's config, so it is left as a known gap rather
 * than half-wired.</p>
 */
public final class JediTermFxRuntime implements TerminalRuntime {

    private static final Logger LOG = System.getLogger(JediTermFxRuntime.class.getName());

    private final Runnable onWakeup;
    private final Optional<Path> configFile;

    public JediTermFxRuntime(Runnable onWakeup, Optional<Path> configFile) {
        this.onWakeup = onWakeup;
        this.configFile = configFile;
    }

    @Override
    public void tick() {
        // No-op: JediTermFX runs its own emulator/read thread; there is no
        // tick-and-draw pump to drive. The wakeup callback is retained for
        // interface symmetry but is not fired by this backend.
    }

    @Override
    public void setFocus(boolean focused) {
        // No-op: the widget manages focus.
    }

    @Override
    public void updateConfig(Path configFile) {
        // Theming at runtime is deferred (see class Javadoc). Logged so a
        // theme switch on the Windows backend is visible rather than silent.
        LOG.log(Logger.Level.DEBUG, "JediTermFX runtime re-theme requested (not yet implemented): {0}", configFile);
    }

    @Override
    public TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec) {
        if (!(host instanceof JediTermFxHostView jeditermHost)) {
            throw new IllegalStateException(
                "JediTermFxRuntime requires a JediTermFxHostView, got " + host.getClass().getName());
        }

        SettingsProvider settings = new DarkSettingsProvider();
        JediTermFxWidget widget = new JediTermFxWidget(80, 24, settings);

        PtyProcess process = startProcess(spec);
        PtyConnector connector = new PtyConnector(process, StandardCharsets.UTF_8);
        widget.setTtyConnector(connector);
        widget.start();

        Node pane = widget.getPane();
        jeditermHost.setEmbeddedNode(pane);
        // The wakeup callback is the runtime's own "a frame is ready" signal
        // on the native path; JediTermFX renders itself, so it is not driven
        // by wakeups. Retained for future wiring but not invoked here.
        return new JediTermFxSurface(widget, process, connector);
    }

    @Override
    public void close() {
        // No-op: the surface owns the widget + process; the runtime holds none.
    }

    /**
     * Starts the spec's command in a PTY. The macOS path hands the command
     * string to libghostty, which wraps it in a login shell itself; this
     * backend runs it through a shell explicitly so the one {@link
     * TerminalSpec} string model serves both -- {@code bash -lc} on POSIX,
     * {@code cmd.exe /c} on Windows. {@code cmd /c} nesting an interactive
     * {@code cmd.exe} (the shell sub-tab) is harmless, and {@code bash -lc
     * claude} matches how the macOS login-shell wrapper runs the agent.
     */
    private static PtyProcess startProcess(TerminalSpec spec) {
        String[] command = isWindows()
                ? new String[] {"cmd.exe", "/c", spec.command()}
                : new String[] {"/bin/bash", "-lc", spec.command()};
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        try {
            PtyProcessBuilder builder = new PtyProcessBuilder()
                    .setCommand(command)
                    .setEnvironment(env);
            String cwd = spec.workingDirectory();
            if (cwd != null && !cwd.isBlank()) {
                builder.setDirectory(cwd);
            }
            return builder.start();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start PTY for " + spec.command()
                    + " in " + (spec.workingDirectory() == null ? "<inherit>" : spec.workingDirectory()), e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Dark variant of JediTermFX's {@link DefaultSettingsProvider}, matching
     * the app's dark chrome. Two overrides -- the rest (palette, font, copy
     * behaviour, ...) inherits from the base. A full theme bridge (app theme
     * -> JediTermFX settings) is deferred; this keeps the terminal from
     * flashing a white background on a dark window.
     */
    private static final class DarkSettingsProvider extends DefaultSettingsProvider {
        @Override
        public com.techsenger.jeditermfx.core.TerminalColor getDefaultBackground() {
            return new com.techsenger.jeditermfx.core.TerminalColor(0, 0, 0);
        }

        @Override
        public com.techsenger.jeditermfx.core.TerminalColor getDefaultForeground() {
            return new com.techsenger.jeditermfx.core.TerminalColor(255, 255, 255);
        }

        @Override
        public Font getTerminalFont() {
            return Font.font(isWindows() ? "Consolas" : "Menlo", getTerminalFontSize());
        }
    }
}
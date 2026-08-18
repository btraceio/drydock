package app.drydock.terminal.jediterm;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import com.techsenger.jeditermfx.core.ProcessTtyConnector;
import com.techsenger.jeditermfx.core.util.TermSize;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;

/**
 * Adapts a pty4j {@link PtyProcess} to JediTermFX's {@code TtyConnector}.
 * Replicates {@code com.techsenger.jeditermfx.app.pty.PtyProcessTtyConnector}
 * (in the {@code jeditermfx-app} module, deliberately not depended on -- its
 * POM hardcodes a Linux JavaFX classifier) so the backend needs only
 * {@code jeditermfx-ui}. The base {@link ProcessTtyConnector} supplies
 * read/write/ready/waitFor over the process streams; the overrides here are
 * the PTY-specific bits (resize via {@link PtyProcess#setWinSize}, liveness,
 * name).
 */
final class PtyConnector extends ProcessTtyConnector {

    private final PtyProcess process;

    PtyConnector(PtyProcess process, Charset charset) {
        super(process, charset);
        this.process = process;
    }

    @Override
    public void resize(@NotNull TermSize size) {
        if (isConnected()) {
            process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }
    }

    @Override
    public boolean isConnected() {
        return process.isAlive();
    }

    @Override
    public String getName() {
        return "local";
    }
}
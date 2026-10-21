package app.drydock.intellij.agent;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationListener;
import com.intellij.openapi.application.ApplicationManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The one "Deactivate n working agents?" question of an IDE quit, asked before the
 * quit can go ahead — for every working agent in the IDE, shown in a window or
 * not.
 *
 * <p>Asked from {@code ApplicationListener.canExitApplication}, which the
 * platform calls on every quit before it asks any window whether it may close
 * (read off {@code ApplicationImpl.canExit}'s bytecode in 2025.3). It is the
 * only veto that exists when no project window is open: then no
 * {@code ProjectCloseHandler} runs at all, and before this a quit from the
 * Welcome screen stopped agents no window showed without asking. The method
 * is deprecated, as "obsolete", with no replacement; Plugin Verifier does not
 * flag overriding it (IU-253, IU-263). The windows' own close
 * checks that follow find the quit already approved
 * ({@link AgentCloseConfirmation}).</p>
 *
 * <p>A failure inside Drydock never blocks a quit: it is logged and the quit
 * goes ahead, which only loses the question.</p>
 */
public final class QuitConfirmation implements ApplicationListener {

    private static final Logger LOG = System.getLogger(QuitConfirmation.class.getName());

    private final BooleanSupplier ask;

    QuitConfirmation() {
        this(AgentCloseConfirmation::confirmQuit);
    }

    /** Registers the quit question for as long as {@code parent} lives. */
    public static void register(Disposable parent) {
        ApplicationManager.getApplication().addApplicationListener(new QuitConfirmation(), parent);
    }

    /** Test seam: the question itself. */
    QuitConfirmation(BooleanSupplier ask) {
        this.ask = Objects.requireNonNull(ask, "ask");
    }

    @Override
    @SuppressWarnings("deprecation")   // see the class comment: there is no replacement
    public boolean canExitApplication() {
        try {
            return ask.getAsBoolean();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not ask about working agents before quitting: " + e);
            return true;
        }
    }
}

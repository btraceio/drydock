package app.drydock.intellij.editorqa;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.intellij.agent.AgentConsoles;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * "Open session": shows the exact exchange {@code exchangeId} of a marked Q&A
 * session in the canonical "Drydock Sessions" host of the caller's window.
 *
 * <p>Show-only: the host is shown through {@link AgentConsoles#show} and the
 * history is read from the store; nothing is launched, resumed or sent, so a
 * stopped or detached session opens the same pane. The identity is the
 * exchange ID alone, never text, row index, relative time or terminal
 * position. Each click captures the host epoch its show issued; a later click
 * (or a closed host/editor/project) makes the earlier one's late completion a
 * no-op, so it cannot displace the newer navigation. EDT entry; the history
 * load runs on the background executor.</p>
 */
public final class EditorAskNavigation {

    /** Where the history and the actions come from; supplied by the plugin wiring. */
    public interface Source {
        /** The bounded history of {@code checkout}; blocking, called off the EDT. */
        List<EditorAskRecord> loadHistory(String checkout);

        EditorAskUiModel model();

        EditorAskHistoryPanel.Actions actions(ManagedSessionId session);

        /** The tab composer's sink, or null when the tab has none. */
        EditorAskComposerPanel.Sink composer(String checkout, ManagedSessionId session);
    }

    /** The show/mount step; the production one is {@link AgentConsoles}. */
    public interface Host {
        /** Shows the session, starting nothing; returns the epoch issued, or -1. */
        long show(Project project, ManagedSessionId session);

        /** Mounts the view if {@code epoch} is still the host's newest and the session is shown. */
        boolean mount(Project project, ManagedSessionId session, long epoch, JComponent view);
    }

    private static final Host CONSOLES = new Host() {
        @Override
        public long show(Project project, ManagedSessionId session) {
            return AgentConsoles.showForEditorAsk(project, session);
        }

        @Override
        public boolean mount(Project project, ManagedSessionId session, long epoch, JComponent view) {
            return AgentConsoles.mountEditorAskView(project, session, epoch, view);
        }
    };

    private final Source source;
    private final Host host;
    private final Executor background;
    private final Executor ui;
    private final Map<ManagedSessionId, Mounted> mounted = new HashMap<>();

    private record Mounted(EditorAskHistoryPanel history, EditorAskComposerPanel composer, JPanel view, String checkout) {
    }

    public EditorAskNavigation(Source source) {
        this(source, CONSOLES, task -> ApplicationManager.getApplication().executeOnPooledThread(task),
                task -> ApplicationManager.getApplication().invokeLater(task, ModalityState.any()));
    }

    public EditorAskNavigation(Source source, Host host, Executor background, Executor ui) {
        this.source = Objects.requireNonNull(source, "source");
        this.host = Objects.requireNonNull(host, "host");
        this.background = Objects.requireNonNull(background, "background");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    /**
     * Navigates to {@code exchangeId} of {@code session}. EDT. {@code alive}
     * reports whether the caller (the editor card) is still live when the load
     * returns.
     */
    public void open(Project project, String checkout, ManagedSessionId session, String exchangeId,
                     BooleanSupplier alive) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(checkout, "checkout");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(alive, "alive");
        if (project.isDisposed()) {
            return;
        }
        long epoch = host.show(project, session);   // immediate: the host is visible before the load returns
        if (epoch < 0) {
            return;
        }
        background.execute(() -> {
            List<EditorAskRecord> records;
            try {
                records = source.loadHistory(checkout);
            } catch (RuntimeException e) {
                records = List.of();   // the view still opens and shows what the model already has
            }
            List<EditorAskRecord> loaded = records;
            ui.execute(() -> finish(project, checkout, session, exchangeId, epoch, alive, loaded));
        });
    }

    private void finish(Project project, String checkout, ManagedSessionId session, String exchangeId, long epoch,
                        BooleanSupplier alive, List<EditorAskRecord> records) {
        if (project.isDisposed() || !alive.getAsBoolean()) {
            return;
        }
        Mounted m = mounted.get(session);
        boolean fresh = m == null || !m.checkout().equals(checkout);
        if (fresh) {
            m = build(checkout, session);
        }
        if (!host.mount(project, session, epoch, m.view())) {
            if (fresh) {
                dispose(m);   // superseded or closed: nothing was shown, keep no listener behind
            }
            return;
        }
        if (fresh) {
            Mounted old = mounted.put(session, m);
            if (old != null) {
                dispose(old);
            }
        }
        source.model().load(checkout, records);   // only after the host fence accepted this navigation
        m.history().refresh();
        m.history().select(exchangeId);
    }

    private Mounted build(String checkout, ManagedSessionId session) {
        EditorAskHistoryPanel history = new EditorAskHistoryPanel(source.model(), checkout, source.actions(session));
        EditorAskComposerPanel.Sink sink = source.composer(checkout, session);
        EditorAskComposerPanel composer = sink == null ? null : new EditorAskComposerPanel(sink);
        JPanel view = new JPanel(new BorderLayout());
        view.add(history, BorderLayout.CENTER);
        if (composer != null) {
            view.add(composer, BorderLayout.SOUTH);
        }
        return new Mounted(history, composer, view, checkout);
    }

    private static void dispose(Mounted m) {
        m.history().dispose();
        if (m.composer() != null) {
            m.composer().dispose();
        }
    }
}

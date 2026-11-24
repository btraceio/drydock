package app.drydock.intellij.editorqa;

import java.awt.Dimension;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.JComponent;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Key;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import app.drydock.editorqa.EditorAskLimits;
import app.drydock.editorqa.EditorAskRecord.TextRange;

/**
 * "Ask Agent…" in the ordinary editor popup: ask a question about the
 * selected code. The flow, in order:
 *
 * <ol>
 *   <li>EDT: revalidate eligibility and freeze the selection
 *     ({@link EditorAskCapture}); a progress indicator appears at once.</li>
 *   <li>Background: resolve the owning checkout
 *     ({@link EditorAskCheckoutResolver}). Git can block; the EDT does not.</li>
 *   <li>EDT: only now, with eligibility settled, ask for the question. A
 *     cancelled, blank or over-limit question dispatches nothing.</li>
 *   <li>EDT: hand {@link Request} to the {@link Dispatcher}.</li>
 * </ol>
 *
 * <p>The selection is captured once and never recaptured: whatever the
 * caret, the tab or the disk did while Git and the dialog ran is not what
 * the question is about. Every path that does not dispatch releases the
 * snapshot's marker and clears the per-editor busy flag; a dispatched
 * request hands the marker to the dispatcher.</p>
 *
 * <p>The action is deliberately not registered for the diff popup and has
 * no review binding.</p>
 */
public final class EditorAskAction extends AnAction implements DumbAware {

    /** The progress title shown from the click until the question dialog or a refusal. */
    static final String PROGRESS_TITLE = "Ask Agent: checking the checkout…";

    /** Per-editor busy flag: one ask is being prepared; a second click is refused, not queued. */
    private static final Key<Boolean> PREPARING = Key.create("drydock.editorAsk.preparing");

    /**
     * What an accepted ask hands over. The dispatcher owns
     * {@code capture.marker()} from this point.
     */
    record Request(Project project, EditorAskCapture.Snapshot capture,
            EditorAskCheckoutResolver.Resolution checkout, String question) {
        Request {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(checkout, "checkout");
            Objects.requireNonNull(question, "question");
        }
    }

    /** The narrow seam to the coordinator (arrives with its own task): takes a fully resolved ask. */
    public interface Dispatcher {
        /** Called on the EDT; must not block. */
        void dispatch(Request request);
    }

    private static final AtomicReference<Dispatcher> DISPATCHER = new AtomicReference<>();

    /** Installs the coordinator seam; until then the action is disabled with a reason. */
    public static void install(Dispatcher dispatcher) {
        DISPATCHER.set(Objects.requireNonNull(dispatcher, "dispatcher"));
    }

    /** Removes the seam if {@code dispatcher} is still the installed one. */
    public static void uninstall(Dispatcher dispatcher) {
        DISPATCHER.compareAndSet(dispatcher, null);
    }

    /** Runs the blocking resolution off the EDT with visible progress; calls back on the EDT exactly once. */
    interface Launcher {
        void launch(Project project, String title, Supplier<EditorAskCheckoutResolver.Outcome> work,
                Consumer<EditorAskCheckoutResolver.Outcome> onEdt);
    }

    /** Asks for the question on the EDT; empty when the user cancelled. */
    interface Prompter {
        Optional<String> ask(Project project, EditorAskCapture.Snapshot capture,
                EditorAskCheckoutResolver.Resolution checkout);
    }

    /** Everything the flow touches, so tests can drive it without a real Git or dialog. */
    record Wiring(Supplier<Dispatcher> dispatcher, Function<Path, EditorAskCheckoutResolver.Outcome> resolver,
            Launcher launcher,
            Prompter prompter, BiConsumer<Editor, String> refusals) {
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (e.getProject() == null || editor == null || editor.getEditorKind() != EditorKind.MAIN_EDITOR
                || diffContext(e)) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        Optional<String> reason = disabledReason(editor, false, DISPATCHER.get() != null);
        e.getPresentation().setVisible(true);
        e.getPresentation().setEnabled(reason.isEmpty());
        e.getPresentation().setDescription(reason.orElse("Ask the agent about the selected code"));
    }

    /** Why the action cannot run now, from in-memory state only. */
    static Optional<String> disabledReason(Editor editor, boolean diffContext, boolean dispatcherInstalled) {
        if (!dispatcherInstalled) {
            return Optional.of("Editor questions are not available yet");
        }
        if (PREPARING.get(editor) != null) {
            return Optional.of("A question is already being prepared");
        }
        return EditorAskCapture.refusal(editor, diffContext);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        begin(project, editor, diffContext(e), productionWiring());
    }

    private static boolean diffContext(AnActionEvent e) {
        return e.getData(DiffDataKeys.DIFF_REQUEST) != null || e.getData(DiffDataKeys.DIFF_VIEWER) != null;
    }

    static Wiring productionWiring() {
        return new Wiring(DISPATCHER::get, EditorAskCheckoutResolver.production()::resolve, new ProgressLauncher(),
                new DialogPrompter(), (editor, reason) -> HintManager.getInstance().showErrorHint(editor, reason));
    }

    /** The invocation: revalidate, capture, resolve off the EDT, prompt, dispatch. EDT only. */
    static void begin(Project project, Editor editor, boolean diffContext, Wiring wiring) {
        Optional<String> refusal = disabledReason(editor, diffContext, wiring.dispatcher().get() != null);
        if (refusal.isPresent()) {
            wiring.refusals().accept(editor, refusal.get());
            return;
        }
        EditorAskCapture.Snapshot capture;
        try {
            capture = EditorAskCapture.capture(editor);
        } catch (EditorAskLimits.RejectedInputException e) {
            wiring.refusals().accept(editor, e.getMessage());
            return;
        }
        editor.putUserData(PREPARING, Boolean.TRUE);
        boolean launched = false;
        try {
            wiring.launcher().launch(project, PROGRESS_TITLE,
                    () -> wiring.resolver().apply(capture.file()),
                    outcome -> finish(project, editor, capture, outcome, wiring));
            launched = true;
        } finally {
            if (!launched) {
                editor.putUserData(PREPARING, null);
                capture.release();
            }
        }
    }

    private static void finish(Project project, Editor editor, EditorAskCapture.Snapshot capture,
            EditorAskCheckoutResolver.Outcome outcome, Wiring wiring) {
        boolean dispatched = false;
        try {
            if (project.isDisposed() || editor.isDisposed()) {
                return;
            }
            if (outcome instanceof EditorAskCheckoutResolver.Refused refused) {
                wiring.refusals().accept(editor, refused.reason());
                return;
            }
            EditorAskCheckoutResolver.Resolution checkout =
                    ((EditorAskCheckoutResolver.Resolved) outcome).resolution();
            Dispatcher dispatcher = wiring.dispatcher().get();
            if (dispatcher == null) {
                wiring.refusals().accept(editor, "Editor questions are not available yet");
                return;
            }
            Optional<String> question = wiring.prompter().ask(project, capture, checkout);
            if (question.isEmpty() || question.get().isBlank()) {
                return;
            }
            try {
                EditorAskLimits.checkQuestion(question.get());
            } catch (EditorAskLimits.RejectedInputException e) {
                wiring.refusals().accept(editor, e.getMessage());
                return;
            }
            dispatched = true;
            dispatcher.dispatch(new Request(project, capture, checkout, question.get()));
        } finally {
            editor.putUserData(PREPARING, null);
            if (!dispatched) {
                capture.release();
            }
        }
    }

    /**
     * Production launcher: a non-cancellable background task, so the click
     * shows progress at once and the EDT never waits on Git. {@code
     * onFinished} runs on the EDT after success, failure and cancellation
     * alike, which is what makes the single callback unconditional.
     */
    private static final class ProgressLauncher implements Launcher {
        @Override
        public void launch(Project project, String title, Supplier<EditorAskCheckoutResolver.Outcome> work,
                Consumer<EditorAskCheckoutResolver.Outcome> onEdt) {
            ProgressManager.getInstance().run(new Task.Backgroundable(project, title, false) {
                private volatile EditorAskCheckoutResolver.Outcome outcome =
                        new EditorAskCheckoutResolver.Refused("The checkout check did not finish");

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        outcome = work.get();
                    } catch (RuntimeException | Error e) {
                        outcome = new EditorAskCheckoutResolver.Refused("The checkout check failed");
                        throw e;
                    }
                }

                @Override
                public void onFinished() {
                    onEdt.accept(outcome);
                }
            });
        }
    }

    /** Production prompter: a modal dialog with the question field, validated against the wire limits. */
    private static final class DialogPrompter implements Prompter {
        @Override
        public Optional<String> ask(Project project, EditorAskCapture.Snapshot capture,
                EditorAskCheckoutResolver.Resolution checkout) {
            QuestionDialog dialog = new QuestionDialog(project, capture.range(), checkout.relativePath());
            return dialog.showAndGet() ? Optional.of(dialog.question()) : Optional.empty();
        }
    }

    private static final class QuestionDialog extends DialogWrapper {
        private final JBTextArea field = new JBTextArea(6, 56);

        QuestionDialog(Project project, TextRange range, String relativePath) {
            super(project, true);
            setTitle("Ask Agent about " + relativePath + ":" + (range.startLine() + 1)
                    + (range.endLine() > range.startLine() ? "–" + (range.endLine() + 1) : ""));
            field.setLineWrap(true);
            field.setWrapStyleWord(true);
            setOKButtonText("Ask");
            init();
        }

        String question() {
            return field.getText();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JBScrollPane pane = new JBScrollPane(field);
            pane.setPreferredSize(new Dimension(520, 140));
            return pane;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return field;
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            try {
                EditorAskLimits.checkQuestion(field.getText());
                return null;
            } catch (EditorAskLimits.RejectedInputException e) {
                return new ValidationInfo(e.getMessage(), field);
            }
        }
    }
}

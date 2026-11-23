package app.drydock.tasks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The values of a task saved for later from the New Session dialog (T19a,
 * D5/P5), in the T18a result discipline: immutable, defensively copied,
 * owning its invariants, with no disk writes of its own. A saved task is a
 * definition only -- the write happens later, asynchronously, through the
 * task service ({@code TaskLauncher.saveTask}), never here.
 *
 * <p>The project identity is the main-checkout root and display name the
 * shared projects registry registers, NOT its id: the id the dialog's
 * inputs carried may be a placeholder minted for an unregistered project,
 * so the save resolves the STABLE id by registering that root when needed.
 * The New Session dialog has no prerequisite picker (that is the New Task
 * dialog's, T18a) and no done_by control, so its saves carry no {@code
 * after} refs and only the default HUMAN policy (D1/D16).</p>
 *
 * <p>This class shares its name with the New Session dialog's launch
 * decisions ({@code app.drydock.intellij.tasks.NewTaskPlan}); same-name
 * collisions are the one sanctioned fully-qualified-name case, so callers
 * in that package reference this type by its qualified name.</p>
 */
public record NewTaskPlan(String title, String body, List<String> after, DoneBy doneBy,
                          Path projectRoot, String projectName) {

    public NewTaskPlan {
        title = title == null ? "" : title.strip();
        if (title.isBlank()) {
            throw new IllegalArgumentException("A saved task needs a title");
        }
        body = body == null ? "" : body;
        after = after == null ? List.of() : List.copyOf(after);
        doneBy = doneBy == null ? DoneBy.HUMAN : doneBy;
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        projectName = Objects.requireNonNull(projectName, "projectName").strip();
        if (projectName.isBlank()) {
            throw new IllegalArgumentException("A saved task needs its project's name");
        }
    }

    /** The OK button's label while the save-later box is checked (P5). */
    public static final String SAVE_OK_LABEL = "Save Task";

    /**
     * Whether one start field stays usable: a saved task starts nothing, so
     * every start field is off in save mode whatever the launch mode allows
     * (P5). Pure, so the mode decisions are checkable without the dialog.
     */
    public static boolean startEnabled(boolean saving, boolean launchEnabled) {
        return !saving && launchEnabled;
    }

    /**
     * Whether the dialog's Start validation runs: it polices a launch (the
     * agent, the branch, the folder), never a save -- so a save works with
     * no agent installed.
     */
    public static boolean validatesStart(boolean saving) {
        return !saving;
    }

    /**
     * The body as the store's note paragraphs: the production write path
     * stores task body content as session notes, and one note refuses line
     * breaks, so each non-blank line becomes its own paragraph and blank
     * lines carry nothing. The text of each line is kept as typed.
     */
    public List<String> bodyNotes() {
        List<String> notes = new ArrayList<>();
        for (String line : body.split("\\R", -1)) {
            if (!line.strip().isEmpty()) {
                notes.add(line);
            }
        }
        return List.copyOf(notes);
    }
}

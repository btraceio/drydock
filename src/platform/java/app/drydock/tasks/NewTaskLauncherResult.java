package app.drydock.tasks;

import app.drydock.intellij.tasks.NewTaskPlan.Choice;

import java.util.Objects;

/**
 * What the New Session dialog accepted (T19a, D5/P5): either a launch
 * choice -- exactly the value the dialog's {@code acceptedChoice()} has
 * always returned, so existing callers stay compatible -- or the values of
 * a task saved for later, never both. Additive: the launch path reads
 * {@link #isLaunch()} and {@link #choice()}, the save path
 * {@link #saved()}.
 *
 * <p>Immutable by construction: the launch {@link Choice} is an immutable
 * record and the saved values are the immutable {@link NewTaskPlan} of this
 * package (the saved-task values type, not the New Session dialog's
 * launch-decision class of the same name in
 * {@code app.drydock.intellij.tasks}).</p>
 */
public record NewTaskLauncherResult(Choice choice, NewTaskPlan saved) {

    public NewTaskLauncherResult {
        if ((choice == null) == (saved == null)) {
            throw new IllegalArgumentException("exactly one of choice and saved must be set");
        }
    }

    /** A launch: the choice the dialog's OK accepted. */
    public static NewTaskLauncherResult launched(Choice choice) {
        return new NewTaskLauncherResult(Objects.requireNonNull(choice, "choice"), null);
    }

    /** A save: the task values the dialog's OK accepted. */
    public static NewTaskLauncherResult savedTask(NewTaskPlan saved) {
        return new NewTaskLauncherResult(null, Objects.requireNonNull(saved, "saved"));
    }

    /** Whether the user chose to launch now; false means save for later. */
    public boolean isLaunch() {
        return choice != null;
    }

    /**
     * The accepted launch choice; refuses on a save. The same value the
     * dialog's {@code acceptedChoice()} returns for a launch.
     */
    @Override
    public Choice choice() {
        if (choice == null) {
            throw new IllegalStateException(
                    "The dialog saved a task for later; it did not accept a launch choice");
        }
        return choice;
    }

    /** The accepted saved-task values; refuses on a launch. */
    @Override
    public NewTaskPlan saved() {
        if (saved == null) {
            throw new IllegalStateException(
                    "The dialog accepted a launch choice, not a saved task");
        }
        return saved;
    }
}

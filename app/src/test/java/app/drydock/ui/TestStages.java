package app.drydock.ui;

import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Shows a TestFX scene on a stage that is sized to it, explicitly.
 *
 * <p><strong>Why this exists.</strong> TestFX hands every test class in a JVM
 * the SAME primary stage, and a stage remembers an explicit size across
 * classes. While no class ever set one, {@code stage.show()} sized itself to
 * whatever {@code Scene} it had just been given and every class silently got
 * the width it asked for. One class does set one -- {@code
 * ReviewVerdictBarFitTest} must, since the width IS what it tests -- and from
 * that moment every later class in the run inherits it instead of its own.</p>
 *
 * <p>Nothing about that failure names a width. It surfaces as a class whose
 * clicks land on nothing ("no clickable gutter") or whose geometry assertion
 * silently inverts: {@code ReviewDiffColumnWidthTest}'s wrap check passes at
 * an inherited 560px and FAILS at an inherited 1400px, because a 400-character
 * line stops needing to wrap. Which way it falls depends on the order the
 * classes happen to run in, so it presents as flakiness -- and it cost this
 * branch two rounds of chasing exactly that.</p>
 *
 * <p>The rule, so it does not have to be re-derived: <strong>a test class that
 * renders anything owns its own stage size.</strong> Take it from the scene
 * the class already declares rather than from a number repeated beside it, so
 * the two cannot drift.</p>
 */
public final class TestStages {

    private TestStages() {
    }

    /**
     * Sets {@code scene} on {@code stage}, sizes the stage to it, and shows
     * it. The size comes from the scene's own constructed dimensions, so
     * there is no second copy of the number to keep in step.
     */
    public static void show(Stage stage, Scene scene) {
        stage.setScene(scene);
        if (scene.getWidth() > 0 && scene.getHeight() > 0) {
            stage.setWidth(scene.getWidth());
            stage.setHeight(scene.getHeight());
        } else {
            // A scene built without dimensions -- new Scene(root) -- has none
            // to copy, and setting them anyway pins the stage at 0x0: every
            // node lays out at nothing and the class fails naming no width,
            // which is the exact signature this helper exists to eliminate.
            // sizeToScene is what the plain stage.show() this replaced would
            // have done, so an unsized scene keeps its old behaviour AND
            // stops inheriting whatever the last class left.
            stage.sizeToScene();
        }
        stage.show();
    }
}

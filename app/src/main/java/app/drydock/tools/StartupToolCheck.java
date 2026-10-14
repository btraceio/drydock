package app.drydock.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The startup gate's arithmetic: which {@link ExternalTool}s are required
 * for the app to be usable, which are present, and what to ask the user to
 * fix when something is missing. Plain logic, no UI and no filesystem
 * access of its own -- the present/absent predicate is supplied by the
 * caller (production: {@code ToolLocator.locate().isPresent()}), so tests
 * exercise every branch without touching the host's installed tools.
 *
 * <p>Requirements: {@code git} must be present (every repository surface
 * runs git commands), and at least one harness ({@code pi}, {@code claude},
 * {@code codex}) must be present, because without one no session can ever
 * start. When no harness is present, all of them are reported missing: the
 * user needs to fix whichever one they intend to use, and the gate dialog
 * cannot know which.</p>
 */
public final class StartupToolCheck {

    /** The missing tools to offer fix-in-place fields for, and whether startup may proceed. */
    public record Result(List<ExternalTool> missing, boolean satisfied) {
    }

    private StartupToolCheck() {
    }

    public static Result check(Predicate<ExternalTool> present) {
        return check(present, true);
    }

    /**
     * As above, with the harness requirement optional: the {@code
     * app.drydock.diag.command} diagnostic override replaces the provider
     * built command, so a diag session runs that command and needs no
     * installed agent CLI (the CI-runner path). git is still required.
     */
    public static Result check(Predicate<ExternalTool> present, boolean requireHarness) {
        List<ExternalTool> missing = new ArrayList<>();
        if (!present.test(ExternalTool.GIT)) {
            missing.add(ExternalTool.GIT);
        }
        boolean anyHarness = ExternalTool.HARNESSES.stream().anyMatch(present);
        if (!anyHarness && requireHarness) {
            missing.addAll(ExternalTool.HARNESSES);
        }
        return new Result(List.copyOf(missing), missing.isEmpty());
    }
}

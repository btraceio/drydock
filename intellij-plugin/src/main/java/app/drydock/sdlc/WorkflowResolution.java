package app.drydock.sdlc;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of resolving a workflow for one target. {@link Invalid} is a distinct
 * variant so a malformed selection is never confused with {@link Missing}
 * (which means "use the default"); there is no empty/Optional result.
 */
public sealed interface WorkflowResolution {

    /** No configuration or no leaf for the target: the default applies. */
    record Missing(String reason) implements WorkflowResolution {
        public Missing {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A valid selection, snapshotted. */
    record Selected(SelectedWorkflow workflow) implements WorkflowResolution {
        public Selected {
            Objects.requireNonNull(workflow, "workflow");
        }
    }

    /** A selection that exists but cannot be used; keeps key/source and a safe category. */
    record Invalid(WorkflowProblem problem) implements WorkflowResolution {
        public Invalid {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** Safe error categories: never carry raw file content, absolute paths or secrets. */
    enum ProblemCategory {
        SCHEMA, VERSION, UNKNOWN_FIELD, DUPLICATE, TYPE, BLANK, UNSUPPORTED_SOURCE,
        SOURCE_UNREADABLE, ESCAPES_ROOT, LIMIT_EXCEEDED, SPECIAL_FILE, ENCODING, CANCELLED
    }

    /** {@code source} is repository-relative when present. */
    record WorkflowProblem(String configKey, Optional<String> source, ProblemCategory category, String message) {
        public WorkflowProblem {
            Objects.requireNonNull(configKey, "configKey");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(message, "message");
        }
    }
}

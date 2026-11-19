package app.drydock.sdlc;

import app.drydock.agent.api.CreateContext;
import app.drydock.domain.ModelTiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure prompt composition over an immutable {@link SelectedWorkflow} and the
 * caller's target, model and integration inputs; it reads no file or config.
 *
 * <p>Free-form: the policy (configured prePrompt, else the caller's default)
 * replaces only the clarify-first slot; title framing, the model rule,
 * {@link CreateContext#TASK_SEPARATOR} and the task bytes after it are kept.
 * Issue: the methodology (configured, else the caller's default) replaces
 * steps, quality gates, definition of done and role strategy; identity goes
 * in once, after the separator, outside the editable text. Edited methodology
 * is authoritative and survives any change of selection or harness.</p>
 */
public final class WorkflowPromptComposer {

    private WorkflowPromptComposer() {
    }

    public enum Origin { FREE_FORM, ISSUE }

    /**
     * @param workflow           selected project workflow, empty for the default path
     * @param defaultPolicy      built-in text used when {@code workflow} is empty
     * @param identity           issue identity/context (issue origin only)
     * @param task               the user's task text (free-form origin only)
     * @param editedMethodology  current editor text of an issue invocation, if any
     * @param previousSeed       seed that editor text was generated from, if any
     * @param titleFraming       available title/integration framing, verbatim
     * @param legacyTierRule     existing model rule, used only on the default path
     * @param tiers              accepted starting tiers
     * @param subagentHarness    whether the harness can dispatch subagents
     */
    public record Input(
            Origin origin,
            Optional<SelectedWorkflow> workflow,
            String defaultPolicy,
            String identity,
            String task,
            Optional<String> editedMethodology,
            Optional<String> previousSeed,
            Optional<String> titleFraming,
            Optional<String> legacyTierRule,
            Optional<ModelTiers> tiers,
            boolean subagentHarness) {

        public Input {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(defaultPolicy, "defaultPolicy");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(editedMethodology, "editedMethodology");
            Objects.requireNonNull(previousSeed, "previousSeed");
            Objects.requireNonNull(titleFraming, "titleFraming");
            Objects.requireNonNull(legacyTierRule, "legacyTierRule");
            Objects.requireNonNull(tiers, "tiers");
        }
    }

    public static ComposedPrompt compose(Input in) {
        in.workflow().ifPresent(w -> requireTarget(in.origin(), w.target()));
        boolean configured = in.workflow().isPresent();
        String seed = configured ? in.workflow().get().promptText() : in.defaultPolicy();
        String source = describe(in.workflow());
        String tierRule = tierRule(in, configured);
        String title = in.titleFraming().orElse("");

        if (in.origin() == Origin.FREE_FORM) {
            Optional<String> prompt = Optional.of(in.task()).filter(t -> !t.isBlank()).map(t -> {
                if (NativeCommand.isSlashOneLiner(t)) {
                    return t;
                }
                String policy = configured ? seed.stripTrailing() + "\n\n" : seed;
                return policy + title + tierRule + CreateContext.TASK_SEPARATOR + "\n" + t;
            });
            return new ComposedPrompt(prompt, seed, seed, false, source);
        }

        boolean edited = in.editedMethodology().isPresent()
                && !in.editedMethodology().equals(in.previousSeed());
        String effective = edited ? in.editedMethodology().get() : seed;
        String provenance = edited ? "Edited for this invocation; based on " + source : source;
        List<String> body = new ArrayList<>();
        if (!in.identity().isBlank()) {
            body.add(in.identity().strip());
        }
        if (!effective.isBlank()) {
            body.add(effective.strip());
        }
        String text = title + tierRule + CreateContext.TASK_SEPARATOR + "\n" + String.join("\n\n", body);
        return new ComposedPrompt(Optional.of(text), seed, effective, edited, provenance);
    }

    /** Role-neutral, conditional tier guidance: no role mapping, nothing mandatory. */
    public static Optional<String> neutralTierRule(ModelTiers tiers) {
        List<String> parts = new ArrayList<>();
        tiers.strong().ifPresent(m -> parts.add("strong: `" + clean(m) + "`"));
        tiers.mid().ifPresent(m -> parts.add("mid: `" + clean(m) + "`"));
        tiers.low().ifPresent(m -> parts.add("low: `" + clean(m) + "`"));
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("If you choose to dispatch subagents, these model tiers are available: "
                + String.join(", ", parts) + ". Pick whichever fits the work, if any.");
    }

    private static String tierRule(Input in, boolean configured) {
        Optional<String> rule = configured
                ? in.subagentHarness() ? in.tiers().flatMap(WorkflowPromptComposer::neutralTierRule) : Optional.empty()
                : in.legacyTierRule();
        return rule.map(r -> r + "\n\n").orElse("");
    }

    private static void requireTarget(Origin origin, WorkflowTarget target) {
        boolean ok = origin == Origin.FREE_FORM
                ? target == WorkflowTarget.FREE_FORM_PRE_PROMPT
                : target == WorkflowTarget.ISSUE_IMPLEMENT || target == WorkflowTarget.ISSUE_TRIAGE;
        if (!ok) {
            throw new IllegalArgumentException(target + " does not apply to " + origin);
        }
    }

    private static String describe(Optional<SelectedWorkflow> workflow) {
        return workflow.map(w -> "project workflow " + w.source().configKey()
                + w.source().relativeSource().map(p -> " (" + p + ")").orElse(" (inline)"))
                .orElse("built-in default");
    }

    private static String clean(String model) {
        return model.replace("\n", "").replace("\r", "").replace('`', '\'');
    }

    /** A single-line harness command is sent exactly as typed. */
    private static final class NativeCommand {
        static boolean isSlashOneLiner(String text) {
            String stripped = text.strip();
            return stripped.startsWith("/") && stripped.indexOf('\n') < 0;
        }
    }
}

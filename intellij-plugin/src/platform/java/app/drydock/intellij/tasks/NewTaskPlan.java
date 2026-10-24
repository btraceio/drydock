package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.SessionMode;
import app.drydock.git.BranchNameRules;
import app.drydock.git.WorktreeNaming;
import app.drydock.projects.SharedProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The decisions behind the New task dialog, free of Swing: the generated
 * branch and folder, the one-line summary, what blocks Start, and what the
 * agent is told.
 *
 * <p>A task is named for people; the name labels the task in the tree and
 * on its tab and is <b>never</b> sent to the agent. What the agent is told
 * is the optional instructions, and nothing when they are empty — the agent
 * then opens at its own prompt.</p>
 */
public final class NewTaskPlan {

    /** Where the task works. */
    public enum WorkIn { NEW_WORKTREE, MAIN_CHECKOUT, EXISTING_WORKTREE }

    /**
     * Which window shows it: this one — as a tab, hosted when the task's
     * worktree is another project's (cross-project tabs) — or the
     * worktree's own window, opened or reused.
     */
    public enum OpenIn { THIS_WINDOW, OWN_WINDOW }

    /** A worktree the task must use: opened from {@code Start ▸} or a worktree's {@code ＋}. */
    public record FixedWorktree(Path dir, String branch) {
        public FixedWorktree {
            Objects.requireNonNull(dir, "dir");
            Objects.requireNonNull(branch, "branch");
        }
    }

    /** Everything resolved off the EDT before the dialog opens. */
    public record Inputs(SharedProject project, String repoName, Optional<FixedWorktree> fixedWorktree,
                         List<Agent> agents, AgentKind preselected, String defaultStart, List<String> startPoints,
                         List<String> remoteNames, Set<String> takenBranches, Path worktreeBase,
                         boolean writerActiveInMain, Function<AgentKind, Optional<String>> lastUsedModel,
                         Function<AgentKind, List<String>> modelSuggestions,
                         Function<AgentKind, ModelTiers> lastUsedTiers,
                         Predicate<AgentKind> supportsSubagents) {
        public Inputs {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(fixedWorktree, "fixedWorktree");
            agents = List.copyOf(agents);
            startPoints = List.copyOf(startPoints);
            remoteNames = List.copyOf(remoteNames);
            takenBranches = Set.copyOf(takenBranches);
            Objects.requireNonNull(lastUsedModel, "lastUsedModel");
            Objects.requireNonNull(modelSuggestions, "modelSuggestions");
            Objects.requireNonNull(lastUsedTiers, "lastUsedTiers");
            Objects.requireNonNull(supportsSubagents, "supportsSubagents");
        }
    }

    /**
     * What the user chose. {@code branch} and {@code folder} only mean something for a new worktree.
     *
     * <p>{@code tiers} are the subagent model tiers picked for the launch:
     * empty when the pickers were not shown (the launch records nothing and
     * the remembered tiers stay), present -- possibly all-empty, which
     * clears them -- when they were; {@code
     * subagentModelRule} is the prompt's subagent model rules clause, fully
     * composed by the dialog (it needs the preset's role mapping, the
     * harness's subagent capability and the starting model, none of which
     * travel on a Choice) or empty when the launch carries none. The rule is
     * appended to the prompt by {@link #initialPrompt(Choice)}; the tiers
     * themselves ride the launch chain so the launch records them, exactly
     * like the starting model.</p>
     */
    public record Choice(String name, Optional<String> instructions, AgentKind agent, SessionMode mode, WorkIn workIn,
                         String branch, String start, Path folder, OpenIn openIn, Optional<String> model,
                         Optional<ModelTiers> tiers, Optional<String> subagentModelRule) {
        public Choice {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(instructions, "instructions");
            Objects.requireNonNull(workIn, "workIn");
            Objects.requireNonNull(openIn, "openIn");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(tiers, "tiers");
            Objects.requireNonNull(subagentModelRule, "subagentModelRule");
        }
    }

    /** Used when a name leaves nothing usable for a branch ({@code "!!!"}, an emoji). */
    private static final String FALLBACK_BRANCH = "task";

    /** The label used when the description leaves nothing usable for a name. */
    private static final String FALLBACK_NAME = "New task";

    /** A task label longer than this is cut at a word boundary. */
    private static final int NAME_LIMIT = 60;

    private NewTaskPlan() {
    }

    /** Agents the dialog offers: the coding agents, not a plain terminal. */
    public static boolean offersAgent(AgentKind kind) {
        return kind != AgentKind.TERMINAL;
    }

    /**
     * The task's label, inferred from the description: its first non-blank
     * line with markdown markers stripped, whitespace collapsed, and cut at
     * a word boundary. The name is a label for people and is never sent to
     * the agent; text without a usable word becomes {@code New task}.
     */
    public static String nameFor(String instructions) {
        if (instructions == null) {
            return FALLBACK_NAME;
        }
        String line = "";
        for (String candidate : instructions.split("\\n", -1)) {
            String stripped = stripMarkdown(candidate).strip();
            if (!stripped.isEmpty()) {
                line = stripped;
                break;
            }
        }
        if (!usable(line)) {
            return FALLBACK_NAME;
        }
        String name = line.length() <= NAME_LIMIT ? line : cutAtWord(line);
        String unpunctuated = name.replaceAll("[\\s.,:;!?—–-]+$", "");
        return unpunctuated.isEmpty() ? name : unpunctuated;
    }

    /** Repeats so nested markers go too: {@code "- ## Title"} loses both. */
    private static String stripMarkdown(String line) {
        String stripped = line.stripLeading();
        while (true) {
            String next = stripped.replaceFirst("^(#{1,6}|[-*+]|\\d+[.)]|>)\\s+", "");
            if (next.equals(stripped)) {
                return stripped;
            }
            stripped = next;
        }
    }

    /** {@code "!!!"} and emoji-only lines label nothing. */
    private static boolean usable(String line) {
        return line.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static String cutAtWord(String line) {
        int cut = line.lastIndexOf(' ', NAME_LIMIT);
        if (cut < NAME_LIMIT / 2) {
            // One long word survives whole rather than vanishing.
            return line.substring(0, NAME_LIMIT).strip();
        }
        return line.substring(0, cut);
    }

    /** The first three words of the name as a branch, made unique against {@code taken}. */
    public static String branchFor(String name, Set<String> taken) {
        String base = FALLBACK_BRANCH;
        if (name != null && !name.isBlank()) {
            String[] words = name.strip().split("\\s+");
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < Math.min(3, words.length); i++) {
                first.append(' ').append(words[i]);
            }
            // Not WorktreeNaming.slug: that keeps only what follows the last
            // "/" (it names folders after branches like feat/x), which turned
            // "CI/CD pipeline fix" into "cd-pipeline-fix", and it answers
            // "worktree" for nothing usable, so a task named "Worktree" lost
            // its name too.
            String slug = first.toString().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (!slug.isEmpty()) {
                base = slug;
            }
        }
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    /** {@code <base>/<repo>-<branch slug>}. */
    public static Path folderFor(Path base, String repoName, String branch) {
        return WorktreeNaming.defaultDirectory(base, Optional.of(base), repoName, branch);
    }

    /** The one line under the agent and mode. */
    public static String summary(Inputs in, Choice choice) {
        return switch (choice.workIn()) {
            case EXISTING_WORKTREE -> "In existing worktree ⑂ " + choice.branch();
            case MAIN_CHECKOUT -> "Runs in the main checkout of " + in.repoName();
            case NEW_WORKTREE -> "New branch " + choice.branch() + " from " + choice.start() + " in "
                    + abbreviateHome(choice.folder());
        };
    }

    /**
     * Why Start must stay disabled, if it must. {@code folderOccupied}
     * answers whether a folder exists and is not empty (I/O, injected).
     */
    public static Optional<String> refusal(Inputs in, Choice choice, Predicate<Path> folderOccupied) {
        if (choice.agent() == null) {
            // Checked here, not after: the launch would otherwise create the
            // worktree and the window before failing.
            return Optional.of("No agent is installed. Install Claude, Codex or pi to start a task.");
        }
        if (choice.workIn() != WorkIn.NEW_WORKTREE) {
            return Optional.empty();
        }
        String branch = choice.branch().strip();
        if (branch.isEmpty()) {
            return Optional.of("Give the branch a name.");
        }
        Optional<BranchNameRules.Refusal> invalid = BranchNameRules.check(branch, in.remoteNames());
        if (invalid.isPresent()) {
            return Optional.of(BranchNameRules.humanSentence(invalid.get()));
        }
        if (!choice.folder().isAbsolute()) {
            return Optional.of("Choose a full folder path for the worktree.");
        }
        if (in.takenBranches().contains(branch)) {
            return Optional.of("A branch named " + branch + " already exists. Choose another name.");
        }
        if (folderOccupied.test(choice.folder())) {
            return Optional.of(choice.folder() + " already exists and is not empty.");
        }
        return Optional.empty();
    }

    /** A caution that leaves Start enabled but renames it {@code Start anyway}. */
    public static Optional<String> warning(Inputs in, Choice choice) {
        if (choice.mode() != SessionMode.WRITES || choice.workIn() != WorkIn.MAIN_CHECKOUT) {
            return Optional.empty();
        }
        return Optional.of(in.writerActiveInMain()
                ? "Another writing agent is active in the main checkout. Two writers can overwrite each other."
                : "A writing agent in the main checkout shares the tree with your own edits.");
    }

    /**
     * Prepended to the New task dialog's instructions: the agent must
     * clarify and settle the exact requirements before it writes code, not
     * run off with a half-specified prompt.
     */
    private static final String CLARIFY_FIRST = "Before writing any code, thoroughly clarify this request: "
            + "brainstorm to pin down the exact requirements, and confirm them before implementing.\n\n";

    /**
     * What the agent is told on start: the instructions behind a clarify-first directive, or nothing. Never the name.
     * A launch that carries a composed subagent model rule gets it appended, after a blank line.
     */
    public static Optional<String> initialPrompt(Choice choice) {
        return choice.instructions().filter(text -> !text.isBlank())
                .map(CLARIFY_FIRST::concat)
                .map(text -> choice.subagentModelRule()
                        .map(rule -> text + "\n\n" + rule)
                        .orElse(text));
    }

    public static String primaryLabel(boolean warned) {
        return warned ? "Start anyway" : "Start task";
    }

    private static String abbreviateHome(Path folder) {
        Path home = Path.of(System.getProperty("user.home"));
        return folder.startsWith(home) ? "~/" + home.relativize(folder) : folder.toString();
    }
}

package app.drydock.agent.spi;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.SphinxReviewSupport;

import app.drydock.editorqa.EditorAskLimits;
import app.drydock.editorqa.EditorAskRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one interface each agentic CLI implements. Discovered via
 * {@link java.util.ServiceLoader}, so implementations need a public no-arg
 * constructor and receive collaborators via {@link #init(AgentContext)}.
 *
 * <p>{@link #buildCreateCommand}/{@link #buildResumeCommand},
 * {@link #locateExecutable}, and {@link #probeCapabilities} may perform
 * blocking work (process spawns, filesystem probes) and MUST be called off the
 * UI thread.</p>
 */
public interface AgentProvider {

    AgentKind kind();

    String displayName();

    void init(AgentContext ctx);

    Optional<Path> locateExecutable();

    String describeSearched();

    AgentCapabilities probeCapabilities();

    /**
     * Whether this integration supports SSH-remote sessions. This is a static
     * fact about the integration (not something detected from the CLI), so
     * implementations MUST make this CHEAP and non-blocking: no process
     * spawns, no filesystem or network I/O. Safe to call on the UI
     * Application Thread.
     */
    boolean supportsRemote();

    /**
     * Whether this integration can dispatch a subagent -- a nested agent with
     * its own context. Drydock asks for a review in one when it can, so the
     * review is read outside the context that wrote the code.
     *
     * <p>Like {@link #supportsRemote()}, a static fact about the integration:
     * implementations MUST make this CHEAP and non-blocking -- no process
     * spawns, no filesystem or network I/O. Safe to call on the UI
     * Application Thread.</p>
     */
    default boolean supportsSubagents() {
        return false;
    }

    /**
     * Whether this integration can actually hold a read-only session to
     * reading -- i.e. whether {@code CreateContext.readOnly()} turns into a
     * real flag on the CLI's command line. A provider whose CLI has no
     * read-only mode returns {@code false}, and the UI then says the
     * restriction is <em>not enforced</em> instead of showing a guarantee the
     * agent cannot keep.
     *
     * <p>Like {@link #supportsRemote()}, a static fact about the integration:
     * implementations MUST make this CHEAP and non-blocking. A provider whose
     * flag is probed from {@code --help} returns the probed snapshot, never a
     * fresh probe.</p>
     */
    default boolean enforcesReadOnly() {
        return false;
    }

    /**
     * How this integration is handed drydock's own MCP tools -- a file it
     * points at, overrides on its command line, or not at all. A static fact
     * about the integration, like {@link #supportsRemote()}, so implementations
     * MUST make this CHEAP and non-blocking: no process spawns, no I/O. Safe on
     * the UI thread.
     *
     * <p>Distinct from any probed CLI flag: this says what the integration can
     * consume at all, not that the installed binary accepts the option. The
     * binary check stays provider-internal (Claude's {@code
     * ClaudeCapabilities.supportsMcpConfig}), per this interface's rule that
     * provider-internal flag detail is not exposed here.</p>
     *
     * <p>This replaced a boolean {@code supportsMcpConfig}. The question it
     * asked -- "does this understand Claude's config file" -- stopped being
     * answerable yes/no once Codex reached the same server by a different
     * mechanism and had no use for a file.</p>
     */
    McpDelivery mcpDelivery();

    LaunchPlan buildCreateCommand(CreateContext c);

    LaunchPlan buildResumeCommand(ResumeContext r);

    /**
     * A non-interactive argv that resumes the exact provider conversation and
     * asks it for one transfer summary.  Unlike the interactive launch plans,
     * this is an argument list for {@code ProcessRunner}: no shell expansion,
     * no PTY, and no provider picker.  Providers that cannot do this return
     * empty, which makes Transfer unavailable instead of risking the wrong
     * conversation.
     */
    default Optional<List<String>> buildTransferSummaryCommand(Path workingDirectory,
                                                                String agentSessionId,
                                                                String prompt) {
        return Optional.empty();
    }

    /**
     * A non-interactive, WRITE-CAPABLE argv that resumes the exact provider
     * conversation {@code agentSessionId} in {@code workingDirectory} with
     * one prompt and exits when the turn ends: the detached babysitting
     * watcher's headless run, while the IDE is closed. The agent may edit
     * files and run {@code git}/{@code gh}; nothing prompts (there is no
     * terminal), so a provider must grant exactly that up front and nothing
     * it cannot grant without asking. {@code sharedDirectory} is the
     * babysitting directory outside the worktree the agent must read (its
     * delta and instructions) and write (its request files) -- a provider
     * whose tools are confined to the working directory adds it.
     *
     * <p>Like {@link #buildTransferSummaryCommand}: an argument list for
     * {@code ProcessRunner}, never a shell string, never a picker. Called by
     * a separate watcher process that never calls {@link #init}: an
     * implementation may use executable discovery and bounded, ProcessRunner-run
     * capability probes of the executable itself (for example, {@code --help}),
     * but must not depend on {@code init} state or run on the UI thread.
     * Providers that cannot supply a required capability return empty;
     * the watcher leaves pending events for delivery when the IDE is available.</p>
     */
    default Optional<List<String>> buildHeadlessWriteCommand(Path workingDirectory, Path sharedDirectory,
                                                              String agentSessionId, String prompt) {
        return Optional.empty();
    }

    SessionIdStrategy idStrategy();

    /**
     * Model ids this harness's CLI can be launched with, offered by the
     * new-task dialog's model picker. May block (Pi enumerates its models
     * with a {@code pi --list-models} subprocess), so callers MUST run this
     * off the EDT. The list is a SUGGESTION, never a whitelist: the launch
     * command carries whatever model string the human typed, whether or not
     * it appears here -- a catalog can be stale, and Claude and Codex have
     * no listing command at all, so their implementations return only
     * curated aliases or nothing.
     */
    default List<String> modelSuggestions() {
        return List.of();
    }

    Optional<ConversationSource> conversations();

    Optional<ActivityReporter> activity();

    /** Present only for DISCOVERED-strategy providers; empty for PRESET. */
    Optional<SessionIdDiscovery> idDiscovery();

    /**
     * How this integration reaches the sphinx review plugin, probed
     * out-of-band (e.g. at {@link #init}, a cheap config-file read) and
     * returned as a snapshot -- never probe here, so callers may read this on
     * the EDT. Empty when the plugin is not installed; callers hide sphinx
     * affordances rather than disable them.
     */
    default Optional<SphinxReviewSupport> sphinxReview() {
        return Optional.empty();
    }

    /**
     * Whether this provider can actually route an eval session's traffic to
     * the eval account. Cheap and cached: the UI reads it synchronously to
     * enable/disable the eval checkbox, so an implementor MUST probe
     * out-of-band (e.g. at {@link #init}) and return a snapshot here, never
     * block. Default {@code true} (the provider injects eval through the
     * command or a side channel); a provider with no working injection path
     * returns {@code false} so the checkbox is disabled rather than misleading.
     */
    default boolean evalAvailable() {
        return true;
    }

    /**
     * Marks {@code sessionKey} as an eval session with whatever side channel
     * the provider uses (e.g. an HTTP call to a local proxy that injects the
     * header). Called on a background executor at eval-session launch (new
     * and resume); best-effort, never fails the launch. Default no-op: a
     * provider that injects eval purely through the launch command (env var)
     * needs do nothing. {@code sessionKey} is the agent session id the CLI
     * sends on its requests (for PRESET providers, the {@code --session-id}
     * drydock generated).
     */
    default void markEvalSession(String sessionKey) { }

    /**
     * Reverses {@link #markEvalSession}. Called on a background executor when
     * an eval session closes, exits, or is deleted. Idempotent and safe when
     * {@code markEvalSession} never ran for {@code sessionKey}.
     */
    default void unmarkEvalSession(String sessionKey) { }

    /**
     * When the eval session's auth token expires, for display only. Empty
     * for a provider that does not resolve a token with a knowable expiry
     * (or before {@link #markEvalSession} has run). The value is a snapshot
     * from the token resolved at launch; it is not refreshed live, so a
     * long-running session may outlive it -- the UI shows it as a hint, not
     * a hard deadline.
     */
    default Optional<Instant> evalTokenExpiry(String sessionKey) {
        return Optional.empty();
    }

    /**
     * Prepares a marked editor-ask <em>create</em> launch: the one command that
     * starts the conversation carrying {@code request}'s first turn as the
     * provider's single positional launch prompt, plus typed proof that the
     * rendered command reaches {@code editor_ask_answer} and observable turn
     * evidence. Default: refused -- a provider that has not built this cannot
     * serve Q&amp;A. May block (file installs, capability probes); call off the
     * UI thread. Arms {@code request.firstTurnMarker()} on the returned observer
     * before returning, i.e. before any spawn or write.
     *
     * <p>{@code c.initialPrompt()} must be empty: the prompt travels only in
     * {@code request}, so the first turn is carried exactly once.</p>
     */
    default EditorAskLaunch prepareEditorAskCreate(CreateContext c, EditorAskRequest request) {
        return EditorAskLaunch.refused("This agent does not support editor questions.");
    }

    /**
     * As {@link #prepareEditorAskCreate} for a pinned <em>resume</em> of the
     * exact conversation {@code r.agentSessionId()} (required);
     * {@code r.resumePrompt()} must be empty. No model flag is rendered.
     */
    default EditorAskLaunch prepareEditorAskResume(ResumeContext r, EditorAskRequest request) {
        return EditorAskLaunch.refused("This agent does not support editor questions.");
    }

    /**
     * What a Q&amp;A launch carries.
     *
     * @param launchNonce      fresh, nonsecret nonce of this launch (also the
     *                         environment/hook correlation value where rendered)
     * @param firstTurnMarker  canonical turn ID armed before the launch
     * @param firstTurnPrompt  the composed single-line first turn
     */
    record EditorAskRequest(String launchNonce, String firstTurnMarker, String firstTurnPrompt) {
        private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9-]{8,64}");

        public EditorAskRequest {
            if (!NONCE.matcher(Objects.requireNonNull(launchNonce, "launchNonce")).matches()) {
                throw new IllegalArgumentException("invalid launch nonce");
            }
            EditorAskRecord.parseId(Objects.requireNonNull(firstTurnMarker, "firstTurnMarker"));
            EditorAskLimits.checkPrompt(Objects.requireNonNull(firstTurnPrompt, "firstTurnPrompt"));
            if (!firstTurnPrompt.contains(firstTurnMarker)) {
                throw new IllegalArgumentException("the first turn does not carry its marker");
            }
        }

        @Override
        public String toString() {
            return "EditorAskRequest[]";
        }
    }

    /** One thing a rendered Q&amp;A command must contain for the tool to be reachable. */
    enum EditorAskPiece {
        /** Claude's supported {@code --mcp-config <owner-only file>} flag. */
        CLAUDE_MCP_CONFIG_FLAG,
        /** Claude's per-launch correlated hook settings ({@code --settings}). */
        CLAUDE_LAUNCH_HOOKS,
        /** Pi's owner-only MCP JSON, handed over by its environment variable. */
        PI_MCP_CONFIG_FILE,
        /** Pi's installed per-session MCP registration adapter ({@code -e}). */
        PI_MCP_ADAPTER,
        /** Pi's per-launch turn-evidence extension ({@code -e}). */
        PI_EDITOR_ASK_EXTENSION,
        /** Codex's {@code mcp_servers.*.url} and {@code env_http_headers} overrides. */
        CODEX_ENDPOINT_OVERRIDES,
        /** Codex's token environment variable read from the owner-only token file. */
        CODEX_TOKEN_ENV_FROM_FILE,
        /** The first turn as the single positional launch prompt. */
        POSITIONAL_LAUNCH_PROMPT
    }

    /** How the provider conversation ID is proven for this launch. */
    enum EditorAskConversationProof {
        /** The preset session ID rendered into this command. */
        RENDERED_PRESET_ID,
        /** An ID reported by this launch's correlated hook. */
        CORRELATED_HOOK,
        /** The exact ID reported by this launch's Pi extension. */
        PI_EXTENSION,
        /** The thread of the Codex rollout holding this turn's user marker. */
        CODEX_ROLLOUT_THREAD
    }

    /**
     * Typed evidence, built from the rendering branches, that a launch plan
     * delivers the editor-answer tool. The constructor refuses a piece set
     * that is incomplete for the provider, so an instance IS the proof;
     * {@code LaunchPlan.supported()}, a file existing, {@link McpDelivery} and
     * a minted token are none of it.
     */
    record EditorAskDelivery(AgentKind provider, Set<EditorAskPiece> pieces,
                             EditorAskConversationProof proof) {
        public EditorAskDelivery {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(proof, "proof");
            pieces = Set.copyOf(pieces);
            if (!pieces.containsAll(required(provider))) {
                throw new IllegalArgumentException("incomplete editor-ask delivery for " + provider);
            }
        }

        /** The pieces every launch of {@code provider} must render. */
        public static Set<EditorAskPiece> required(AgentKind provider) {
            return switch (provider) {
                case CLAUDE -> EnumSet.of(EditorAskPiece.CLAUDE_MCP_CONFIG_FLAG,
                        EditorAskPiece.CLAUDE_LAUNCH_HOOKS, EditorAskPiece.POSITIONAL_LAUNCH_PROMPT);
                case PI -> EnumSet.of(EditorAskPiece.PI_MCP_CONFIG_FILE, EditorAskPiece.PI_MCP_ADAPTER,
                        EditorAskPiece.PI_EDITOR_ASK_EXTENSION, EditorAskPiece.POSITIONAL_LAUNCH_PROMPT);
                case CODEX -> EnumSet.of(EditorAskPiece.CODEX_ENDPOINT_OVERRIDES,
                        EditorAskPiece.CODEX_TOKEN_ENV_FROM_FILE, EditorAskPiece.POSITIONAL_LAUNCH_PROMPT);
                default -> throw new IllegalArgumentException("no editor-ask delivery for " + provider);
            };
        }
    }

    /**
     * The result of preparing a Q&amp;A launch: the plan to run, the delivery
     * evidence and the already-armed observer -- or a refusal. Absent
     * evidence means refuse: nothing may be handed to the harness.
     */
    record EditorAskLaunch(LaunchPlan plan, Optional<EditorAskDelivery> delivery,
                           Optional<EditorAskTurnObserver> observer, String refusal) {
        public EditorAskLaunch {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(observer, "observer");
            Objects.requireNonNull(refusal, "refusal");
            if (delivery.isPresent() && (!plan.supported() || observer.isEmpty())) {
                throw new IllegalArgumentException("delivery evidence needs a supported plan and an observer");
            }
        }

        public static EditorAskLaunch refused(String reason) {
            return new EditorAskLaunch(LaunchPlan.unsupported(), Optional.empty(), Optional.empty(), reason);
        }

        public static EditorAskLaunch accepted(LaunchPlan plan, EditorAskDelivery delivery,
                                               EditorAskTurnObserver observer) {
            return new EditorAskLaunch(plan, Optional.of(delivery), Optional.of(observer), "");
        }

        /** True only when the evidence is complete: safe to hand the prompt to the harness. */
        public boolean accepted() {
            return delivery.isPresent();
        }

        /** The command is never printed: it carries the endpoint and credential locations. */
        @Override
        public String toString() {
            return "EditorAskLaunch[accepted=" + accepted() + "]";
        }

        /** Why a create context is not a Q&amp;A launch, or empty. */
        public static Optional<String> refusalFor(CreateContext c) {
            return contextRefusal(c.remote().isPresent(), c.evalMode(), c.readOnly(), c.mcp().isPresent(),
                    c.initialPrompt().isPresent(), !c.stdioMcpServers().isEmpty(),
                    c.launchResources().isPresent(), c.persistence().isPresent(),
                    c.workflowAccess().isPresent(), c.remoteLaunchSupport().isPresent());
        }

        /** Why a resume context is not a pinned Q&amp;A resume, or empty. */
        public static Optional<String> refusalFor(ResumeContext r) {
            if (r.agentSessionId().isEmpty()) {
                return Optional.of("Resume needs the exact conversation ID.");
            }
            return contextRefusal(r.remote().isPresent(), r.evalMode(), r.readOnly(), r.mcp().isPresent(),
                    r.resumePrompt().isPresent(), !r.stdioMcpServers().isEmpty(),
                    r.launchResources().isPresent(), r.persistence().isPresent(),
                    r.workflowAccess().isPresent(), r.remoteLaunchSupport().isPresent());
        }

        private static Optional<String> contextRefusal(boolean remote, boolean eval, boolean readOnly,
                                                       boolean mcp, boolean prompt, boolean stdio,
                                                       boolean resources, boolean persistence,
                                                       boolean workflow, boolean remoteSupport) {
            if (remote || persistence || remoteSupport) {
                return Optional.of("Editor questions run locally only.");
            }
            if (eval) {
                return Optional.of("Editor questions do not run in eval mode.");
            }
            if (!readOnly) {
                return Optional.of("Editor questions need a read-only session.");
            }
            if (!mcp) {
                return Optional.of("No tool access was prepared for this launch.");
            }
            if (prompt) {
                return Optional.of("The first turn must travel only in the Q&A request.");
            }
            if (stdio || resources || workflow) {
                return Optional.of("Editor questions carry no extra servers or resources.");
            }
            return Optional.empty();
        }
    }
}

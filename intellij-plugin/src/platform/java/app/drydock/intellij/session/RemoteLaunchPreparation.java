package app.drydock.intellij.session;

import app.drydock.intellij.remote.RemoteForwardOwner.ForwardException;
import app.drydock.intellij.remote.RemoteReviewService;
import app.drydock.intellij.remote.RemoteReviewService.Session;

import java.util.Objects;
import java.util.Optional;

/**
 * The remote launch's capability preparation (INV-44, INV-64): core launch, workflow resources, the OPTIONAL
 * review connection/enrollment, review data and attached delivery are typed separately, never one boolean.
 * The review connection comes only from {@link RemoteReviewService#ensure} (live forward + current
 * credentials); nothing here installs software or edits SSH configuration. Blocking: callers run it off the FX thread.
 */
final class RemoteLaunchPreparation {

    /** Each capability is exactly one of these; {@code NotConsumed} marks a capability the invocation never uses. */
    sealed interface Capability {
        record Ready() implements Capability { }
        record Unavailable(String reason) implements Capability { }
        record Unverifiable(String reason) implements Capability { }
        record NotConsumed() implements Capability { }

        default boolean ready() {
            return this instanceof Ready;
        }
    }

    enum Invocation { FRESH_TASK, RESUME_NEW_PROCESS, REATTACH_LIVE, EXPLICIT_REVIEW }

    enum Attachment { ATTACHED, DETACHED, LEGACY, UNVERIFIABLE }

    /** The seam {@link RemoteReviewService#ensure} satisfies. */
    @FunctionalInterface
    interface Ensurer {
        RemoteReviewService.Ready ensure(Session session) throws ForwardException, InterruptedException;
    }

    /**
     * @param resumeId the stored conversation ID (required for {@code RESUME_NEW_PROCESS})
     * @param workflow selected/retained workflow resources, or NotConsumed for default/blank launches
     * @param data review data capability (only consumed by {@code EXPLICIT_REVIEW})
     * @param promptBypass blank/native-command prompt: no title/tool guidance is ever injected
     */
    record Request(Invocation invocation, Attachment attachment, String resumeId, Capability core,
                   Capability workflow, Capability data, boolean promptBypass, Session session) {
        Request {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(attachment, "attachment");
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(session, "session");
        }
    }

    /**
     * {@code blocked} is the hard refusal reason (no spawn, no send); {@code notice} the visible optional-tools
     * message; {@code guidanceNote} the neutral statement replacing title/tool guidance when tools are absent;
     * {@code legacyExplanation} the manual Stop-then-Resume text for a proven-legacy live process.
     */
    record Prepared(Capability core, Capability workflow, Capability review, Capability data,
                    Capability delivery, Optional<String> blocked, Optional<String> notice,
                    Optional<String> guidanceNote, Optional<String> legacyExplanation) {
        boolean proceeds() {
            return blocked.isEmpty();
        }

        /** Title guidance and the review-tools prompt are valid only when the review connection is ready. */
        boolean titleGuidance() {
            return review.ready() && guidanceNote.isEmpty();
        }
    }

    static final String TOOLS_UNAVAILABLE = "Drydock review and rename tools are unavailable in this session.";
    static final String LEGACY_EXPLANATION = "This running agent was started before Drydock review tools were "
            + "enrolled and cannot gain them. Stop it, then Resume the same conversation to get the tools; "
            + "Drydock does not restart it for you.";
    private static final int REASON_LIMIT = 200;

    private final Ensurer ensurer;

    RemoteLaunchPreparation(Ensurer ensurer) {
        this.ensurer = Objects.requireNonNull(ensurer, "ensurer");
    }

    /** Blocking. Hard checks run first so a refused launch never opens a forward. */
    Prepared prepare(Request r) {
        boolean review = r.invocation() == Invocation.EXPLICIT_REVIEW;
        Optional<String> hard = hardFailure(r);
        Capability data = review ? r.data() : new Capability.NotConsumed();
        Capability delivery = review ? delivery(r.attachment()) : new Capability.NotConsumed();
        if (hard.isPresent()) {
            return build(r, new Capability.NotConsumed(), data, delivery, hard, Optional.empty());
        }
        if (r.invocation() == Invocation.REATTACH_LIVE && r.attachment() == Attachment.LEGACY) {
            // Proven legacy only (UNVERIFIABLE is not legacy): explain, never auto-fix; no forward, no ensure.
            return build(r, new Capability.NotConsumed(), data, delivery, Optional.empty(),
                    Optional.of(LEGACY_EXPLANATION));
        }
        Capability conn = connect(r.session());
        String refusal = review ? firstNotReady(conn, data, delivery) : null;
        return build(r, conn, data, delivery, Optional.ofNullable(refusal), Optional.empty());
    }

    /**
     * Post-spawn enrollment outcome. The process is already running and is never killed or restarted: a
     * failure downgrades ONLY the tools (review) capability, adding the optional-tools notice and guidance note.
     */
    Prepared afterSpawn(Prepared p, Capability enrollment, boolean promptBypass) {
        if (enrollment.ready() || enrollment instanceof Capability.NotConsumed) {
            return p;
        }
        Optional<String> guidance = promptBypass ? Optional.<String>empty() : Optional.of(TOOLS_UNAVAILABLE);
        return new Prepared(p.core(), p.workflow(), enrollment, p.data(), p.delivery(), p.blocked(),
                Optional.of("Task started without Drydock review tools: " + reasonOf(enrollment)), guidance,
                p.legacyExplanation());
    }

    private static Optional<String> hardFailure(Request r) {
        if (r.invocation() == Invocation.RESUME_NEW_PROCESS
                && (r.resumeId() == null || r.resumeId().isBlank())) {
            return Optional.of("Cannot resume: no stored conversation ID; a new conversation is never started");
        }
        return Optional.ofNullable(firstNotReady(r.core(), r.workflow()));
    }

    private static String firstNotReady(Capability... caps) {
        for (Capability c : caps) {
            if (c instanceof Capability.Unavailable u) {
                return u.reason();
            }
            if (c instanceof Capability.Unverifiable u) {
                return u.reason();
            }
        }
        return null;
    }

    private static Capability delivery(Attachment a) {
        return switch (a) {
            case ATTACHED -> new Capability.Ready();
            case DETACHED -> new Capability.Unavailable("Resume/reattach the console, then retry review");
            case LEGACY -> new Capability.Unavailable("the running agent has no Drydock tools; Stop it, "
                    + "then Resume the same conversation, and retry review");
            case UNVERIFIABLE -> new Capability.Unverifiable("the session's attachment could not be verified");
        };
    }

    private Capability connect(Session session) {
        try {
            ensurer.ensure(session);
            return new Capability.Ready();
        } catch (ForwardException e) {
            return new Capability.Unavailable(safe(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Capability.Unverifiable("interrupted while preparing the review connection");
        } catch (RuntimeException e) {
            return new Capability.Unverifiable(safe(e.getMessage()));
        }
    }

    private static Prepared build(Request r, Capability conn, Capability data, Capability delivery,
                                  Optional<String> blocked, Optional<String> legacy) {
        boolean task = r.invocation() != Invocation.EXPLICIT_REVIEW;
        boolean absent = !conn.ready() && !(conn instanceof Capability.NotConsumed) && blocked.isEmpty();
        Optional<String> notice = task && absent
                ? Optional.of("Task started without Drydock review tools: " + reasonOf(conn)) : Optional.empty();
        Optional<String> guidance = task && !conn.ready() && !r.promptBypass()
                ? Optional.of(TOOLS_UNAVAILABLE) : Optional.empty();
        return new Prepared(r.core(), r.workflow(), conn, data, delivery, blocked, notice, guidance, legacy);
    }

    private static String reasonOf(Capability c) {
        return c instanceof Capability.Unavailable u ? u.reason()
                : c instanceof Capability.Unverifiable u ? u.reason() : "";
    }

    private static String safe(String s) {
        String t = s == null || s.isBlank() ? "unknown failure" : s.strip();
        return t.length() > REASON_LIMIT ? t.substring(0, REASON_LIMIT) + "…" : t;
    }
}

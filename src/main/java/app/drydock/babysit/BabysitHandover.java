package app.drydock.babysit;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.babysit.BabysitStateFile.Contents;
import app.drydock.babysit.BabysitStateFile.DetachedEngagement;
import app.drydock.babysit.BabysitStateFile.Detachment;
import app.drydock.babysit.BabysitStateFile.Owner;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The plugin's half of detached babysitting: handing the active engagements
 * to a watcher at IDE quit ({@link #detach}) and taking them back at the
 * next start ({@link #reattach}). The decisions are pure functions; the two
 * entry points do the file work and leave the IDE-bound parts (publishing,
 * cleaning files of ended engagements, following a watcher that is still
 * finishing) to the caller.
 *
 * <p><b>Who owns what.</b> {@code state.json} only ever has the plugin as
 * writer. The babysitting state file is shared, each write under its lock:
 * the plugin writes the {@link Detachment} at detach and clears it, the
 * ingested escalations and the owner marker at re-attach; the watcher
 * writes its owner marker, queued escalations, rerun counts, and the end of
 * an engagement. The watcher lock says whether a watcher lives.</p>
 *
 * <p><b>Detach</b> (IDE quit, after the in-IDE monitor stopped, before the
 * state store's final flush): one {@link DetachedEngagement} per active
 * pairing whose rows and repository still resolve, under a fresh
 * generation, written to the state file -- which also tells any older
 * watcher still finishing a run to stop -- and then the watcher is spawned
 * with that generation. Nothing is written when no engagement is active.</p>
 *
 * <p><b>Re-attach</b> (plugin start, after the monitor's restore): whether a
 * watcher lives comes from its lock, not from the owner marker (a crashed
 * watcher leaves a stale marker; {@link #ownerStatus}). First the
 * detachment is REVOKED in the state file (which is what stops a live
 * watcher: no run starts and no end is recorded under a revoked
 * generation); when that cannot be confirmed the caller must not wake those
 * babysitters ({@link Reattached#hold()}). Then the queued escalations go
 * into {@code ApplicationState.escalations} in one store update --
 * de-duplicated by id -- together with STOPPED for every engagement the
 * revoked detachment records as ended; when no watcher lives, requests left
 * in a drop box (a watcher that died before draining it) are taken too:
 * escalations as written, a rerun request as an escalation saying nothing
 * was rerun, each under its request's stable id. Only once the store has
 * saved that durably ({@link ApplicationStateStore#flushDurably}) is the
 * state file cleared of exactly what was ingested (the ids, the revoked
 * detachment, the owner marker read at the start) and are the drop-box
 * files deleted; a failure at any step leaves the sources for a later
 * re-attach. A live watcher may still be finishing one headless run; the
 * caller runs {@link #followUp} when it exits, to take what that run
 * queued.</p>
 */
public final class BabysitHandover {

    private static final Logger LOG = System.getLogger(BabysitHandover.class.getName());

    private BabysitHandover() {
    }

    /** Whether a watcher owns the state file. */
    public enum OwnerStatus {
        /** No watcher lives and none left a marker. */
        NONE,
        /** A watcher holds the watcher lock. */
        LIVE,
        /** A marker remains but its watcher is gone (the OS released its lock). */
        STALE
    }

    /** The watcher's status: the lock decides liveness, the marker only whether one is left behind. Pure. */
    public static OwnerStatus ownerStatus(Optional<Owner> owner, boolean watcherLockHeld) {
        if (watcherLockHeld) {
            return OwnerStatus.LIVE;
        }
        return owner.isPresent() ? OwnerStatus.STALE : OwnerStatus.NONE;
    }

    /**
     * Whether the process now running as {@code owner.pid()}, which started
     * at {@code startOfPid}, is the watcher that wrote {@code owner}: a pid
     * is reused, its start instant is not. Pure.
     */
    public static boolean isOwnerProcess(Owner owner, Optional<Instant> startOfPid) {
        return startOfPid.filter(owner.startedAt()::equals).isPresent();
    }

    // ---- detach ----------------------------------------------------------------

    /**
     * The detachment of every active engagement in {@code state}, under
     * {@code generation}; empty when none can be carried. An engagement is
     * skipped (logged) when its babysitter or watched row, or its
     * repository, no longer resolves, when the babysitter is remote or a
     * plain terminal. {@code repositoryOf} answers a repository root's
     * configured GitHub {@code owner/repo}. Pure but for the logging.
     */
    public static Optional<Detachment> detachmentOf(ApplicationState state, UUID generation, Instant at,
                                                    Function<Path, Optional<String>> repositoryOf) {
        List<DetachedEngagement> engagements = new ArrayList<>();
        for (BabysitPairing pairing : state.pairings()) {
            if (pairing.state() == BabysitState.STOPPED) {
                continue;
            }
            Optional<ManagedAgentSession> babysitter = row(state, pairing.babysitterId());
            Optional<Path> root = state.repositories().stream()
                    .filter(repository -> repository.id().equals(pairing.repositoryId()))
                    .map(Repository::root)
                    .findFirst();
            if (babysitter.isEmpty() || row(state, pairing.watchedId()).isEmpty() || root.isEmpty()) {
                LOG.log(Level.INFO, "Babysitting PR #" + pairing.prNumber() + " of " + pairing.watchedId()
                        + " is not detached: a session or its repository no longer exists");
                continue;
            }
            if (babysitter.get().workspaceBox().isPresent() || babysitter.get().agentKind() == AgentKind.TERMINAL) {
                LOG.log(Level.INFO, "Babysitter " + pairing.babysitterId() + " cannot run headlessly; not detached");
                continue;
            }
            Optional<String> repository;
            try {
                repository = repositoryOf.apply(root.get());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read the GitHub repository setting of " + root.get(), e);
                repository = Optional.empty();
            }
            engagements.add(new DetachedEngagement(pairing.babysitterId(), pairing.watchedId(),
                    pairing.repositoryId(), pairing.prNumber(), root.get(), repository,
                    babysitter.get().workingDirectory(), babysitter.get().agentKind().persistedName(),
                    babysitter.get().agentSessionId(), pairing.knownHead(), Optional.empty()));
        }
        return engagements.isEmpty() ? Optional.empty() : Optional.of(new Detachment(generation, at, engagements));
    }

    /** Starts the watcher for a generation; {@code spawn} is the documented detached spawn. */
    @FunctionalInterface
    public interface Spawner {
        long spawn(UUID generation) throws IOException;
    }

    /**
     * Hands {@code state}'s active engagements to a new watcher: the
     * detachment is written first, then the watcher is spawned with its
     * generation. Answers the generation, empty when nothing was handed over
     * (no active engagement, an unwritable state file, or a failed spawn --
     * a detachment then stays without a watcher and the next start's
     * re-attach clears it). Blocking; never throws.
     */
    public static Optional<UUID> detach(ApplicationState state, BabysitStateFile stateFile,
                                        Function<Path, Optional<String>> repositoryOf, Instant at, Spawner spawner) {
        Optional<Detachment> detachment = detachmentOf(state, UUID.randomUUID(), at, repositoryOf);
        if (detachment.isEmpty()) {
            return Optional.empty();
        }
        try {
            stateFile.update(contents -> contents.withDetachment(detachment));
        } catch (BabysitStateFileException e) {
            LOG.log(Level.WARNING, "Could not hand babysitting over to a detached watcher", e);
            return Optional.empty();
        }
        UUID generation = detachment.get().generation();
        try {
            long pid = spawner.spawn(generation);
            LOG.log(Level.INFO, "Babysitting " + detachment.get().engagements().size() + " engagement(s) continues"
                    + " in detached watcher " + pid + " (detachment " + generation + ")");
            return Optional.of(generation);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not start the detached babysitting watcher; babysitting pauses until"
                    + " the IDE starts again", e);
            return Optional.empty();
        }
    }

    // ---- re-attach -------------------------------------------------------------

    /** What a re-attach takes from the state file (and leftover drop boxes). */
    public record Ingest(List<Escalation> escalations, Set<ManagedSessionId> ended, Optional<UUID> generation) {
        public Ingest {
            escalations = List.copyOf(escalations);
            ended = Set.copyOf(ended);
            Objects.requireNonNull(generation, "generation");
        }

        public Ingest plus(List<Escalation> more) {
            List<Escalation> all = new ArrayList<>(escalations);
            all.addAll(more);
            return new Ingest(all, ended, generation);
        }

        boolean takesAnything() {
            return !escalations.isEmpty() || !ended.isEmpty();
        }
    }

    /**
     * What {@code contents} hands back: its queue, and -- only from a
     * REVOKED detachment of {@code generation} (any generation when empty)
     * -- the engagements that ended and the generation to clear. A
     * detachment still in force is a watcher's to write: none of its ends
     * is taken, since it could record another one after this read. Pure.
     */
    public static Ingest planIngest(Contents contents, Optional<UUID> generation) {
        Optional<Detachment> revoked = contents.detachment()
                .filter(Detachment::revoked)
                .filter(detachment -> generation.isEmpty() || generation.get().equals(detachment.generation()));
        Set<ManagedSessionId> ended = new LinkedHashSet<>();
        revoked.ifPresent(detachment -> detachment.engagements().stream()
                .filter(engagement -> engagement.ended().isPresent())
                .forEach(engagement -> ended.add(engagement.babysitterId())));
        return new Ingest(contents.escalationQueue(), ended, revoked.map(Detachment::generation));
    }

    /**
     * {@code state} with {@code ingest} applied: every escalation not
     * already present (by id) appended to its engagement, which becomes
     * ESCALATED ({@link BabysitStateTransforms#withEscalationAppended}) --
     * one whose engagement is no longer active is skipped and handed to
     * {@code dropped} (to log once the store's lock is released) -- then
     * every ended engagement STOPPED. The same instance when nothing
     * changes. Pure but for {@code dropped}.
     */
    public static ApplicationState applyIngest(ApplicationState state, Ingest ingest, Consumer<Escalation> dropped) {
        ApplicationState next = state;
        Set<UUID> present = new HashSet<>();
        state.escalations().forEach(escalation -> present.add(escalation.id()));
        for (Escalation escalation : ingest.escalations()) {
            if (!present.add(escalation.id())) {
                continue;
            }
            try {
                next = BabysitStateTransforms.withEscalationAppended(next, escalation);
            } catch (IllegalArgumentException e) {
                dropped.accept(escalation);
            }
        }
        for (ManagedSessionId babysitter : ingest.ended()) {
            next = BabysitStateTransforms.withPairingState(next, babysitter, BabysitState.STOPPED);
        }
        return next;
    }

    /**
     * {@code contents} once {@code ingest} is durably in {@code state.json}:
     * exactly those escalations gone, the detachment gone only when it is
     * still the revoked one of {@code ingest}'s generation (whose every end
     * the ingest took: a revoked detachment records no more), and the owner
     * marker gone only when it is still {@code owner}. A newer detachment,
     * or a newer watcher's marker, is left alone. Pure.
     */
    public static Contents afterIngest(Contents contents, Ingest ingest, Optional<Owner> owner) {
        Set<UUID> ids = new HashSet<>();
        ingest.escalations().forEach(escalation -> ids.add(escalation.id()));
        Contents next = contents.withoutQueued(ids);
        if (owner.isPresent() && contents.owner().equals(owner)) {
            next = next.withOwner(Optional.empty());
        }
        if (ingest.generation().isPresent() && contents.detachment()
                .filter(detachment -> detachment.revoked() && detachment.generation().equals(ingest.generation().get()))
                .isPresent()) {
            next = next.withDetachment(Optional.empty());
        }
        return next;
    }

    /**
     * The escalations a dead watcher left in {@code babysitterId}'s drop box,
     * as {@code state}'s pairings name their engagements, each under its
     * request's stable id (a re-read after a crash de-duplicates). Pure.
     */
    public static List<Escalation> leftoverEscalations(ApplicationState state, ManagedSessionId babysitterId,
                                                       BabysitDropBox.Drained drained, Instant at) {
        Optional<BabysitPairing> pairing = state.pairings().stream()
                .filter(candidate -> candidate.babysitterId().equals(babysitterId))
                .reduce((first, second) -> second.state() != BabysitState.STOPPED ? second : first);
        if (pairing.isEmpty()) {
            return List.of();
        }
        List<Escalation> escalations = new ArrayList<>();
        for (BabysitDropBox.Request request : drained.requests()) {
            request.escalation().ifPresent(text -> escalations.add(escalation(pairing.get(), request.id(), text, at)));
            request.rerun().ifPresent(rerun -> escalations.add(escalation(pairing.get(), request.id(),
                    "While the IDE was closed the babysitter asked Drydock to rerun CI run " + rerun.runId()
                            + rerun.checkName().map(n -> " (" + n + ")").orElse("")
                            + ", but the detached watcher ended before it did: nothing was rerun. Check the run"
                            + " and rerun it if it is a flake.", at)));
        }
        return escalations;
    }

    private static Escalation escalation(BabysitPairing pairing, UUID id, String text, Instant at) {
        return new Escalation(id, pairing.watchedId(), pairing.babysitterId(), pairing.prNumber(), text, at, false);
    }

    /**
     * Whose wakes the IDE must hold back after a re-attach could not
     * confirm that no watcher may still run them: every babysitter when
     * {@code all} (the state file could not be read), else those of the
     * detachment whose revocation failed.
     */
    public record Hold(boolean all, Set<ManagedSessionId> babysitters) {
        public Hold {
            babysitters = Set.copyOf(babysitters);
        }

        public static final Hold ALL = new Hold(true, Set.of());

        /** Whether {@code babysitter}'s wakes are held. */
        public boolean covers(ManagedSessionId babysitter) {
            return all || babysitters.contains(babysitter);
        }
    }

    /**
     * What a re-attach did, for the caller's IDE-side follow-up. {@code
     * hold} is present when the takeover is NOT confirmed: a watcher may
     * still be allowed to run the held babysitters, so the IDE must not wake
     * them until a later re-attach confirms it. {@code complete} is false
     * when what was taken could not be acknowledged (the catalog save, or
     * the state file's clearing, failed): the sources stay, and a later
     * re-attach takes them again, de-duplicated by id. {@code liveWatcher}
     * and {@code generation} name a watcher that still lives and the
     * revoked generation it ran, for {@link #followUp} once it exits.
     */
    public record Reattached(boolean stateChanged, Set<ManagedSessionId> ended, Optional<Owner> liveWatcher,
                             Optional<UUID> generation, Optional<Hold> hold, boolean complete) {
        public Reattached {
            ended = Set.copyOf(ended);
            Objects.requireNonNull(liveWatcher, "liveWatcher");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(hold, "hold");
        }

        public static Reattached nothing() {
            return new Reattached(false, Set.of(), Optional.empty(), Optional.empty(), Optional.empty(), true);
        }

        static Reattached held(Hold hold) {
            return new Reattached(false, Set.of(), Optional.empty(), Optional.empty(), Optional.of(hold), false);
        }
    }

    /**
     * Takes the engagements back from a detached watcher (see the class
     * doc), in this order, each step only once the previous one is durable:
     * <ol>
     *   <li>REVOKE: the detachment is marked revoked under the state file's
     *       lock, so no watcher starts a run or records an end from here on
     *       (its under-lock check needs a readable, current generation).
     *       An unreadable file, or a revocation that cannot be written, is a
     *       {@link Reattached#hold()}: the IDE must not wake those
     *       babysitters, and nothing else is done.</li>
     *   <li>INGEST what the revoked file holds (and, when no watcher lives,
     *       the leftover drop-box requests) into {@code state.json} in one
     *       store update, then {@link ApplicationStateStore#flushDurably}.
     *       A failed save leaves every source as it was.</li>
     *   <li>ACKNOWLEDGE: only then are exactly the ingested escalation ids,
     *       the revoked detachment (all of whose ends were ingested) and the
     *       owner marker read at the start removed from the state file, and
     *       the drop-box request files deleted.</li>
     * </ol>
     * Blocking; never throws.
     */
    public static Reattached reattach(ApplicationStateStore store, BabysitStateFile stateFile, Path baseDir,
                                      Instant at) {
        boolean watcherLive = BabysitLocks.isHeld(BabysitLocks.watcherLockFile(baseDir));
        BabysitStateFile.Snapshot snapshot = stateFile.read();
        if (snapshot.problem().isPresent()) {
            LOG.log(Level.WARNING, "Babysitting cannot re-attach: " + snapshot.problem().get()
                    + ". Babysitters are not woken until it can.");
            return Reattached.held(Hold.ALL);
        }
        Contents contents = snapshot.contents();
        Optional<Detachment> inForce = contents.detachment().filter(detachment -> !detachment.revoked());
        if (inForce.isPresent()) {
            try {
                contents = stateFile.update(Contents::withDetachmentRevoked);
            } catch (BabysitStateFileException e) {
                LOG.log(Level.WARNING, "Could not take babysitting back from the detached watcher; its"
                        + " babysitters are not woken until it can", e);
                Set<ManagedSessionId> held = new LinkedHashSet<>();
                inForce.get().engagements().forEach(engagement -> held.add(engagement.babysitterId()));
                return Reattached.held(new Hold(false, held));
            }
        }
        return ingestRevoked(store, stateFile, baseDir, at, contents, Optional.empty(), watcherLive,
                snapshot.contents().owner());
    }

    /**
     * The drain of a watcher this IDE took over, once that process has
     * exited: what its last run queued, and the ends of {@code generation}'s
     * revoked detachment. Revokes nothing and clears nothing newer -- a
     * later detach's generation, or a newer watcher's marker, stays -- and
     * clears the owner marker only when it is still {@code owner}. Blocking;
     * never throws.
     */
    public static Reattached followUp(ApplicationStateStore store, BabysitStateFile stateFile, Path baseDir,
                                      Instant at, UUID generation, Owner owner) {
        boolean watcherLive = BabysitLocks.isHeld(BabysitLocks.watcherLockFile(baseDir));
        BabysitStateFile.Snapshot snapshot = stateFile.read();
        if (snapshot.problem().isPresent()) {
            LOG.log(Level.WARNING, "Babysitting cannot take the exited watcher's last requests: "
                    + snapshot.problem().get());
            return new Reattached(false, Set.of(), Optional.empty(), Optional.empty(), Optional.empty(), false);
        }
        Reattached result = ingestRevoked(store, stateFile, baseDir, at, snapshot.contents(),
                Optional.of(generation), watcherLive, Optional.of(owner));
        // The followed watcher has exited: nothing is left to follow.
        return new Reattached(result.stateChanged(), result.ended(), Optional.empty(), Optional.empty(),
                Optional.empty(), result.complete());
    }

    /** Steps 2 and 3 of {@link #reattach}, over {@code contents} as they stand after the revocation. */
    private static Reattached ingestRevoked(ApplicationStateStore store, BabysitStateFile stateFile, Path baseDir,
                                            Instant at, Contents contents, Optional<UUID> generation,
                                            boolean watcherLive, Optional<Owner> owner) {
        Ingest planned = planIngest(contents, generation);
        Leftovers leftovers = watcherLive ? Leftovers.NONE : readLeftovers(store.state(), baseDir, at);
        Ingest ingest = planned.plus(leftovers.escalations());
        OwnerStatus status = ownerStatus(owner, watcherLive);
        if (status == OwnerStatus.STALE) {
            LOG.log(Level.INFO, "The detached babysitting watcher " + owner.get().pid()
                    + " is gone; its marker is cleared");
        }
        Optional<Owner> live = status == OwnerStatus.LIVE ? owner : Optional.empty();
        if (!ingest.takesAnything() && leftovers.requests().isEmpty() && ingest.generation().isEmpty()
                && status == OwnerStatus.NONE) {
            return Reattached.nothing();
        }
        AtomicBoolean changed = new AtomicBoolean();
        List<Escalation> dropped = new ArrayList<>();
        store.update(state -> {
            dropped.clear();
            ApplicationState next = applyIngest(state, ingest, dropped::add);
            changed.set(next != state);
            return next;
        });
        for (Escalation escalation : dropped) {
            LOG.log(Level.WARNING, "Dropping an escalation queued while the IDE was closed: its engagement"
                    + " ended. It said: " + BabysitDelta.plain(escalation.message()));
        }
        if (ingest.takesAnything() && !store.flushDurably()) {
            LOG.log(Level.WARNING, "What the detached watcher recorded could not be saved; it stays in the"
                    + " babysitting state file and is taken again later");
            return new Reattached(changed.get(), ingest.ended(), live, ingest.generation(), Optional.empty(), false);
        }
        boolean complete = true;
        try {
            stateFile.update(current -> afterIngest(current, ingest, owner));
        } catch (BabysitStateFileException e) {
            // Durably in the store already; the next re-attach takes them again and skips them by id.
            LOG.log(Level.WARNING, "Could not clear the ingested babysitting state; it is ingested again harmlessly", e);
            complete = false;
        }
        complete &= leftovers.delete();
        if (ingest.takesAnything()) {
            LOG.log(Level.INFO, "Re-attached babysitting: " + ingest.escalations().size() + " escalation(s), "
                    + ingest.ended().size() + " ended engagement(s)");
        }
        return new Reattached(changed.get(), ingest.ended(), live, ingest.generation(), Optional.empty(), complete);
    }

    /** The requests dead watchers left in the drop boxes, not yet deleted, and what they become. */
    private record Leftovers(List<Escalation> escalations, List<BabysitDropBox.Request> requests, List<Path> boxes) {
        static final Leftovers NONE = new Leftovers(List.of(), List.of(), List.of());

        /** Deletes the requests, then every box left empty; false when one could not be deleted. Blocking. */
        boolean delete() {
            boolean deleted = true;
            for (BabysitDropBox.Request request : requests) {
                try {
                    BabysitDropBox.delete(request);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not delete the ingested drop-box request " + request.file(), e);
                    deleted = false;
                }
            }
            for (Path box : boxes) {
                try (DirectoryStream<Path> rest = Files.newDirectoryStream(box)) {
                    if (!rest.iterator().hasNext()) {
                        Files.deleteIfExists(box);
                    }
                } catch (IOException e) {
                    LOG.log(Level.DEBUG, () -> "Could not remove the drop box " + box + ": " + e);
                }
            }
            return deleted;
        }
    }

    /** Reads every drop box a dead watcher left, deleting nothing. Blocking. */
    private static Leftovers readLeftovers(ApplicationState state, Path baseDir, Instant at) {
        Path root = BabysitDropBox.root(baseDir);
        if (!Files.isDirectory(root)) {
            return Leftovers.NONE;
        }
        List<Escalation> escalations = new ArrayList<>();
        List<BabysitDropBox.Request> requests = new ArrayList<>();
        List<Path> boxes = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path box : entries) {
                ManagedSessionId babysitter;
                try {
                    babysitter = ManagedSessionId.of(box.getFileName().toString());
                } catch (IllegalArgumentException notABox) {
                    continue;
                }
                try {
                    BabysitDropBox.Drained drained = BabysitDropBox.read(box);
                    escalations.addAll(leftoverEscalations(state, babysitter, drained, at));
                    requests.addAll(drained.requests());
                    boxes.add(box);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not read the babysitting drop box " + box, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list the babysitting drop boxes in " + root, e);
        }
        return new Leftovers(escalations, requests, boxes);
    }

    private static Optional<ManagedAgentSession> row(ApplicationState state, ManagedSessionId id) {
        return state.sessions().stream().filter(session -> session.id().equals(id)).findFirst();
    }
}

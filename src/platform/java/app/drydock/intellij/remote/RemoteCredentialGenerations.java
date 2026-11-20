package app.drydock.intellij.remote;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.BoxShell.BoxResult;
import app.drydock.workspace.CommandRunner;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Per-session credential / port / IDE-epoch generation state (INV-42, INV-45). Every publish gets a fresh
 * unpredictable epoch and token and a strictly higher generation; the previous generation is revoked first.
 * A publish needs {@code Ready} enrollment evidence, and a late completion of a superseded, revoked or
 * cancelled publish is discarded. Delivery of the manifest to the box goes through the {@link Delivery} seam.
 *
 * <p>Revoke/rotate marks the old generation's {@link RemoteAuthorizationPermit.Fence} invalid under the fence
 * lock alone, releases it, and only then does remote cleanup; no other lock is held while waiting on a fence.</p>
 */
public final class RemoteCredentialGenerations {

    private static final System.Logger LOG = System.getLogger(RemoteCredentialGenerations.class.getName());
    static final List<Duration> BACKOFF = List.of(1, 2, 4, 8, 16, 30).stream().map(Duration::ofSeconds).toList();
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** What identifies the session whose manifest is published. */
    public record Request(String host, String installationId, ManagedSessionId session, String agentSessionId,
                          String launchNonce, int port, int bridgeProtocol, String helperPath) {
        public Request {
            for (String s : List.of(installationId, agentSessionId, launchNonce)) {
                if (!SAFE.matcher(s).matches()) {
                    throw new IllegalArgumentException("unsafe manifest identifier");
                }
            }
            // Validates host and port with the one argv builder that owns those rules.
            SshCommandBuilder.reverseForwardCommand(host, port, 1);
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(helperPath, "helperPath");
        }
    }

    public enum Outcome { PUBLISHED, NOT_READY, BACKING_OFF, CANCELLED, DISCARDED, FAILED }

    /** The secret material of one generation; handed to the delivery route only, never logged. */
    record Credential(Request request, long generation, String token, String epoch) { }

    /** How a credential reaches the box (and is withdrawn from it); blocking, never on the FX thread. */
    interface Delivery {
        /** Delivers {@code credential}; false when it could not be delivered. Cleans up after itself. */
        boolean deliver(Credential credential, BooleanSupplier cancelled) throws IOException, InterruptedException;

        /** Removes what was published (or may be pending) for {@code request}; failures are the route's debt. */
        void withdraw(Request request, boolean published);

        /** Names of remote files that could not be removed; never content. */
        default List<String> cleanupDebt() {
            return List.of();
        }
    }

    private static final class Generation implements RemoteAuthorizationPermit.Fence {
        private final Object lock = new Object();
        private boolean revoked;
        final Request request;
        final long number;
        final String token;

        Generation(Request request, long number, String token) {
            this.request = request;
            this.number = number;
            this.token = token;
        }

        @Override
        public Object lock() {
            return lock;
        }

        @Override
        public boolean revoked() {
            return revoked;
        }

        void markRevoked() {
            synchronized (lock) {
                revoked = true;
            }
        }
    }

    private final Delivery delivery;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Object stateLock = new Object();
    private final Map<ManagedSessionId, Generation> current = new HashMap<>();
    private final Map<ManagedSessionId, Long> lastNumber = new HashMap<>();
    private final Map<ManagedSessionId, Integer> failures = new HashMap<>();
    private final Map<ManagedSessionId, Long> notBeforeMillis = new HashMap<>();

    /** {@code localDir} must be a private (0700) directory owned by this IDE. */
    public RemoteCredentialGenerations(CommandRunner runner, Path localDir, Clock clock) {
        this(new PrivateFileDelivery(Objects.requireNonNull(runner, "runner"),
                Objects.requireNonNull(localDir, "localDir")), clock);
    }

    RemoteCredentialGenerations(Delivery delivery, Clock clock) {
        this.delivery = delivery;
        this.clock = clock;
    }

    /** The T54 permit source: a permit only for the live generation of that session. */
    public Optional<RemoteAuthorizationPermit> permitFor(ManagedSessionId session) {
        synchronized (stateLock) {
            return Optional.ofNullable(current.get(session))
                    .map(g -> new RemoteAuthorizationPermit(session, g.number, g));
        }
    }

    /** The session a presented token authenticates, constant-time per candidate; revoked tokens never match. */
    public Optional<ManagedSessionId> authenticate(String token) {
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        synchronized (stateLock) {
            for (Map.Entry<ManagedSessionId, Generation> e : current.entrySet()) {
                if (MessageDigest.isEqual(presented, e.getValue().token.getBytes(StandardCharsets.UTF_8))) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Optional.empty();
    }

    public int liveTokenCount() {
        synchronized (stateLock) {
            return current.size();
        }
    }

    /** Remote pending/published files that could not be removed; names only, never content. */
    public List<String> cleanupDebt() {
        return delivery.cleanupDebt();
    }

    /** Delay before the next publish attempt for the session may start, or zero. */
    public Duration retryDelay(ManagedSessionId session) {
        synchronized (stateLock) {
            long wait = notBeforeMillis.getOrDefault(session, 0L) - clock.millis();
            return wait > 0 ? Duration.ofMillis(wait) : Duration.ZERO;
        }
    }

    /**
     * Records a failed attempt of {@code generation} (1, 2, 4, 8, 16, then 30 s). A failure reported for a
     * generation that is no longer the newest one is ignored, so a late prior-epoch completion cannot move
     * the schedule.
     */
    public void recordFailure(ManagedSessionId session, long generation) {
        synchronized (stateLock) {
            if (lastNumber.getOrDefault(session, 0L) != generation) {
                return;
            }
            int n = failures.merge(session, 1, Integer::sum);
            Duration d = BACKOFF.get(Math.min(n, BACKOFF.size()) - 1);
            notBeforeMillis.put(session, clock.millis() + d.toMillis());
        }
    }

    /** Revokes the session's live generation (loss of forward/enrollment/owner close) and cleans up. */
    public void revoke(ManagedSessionId session) {
        Generation old;
        synchronized (stateLock) {
            old = current.remove(session);
            lastNumber.merge(session, 1L, Long::sum);
        }
        if (old != null) {
            // Ordering constraint: the fence is marked under its own lock alone, outside stateLock, BEFORE any
            // remote cleanup, so a committer holding the fence never waits on slow network work or stateLock.
            old.markRevoked();
            delivery.withdraw(old.request, true);
        }
    }

    /** Publishes a fresh generation if enrollment is Ready now; blocking, never on the FX thread. */
    public Outcome publish(Request req, Supplier<Verdict> enrollment, BooleanSupplier cancelled)
            throws InterruptedException {
        ManagedSessionId session = req.session();
        if (!retryDelay(session).isZero()) {
            return Outcome.BACKING_OFF;
        }
        if (!(enrollment.get() instanceof Verdict.Ready)) {
            return Outcome.NOT_READY;
        }
        Generation gen;
        Generation old;
        synchronized (stateLock) {
            long number = lastNumber.merge(session, 1L, Long::sum);
            gen = new Generation(req, number, hex(32));
            old = current.put(session, gen);
        }
        if (old != null) {
            old.markRevoked();
        }
        Credential credential = new Credential(req, gen.number, gen.token, hex(16));
        boolean published = false;
        Outcome outcome = Outcome.FAILED;
        try {
            if (cancelled.getAsBoolean()) {
                return outcome = Outcome.CANCELLED;
            }
            if (!delivery.deliver(credential, cancelled)) {
                return outcome = cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.FAILED;
            }
            published = true;
            outcome = cancelled.getAsBoolean() ? Outcome.CANCELLED
                    : isLive(session, gen) ? Outcome.PUBLISHED : Outcome.DISCARDED;
            return outcome;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "credential publish failed for " + session.value() + ": " + e.getClass());
            return outcome = Outcome.FAILED;
        } finally {
            if (outcome == Outcome.PUBLISHED) {
                synchronized (stateLock) {
                    failures.remove(session);
                    notBeforeMillis.remove(session);
                }
            } else {
                abandon(gen);
                if (outcome == Outcome.FAILED) {
                    recordFailure(session, gen.number);
                }
                delivery.withdraw(req, published && !isNewerLive(session));
            }
        }
    }

    private boolean isLive(ManagedSessionId session, Generation gen) {
        synchronized (stateLock) {
            return current.get(session) == gen;
        }
    }

    /** A failed, cancelled or superseded attempt must not stay authorized (and never revokes a newer one). */
    private void abandon(Generation gen) {
        synchronized (stateLock) {
            current.remove(gen.request.session(), gen);
        }
        gen.markRevoked();
    }

    private boolean isNewerLive(ManagedSessionId session) {
        synchronized (stateLock) {
            return current.containsKey(session);
        }
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * The accepted private-file route: manifest to a private local 0600 file (exclusive create, deleted in
     * finally), bounded scp into a verified remote private pending file, then the T58 helper run with only
     * the pending name and generation. No secret reaches argv, env, logs or stdin; every local and remote
     * pending file is removed on every outcome, a failed remote removal being recorded as debt.
     */
    private static final class PrivateFileDelivery implements Delivery {
        private static final Duration COPY_TIMEOUT = Duration.ofSeconds(30);
        private static final String NS = ".local/share/drydock/%s/sessions/%s";
        private static final String MKDIR = "umask 077; mkdir -p \"$HOME/$1/pending\"";
        private static final String VERIFY = "chmod 600 \"$HOME/$1\" && test -f \"$HOME/$1\" "
                + "&& test ! -L \"$HOME/$1\" && test -O \"$HOME/$1\"";
        private static final String PUBLISH = "exec python3 \"$1\" \"$HOME\" \"$2\" \"$3\" \"$4\" \"$5\"";
        private static final String REMOVE = "rm -f \"$HOME/$1\"";

        private final CommandRunner runner;
        private final BoxShell box;
        private final Path localDir;
        private final Set<String> debt = ConcurrentHashMap.newKeySet();

        PrivateFileDelivery(CommandRunner runner, Path localDir) {
            this.runner = runner;
            this.box = new BoxShell(runner);
            this.localDir = localDir;
        }

        @Override
        public boolean deliver(Credential c, BooleanSupplier cancelled) throws IOException, InterruptedException {
            Request r = c.request();
            String name = "m" + c.generation() + "-" + HexFormat.of().formatHex(randomBytes(8)) + ".json";
            String ns = NS.formatted(r.installationId(), r.session().value());
            String remote = ns + "/pending/" + name;
            Path local = localDir.resolve(name);
            boolean remoteTouched = false;
            try {
                Files.writeString(Files.createFile(local,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))),
                        manifest(c), StandardCharsets.UTF_8, StandardOpenOption.WRITE);
                if (cancelled.getAsBoolean() || !ssh(r.host(), MKDIR, ns)) {
                    return false;
                }
                remoteTouched = true;
                if (cancelled.getAsBoolean()
                        || !(box.copy(local, r.host(), remote, COPY_TIMEOUT) instanceof BoxResult.Ok)
                        || !ssh(r.host(), VERIFY, remote) || cancelled.getAsBoolean()) {
                    return false;
                }
                return ssh(r.host(), PUBLISH, r.helperPath(), r.installationId(), r.session().toString(), name,
                        Long.toString(c.generation()));
            } finally {
                try {
                    Files.deleteIfExists(local);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "local pending credential not removed: " + name);
                }
                if (remoteTouched) {
                    remove(r.host(), remote);
                }
            }
        }

        @Override
        public void withdraw(Request r, boolean published) {
            if (published) {
                remove(r.host(), NS.formatted(r.installationId(), r.session().value()) + "/connection.json");
            }
        }

        @Override
        public List<String> cleanupDebt() {
            return List.copyOf(debt);
        }

        private void remove(String host, String remote) {
            String key = host + ":" + remote;
            boolean ok = false;
            try {
                ok = ssh(host, REMOVE, remote);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // recorded as debt below
            }
            if (ok) {
                debt.remove(key);
            } else {
                debt.add(key);
                LOG.log(Level.WARNING, "remote credential file not removed: " + key);
            }
        }

        /** Runs {@code sh -c script sh args...} on the box; true only on exit 0. */
        private boolean ssh(String host, String script, String... args) throws IOException, InterruptedException {
            List<String> inner = new ArrayList<>(List.of("sh", "-c", script, "sh"));
            inner.addAll(List.of(args));
            ProcessResult result = runner.run(SshCommandBuilder.remoteCommand(host, inner),
                    new ProcessRunner.Options(null, BoxShell.SHORT_COMMAND_TIMEOUT, true, Map.of()),
                    BoxShell.MAX_OUTPUT_BYTES);
            return result.exitCode() == 0;
        }

        private static String manifest(Credential c) {
            Request r = c.request();
            return "{\"version\":1,\"installationId\":\"" + r.installationId() + "\",\"managedSessionId\":\""
                    + r.session().value() + "\",\"agentSessionId\":\"" + r.agentSessionId()
                    + "\",\"launchNonce\":\"" + r.launchNonce() + "\",\"epoch\":\"" + c.epoch()
                    + "\",\"generation\":" + c.generation() + ",\"bridgeProtocol\":" + r.bridgeProtocol()
                    + ",\"port\":" + r.port() + ",\"token\":\"" + c.token() + "\"}";
        }

        private static byte[] randomBytes(int n) {
            byte[] b = new byte[n];
            new SecureRandom().nextBytes(b);
            return b;
        }
    }
}

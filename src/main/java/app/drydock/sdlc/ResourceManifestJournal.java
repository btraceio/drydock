package app.drydock.sdlc;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plugin-owned journal of workflow resource leases and cleanup debt. Persisted as one checksummed file that is
 * replaced atomically, so a crash leaves either the previous or the new complete content; a torn file is
 * detected on recovery. It is NOT an ApplicationState writer and never touches state.json.
 */
public final class ResourceManifestJournal {

    public enum State { PENDING, RETAINED }

    public record Lease(String session, String bundleId, long localBytes, long remoteBytes, State state) { }

    /** A cleanup that could not finish (for example an unreachable remote); survives restart until cleared. */
    public record Debt(String session, String target, String reason) { }

    /** Startup classification: retained leases stay usable, pending ones are abandoned and must be removed. */
    public record Recovery(List<Lease> retained, List<Lease> abandonedPending, List<Debt> debts, boolean corrupt) { }

    private static final String HEADER = "DRYDOCK-RMJ\t1\n";
    private static final long MAX_BYTES = 1024 * 1024;

    private final Path file;
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final List<Debt> debts = new ArrayList<>();

    private ResourceManifestJournal(Path file) {
        this.file = file;
    }

    /** Opens an empty journal bound to {@code file}; call {@link #recover} first to adopt prior content. */
    public static ResourceManifestJournal open(Path file) {
        return new ResourceManifestJournal(Objects.requireNonNull(file, "file"));
    }

    /** Reads the persisted journal; missing means empty, a torn/foreign/oversize file is reported corrupt. */
    public static Recovery recover(Path file) {
        List<Lease> retained = new ArrayList<>();
        List<Lease> pending = new ArrayList<>();
        List<Debt> debts = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                return new Recovery(retained, pending, debts, false);
            }
            if (Files.size(file) > MAX_BYTES) {
                return corrupt();
            }
            String all = Files.readString(file, StandardCharsets.UTF_8);
            int end = all.lastIndexOf("END\t");
            if (!all.startsWith(HEADER) || end < 0 || !all.endsWith("\n")
                    || !all.substring(end + 4, all.length() - 1).equals(WorkflowBundle.sha256Hex(
                            all.substring(0, end).getBytes(StandardCharsets.UTF_8)))) {
                return corrupt();
            }
            for (String line : all.substring(HEADER.length(), end).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f[0].equals("L") && f.length == 6) {
                    Lease l = new Lease(dec(f[1]), dec(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]),
                            State.valueOf(f[5]));
                    (l.state() == State.PENDING ? pending : retained).add(l);
                } else if (f[0].equals("D") && f.length == 4) {
                    debts.add(new Debt(dec(f[1]), dec(f[2]), dec(f[3])));
                } else {
                    return corrupt();
                }
            }
            return new Recovery(retained, pending, debts, false);
        } catch (IOException | RuntimeException e) {
            return corrupt();
        }
    }

    private static Recovery corrupt() {
        return new Recovery(List.of(), List.of(), List.of(), true);
    }

    /** Adopts recovered retained leases and debts (abandoned pending ones are dropped: the caller cleans them). */
    public synchronized void adopt(Recovery r) {
        leases.clear();
        debts.clear();
        r.retained().forEach(l -> leases.put(key(l.session(), l.bundleId()), l));
        debts.addAll(r.debts());
    }

    public synchronized void putPending(String session, String bundleId, long localBytes, long remoteBytes) {
        leases.put(key(session, bundleId), new Lease(session, bundleId, localBytes, remoteBytes, State.PENDING));
    }

    /** Pending -> retained exactly once; false if absent or already retained. */
    public synchronized boolean retain(String session, String bundleId) {
        Lease l = leases.get(key(session, bundleId));
        if (l == null || l.state() != State.PENDING) {
            return false;
        }
        leases.put(key(session, bundleId), new Lease(session, bundleId, l.localBytes(), l.remoteBytes(),
                State.RETAINED));
        return true;
    }

    public synchronized boolean remove(String session, String bundleId) {
        return leases.remove(key(session, bundleId)) != null;
    }

    public synchronized void addDebt(Debt debt) {
        debts.add(Objects.requireNonNull(debt, "debt"));
    }

    public synchronized void clearDebts(String session) {
        debts.removeIf(d -> d.session().equals(session));
    }

    public synchronized List<Lease> leases() {
        return List.copyOf(leases.values());
    }

    public synchronized List<Debt> debts() {
        return List.copyOf(debts);
    }

    /** Writes a private temp file in the same directory, fsyncs it, then atomically replaces the journal. */
    public synchronized void flush() throws IOException {
        StringBuilder body = new StringBuilder(HEADER);
        for (Lease l : leases.values()) {
            body.append("L\t").append(enc(l.session())).append('\t').append(enc(l.bundleId())).append('\t')
                    .append(l.localBytes()).append('\t').append(l.remoteBytes()).append('\t').append(l.state())
                    .append('\n');
        }
        for (Debt d : debts) {
            body.append("D\t").append(enc(d.session())).append('\t').append(enc(d.target())).append('\t')
                    .append(enc(d.reason())).append('\n');
        }
        String text = body + "END\t" + WorkflowBundle.sha256Hex(body.toString().getBytes(StandardCharsets.UTF_8))
                + "\n";
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".rmj-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic journal replace unsupported: " + file, e);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String key(String session, String bundleId) {
        return enc(session) + "/" + enc(bundleId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}

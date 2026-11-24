package app.drydock.intellij.editorqa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskPorts.Events;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.editorqa.EditorAskRecord.Status;

/**
 * The UI-side mirror of one or more checkouts' editor-Q&A exchanges, fed by
 * the coordinator's {@link Events}. It holds no Swing components: state is
 * guarded by one lock, change notification is coalesced (~150 ms, one
 * pending delivery) and delivered on the UI executor, so a burst of
 * progress events costs one repaint. Identity is the exchange ID, never
 * text or position. Per checkout the mirror is bounded.
 *
 * <p>Nothing here blocks: {@link #progress} and friends only mutate memory
 * and schedule a delivery, so the coordinator's threads are never held up.</p>
 */
public final class EditorAskUiModel implements Events, AutoCloseable {

    /** Coalescing window for change notifications. */
    public static final long COALESCE_MILLIS = 150;
    /** 128 settled + 32 unfinished: the store's own bound. */
    static final int MAX_ROWS = 160;
    private static final int MAX_NOTICES = 8;

    /** What the user can do about a row. */
    public enum Recovery { NONE, SETTINGS, USER_TERMINAL, RETRY }

    /** One exchange as shown: the record, the live label and the recovery on offer. */
    public record Row(EditorAskRecord record, String label, String reason, Recovery recovery,
                      boolean settingsPointer) {
        public String id() {
            return record.id();
        }
    }

    private static final class Item {
        EditorAskRecord record;
        String label;
        String reason;

        Item(EditorAskRecord record) {
            this.record = record; // null while only progress is known; the record arrives with recorded()
        }
    }

    private static final class Root {
        final Map<String, Item> items = new LinkedHashMap<>();
        final List<String> notices = new ArrayList<>();
    }

    private final Object lock = new Object();
    private final Map<String, Root> roots = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Executor ui;
    private final ScheduledExecutorService scheduler;
    private final long coalesceMillis;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile boolean closed;

    public EditorAskUiModel() {
        this(SwingUtilities::invokeLater, COALESCE_MILLIS);
    }

    public EditorAskUiModel(Executor ui, long coalesceMillis) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.coalesceMillis = coalesceMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drydock-editor-ask-ui");
            t.setDaemon(true);
            return t;
        });
    }

    /** Registers a change listener, delivered on the UI executor; returns its remover. */
    public Runnable addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /** Seeds a checkout from persisted history (call off the EDT; the store read is blocking). */
    public void load(String root, List<EditorAskRecord> records) {
        synchronized (lock) {
            Root r = roots.computeIfAbsent(root, k -> new Root());
            for (EditorAskRecord rec : records) {
                Item item = r.items.get(rec.id());
                if (item == null) {
                    r.items.put(rec.id(), new Item(rec));
                } else if (item.record == null || !item.record.isSettled() || rec.isSettled()) {
                    item.record = rec;
                }
            }
            trim(r);
        }
        changed();
    }

    /** The mirror of one checkout, oldest first by acceptance sequence. */
    public List<Row> rows(String root) {
        synchronized (lock) {
            Root r = roots.get(root);
            if (r == null) {
                return List.of();
            }
            List<Row> out = new ArrayList<>();
            for (Item i : r.items.values()) {
                if (i.record != null) {
                    out.add(row(i));
                }
            }
            out.sort(Comparator.comparingLong((Row x) -> x.record().acceptanceSequence()));
            return out;
        }
    }

    /** Recent notices/recovery problems for a checkout (bounded, newest last). */
    public List<String> notices(String root) {
        synchronized (lock) {
            Root r = roots.get(root);
            return r == null ? List.of() : List.copyOf(r.notices);
        }
    }

    // ---- Events ----

    @Override
    public void progress(String root, String id, Status status, String label, String reason) {
        synchronized (lock) {
            Root r = roots.computeIfAbsent(root, k -> new Root());
            Item i = r.items.get(id);
            if (i == null) {
                if (r.items.size() >= MAX_ROWS) {
                    return;
                }
                i = new Item(null); // progress can outrun the record; keep the label for it
                r.items.put(id, i);
            }
            if (i.record != null && i.record.isSettled() && !status.isSettled()) {
                return; // a late label never reopens a settled exchange
            }
            i.label = label;
            i.reason = reason;
        }
        changed();
    }

    @Override
    public void recorded(String root, EditorAskRecord record) {
        synchronized (lock) {
            Root r = roots.computeIfAbsent(root, k -> new Root());
            Item i = r.items.get(record.id());
            if (i == null) {
                r.items.put(record.id(), new Item(record));
                trim(r);
            } else {
                i.record = record;
                if (record.isSettled()) {
                    i.label = null; // the persisted status is the label now
                }
            }
        }
        changed();
    }

    @Override
    public void notice(String root, ManagedSessionId row, String message) {
        addNotice(root, message);
    }

    @Override
    public void recoveryError(String root, String message) {
        addNotice(root, message);
    }

    private void addNotice(String root, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        synchronized (lock) {
            Root r = roots.computeIfAbsent(root, k -> new Root());
            r.notices.add(message);
            while (r.notices.size() > MAX_NOTICES) {
                r.notices.removeFirst();
            }
        }
        changed();
    }

    // ---- derivation ----

    private static Row row(Item i) {
        EditorAskRecord rec = i.record;
        String reason = i.reason != null ? i.reason : rec.failureReason();
        String label = i.label != null && !rec.isSettled() ? i.label : labelFor(rec, reason);
        return new Row(rec, label, reason, recoveryOf(rec, reason), pointsToSettings(rec, reason));
    }

    static String labelFor(EditorAskRecord rec, String reason) {
        return switch (rec.status()) {
            case ACCEPTED, QUEUED -> "Queued";
            case SUBMITTING -> "Starting…";
            case AWAITING -> "Waiting for answer…";
            case ANSWERED -> "Answered";
            case NO_ANSWER -> reason == null ? "No answer received" : reason;
            case FAILED_NOT_SENT -> reason == null ? "Not sent" : reason;
            case INTERRUPTED_POSSIBLY_SENT -> reason == null ? "Interrupted; the question may have been sent" : reason;
        };
    }

    private static boolean pointsToSettings(EditorAskRecord rec, String reason) {
        if (rec.status() != Status.FAILED_NOT_SENT || reason == null) {
            return false;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        return r.contains("settings") || r.contains("harness") || r.contains("not available")
                || r.contains("unavailable") || r.contains("no agent");
    }

    private static Recovery recoveryOf(EditorAskRecord rec, String reason) {
        if (!rec.isSettled() || rec.status() == Status.ANSWERED) {
            return Recovery.NONE;
        }
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.contains("trust") || r.contains("auth") || r.contains("permission") || r.contains("login")
                || r.contains("log in") || r.contains("approve")) {
            return Recovery.USER_TERMINAL;
        }
        if (pointsToSettings(rec, reason)) {
            return Recovery.SETTINGS;
        }
        return Recovery.RETRY;
    }

    private static void trim(Root r) {
        while (r.items.size() > MAX_ROWS) {
            String victim = null;
            long best = Long.MAX_VALUE;
            for (Item i : r.items.values()) {
                if (i.record != null && i.record.isSettled() && i.record.acceptanceSequence() < best) {
                    best = i.record.acceptanceSequence();
                    victim = i.record.id();
                }
            }
            if (victim == null) {
                return;
            }
            r.items.remove(victim);
        }
    }

    // ---- coalesced delivery ----

    private void changed() {
        if (closed || listeners.isEmpty() || !pending.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.schedule(() -> ui.execute(this::deliver), coalesceMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            pending.set(false); // scheduler closed: nothing to deliver to
        }
    }

    private void deliver() {
        pending.set(false);
        if (closed) {
            return;
        }
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                // one broken view must not starve the others
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        listeners.clear();
        scheduler.shutdownNow();
    }
}

package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds each scope's intent grouping: whatever {@code review_intents}
 * supplied, or the by-file fallback when nothing has (Review MCP schema §2).
 *
 * <p>The fallback is what keeps Review usable with no reviewer configured:
 * there is always something to settle, so the verdict bar and the submit
 * flow work on a plain diff exactly as they do on a reviewed one.</p>
 *
 * <p>Thread-safe: the MCP router writes on its own executor, the UI reads on
 * the FX thread.</p>
 */
public final class IntentGrouping {

    /**
     * The reading path's rank has no out-of-diff fan-in scan behind it here
     * (Task 18 follow-up, correction 4): {@link OutOfDiffFanIn#scan} spawns a
     * blocking {@code git grep} per scope, a separate concern from reordering
     * a grouping already in hand. {@code unavailable=true} is the honest
     * input for a signal nothing computed -- the same choice the rail and
     * {@code McpToolRouter} both make.
     */
    private static final OutOfDiffFanIn.Result NO_FAN_IN_SCAN = new OutOfDiffFanIn.Result(Map.of(), true);

    private final Map<String, List<ReviewIntent>> byScope = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /**
     * How many times each scope's grouping has changed -- bumped by {@link
     * #notifyChanged}, so a caller that caches {@link #intentsFor}'s result
     * across more than one call (nothing else here can go stale: {@code
     * diff} and {@code graph} are plain values a caller can compare by
     * identity) has a single number to compare instead of recomputing on
     * every call to find out nothing changed.
     */
    private final Map<String, Long> versionByScope = new ConcurrentHashMap<>();

    /**
     * {@code scopeId}'s current grouping version -- 0 until the first
     * {@link #set}/{@link #clear}, and incremented by every one after that.
     * Never decreases and never repeats for a scope, so two reads that
     * differ mean the reviewer's grouping genuinely changed in between; two
     * reads that agree mean it provably did not, however far apart in time.
     */
    public long version(String scopeId) {
        return versionByScope.getOrDefault(scopeId, 0L);
    }

    /**
     * Replaces {@code scopeId}'s grouping with what a reviewer supplied.
     * Numbering is assigned here rather than trusted from the caller, so the
     * rail's {@code 1..N} is always dense and in order.
     *
     * <p>The ORDER numbered is still the reviewer's own -- either the order
     * it listed its intents in, or, when any of them declares {@link
     * ReviewIntent#reads()}, that declared dependency order (see {@link
     * #orderByReads}). Both are the agent's assertion about its own change;
     * neither is drydock re-deciding what card is (1).</p>
     */
    public void set(String scopeId, List<ReviewIntent> intents) {
        Objects.requireNonNull(scopeId, "scopeId");
        List<ReviewIntent> numbered = new ArrayList<>();
        int number = 1;
        for (ReviewIntent intent : orderByReads(intents)) {
            numbered.add(new ReviewIntent(intent.id(), number++, intent.title(), intent.kind(),
                    intent.risk(), intent.rationale(), intent.hunkIds(), intent.collapse(),
                    intent.autoApprove(), intent.reads()));
        }
        byScope.put(scopeId, List.copyOf(numbered));
        notifyChanged(scopeId);
    }

    /** The position stood in for a {@code reads} naming no intent in the batch. */
    private static final int UNRESOLVED_READ = -1;

    /**
     * {@code intents} in the dependency order the agent declared through
     * {@link ReviewIntent#reads()} -- foundation first -- or unchanged when
     * none of them declares anything, which is every grouping sent before
     * the field existed.
     *
     * <p>The graph's nodes are POSITIONS in {@code intents}, not intent ids.
     * Nothing stops an agent sending the same id twice, and a set of ids
     * would collapse those two into one node and lose a card outright; a set
     * of positions cannot, whatever the ids say. It also makes the tie-break
     * the agent's own array order, which is total by construction and is the
     * right answer anyway: where {@code reads} says nothing, the order the
     * agent listed them in is the only other thing it told us.</p>
     *
     * <p>{@link Graphs#topologicalOrder} returns a list OF units -- a cycle
     * comes back as one unit with its members already in tie-break order,
     * not as an error. An agent declaring {@code A reads B} and {@code B
     * reads A} is describing genuinely entangled work, and refusing its whole
     * batch over that would be worse than showing the two adjacent, so the
     * units are simply flattened in order.</p>
     */
    private static List<ReviewIntent> orderByReads(List<ReviewIntent> intents) {
        if (intents.stream().allMatch(intent -> intent.reads().isEmpty())) {
            return intents;
        }
        Map<String, Integer> positionOf = new LinkedHashMap<>();
        for (int position = 0; position < intents.size(); position++) {
            positionOf.putIfAbsent(intents.get(position).id(), position);
        }
        SortedSet<Integer> nodes = new TreeSet<>();
        Map<Integer, SortedSet<Integer>> readsOf = new TreeMap<>();
        for (int position = 0; position < intents.size(); position++) {
            nodes.add(position);
            SortedSet<Integer> targets = new TreeSet<>();
            for (String read : intents.get(position).reads()) {
                // An id no intent in the batch carries is rejected at decode,
                // in ReviewToolCodec.intentsFromJson, with an MCP error naming
                // it -- a malformed agent payload must not first be noticed
                // here, where the only report left is an exception on whatever
                // thread happened to call set. UNRESOLVED_READ is outside nodes,
                // so an id that reaches here anyway (from in-process code,
                // which is a drydock bug and not an agent's) still makes
                // Graphs refuse rather than silently drop the edge.
                targets.add(positionOf.getOrDefault(read, UNRESOLVED_READ));
            }
            readsOf.put(position, targets);
        }
        List<ReviewIntent> ordered = new ArrayList<>();
        // getOrDefault, not readsOf::get: every position was populated just
        // above so an unmapped key cannot happen today, but a method reference
        // that answers null on one would surface as an NPE inside Graphs'
        // traversal rather than as anything a reader could trace back here.
        for (List<Integer> unit : Graphs.topologicalOrder(nodes,
                position -> readsOf.getOrDefault(position, Collections.emptySortedSet()),
                Comparator.naturalOrder())) {
            for (Integer position : unit) {
                ordered.add(intents.get(position));
            }
        }
        return ordered;
    }

    /** Drops a scope's grouping (the scope left the queue). */
    public void clear(String scopeId) {
        if (byScope.remove(scopeId) != null) {
            notifyChanged(scopeId);
        }
    }

    /** Whether a reviewer has supplied a grouping for this scope. */
    public boolean hasReviewerGrouping(String scopeId) {
        return byScope.containsKey(scopeId);
    }

    /**
     * {@code scopeId}'s intents: the reviewer's grouping when there is one,
     * otherwise {@link FallbackIntents}' clustering of {@code diff}.
     *
     * <p>Equivalent to calling {@link #intentsFor(String, UnifiedDiff,
     * Optional)} with no graph -- for callers with no {@link ChangeGraph} to
     * offer, which fall back to the (kind, directory) clustering exactly as
     * they always have.</p>
     */
    public List<ReviewIntent> intentsFor(String scopeId, UnifiedDiff diff) {
        return intentsFor(scopeId, diff, Optional.empty());
    }

    /**
     * {@code scopeId}'s intents: the reviewer's grouping when there is one,
     * otherwise the computed sections -- and, with no graph to compute from,
     * {@link FallbackIntents}' clustering of {@code diff}.
     *
     * <p>A reviewer's grouping is never re-sorted or re-drawn. It came from
     * something that read the change; recomputing over it would be drydock
     * overruling the reviewer -- so its {@code number}s stay exactly {@link
     * #set}'s own dense 1..N over whatever order the reviewer supplied --
     * its array order, or the {@link ReviewIntent#reads()} order it declared,
     * both of them the reviewer's own -- unrelated to {@link ReadingPath}'s
     * reading order. Only the COMPUTED path below is renumbered against it,
     * because only there is drydock itself the one deciding what card is
     * (1).</p>
     *
     * <p>When the graph turns out to have nothing structural to add --
     * {@link Sections#of} takes the same (kind, directory) clustering itself
     * in that case -- this returns the fallback's OWN {@link ReviewIntent}s
     * rather than restating them under a fresh {@code computed:} identity. A
     * finding recorded against the fallback's id while the graph was still
     * building must not be orphaned by a rebuild that, in the end, found
     * nothing more to say: that would silently defeat {@code
     * blockingFindingOpen}'s id match for no reason a reviewer caused.</p>
     */
    public List<ReviewIntent> intentsFor(String scopeId, UnifiedDiff diff,
                                         Optional<ChangeGraph> graph) {
        List<ReviewIntent> supplied = byScope.get(scopeId);
        if (supplied != null) {
            return supplied;
        }
        List<ReviewIntent> fallback = FallbackIntents.group(diff);
        if (graph.isEmpty()) {
            return fallback;
        }
        List<Sections.Section> sections = Sections.of(diff, graph.get());
        // sameAsFallback compares against Sections.of's own (unreordered)
        // list -- the ONE case that matters here is content equality with
        // the fallback, which reordering cannot create or hide.
        if (sameAsFallback(sections, fallback)) {
            return fallback;
        }
        // Numbered in the SAME order the rail's PATH mode and
        // McpToolRouter's review_scope both use (Task 18, correction 4):
        // ReadingPath.of reorders Sections.of's own list by reading order,
        // so a human looking at computed card (1) here and an agent reading
        // section (1) off review_scope never disagree about which section
        // that is -- and pressing p in the rail does not silently renumber
        // every card underneath whichever intent a finding or verdict named.
        List<Sections.Section> ordered =
                ReadingPath.of(diff, graph.get(), sections, NO_FAN_IN_SCAN).sections();
        Map<String, ReviewIntent> fallbackByHunk = new LinkedHashMap<>();
        for (ReviewIntent intent : fallback) {
            for (String hunkId : intent.hunkIds()) {
                fallbackByHunk.put(hunkId, intent);
            }
        }
        List<ReviewIntent> computed = new ArrayList<>();
        int number = 1;
        for (Sections.Section section : ordered) {
            computed.add(new ReviewIntent(computedId(section), number,
                    section.title(), kindOf(section, fallbackByHunk), riskOf(section, fallbackByHunk),
                    // No reads: this is the COMPUTED path, where drydock
                    // itself decided the order -- there is no agent assertion
                    // to carry, and ReadingPath.of above already ordered it.
                    rationale(section), section.hunkIds(), Optional.empty(), false, List.of()));
            number++;
        }
        return List.copyOf(computed);
    }

    /**
     * What kind of change a computed section is: the most significant kind
     * among the fallback intents whose hunks it covers, in the same
     * (production change over its own tests, generated output or config)
     * priority {@link FallbackIntents} itself orders the rail by. A section
     * merging a header with its implementation, or a change with the test
     * that covers it, must not flatten to a bare {@code change} tag just
     * because {@code Sections} does not itself infer kind -- the fallback
     * already worked that out per file, and grouping the hunks differently
     * is no reason to discard it.
     */
    private static ReviewIntent.Kind kindOf(Sections.Section section, Map<String, ReviewIntent> fallbackByHunk) {
        ReviewIntent.Kind best = null;
        for (String hunkId : section.hunkIds()) {
            ReviewIntent covering = fallbackByHunk.get(hunkId);
            if (covering == null) {
                continue;
            }
            if (best == null || kindPriority(covering.kind()) < kindPriority(best)) {
                best = covering.kind();
            }
        }
        return best == null ? ReviewIntent.Kind.CHANGE : best;
    }

    /**
     * Mirrors {@link FallbackIntents}' own (private) reading-order priority:
     * a production change is more significant than the tests or config that
     * came with it, so ONE kind has to win when a section spans several, and
     * this is the same choice the rail's own ordering already makes.
     */
    private static int kindPriority(ReviewIntent.Kind kind) {
        return switch (kind) {
            case CHANGE -> 0;
            case REFACTOR -> 1;
            case MOVE -> 2;
            case CONFIG -> 3;
            case TESTS -> 4;
            case GENERATED -> 5;
        };
    }

    /**
     * A computed section's risk: the worst of the fallback intents whose
     * hunks it covers. A section is only as safe to wave through as its
     * riskiest part, so the churn-derived HIGH/MED/LOW the fallback already
     * measured per file must not vanish into a flat {@code NONE} the moment
     * {@code Sections} regroups those same hunks.
     */
    private static ReviewIntent.Risk riskOf(Sections.Section section, Map<String, ReviewIntent> fallbackByHunk) {
        ReviewIntent.Risk worst = ReviewIntent.Risk.NONE;
        for (String hunkId : section.hunkIds()) {
            ReviewIntent covering = fallbackByHunk.get(hunkId);
            if (covering != null && covering.risk().ordinal() < worst.ordinal()) {
                worst = covering.risk();
            }
        }
        return worst;
    }

    /**
     * The id one computed section is addressed by: derived from WHICH hunks
     * it covers, never from where it happens to sit in the rail.
     *
     * <p>{@link Sections#of} orders sections topologically, so an edit
     * elsewhere in the diff can shift a section's position in that order
     * without changing what it is about. A positional {@code computed:N}
     * id would then quietly re-point any verdict or finding recorded
     * against {@code N} at a DIFFERENT section covering different hunks --
     * worse than losing track of it, because nothing about the result looks
     * wrong. Hashed over the section's own hunk ids instead, sorted so the
     * identity is the SET of hunks, not the order {@link Sections} happened
     * to read them in.</p>
     *
     * <p>The file set is hashed in too, not just the hunks: a binary file or
     * a pure rename has no hunks at all ({@code UnifiedDiff} carries neither
     * for those), so a section built from one alone hashes an EMPTY hunk
     * list -- and every such section would collide on the identical id
     * without the files to still tell them apart. Positional ids could
     * never collide this way; content-derived ones must not either.</p>
     */
    private static String computedId(Sections.Section section) {
        List<String> sortedFiles = new ArrayList<>(section.files());
        Collections.sort(sortedFiles);
        List<String> sortedHunks = new ArrayList<>(section.hunkIds());
        Collections.sort(sortedHunks);
        // Files and hunks are hashed SEPARATELY, then 8 hex characters are
        // taken from EACH digest, rather than truncating one concatenated
        // string -- that would keep only the leading digest's bytes and
        // silently drop the other, which is exactly the bug this id exists
        // to avoid: the id must depend on both the file set and the hunk
        // set, since the file set alone is what tells two hunkless sections
        // apart, and the hunk set alone is what makes the id survive a
        // reordering that touches neither section's own hunks.
        String files = sha256Hex(String.join("\n", sortedFiles));
        String hunks = sha256Hex(String.join("\n", sortedHunks));
        return "computed:" + files.substring(0, 8) + hunks.substring(0, 8);
    }

    private static String sha256Hex(String material) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; its absence is not a
            // condition this application can meaningfully continue past.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Whether {@code sections} is exactly {@link FallbackIntents}' own
     * clustering, restated: {@link Sections#of} takes that path itself
     * whenever it finds no dependency or convention edge at all. Compared by
     * title and hunk ids, in order -- the two things a card actually shows
     * and settles by -- rather than by re-deriving {@link Sections}'
     * internal edge computation here.
     */
    private static boolean sameAsFallback(List<Sections.Section> sections, List<ReviewIntent> fallback) {
        if (sections.size() != fallback.size()) {
            return false;
        }
        for (int i = 0; i < sections.size(); i++) {
            Sections.Section section = sections.get(i);
            ReviewIntent intent = fallback.get(i);
            if (!section.title().equals(intent.title()) || !section.hunkIds().equals(intent.hunkIds())) {
                return false;
            }
        }
        return true;
    }

    /** At most this many cycle members are named before "and N more" takes over. */
    private static final int CYCLE_NAMES_SHOWN = 3;

    /**
     * What a computed section says for itself with no agent to name it: the
     * structural facts, and the cycle when it is in one.
     */
    private static String rationale(Sections.Section section) {
        String base = section.files().size() + " files  ·  "
                + section.hunkIds().size() + " hunks  ·  grouped by drydock, no reviewer has run";
        if (section.cycleWith().isEmpty()) {
            return base;
        }
        // cycleWith() names members of THIS section's own unit that
        // reference each other -- the section IS the cycle, not something
        // pointing outward at one -- so "in a dependency cycle with" reads
        // as though these files belonged elsewhere, which they do not.
        return base + "  ·  its files reference each other in a cycle: "
                + summarizeCycle(section.cycleWith());
    }

    /**
     * At most {@link #CYCLE_NAMES_SHOWN} names, "and N more" beyond that. A
     * unit's cycle can be its entire membership -- this branch's own
     * ChangeGraph section names 24 files, all mutually referencing -- and
     * spelling every one of them inline turns a two-line rationale into a
     * card taller than the rail's own viewport.
     */
    private static String summarizeCycle(List<String> names) {
        if (names.size() <= CYCLE_NAMES_SHOWN) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, CYCLE_NAMES_SHOWN))
                + " and " + (names.size() - CYCLE_NAMES_SHOWN) + " more";
    }

    /**
     * The intent a given file belongs to, for anchoring a finding that names
     * no intent. Matched through the hunks an intent names rather than
     * through its id: the fallback groups several files into one intent now,
     * so an id can no longer be reconstructed from a path.
     */
    public Optional<ReviewIntent> intentForFile(String scopeId, UnifiedDiff diff, String file) {
        return intentsFor(scopeId, diff).stream()
                .filter(intent -> intent.touches(file))
                .findFirst();
    }

    /** Subscribes to grouping changes; the returned runnable unsubscribes. */
    public Runnable addChangeListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyChanged(String scopeId) {
        versionByScope.merge(scopeId, 1L, Long::sum);
        for (Consumer<String> listener : listeners) {
            listener.accept(scopeId);
        }
    }
}

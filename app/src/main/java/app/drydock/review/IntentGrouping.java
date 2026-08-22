package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final Map<String, List<ReviewIntent>> byScope = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Replaces {@code scopeId}'s grouping with what a reviewer supplied.
     * Numbering is assigned here rather than trusted from the caller, so the
     * rail's {@code 1..N} is always dense and in order.
     */
    public void set(String scopeId, List<ReviewIntent> intents) {
        Objects.requireNonNull(scopeId, "scopeId");
        List<ReviewIntent> numbered = new ArrayList<>();
        int number = 1;
        for (ReviewIntent intent : intents) {
            numbered.add(new ReviewIntent(intent.id(), number++, intent.title(), intent.kind(),
                    intent.risk(), intent.rationale(), intent.hunkIds(), intent.collapse(),
                    intent.autoApprove()));
        }
        byScope.put(scopeId, List.copyOf(numbered));
        notifyChanged(scopeId);
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
     * overruling the reviewer.</p>
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
        if (sameAsFallback(sections, fallback)) {
            return fallback;
        }
        Map<String, ReviewIntent> fallbackByHunk = new LinkedHashMap<>();
        for (ReviewIntent intent : fallback) {
            for (String hunkId : intent.hunkIds()) {
                fallbackByHunk.put(hunkId, intent);
            }
        }
        List<ReviewIntent> computed = new ArrayList<>();
        int number = 1;
        for (Sections.Section section : sections) {
            computed.add(new ReviewIntent(computedId(section), number,
                    section.title(), kindOf(section, fallbackByHunk), riskOf(section, fallbackByHunk),
                    rationale(section), section.hunkIds(), Optional.empty(), false));
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
        // Files and hunks are hashed SEPARATELY, then the two digests are
        // concatenated -- rather than joined into one string with a
        // separator, which is one more thing to get exactly right. Each
        // digest is already unambiguous within its own sorted, newline-
        // joined list, so nothing is lost by keeping them apart.
        String files = sha256Hex(String.join("\n", sortedFiles));
        String hunks = sha256Hex(String.join("\n", sortedHunks));
        return "computed:" + (files + hunks).substring(0, 16);
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
        for (Consumer<String> listener : listeners) {
            listener.accept(scopeId);
        }
    }
}

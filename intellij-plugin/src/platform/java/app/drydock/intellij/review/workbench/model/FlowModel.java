package app.drydock.intellij.review.workbench.model;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewTabModel;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The flow review's queue (design A, "Flow"), pure: every hunk of the
 * review in the order Next walks them, grouped for the queue pane. The
 * Sections tab groups by section in section order, a hunk that several
 * sections reach is listed under the first one only, and the hunks no
 * section reaches follow under {@link #NOT_IN_A_SECTION}. The Files tab
 * groups by file in path order.
 *
 * <p>The seen and flagged hunks are the caller's sets of digests. A hunk
 * whose digest has an approved, current verdict ({@link SectionsModel.Hunk#reviewed})
 * also counts as seen.</p>
 */
public final class FlowModel {

    /** The trailing group's title in the Sections tab: the hunks no section reaches. */
    public static final String NOT_IN_A_SECTION = "Not in a section";

    private FlowModel() {
    }

    /**
     * One hunk of the queue.
     *
     * @param key          the hunk id, {@code HunkIds.hunkId(file, index)}: unique in the queue
     * @param lines        the line span ({@code 12–30}, or {@code old 5–7} for a hunk that only removes)
     * @param scrollKey    the line key of the hunk's first changed row
     * @param rowKeys      the line keys of every row the hunk shows
     * @param openFindings the file's open findings that start on one of {@code rowKeys}
     * @param reviewed     an approved verdict that is current covers the hunk's digest
     */
    public record Hunk(String key, String file, int index, String digest, String lines, String scrollKey,
                       int removed, int added, Set<String> rowKeys, int openFindings, boolean reviewed) {
        public Hunk {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(lines, "lines");
            Objects.requireNonNull(scrollKey, "scrollKey");
            rowKeys = Set.copyOf(rowKeys);
        }

        /** The file's name without its directory. */
        public String fileName() {
            return file.substring(file.lastIndexOf('/') + 1);
        }

        /** The file's directory with a trailing slash, or "" at the root. */
        public String directory() {
            return file.substring(0, file.lastIndexOf('/') + 1);
        }

        /** Whether the hunk counts as seen under {@code seen}, a set of digests. */
        public boolean seenIn(Set<String> seen) {
            return reviewed || seen.contains(digest);
        }
    }

    /**
     * One group of the queue: a section (with its {@code number} and
     * dependencies) or a file ({@code number} empty, {@code title} the path).
     */
    public record Group(Optional<Integer> number, String title, List<Integer> buildsOn, List<Integer> neededBy,
                        List<Hunk> hunks) {
        public Group {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(title, "title");
            buildsOn = List.copyOf(buildsOn);
            neededBy = List.copyOf(neededBy);
            hunks = List.copyOf(hunks);
        }

        /** Whether the group is a section, not a file. */
        public boolean section() {
            return number.isPresent();
        }

        /**
         * A section's second line (pure): "1/2 seen · builds on §1 · needed by §3";
         * "no hunks of its own" when an earlier section lists all of them.
         */
        public String meta(Set<String> seen) {
            StringBuilder text = new StringBuilder();
            if (hunks.isEmpty()) {
                text.append("no hunks of its own");
            } else {
                long seenHunks = hunks.stream().filter(hunk -> hunk.seenIn(seen)).count();
                text.append(seenHunks).append('/').append(hunks.size()).append(" seen");
            }
            if (!buildsOn.isEmpty()) {
                text.append(" · builds on ").append(numbers(buildsOn));
            }
            if (!neededBy.isEmpty()) {
                text.append(" · needed by ").append(numbers(neededBy));
            }
            return text.toString();
        }

        private static String numbers(List<Integer> numbers) {
            return numbers.stream().map(n -> "§" + n).collect(Collectors.joining(", "));
        }
    }

    /** How a hunk row is marked in the queue; flagged wins over seen. */
    public enum Mark { OPEN, SEEN, FLAGGED }

    /** One row of the queue pane: a group's header, or a hunk. */
    public sealed interface Row permits Header, Item {
    }

    /** A group's header row; {@code meta} is a section's second line, empty for a file. */
    public record Header(Group group, String meta) implements Row {
        public Header {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(meta, "meta");
        }
    }

    /** A hunk row; {@code showFile} in the Sections tab, where the group does not name the file. */
    public record Item(Hunk hunk, Mark mark, boolean showFile, boolean current) implements Row {
        public Item {
            Objects.requireNonNull(hunk, "hunk");
            Objects.requireNonNull(mark, "mark");
        }
    }

    /**
     * The whole queue.
     *
     * @param sections whether the groups are sections (the Sections tab) or files
     * @param intent   the change's intent, from the agent's tour
     * @param order    every hunk once, in group order: what Next and Back walk
     */
    public record Queue(boolean sections, Optional<String> intent, List<Group> groups, List<Hunk> order,
                        int files, int sectionCount) {
        public static final Queue EMPTY = new Queue(false, Optional.empty(), List.of(), List.of(), 0, 0);

        public Queue {
            Objects.requireNonNull(intent, "intent");
            groups = List.copyOf(groups);
            order = List.copyOf(order);
        }

        /** The hunk with {@code key}, if the queue has it. */
        public Optional<Hunk> hunk(String key) {
            return order.stream().filter(hunk -> hunk.key().equals(key)).findFirst();
        }

        /** {@code key}'s 0-based position in {@link #order}, or -1. */
        public int indexOf(String key) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).key().equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        /** The hunk after {@code key}; empty at the end or when the queue does not have {@code key}. */
        public Optional<Hunk> after(String key) {
            int at = indexOf(key);
            return at < 0 || at + 1 >= order.size() ? Optional.empty() : Optional.of(order.get(at + 1));
        }

        /** The hunk before {@code key}; empty at the start or when the queue does not have {@code key}. */
        public Optional<Hunk> before(String key) {
            int at = indexOf(key);
            return at <= 0 ? Optional.empty() : Optional.of(order.get(at - 1));
        }

        /** The first hunk not seen under {@code seen}, in queue order. */
        public Optional<Hunk> firstUnseen(Set<String> seen) {
            return order.stream().filter(hunk -> !hunk.seenIn(seen)).findFirst();
        }

        /** The hunk of {@code file} whose rows include {@code lineKey}. */
        public Optional<Hunk> hunkAt(String file, String lineKey) {
            return order.stream()
                    .filter(hunk -> hunk.file().equals(file) && hunk.rowKeys().contains(lineKey))
                    .findFirst();
        }

        /** How many hunks are seen under {@code seen}. */
        public int seenCount(Set<String> seen) {
            return (int) order.stream().filter(hunk -> hunk.seenIn(seen)).count();
        }

        /**
         * What Approve on the finish screen records (pure): the distinct
         * digests of the hunks seen under {@code seen} that no approved
         * verdict covers yet, in queue order.
         */
        public List<String> toApprove(Set<String> seen) {
            return order.stream()
                    .filter(hunk -> !hunk.reviewed() && seen.contains(hunk.digest()))
                    .map(Hunk::digest)
                    .distinct()
                    .toList();
        }

        /** How many of the hunks are flagged under {@code flagged}. */
        public int flaggedCount(Set<String> flagged) {
            return (int) order.stream().filter(hunk -> flagged.contains(hunk.digest())).count();
        }

        /** The lines all hunks add. */
        public int added() {
            return order.stream().mapToInt(Hunk::added).sum();
        }

        /** The lines all hunks remove. */
        public int removed() {
            return order.stream().mapToInt(Hunk::removed).sum();
        }

        /**
         * The queue pane's rows: each group's header, then its hunks. With
         * {@code hideSeen}, a seen hunk is left out unless it is
         * {@code current}, and so is a header whose hunks are all left out.
         */
        public List<Row> rows(Set<String> seen, Set<String> flagged, boolean hideSeen, Optional<String> current) {
            List<Row> rows = new ArrayList<>();
            for (Group group : groups) {
                List<Row> items = new ArrayList<>();
                for (Hunk hunk : group.hunks()) {
                    boolean isCurrent = current.map(hunk.key()::equals).orElse(false);
                    boolean isSeen = hunk.seenIn(seen);
                    if (hideSeen && isSeen && !isCurrent) {
                        continue;
                    }
                    Mark mark = flagged.contains(hunk.digest()) ? Mark.FLAGGED : isSeen ? Mark.SEEN : Mark.OPEN;
                    items.add(new Item(hunk, mark, group.section(), isCurrent));
                }
                if (items.isEmpty() && (hideSeen || !group.section())) {
                    continue;
                }
                rows.add(new Header(group, group.section() ? group.meta(seen) : ""));
                rows.addAll(items);
            }
            return rows;
        }
    }

    /**
     * The queue of {@code state}: grouped by section when the navigator
     * shows Sections and there are sections, else by file.
     */
    public static Queue of(WorkbenchState state) {
        Objects.requireNonNull(state, "state");
        List<ReviewTabModel.FileRow> files = state.review().files();
        Map<String, Boolean> reviewed = new HashMap<>();
        for (SectionsModel.Section section : state.sections().sections()) {
            for (SectionsModel.Hunk hunk : section.hunks()) {
                reviewed.merge(hunk.hunkId(), hunk.reviewed(), Boolean::logicalOr);
            }
        }
        Map<String, List<String>> findingKeys = new HashMap<>();
        for (ReviewTabModel.FileRow row : files) {
            findingKeys.put(row.path(), row.findings().stream().map(ReviewTabModel.FindingRow::startKey).toList());
        }
        Map<String, Hunk> hunks = new LinkedHashMap<>();
        for (SectionsModel.HunkSource source : sources(files)) {
            int open = (int) findingKeys.getOrDefault(source.file(), List.of()).stream()
                    .filter(source.rowKeys()::contains)
                    .count();
            hunks.put(source.hunkId(), new Hunk(source.hunkId(), source.file(), source.index(), source.digest(),
                    source.lines(), source.scrollKey(), source.removed(), source.added(), source.rowKeys(), open,
                    reviewed.getOrDefault(source.hunkId(), false)));
        }
        int fileCount = (int) hunks.values().stream().map(Hunk::file).distinct().count();
        boolean bySection = state.navigator() == WorkbenchState.Navigator.SECTIONS
                && !state.sections().sections().isEmpty();
        List<Group> groups = bySection ? sectionGroups(state.sections(), hunks) : fileGroups(hunks);
        List<Hunk> order = groups.stream().flatMap(group -> group.hunks().stream()).toList();
        return new Queue(bySection, bySection ? state.sections().intent() : Optional.empty(), groups, order,
                fileCount, state.sections().sections().size());
    }

    /**
     * Every hunk of {@code files} in file and hunk order. The digests are
     * the rows' own, so a state built on the EDT hashes nothing; a row whose
     * digest list does not match its hunks hashes them instead.
     */
    private static List<SectionsModel.HunkSource> sources(List<ReviewTabModel.FileRow> files) {
        List<SectionsModel.HunkSource> sources = new ArrayList<>();
        for (ReviewTabModel.FileRow row : files) {
            List<UnifiedDiff.Hunk> fileHunks = row.diff().hunks();
            boolean known = row.hunkDigests().size() == fileHunks.size();
            for (int i = 0; i < fileHunks.size(); i++) {
                UnifiedDiff.Hunk hunk = fileHunks.get(i);
                sources.add(SectionsModel.source(row.path(), i, HunkIds.hunkId(row.path(), i), hunk,
                        known ? row.hunkDigests().get(i) : HunkDigest.of(row.path(), hunk)));
            }
        }
        return sources;
    }

    private static List<Group> sectionGroups(SectionsModel.Snapshot sections, Map<String, Hunk> hunks) {
        Set<String> listed = new LinkedHashSet<>();
        List<Group> groups = new ArrayList<>();
        for (SectionsModel.Section section : sections.sections()) {
            List<Hunk> own = new ArrayList<>();
            for (SectionsModel.Hunk card : section.hunks()) {
                Hunk hunk = hunks.get(card.hunkId());
                if (hunk != null && listed.add(hunk.key())) {
                    own.add(hunk);
                }
            }
            groups.add(new Group(Optional.of(section.number()), section.title(), section.buildsOn(),
                    section.neededBy(), own));
        }
        List<Hunk> rest = hunks.values().stream().filter(hunk -> !listed.contains(hunk.key())).toList();
        if (!rest.isEmpty()) {
            groups.add(new Group(Optional.of(groups.size() + 1), NOT_IN_A_SECTION, List.of(), List.of(), rest));
        }
        return groups;
    }

    private static List<Group> fileGroups(Map<String, Hunk> hunks) {
        Map<String, List<Hunk>> byFile = new LinkedHashMap<>();
        for (Hunk hunk : hunks.values()) {
            byFile.computeIfAbsent(hunk.file(), file -> new ArrayList<>()).add(hunk);
        }
        List<Group> groups = new ArrayList<>();
        byFile.forEach((file, own) -> groups.add(new Group(Optional.empty(), file, List.of(), List.of(), own)));
        return groups;
    }
}

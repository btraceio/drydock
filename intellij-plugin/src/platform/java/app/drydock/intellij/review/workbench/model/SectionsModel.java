package app.drydock.intellij.review.workbench.model;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.Triage;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.HunkTake;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The workbench's Sections navigator and section view (spec §4, §5), pure:
 * the review's hunks grouped into numbered sections, from the agent's tour
 * when one is stored, else from the computed {@link Sections}. A hunk
 * carries the tour's take on its digest, when the agent gave one.
 *
 * <p>A hunk is reviewed when its digest has an approved verdict that is not
 * stale against the current base (the Files navigator's rule; a missing base
 * vouches for none). It collapses to "reviewed in §N" when that verdict was
 * settled in another tour step. Computed sections have no stable id, so a
 * verdict never names one and nothing collapses there.</p>
 */
public final class SectionsModel {

    /** The trailing section's title in tour mode: the hunks no step's anchors reach. */
    public static final String NOT_IN_TOUR = "Not in the tour";

    private SectionsModel() {
    }

    /** Where the sections come from. */
    public enum Source {
        /** The agent's stored tour, one section per step. */
        TOUR,
        /** {@link Sections#of}, titles only. */
        COMPUTED,
        /** Neither: no tour, and the computed sections are not available. */
        NONE
    }

    /** What the sections pane shows. */
    public record Snapshot(Source source, Optional<String> intent, List<Section> sections) {
        public static final Snapshot EMPTY = new Snapshot(Source.NONE, Optional.empty(), List.of());

        public Snapshot {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(intent, "intent");
            sections = List.copyOf(sections);
        }

        /** The section with {@code key}, if this snapshot has it. */
        public Optional<Section> section(String key) {
            return sections.stream().filter(section -> section.key().equals(key)).findFirst();
        }
    }

    /**
     * One section.
     *
     * @param key          stable within a snapshot source: {@code step:<id>}, {@code computed:<number>},
     *                     or {@code rest} for the hunks no tour step reaches
     * @param number       1-based, the "§N" the chips and collapsed rows name
     * @param stepId       the tour step, in tour mode
     * @param buildsOn     the numbers of the sections this one depends on
     * @param neededBy     the numbers of the sections that depend on this one
     * @param reviewed     distinct digests reviewed
     * @param total        distinct digests
     * @param openFindings open findings on the section's hunks
     */
    public record Section(String key, int number, Optional<String> stepId, String title, String narrative,
                          List<Integer> buildsOn, List<Integer> neededBy, List<Hunk> hunks,
                          int reviewed, int total, int openFindings) {
        public Section {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(narrative, "narrative");
            buildsOn = List.copyOf(buildsOn);
            neededBy = List.copyOf(neededBy);
            hunks = List.copyOf(hunks);
        }

        /** The navigator's progress text, {@code k/n}. */
        public String progressText() {
            return reviewed + "/" + total;
        }
    }

    /**
     * One hunk card.
     *
     * @param lines            the card header's line span ({@code 12–30}, or {@code old 5–7} for a
     *                         hunk that only removes lines)
     * @param scrollKey        the line key the card's diff scrolls to: the hunk's first changed row
     * @param alsoIn           the numbers of the other sections that show the same digest
     * @param settledElsewhere the section the hunk was reviewed in, when that is another tour step
     * @param take             the agent's take on the hunk, from the tour
     */
    public record Hunk(String file, int index, String hunkId, String digest, String lines, String scrollKey,
                       int removed, int added, List<Integer> alsoIn, boolean reviewed,
                       Optional<Settled> settledElsewhere, Optional<HunkTake> take) {
        public Hunk {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(hunkId, "hunkId");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(lines, "lines");
            Objects.requireNonNull(scrollKey, "scrollKey");
            alsoIn = List.copyOf(alsoIn);
            Objects.requireNonNull(settledElsewhere, "settledElsewhere");
            Objects.requireNonNull(take, "take");
        }

        /** Whether the card shows as the one-line "reviewed in §N" row. */
        public boolean collapsed() {
            return settledElsewhere.isPresent();
        }
    }

    /** The section a hunk was reviewed in. */
    public record Settled(int number, String title) {
        public Settled {
            Objects.requireNonNull(title, "title");
        }

        /** The collapsed row's text. */
        public String text() {
            return "reviewed in §" + number + " · " + title;
        }
    }

    /**
     * Builds the snapshot. {@code tour} wins when present; otherwise
     * {@code computed} is used, and an empty {@code computed} means
     * {@link Source#NONE}. {@code verdicts} and {@code findings} are the one
     * scope's.
     */
    public static Snapshot of(UnifiedDiff diff, Optional<ReviewTour> tour, List<Sections.Section> computed,
                              List<ReviewVerdict> verdicts, List<ReviewAnnotation> findings,
                              Optional<String> currentBase) {
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(tour, "tour");
        Objects.requireNonNull(computed, "computed");
        Map<String, HunkSource> hunks = hunksOf(diff);
        List<Draft> drafts;
        Source source;
        if (tour.isPresent()) {
            drafts = tourDrafts(tour.get(), diff, hunks);
            source = Source.TOUR;
        } else if (!computed.isEmpty()) {
            drafts = computedDrafts(computed, hunks);
            source = Source.COMPUTED;
        } else {
            return Snapshot.EMPTY;
        }
        return new Snapshot(source, tour.flatMap(ReviewTour::intent),
                finish(drafts, tour.map(ReviewTour::takes).orElse(List.of()), verdicts, findings, currentBase));
    }

    /** One hunk of the diff, before it is placed in a section. */
    private record HunkSource(String file, int index, String hunkId, String digest, String lines, String scrollKey,
                              int removed, int added, Set<String> rowKeys) {
    }

    /** A section before numbering and progress. */
    private record Draft(String key, Optional<String> stepId, String title, String narrative,
                         List<String> dependsOn, List<HunkSource> hunks) {
    }

    private static Map<String, HunkSource> hunksOf(UnifiedDiff diff) {
        Map<String, HunkSource> hunks = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (int i = 0; i < file.hunks().size(); i++) {
                UnifiedDiff.Hunk hunk = file.hunks().get(i);
                String id = HunkIds.hunkId(file.path(), i);
                hunks.put(id, source(file.path(), i, id, hunk));
            }
        }
        return hunks;
    }

    private static HunkSource source(String path, int index, String id, UnifiedDiff.Hunk hunk) {
        int removed = 0;
        int added = 0;
        int firstNew = Integer.MAX_VALUE;
        int lastNew = Integer.MIN_VALUE;
        int firstOld = Integer.MAX_VALUE;
        int lastOld = Integer.MIN_VALUE;
        String scrollKey = null;
        Set<String> rowKeys = new LinkedHashSet<>();
        for (UnifiedDiff.Line line : hunk.lines()) {
            rowKeys.add(line.lineKey());
            if (line.kind() == UnifiedDiff.Line.Kind.CONTEXT) {
                continue;
            }
            if (scrollKey == null) {
                scrollKey = line.lineKey();
            }
            if (line.kind() == UnifiedDiff.Line.Kind.ADD) {
                added++;
                int n = line.newLine().orElse(0);
                firstNew = Math.min(firstNew, n);
                lastNew = Math.max(lastNew, n);
            } else {
                removed++;
                int o = line.oldLine().orElse(0);
                firstOld = Math.min(firstOld, o);
                lastOld = Math.max(lastOld, o);
            }
        }
        String lines = added > 0 ? span(firstNew, lastNew) : removed > 0 ? "old " + span(firstOld, lastOld) : "";
        if (scrollKey == null) {
            scrollKey = hunk.lines().isEmpty() ? "n1" : hunk.lines().getFirst().lineKey();
        }
        return new HunkSource(path, index, id, HunkDigest.of(path, hunk), lines, scrollKey, removed, added, rowKeys);
    }

    private static String span(int first, int last) {
        return first == last ? Integer.toString(first) : first + "–" + last;
    }

    private static List<Draft> tourDrafts(ReviewTour tour, UnifiedDiff diff, Map<String, HunkSource> hunks) {
        AnchorIndex index = AnchorIndex.of(diff);
        Set<String> covered = new LinkedHashSet<>();
        List<Draft> drafts = new ArrayList<>();
        for (TourStep step : tour.steps()) {
            Map<String, HunkSource> own = new LinkedHashMap<>();
            for (TourAnchor anchor : step.anchors()) {
                for (AnchorIndex.HunkRef ref : index.hunksTouched(anchor)) {
                    HunkSource hunk = hunks.get(ref.hunkId());
                    if (hunk != null) {
                        own.putIfAbsent(hunk.hunkId(), hunk);
                    }
                }
            }
            covered.addAll(own.keySet());
            drafts.add(new Draft("step:" + step.id(), Optional.of(step.id()), step.title(), step.narrative(),
                    step.dependsOn(), List.copyOf(own.values())));
        }
        List<HunkSource> rest = hunks.values().stream().filter(hunk -> !covered.contains(hunk.hunkId())).toList();
        if (!rest.isEmpty()) {
            drafts.add(new Draft("rest", Optional.empty(), NOT_IN_TOUR, "", List.of(), rest));
        }
        return drafts;
    }

    private static List<Draft> computedDrafts(List<Sections.Section> computed, Map<String, HunkSource> hunks) {
        List<Draft> drafts = new ArrayList<>();
        for (int i = 0; i < computed.size(); i++) {
            Sections.Section section = computed.get(i);
            List<HunkSource> own = new ArrayList<>();
            for (String id : new LinkedHashSet<>(section.hunkIds())) {
                HunkSource hunk = hunks.get(id);
                if (hunk != null) {
                    own.add(hunk);
                }
            }
            drafts.add(new Draft("computed:" + (i + 1), Optional.empty(), section.title(), "", List.of(), own));
        }
        return drafts;
    }

    private static List<Section> finish(List<Draft> drafts, List<HunkTake> takes, List<ReviewVerdict> verdicts,
                                        List<ReviewAnnotation> findings, Optional<String> currentBase) {
        Map<String, HunkTake> takeByDigest = new HashMap<>();
        for (HunkTake take : takes) {
            takeByDigest.putIfAbsent(take.hunkDigest(), take);
        }
        Map<String, ReviewVerdict> byDigest = new LinkedHashMap<>();
        for (ReviewVerdict verdict : verdicts) {
            byDigest.putIfAbsent(verdict.hunkDigest(), verdict);
        }
        Map<String, Integer> numberOfStep = new HashMap<>();
        Map<Integer, String> titleOfNumber = new HashMap<>();
        Map<String, List<Integer>> sectionsOfDigest = new HashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            int number = i + 1;
            draft.stepId().ifPresent(id -> numberOfStep.putIfAbsent(id, number));
            titleOfNumber.put(number, draft.title());
            for (String digest : new LinkedHashSet<>(draft.hunks().stream().map(HunkSource::digest).toList())) {
                sectionsOfDigest.computeIfAbsent(digest, d -> new ArrayList<>()).add(number);
            }
        }
        Map<Integer, List<Integer>> neededBy = new HashMap<>();
        List<List<Integer>> buildsOn = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            List<Integer> numbers = new ArrayList<>();
            for (String id : drafts.get(i).dependsOn()) {
                Integer number = numberOfStep.get(id);
                if (number != null && number != i + 1) {
                    numbers.add(number);
                    neededBy.computeIfAbsent(number, n -> new ArrayList<>()).add(i + 1);
                }
            }
            buildsOn.add(numbers);
        }
        List<ReviewAnnotation> open = findings.stream()
                .filter(finding -> !finding.resolved() && finding.triage() != Triage.DISMISSED)
                .toList();

        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            int number = i + 1;
            List<Hunk> cards = new ArrayList<>();
            Set<String> digests = new LinkedHashSet<>();
            Set<String> reviewedDigests = new LinkedHashSet<>();
            for (HunkSource hunk : draft.hunks()) {
                ReviewVerdict verdict = byDigest.get(hunk.digest());
                boolean reviewed = reviewed(verdict, currentBase);
                digests.add(hunk.digest());
                if (reviewed) {
                    reviewedDigests.add(hunk.digest());
                }
                Optional<Settled> elsewhere = Optional.empty();
                if (reviewed && verdict.settledInStep().isPresent()) {
                    Integer settledIn = numberOfStep.get(verdict.settledInStep().get());
                    if (settledIn != null && settledIn != number) {
                        elsewhere = Optional.of(new Settled(settledIn, titleOfNumber.get(settledIn)));
                    }
                }
                List<Integer> alsoIn = sectionsOfDigest.getOrDefault(hunk.digest(), List.of()).stream()
                        .filter(other -> other != number)
                        .toList();
                cards.add(new Hunk(hunk.file(), hunk.index(), hunk.hunkId(), hunk.digest(), hunk.lines(),
                        hunk.scrollKey(), hunk.removed(), hunk.added(), alsoIn, reviewed, elsewhere,
                        Optional.ofNullable(takeByDigest.get(hunk.digest()))));
            }
            int openFindings = (int) open.stream()
                    .filter(finding -> draft.hunks().stream().anyMatch(hunk -> hunk.file().equals(finding.file())
                            && hunk.rowKeys().contains(finding.startKey())))
                    .count();
            sections.add(new Section(draft.key(), number, draft.stepId(), draft.title(), draft.narrative(),
                    buildsOn.get(i), neededBy.getOrDefault(number, List.of()), cards,
                    reviewedDigests.size(), digests.size(), openFindings));
        }
        return sections;
    }

    private static boolean reviewed(ReviewVerdict verdict, Optional<String> currentBase) {
        return verdict != null && currentBase.isPresent() && !verdict.staleAgainst(currentBase.get())
                && (verdict.decision() == ReviewVerdict.Decision.APPROVED
                || verdict.decision() == ReviewVerdict.Decision.AUTO_APPROVED);
    }

    /** The digests of {@code section} that are not reviewed yet, in card order: what "Mark section reviewed" writes. */
    public static List<String> unsettledDigests(Section section) {
        Set<String> digests = new LinkedHashSet<>();
        for (Hunk hunk : section.hunks()) {
            if (!hunk.reviewed()) {
                digests.add(hunk.digest());
            }
        }
        return List.copyOf(digests);
    }
}

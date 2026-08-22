package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order and cycles (spec §6.1). Foundation first: if A is referenced by B,
 * A is read before B. A cycle is collapsed into one named unit rather than
 * broken arbitrarily -- a cycle among changed symbols is a fact about the
 * change worth showing, and a silent arbitrary break is the unexplained
 * ordering this whole feature exists to remove.
 */
class GraphsTest {

    private static SortedSet<String> set(String... values) {
        return new TreeSet<>(List.of(values));
    }

    private static List<List<String>> order(Map<String, SortedSet<String>> dependsOn) {
        return Graphs.topologicalOrder(new TreeSet<>(dependsOn.keySet()),
                node -> dependsOn.getOrDefault(node, new TreeSet<>()),
                Comparator.naturalOrder());
    }

    @Test
    void aDependencyIsReadBeforeItsDependent() {
        assertEquals(List.of(List.of("guards"), List.of("profiler")),
                order(Map.of("profiler", set("guards"), "guards", set())));
    }

    @Test
    void independentNodesFallBackToTheTieBreak() {
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")),
                order(Map.of("c", set(), "a", set(), "b", set())));
    }

    @Test
    void aCycleBecomesOneUnitHoldingItsMembers() {
        List<List<String>> result = order(Map.of("a", set("b"), "b", set("a"), "c", set("a")));

        assertEquals(List.of("a", "b"), result.get(0));
        assertEquals(List.of("c"), result.get(1));
    }

    /**
     * Determinism, pinned: the same graph presented in a different insertion
     * order must produce the identical result (spec §9.5).
     */
    @Test
    void theOrderDoesNotDependOnInsertionOrder() {
        assertEquals(order(Map.of("a", set(), "b", set("a"), "c", set("b"))),
                order(Map.of("c", set("b"), "a", set(), "b", set("a"))));
    }

    @Test
    void anEmptyGraphOrdersToNothing() {
        assertEquals(List.of(), order(Map.of()));
    }

    // --- The cases below are hand-computed to pin down the iterative
    // Tarjan and Kahn condensation: each comment traces the expected order
    // step by step rather than just stating it, since that reasoning is
    // what would need re-doing if this algorithm is ever touched again. ---

    @Test
    void aChainOrdersFoundationFirst() {
        // a -> b -> c (a depends on b, b depends on c): c, b, a.
        assertEquals(List.of(List.of("c"), List.of("b"), List.of("a")),
                order(Map.of("a", set("b"), "b", set("c"), "c", set())));
    }

    @Test
    void aThreeNodeCycleCollapsesToOneUnitOrderedByTieBreak() {
        // a -> b -> c -> a, a genuine 3-cycle with no other nodes.
        List<List<String>> result = order(Map.of("a", set("b"), "b", set("c"), "c", set("a")));

        assertEquals(1, result.size());
        assertEquals(List.of("a", "b", "c"), result.get(0));
    }

    @Test
    void aCycleWithANodeHangingOffItKeepsTheHangerSeparate() {
        // a <-> b is the cycle; c depends on b but nothing depends on c, and
        // c is not part of the cycle, so it must be its own trailing unit.
        List<List<String>> result = order(Map.of("a", set("b"), "b", set("a"), "c", set("b")));

        assertEquals(List.of(List.of("a", "b"), List.of("c")), result);
    }

    @Test
    void twoDisjointComponentsBothAppearOrderedByTheTieBreak() {
        // x -> y and w -> z: two independent chains, unrelated to each
        // other. Tracing Kahn's ready set step by step (not just the two
        // chains in isolation) is what this case is for: "y" and "z" are
        // both foundation nodes, so both are ready first, and "y" < "z"
        // picks y. Removing y frees x, so the ready set is now {x, z}, and
        // "x" < "z" picks x next -- interleaving the two chains rather than
        // draining one chain before starting the other. Then z, then w.
        List<List<String>> result = order(Map.of(
                "y", set(), "x", set("y"),
                "z", set(), "w", set("z")));

        assertEquals(List.of(List.of("y"), List.of("x"), List.of("z"), List.of("w")), result);
    }

    @Test
    void aSelfLoopIsItsOwnSingletonUnit() {
        // Not producible by ChangeGraph -- filesReferencedBy never includes
        // the file itself -- but Graphs must not corrupt on one anyway.
        List<List<String>> result = order(Map.of("a", set("a"), "b", set("a")));

        assertEquals(List.of(List.of("a"), List.of("b")), result);
    }

    @Test
    void aDiamondOrdersTheSharedBaseFirstWithoutMergingLowLinksWrongly() {
        // top depends on both left and right, each of which depends on
        // base. This is the classic case that catches a wrong low-link
        // merge: left and right must NOT be folded into one SCC with base.
        List<List<String>> result = order(Map.of(
                "top", set("left", "right"),
                "left", set("base"),
                "right", set("base"),
                "base", set()));

        assertEquals(List.of(
                List.of("base"), List.of("left"), List.of("right"), List.of("top")), result);
    }

    @Test
    void aDependencyOutsideTheNodeSetIsRejectedRatherThanSilentlyDropped() {
        // "b" depends on "ghost", which never appears in nodes. Silently
        // dropping this edge would make "no such dependency" and "a
        // dependency on a node the caller forgot to include" produce the
        // same output, so Graphs throws instead of guessing.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> order(Map.of("a", set(), "b", set("a", "ghost"))));

        assertTrue(thrown.getMessage().contains("ghost"),
                "expected the phantom node's name in the message, got: " + thrown.getMessage());
    }
}

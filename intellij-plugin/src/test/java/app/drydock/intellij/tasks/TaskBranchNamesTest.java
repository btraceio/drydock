package app.drydock.intellij.tasks;

import app.drydock.git.BranchNameRules;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBranchNamesTest {

    @Test
    void generatedNameIsThreeLowercaseWordsAndAValidBranch() {
        String branch = TaskBranchNames.generate(Set.of());

        assertTrue(branch.matches("[a-z]+-[a-z]+-[a-z]+"), branch);
        assertTrue(BranchNameRules.check(branch, List.of("origin")).isEmpty(), branch);
    }

    @Test
    void randomDrawSelectsBothEndsOfTheVocabulary() {
        Random first = new Random(0) {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        AtomicInteger draws = new AtomicInteger();
        Random last = new Random(0) {
            @Override
            public int nextInt(int bound) {
                assertTrue(bound > 0 && (bound & (bound - 1)) == 0,
                        "The collision walk requires a power-of-two name space");
                draws.incrementAndGet();
                return bound - 1;
            }
        };

        String firstName = TaskBranchNames.generate(Set.of(), first);
        String lastName = TaskBranchNames.generate(Set.of(), last);

        assertEquals("bouncy-amber-alpaca", firstName);
        assertEquals("zippy-velvet-wren", lastName);
        assertNotEquals(firstName, lastName);
        assertEquals(2, draws.get());
    }

    @Test
    void collisionsChooseOtherThreeWordNamesWithoutNumericSuffixes() {
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 128; i++) {
            // Restarting the same random source forces every prior name on this walk to collide.
            String branch = TaskBranchNames.generate(taken, new Random(42));
            assertTrue(branch.matches("[a-z]+-[a-z]+-[a-z]+"), branch);
            assertTrue(taken.add(branch), "Reused a taken branch: " + branch);
        }
        assertEquals(128, taken.size());
    }

    @Test
    void unrelatedTakenBranchesDoNotChangeTheDraw() {
        String available = TaskBranchNames.generate(Set.of(), new Random(42));

        assertEquals(available, TaskBranchNames.generate(Set.of("main", "fix-parser", "task-2"), new Random(42)));
    }
}

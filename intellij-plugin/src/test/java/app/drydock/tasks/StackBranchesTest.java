package app.drydock.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The stacked-branch naming convention's mutation guard: every branch name
 * is {@link StackBranches#PREFIX} plus the task's id, the parse is the exact
 * inverse, and anything outside the convention -- another prefix, an empty
 * remainder, uppercase, a slash -- parses to null. */
class StackBranchesTest {

    @Test
    void nameIsThePrefixPlusTheTaskId() {
        assertEquals("dd/", StackBranches.PREFIX);
        assertEquals("dd/fix-a1b2", StackBranches.name(TaskId.of("fix-a1b2")));
        assertEquals("dd/retry-login-flow-c81e", StackBranches.name(TaskId.of("retry-login-flow-c81e")));
    }

    @Test
    void taskOfRoundTripsAStackedBranchName() {
        assertEquals(TaskId.of("fix-a1b2"), StackBranches.taskOf("dd/fix-a1b2"));
        assertEquals(TaskId.of("fix-a1b2"), StackBranches.taskOf(StackBranches.name(TaskId.of("fix-a1b2"))));
    }

    @Test
    void taskOfRejectsNamesOutsideTheConvention() {
        assertNull(StackBranches.taskOf("feature/x"));
        assertNull(StackBranches.taskOf("ddfix-a1b2"));
        assertNull(StackBranches.taskOf("dd/"));
        assertNull(StackBranches.taskOf("dd/Fix-Login"));
        assertNull(StackBranches.taskOf("dd/with/slash"));
        assertNull(StackBranches.taskOf(null));
    }

    @Test
    void nameNeedsATask() {
        assertThrows(NullPointerException.class, () -> StackBranches.name(null));
    }
}

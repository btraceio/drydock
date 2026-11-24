package app.drydock.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The priority scale: exactly the seven names the registered Jira site
 * exposes, in Jira's own priority-id order (Top first, Unprioritized last),
 * strict stored-name parsing for front matter, and a lenient parse for
 * values already in the wild.
 */
class TaskPriorityTest {

    @Test
    void storedNamesAreTheJiraNamesVerbatim() {
        assertEquals("Top", TaskPriority.TOP.storedName());
        assertEquals("Highest", TaskPriority.HIGHEST.storedName());
        assertEquals("High", TaskPriority.HIGH.storedName());
        assertEquals("Medium", TaskPriority.MEDIUM.storedName());
        assertEquals("Low", TaskPriority.LOW.storedName());
        assertEquals("Lowest", TaskPriority.LOWEST.storedName());
        assertEquals("Unprioritized", TaskPriority.UNPRIORITIZED.storedName());
    }

    @Test
    void rankIsUrgencyOrderWithUnprioritizedLast() {
        List<TaskPriority> ordered = List.of(TaskPriority.values());
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i - 1).rank() >= ordered.get(i).rank()) {
                throw new AssertionError("rank must strictly increase: "
                        + ordered.get(i - 1) + " then " + ordered.get(i));
            }
        }
        assertEquals(TaskPriority.TOP, ordered.get(0));
        assertEquals(TaskPriority.UNPRIORITIZED, ordered.get(ordered.size() - 1));
    }

    @Test
    void fromStoredAcceptsExactlyTheSevenNames() {
        for (TaskPriority priority : TaskPriority.values()) {
            assertEquals(priority, TaskPriority.fromStored(priority.storedName()));
        }
        assertThrows(IllegalArgumentException.class, () -> TaskPriority.fromStored("highest"));
        assertThrows(IllegalArgumentException.class, () -> TaskPriority.fromStored("P0"));
        assertThrows(IllegalArgumentException.class, () -> TaskPriority.fromStored(""));
    }

    @Test
    void fromKnownMatchesCaseInsensitivelyAndRefusesUnknownWords() {
        assertEquals(Optional.of(TaskPriority.TOP), TaskPriority.fromKnown("top"));
        assertEquals(Optional.of(TaskPriority.LOWEST), TaskPriority.fromKnown("  LOWEST "));
        assertEquals(Optional.empty(), TaskPriority.fromKnown("P0"));
        assertEquals(Optional.empty(), TaskPriority.fromKnown("Critical"));
        assertEquals(Optional.empty(), TaskPriority.fromKnown(""));
    }
}

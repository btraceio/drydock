package app.drydock.intellij.tasks;

import app.drydock.tasks.TaskPriority;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskTreeRendererTopPriorityTest {

    @Test
    void topPriorityUsesHighBadge() {
        assertEquals(Optional.of(TaskTreeRenderer.PriorityKind.HIGH),
                TaskTreeRenderer.priorityKind(TaskPriority.TOP));
    }
}

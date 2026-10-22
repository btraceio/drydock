package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskChangesToolWindowFactoryTest {

    @Test
    void deliversChangesPreparedBeforeLazyToolWindowContentExists() {
        TaskChangesToolWindowFactory.LatestValueDelivery<String> delivery =
                new TaskChangesToolWindowFactory.LatestValueDelivery<>();
        List<String> displayed = new ArrayList<>();

        delivery.publish("prepared changes");
        delivery.attach(displayed::add);

        assertEquals(List.of("prepared changes"), displayed);
    }

    @Test
    void deliversSubsequentChangesToExistingToolWindowContent() {
        TaskChangesToolWindowFactory.LatestValueDelivery<String> delivery =
                new TaskChangesToolWindowFactory.LatestValueDelivery<>();
        List<String> displayed = new ArrayList<>();
        Consumer<String> listener = displayed::add;

        delivery.attach(listener);
        delivery.publish("first");
        delivery.publish("second");
        delivery.detach(listener);
        delivery.publish("after close");

        assertEquals(List.of("first", "second"), displayed);
    }

    @Test
    void selectedAgentTabProvidesAStartupRestoreFallback() {
        ManagedSessionId sessionId = ManagedSessionId.newId();
        AgentConsoleFile console = new AgentConsoleFile(new AgentConsoleFileSystem(), sessionId);

        assertEquals(sessionId, TaskChangesToolWindowFactory.selectedAgentSession(
                new VirtualFile[] {new LightVirtualFile("ordinary.txt"), console}).orElseThrow());
        assertTrue(TaskChangesToolWindowFactory.selectedAgentSession(
                new VirtualFile[] {new LightVirtualFile("ordinary.txt")}).isEmpty());
    }
}

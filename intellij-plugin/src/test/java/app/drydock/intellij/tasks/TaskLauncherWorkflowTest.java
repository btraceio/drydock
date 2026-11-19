package app.drydock.intellij.tasks;

import app.drydock.sdlc.LocalWorkflowMaterializer;
import app.drydock.sdlc.WorkflowBundle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The T18 launcher-wiring pins that run without the IntelliJ platform fixture: the hand-off's bundle id
 * rule is the materializer's staged bundle name (the invocation id is minted inside the controller and
 * never travels, so {@link TaskLauncher.WorkflowHandoff} derives the name through the shared
 * {@link LocalWorkflowMaterializer#bundleName} rule — the delegation makes the two call sites structurally
 * identical), and the hand-off refuses a missing invocation id (a staged tree without its name cannot be
 * cleaned up by the owner).
 */
final class TaskLauncherWorkflowTest {

    @Test
    void bundleNameRuleMatchesTheDocumentedDerivation() {
        String invocationId = UUID.randomUUID().toString();
        assertEquals("wf-" + WorkflowBundle.sha256Hex(invocationId.getBytes(StandardCharsets.UTF_8))
                .substring(0, 24), LocalWorkflowMaterializer.bundleName(invocationId));
    }

    @Test
    void handoffRequiresAnInvocationId() {
        assertThrows(NullPointerException.class,
                () -> new TaskLauncher.WorkflowHandoff(null, null, 0));
    }
}

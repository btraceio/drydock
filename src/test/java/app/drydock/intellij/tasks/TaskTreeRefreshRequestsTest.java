package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskTreeRefreshRequestsTest {

    @Test
    void worktreeChangeDuringAScanInvalidatesTheFollowUpScan() {
        TaskTreeRefreshRequests requests = new TaskTreeRefreshRequests();

        requests.request(false);
        assertEquals(Optional.of(new TaskTreeRefreshRequests.Pass(1, false)), requests.beginPass());

        requests.request(true);
        assertEquals(Optional.empty(), requests.beginPass(), "the current scan still owns the refresh");
        requests.finishPass();

        assertEquals(Optional.of(new TaskTreeRefreshRequests.Pass(2, true)), requests.beginPass(),
                "the removal's invalidation must survive until the earlier scan has finished");
    }
}

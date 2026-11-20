package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AssociatePullRequestTest {
    @Test
    void acceptsOnlyPositiveNumbersAndBlankExplicitlyClears() {
        assertEquals(Optional.of(898), AssociatePullRequest.number("  #898 "));
        assertEquals(Optional.of(943), AssociatePullRequest.number("943"));
        assertEquals(Optional.empty(), AssociatePullRequest.number("   "));
        for (String text : new String[] {"0", "-1", "--repo", "1 other", "2147483648", "#", "1.5"}) {
            assertThrows(IllegalArgumentException.class, () -> AssociatePullRequest.number(text), text);
        }
    }

    @Test
    void providesNonblankDisplayMessageForEveryThrowable() {
        assertUsableMessage("Association failed", new IllegalStateException("Association failed"));
        assertUsableMessage("IllegalStateException", new IllegalStateException());
        assertUsableMessage("IllegalArgumentException", new IllegalArgumentException(" \t"));
        assertUsableMessage("An unexpected error occurred.", new Throwable() { });
    }

    private static void assertUsableMessage(String expected, Throwable cause) {
        String message = AssociatePullRequest.displayMessage(cause);
        assertEquals(expected, message);
        assertNotNull(message);
        assertFalse(message.isBlank());
    }
}

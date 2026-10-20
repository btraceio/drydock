package app.drydock.intellij.agent;

import app.drydock.intellij.agent.AttentionPlan.Ask;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The "needs you" notification's copy, as the handoff states it. */
class AttentionPlanTest {

    private static final Ask CLAUDE = new Ask("Claude", "Add a shout method", "dd-scratch · add-a-shout");
    private static final Ask PI = new Ask("pi", "Review <Greeter> & co", "dd-scratch · main");

    @Test
    void oneAgentIsNamedAndSeveralCollapseIntoOne() {
        assertEquals("Claude needs you", AttentionPlan.title(List.of(CLAUDE)));
        assertEquals("2 agents need you", AttentionPlan.title(List.of(CLAUDE, PI)));
    }

    @Test
    void eachAgentIsALineOfTitleAndPlace() {
        assertEquals("Add a shout method · dd-scratch · add-a-shout", AttentionPlan.plainBody(List.of(CLAUDE)));
        assertEquals("Add a shout method · dd-scratch · add-a-shout\nReview <Greeter> & co · dd-scratch · main",
                AttentionPlan.plainBody(List.of(CLAUDE, PI)));
    }

    @Test
    void aTitleIsTextNeverMarkup() {
        assertEquals("Review &lt;Greeter&gt; &amp; co · dd-scratch · main", AttentionPlan.htmlBody(List.of(PI)));
    }
}

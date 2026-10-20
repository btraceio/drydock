package app.drydock.intellij.agent;

import app.drydock.intellij.agent.AttentionPlan.Ask;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    @Test
    void theBalloonNamesTheSessionsOwnPlace() {
        Ask hosted = new Ask("Codex", "Trim the OTLP profile",
                new SessionPlace("dd-trace-java", Optional.of("jb/rnd_otlp")).label());

        assertEquals("Trim the OTLP profile · dd-trace-java · jb/rnd_otlp", AttentionPlan.plainBody(List.of(hosted)));
    }

    @Test
    void aNotShownSessionOffersShowHere() {
        assertEquals(List.of("Show here", "Open project window"), AttentionPlan.actions(false));
    }

    @Test
    void aShownSessionOffersRaise() {
        assertEquals(List.of("Raise window", "Snooze"), AttentionPlan.actions(true));
    }
}

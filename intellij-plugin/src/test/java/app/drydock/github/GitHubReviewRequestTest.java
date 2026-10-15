package app.drydock.github;

import app.drydock.github.GitHubLineAnchor.Side;
import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The body of POST /repos/{owner}/{repo}/pulls/{n}/reviews. */
class GitHubReviewRequestTest {

    @Test
    void aSingleLineCommentOmitsStartLineEntirely() {
        JsonObject body = (JsonObject) GitHubReviewRequest.body(Event.COMMENT, "looks off",
                List.of(new Comment("app/Sidebar.java", "why this?",
                        new GitHubLineAnchor.Anchor(42, Side.RIGHT, OptionalInt.empty(), Optional.empty()))));

        JsonObject comment = (JsonObject) ((JsonArray) body.get("comments")).elements().get(0);
        assertEquals("app/Sidebar.java", ((JsonString) comment.get("path")).value());
        assertEquals("RIGHT", ((JsonString) comment.get("side")).value());
        assertNull(comment.get("start_line"), "GitHub rejects start_line equal to line");
        assertNull(comment.get("start_side"));
    }

    @Test
    void aMixedSideRangeSendsBothSidesUnchanged() {
        JsonObject body = (JsonObject) GitHubReviewRequest.body(Event.REQUEST_CHANGES, "see inline",
                List.of(new Comment("app/Sidebar.java", "this replacement drops the guard",
                        new GitHubLineAnchor.Anchor(58, Side.RIGHT, OptionalInt.of(55), Optional.of(Side.LEFT)))));

        JsonObject comment = (JsonObject) ((JsonArray) body.get("comments")).elements().get(0);
        assertEquals("LEFT", ((JsonString) comment.get("start_side")).value());
        assertEquals("RIGHT", ((JsonString) comment.get("side")).value());
        assertEquals("REQUEST_CHANGES", ((JsonString) body.get("event")).value());
    }

    @Test
    void anApprovalWithNoCommentsStillCarriesAnEmptyArray() {
        JsonObject body = (JsonObject) GitHubReviewRequest.body(Event.APPROVE, "", List.of());

        assertEquals("APPROVE", ((JsonString) body.get("event")).value());
        assertEquals("", ((JsonString) body.get("body")).value());
        assertInstanceOf(JsonArray.class, body.get("comments"));
        assertEquals(0, ((JsonArray) body.get("comments")).elements().size());
    }

    @Test
    void aNullSummaryBecomesAnEmptyString() {
        JsonObject body = (JsonObject) GitHubReviewRequest.body(Event.COMMENT, null, List.of());

        assertEquals("", ((JsonString) body.get("body")).value());
    }
}

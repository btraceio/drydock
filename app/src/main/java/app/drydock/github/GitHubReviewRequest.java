package app.drydock.github;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The body of {@code POST repos/{owner}/{repo}/pulls/{n}/reviews}: one review,
 * with every comment in it, so a failure posts nothing rather than half of it.
 * Pure -- building the JSON is separable from running {@code gh}, and only
 * this half is worth testing.
 */
public final class GitHubReviewRequest {

    private GitHubReviewRequest() {
    }

    /** The three things a review can be. */
    public enum Event { APPROVE, COMMENT, REQUEST_CHANGES }

    /** One review comment: a repo-relative path, a body, and where it hangs. */
    public record Comment(String path, String body, GitHubLineAnchor.Anchor anchor) {
        public Comment {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(anchor, "anchor");
        }
    }

    public static JsonValue body(Event event, String summary, List<Comment> comments) {
        JsonObject root = JsonObject.empty();
        root.put("event", new JsonString(event.name()));
        root.put("body", new JsonString(summary == null ? "" : summary));
        List<JsonValue> entries = new ArrayList<>();
        for (Comment comment : comments) {
            JsonObject obj = JsonObject.empty();
            obj.put("path", new JsonString(comment.path()));
            obj.put("body", new JsonString(comment.body()));
            obj.put("line", JsonNumber.of(comment.anchor().line()));
            obj.put("side", new JsonString(comment.anchor().side().name()));
            // Sent only for a genuine range: GitHub rejects start_line == line.
            if (comment.anchor().startLine().isPresent()) {
                obj.put("start_line", JsonNumber.of(comment.anchor().startLine().getAsInt()));
                comment.anchor().startSide()
                        .ifPresent(side -> obj.put("start_side", new JsonString(side.name())));
            }
            entries.add(obj);
        }
        root.put("comments", new JsonArray(entries));
        return root;
    }
}

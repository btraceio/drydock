package app.drydock.intellij.review;

import app.drydock.review.tour.ImpactNote;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourDiagram;
import app.drydock.review.tour.TourStep;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the Tour tab shows for one step, as HTML for a {@code JEditorPane},
 * plus the links inside it. Pure, so the rendering and the link contract
 * are testable headless.
 *
 * <p>This is the plain reading of a tour, not the desktop app's guided walk:
 * nothing is hidden until a check is answered, checks are not graded or
 * recorded, and a step's progress is not written back to the tour store. A
 * check's answer stays behind a "Show answer" link so the reviewer can still
 * try it first; what is revealed lives only in the panel.</p>
 *
 * <p>Links carry indexes into the step being shown, never file paths:
 * {@code anchor:<i>} is the step's i-th anchor and {@code check:<i>} reveals
 * the answer of its i-th check. A path or an agent-chosen id in an href would
 * need URL encoding both ways, and the step is at hand when the link is
 * followed anyway.</p>
 */
final class TourView {

    static final String ANCHOR_LINK = "anchor:";
    static final String CHECK_LINK = "check:";

    /** Shown above a step whose tour was written against hunks the review diff no longer has. */
    static final String STALE_NOTICE = "The code changed after this tour was written: some steps may describe "
            + "code that is gone, and their line links may land on moved lines. Ask the agent for a new tour "
            + "(Review Changes with Tour) to bring it up to date.";

    /** A followed link: an anchor to open in the review diff, or a check whose answer to reveal. */
    sealed interface Link permits AnchorLink, RevealLink {}

    /** Open {@code anchor} in the review diff. */
    record AnchorLink(TourAnchor anchor) implements Link {
        AnchorLink {
            Objects.requireNonNull(anchor, "anchor");
        }
    }

    /** Reveal the answer of check {@code checkId}. */
    record RevealLink(String checkId) implements Link {
        RevealLink {
            Objects.requireNonNull(checkId, "checkId");
        }
    }

    private TourView() {
    }

    /** The step list's row text: its number and title. */
    static String stepLabel(int number, TourStep step) {
        return number + ". " + step.title();
    }

    /**
     * Resolves an href from {@link #stepHtml} against the step it was rendered
     * for; empty for anything this view did not write (an out-of-range index,
     * an unknown check, a foreign scheme).
     */
    static Optional<Link> resolve(TourStep step, String href) {
        Objects.requireNonNull(step, "step");
        if (href == null) {
            return Optional.empty();
        }
        if (href.startsWith(ANCHOR_LINK)) {
            return index(href.substring(ANCHOR_LINK.length()), step.anchors().size())
                    .map(i -> new AnchorLink(step.anchors().get(i)));
        }
        if (href.startsWith(CHECK_LINK)) {
            return index(href.substring(CHECK_LINK.length()), step.checks().size())
                    .map(i -> new RevealLink(step.checks().get(i).id()));
        }
        return Optional.empty();
    }

    private static Optional<Integer> index(String raw, int size) {
        try {
            int index = Integer.parseInt(raw);
            return index >= 0 && index < size ? Optional.of(index) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * One step as HTML: heading, the stale notice when {@code stale}, the
     * narrative, the anchors (each a link into the review diff, with its
     * note), the diagram with every stage shown, the impact notes, and the
     * checks — each check's answer only when its id is in {@code revealed}.
     */
    static String stepHtml(int number, int total, TourStep step, boolean stale, Set<String> revealed) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(revealed, "revealed");
        StringBuilder html = new StringBuilder("<html><body>");
        html.append("<h3>Step ").append(number).append(" of ").append(total).append(": ")
                .append(escape(step.title())).append("</h3>");
        if (stale) {
            html.append("<p><i>").append(escape(STALE_NOTICE)).append("</i></p>");
        }
        for (String paragraph : step.narrative().strip().split("\n\\s*\n")) {
            if (!paragraph.isBlank()) {
                html.append("<p>").append(escapeLines(paragraph.strip())).append("</p>");
            }
        }
        if (!step.anchors().isEmpty()) {
            html.append("<p><b>Code</b></p><ul>");
            List<TourAnchor> anchors = step.anchors();
            for (int i = 0; i < anchors.size(); i++) {
                TourAnchor anchor = anchors.get(i);
                html.append("<li><a href=\"").append(ANCHOR_LINK).append(i).append("\">")
                        .append(escape(anchor.file())).append(' ').append(escape(range(anchor)))
                        .append("</a>");
                if (anchor.hasNote()) {
                    html.append("<br>").append(escape(anchor.note()));
                }
                html.append("</li>");
            }
            html.append("</ul>");
        }
        step.diagram().ifPresent(diagram -> appendDiagram(html, diagram));
        if (!step.impactNotes().isEmpty()) {
            html.append("<p><b>Impact elsewhere</b></p><ul>");
            for (ImpactNote note : step.impactNotes()) {
                html.append("<li>").append(escape(note.file())).append(':').append(note.line())
                        .append(" &mdash; ").append(escape(note.text())).append("</li>");
            }
            html.append("</ul>");
        }
        List<TourCheck> checks = step.checks();
        for (int i = 0; i < checks.size(); i++) {
            appendCheck(html, i, checks.get(i), revealed.contains(checks.get(i).id()));
        }
        return html.append("</body></html>").toString();
    }

    /**
     * An anchor's line range in reader terms: {@code n<N>} is line N of the
     * new file, {@code o<N>} line N of the old one. A key of any other shape
     * is shown as written — it is a value from the store, not a crash.
     */
    static String range(TourAnchor anchor) {
        String start = lineLabel(anchor.startKey());
        String end = lineLabel(anchor.endKey());
        return start.equals(end) ? start : start + "–" + end;
    }

    private static String lineLabel(String key) {
        if (key.length() > 1 && key.substring(1).chars().allMatch(Character::isDigit)) {
            if (key.charAt(0) == 'n') {
                return "L" + key.substring(1);
            }
            if (key.charAt(0) == 'o') {
                return "old L" + key.substring(1);
            }
        }
        return key;
    }

    private static void appendDiagram(StringBuilder html, TourDiagram diagram) {
        if (!diagram.caption().isBlank()) {
            html.append("<p><b>").append(escape(diagram.caption())).append("</b></p>");
        }
        html.append("<pre>").append(escape(diagram.visibleText(diagram.stages().size()))).append("</pre>");
    }

    private static void appendCheck(StringBuilder html, int index, TourCheck check, boolean revealed) {
        html.append("<p><b>").append(kindLabel(check.kind())).append(":</b> ")
                .append(escapeLines(check.prompt())).append("</p>");
        if (!check.choices().isEmpty()) {
            html.append("<ol>");
            for (TourCheck.Choice choice : check.choices()) {
                html.append("<li>").append(escape(choice.text())).append("</li>");
            }
            html.append("</ol>");
        }
        if (check.answer().isEmpty()) {
            // A risk check: the desktop app sends the reviewer's answer to the
            // agent to judge; this panel has no such round trip.
            html.append("<p><i>No model answer: think it through, or ask the agent in its conversation.</i></p>");
            return;
        }
        if (!revealed) {
            html.append("<p><a href=\"").append(CHECK_LINK).append(index)
                    .append("\">Show answer</a></p>");
            return;
        }
        int answer = check.answer().getAsInt();
        String choice = answer >= 0 && answer < check.choices().size()
                ? (answer + 1) + ". " + check.choices().get(answer).text()
                : String.valueOf(answer + 1);
        html.append("<p><b>Answer:</b> ").append(escape(choice)).append("</p>");
        if (!check.explanation().isBlank()) {
            html.append("<p>").append(escapeLines(check.explanation())).append("</p>");
        }
    }

    private static String kindLabel(TourCheck.Kind kind) {
        return switch (kind) {
            case PREDICT -> "Predict";
            case TRACE -> "Trace";
            case RISK -> "Risk";
        };
    }

    /** {@link #escape} with single line breaks kept as {@code <br>}. */
    private static String escapeLines(String text) {
        return escape(text).replace("\n", "<br>");
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}

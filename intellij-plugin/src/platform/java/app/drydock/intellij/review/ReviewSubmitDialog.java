package app.drydock.intellij.review;

import app.drydock.github.GitHubReviewRequest;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.SubmitPlan;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The pull request lane's submit dialog (spec §8): what the review will post
 * -- the inline comments, each body editable; the body notes for lines GitHub
 * has no position for; the refusals and why -- with the event (preselected
 * from the reviewer's verdicts) and the summary. Submit is off while gh is
 * unusable, and while the event needs a summary and there is none. Submit
 * shows "Posting…" and keeps the dialog open until the post answers: a
 * posted review closes it, a rejection stays in it as the error. EDT.
 */
final class ReviewSubmitDialog extends DialogWrapper {

    static final String SUBMIT = "Submit Review";
    static final String POSTING = "Posting…";
    static final String SUMMARY_NEEDED = "A Comment or Request changes review needs a summary.";

    /** Posts the review; completes with the error to show, empty when it was posted. */
    interface Post {
        CompletableFuture<Optional<String>> post(SubmitPlan plan, Map<ReviewAnnotation.Key, String> editedBodies,
                                                 Event event, String summary);
    }

    private final SubmitPlan plan;
    private final Post post;
    private final Optional<String> unavailable;
    private final Map<ReviewAnnotation.Key, JBTextArea> bodies = new LinkedHashMap<>();
    private final Map<ReviewAnnotation.Key, String> originals = new LinkedHashMap<>();
    private final Map<Event, JRadioButton> events = new EnumMap<>(Event.class);
    final JBTextArea summary = new JBTextArea(4, 60);
    final JBLabel error = new JBLabel();
    private final String note;
    private final long untriaged;
    private boolean posting;

    /**
     * @param unavailable why gh cannot post, when it cannot (Submit stays off)
     * @param note        the fresh-head note, empty when there is nothing to say
     * @param untriaged   the findings still to triage, which the review leaves out
     */
    ReviewSubmitDialog(Project project, int pr, SubmitPlan plan, Optional<String> unavailable, String note,
                       long untriaged, Post post) {
        super(project, true);
        this.plan = Objects.requireNonNull(plan, "plan");
        this.post = Objects.requireNonNull(post, "post");
        this.unavailable = Objects.requireNonNull(unavailable, "unavailable");
        this.note = Objects.requireNonNull(note, "note");
        this.untriaged = untriaged;
        setTitle("Submit Review on PR #" + pr);
        setOKButtonText(SUBMIT);
        init();
        updateOk();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        unavailable.ifPresent(reason -> content.add(muted("gh cannot post: " + reason)));
        if (!note.isEmpty()) {
            content.add(muted(note));
        }
        if (untriaged > 0) {
            content.add(muted(untriaged + (untriaged == 1 ? " finding is" : " findings are")
                    + " still to triage and not part of this review."));
        }
        content.add(heading(plan.comments().size() + (plan.comments().size() == 1 ? " comment" : " comments")));
        for (int i = 0; i < plan.comments().size(); i++) {
            GitHubReviewRequest.Comment comment = plan.comments().get(i);
            ReviewAnnotation.Key key = plan.posting().get(i);
            content.add(muted(comment.path() + ":" + comment.anchor().line()));
            JBTextArea body = text(comment.body(), true);
            bodies.put(key, body);
            originals.put(key, comment.body());
            content.add(body);
        }
        if (!plan.bodyNotes().isEmpty()) {
            content.add(heading(plan.bodyNotes().size() + " in the review body (no diff line on GitHub)"));
            for (SubmitPlan.BodyNote noteLine : plan.bodyNotes()) {
                content.add(muted(noteLine.location()));
                content.add(text(noteLine.body(), false));
            }
        }
        if (!plan.refusals().isEmpty()) {
            content.add(heading(plan.refusals().size() + " not posting"));
            for (SubmitPlan.Refusal refusal : plan.refusals()) {
                content.add(muted(refusal.reason()));
            }
        }
        JPanel eventRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        ButtonGroup group = new ButtonGroup();
        for (Event event : Event.values()) {
            JRadioButton button = new JRadioButton(label(event));
            button.addActionListener(e -> updateOk());
            group.add(button);
            events.put(event, button);
            eventRow.add(button);
        }
        events.get(plan.preselected()).setSelected(true);
        content.add(heading("Event"));
        content.add(eventRow);
        content.add(heading("Summary"));
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                updateOk();
            }
        });
        content.add(new JBScrollPane(summary));
        error.setForeground(UIUtil.getErrorForeground());
        content.add(error);
        JBScrollPane scroll = new JBScrollPane(content);
        scroll.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(520)));
        JPanel root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    /** The event's label (pure). */
    static String label(Event event) {
        return switch (event) {
            case COMMENT -> "Comment";
            case APPROVE -> "Approve";
            case REQUEST_CHANGES -> "Request changes";
        };
    }

    Event selectedEvent() {
        return events.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey)
                .findFirst().orElse(plan.preselected());
    }

    /** The comments whose body the reviewer changed, with the new text. */
    Map<ReviewAnnotation.Key, String> editedBodies() {
        Map<ReviewAnnotation.Key, String> edited = new LinkedHashMap<>();
        bodies.forEach((key, area) -> {
            String text = area.getText().strip();
            if (!text.isEmpty() && !text.equals(originals.get(key).strip())) {
                edited.put(key, text);
            }
        });
        return edited;
    }

    /** Whether Submit can post now (EDT). */
    boolean canSubmit() {
        return !posting && unavailable.isEmpty()
                && !(SubmitPlan.needsSummary(selectedEvent()) && summary.getText().isBlank());
    }

    private void updateOk() {
        setOKActionEnabled(canSubmit());
        // Only its own message: a post's error stays until the next post or edit replaces it.
        boolean summaryNeeded = SubmitPlan.needsSummary(selectedEvent()) && summary.getText().isBlank();
        if (summaryNeeded && !posting && unavailable.isEmpty()) {
            error.setText(SUMMARY_NEEDED);
        } else if (SUMMARY_NEEDED.equals(error.getText())) {
            error.setText("");
        }
    }

    @Override
    protected void doOKAction() {
        if (!canSubmit()) {
            return;
        }
        posting = true;
        setOKButtonText(POSTING);
        updateOk();
        error.setText("");
        post.post(plan, editedBodies(), selectedEvent(), summary.getText().strip()).whenComplete((failure, thrown) ->
                EventQueue.invokeLater(() -> {
                    posting = false;
                    setOKButtonText(SUBMIT);
                    if (thrown == null && failure != null && failure.isEmpty()) {
                        close(OK_EXIT_CODE);
                        return;
                    }
                    error.setText(thrown != null ? "Could not post the review: " + ReviewTabProvider.rootMessage(thrown)
                            : failure == null ? "" : failure.orElse(""));
                    updateOk();
                }));
    }

    private static JBLabel heading(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(JBUI.Fonts.label().asBold());
        label.setBorder(JBUI.Borders.emptyTop(8));
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return label;
    }

    private static JBLabel muted(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return label;
    }

    private static JBTextArea text(String value, boolean editable) {
        JBTextArea area = new JBTextArea(value);
        area.setEditable(editable);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(JBUI.Fonts.label());
        area.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        area.setBorder(JBUI.Borders.empty(2, 4));
        return area;
    }

    /** The comment bodies the dialog shows, in order: for the tests. */
    List<String> commentBodies() {
        return bodies.values().stream().map(JBTextArea::getText).toList();
    }
}

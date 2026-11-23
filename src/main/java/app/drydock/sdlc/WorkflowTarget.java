package app.drydock.sdlc;

/** The five actions that can carry a project workflow; excluded actions never manufacture a target. */
public enum WorkflowTarget {
    FREE_FORM_PRE_PROMPT,
    ISSUE_IMPLEMENT,
    ISSUE_TRIAGE,
    PULL_REQUEST_REVIEW,
    LOCAL_CHANGES_REVIEW
}

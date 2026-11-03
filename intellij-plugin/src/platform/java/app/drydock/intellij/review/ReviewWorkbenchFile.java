package app.drydock.intellij.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.testFramework.LightVirtualFile;

import java.util.Objects;

/**
 * The review workbench's editor tab (spec §3): one reusable virtual file per
 * project, opened by {@link ReviewWorkbenchEditorProvider}. Its tab title is
 * "Drydock Review — &lt;subject&gt;" once the review's scope is known.
 */
public final class ReviewWorkbenchFile extends LightVirtualFile {

    /** The tab title before a scope is known; the prefix after. */
    static final String TITLE = "Drydock Review";

    private static final Key<ReviewWorkbenchFile> KEY = Key.create("drydock.review.workbenchFile");

    /** The review's subject ({@code ReviewTabProvider.subjectOf}); null until a scope resolves. */
    private volatile String subject;

    private ReviewWorkbenchFile() {
        super(TITLE);
    }

    /** The project's one workbench file, created on first use. */
    public static ReviewWorkbenchFile of(Project project) {
        Objects.requireNonNull(project, "project");
        ReviewWorkbenchFile existing = project.getUserData(KEY);
        if (existing != null) {
            return existing;
        }
        return ((UserDataHolderEx) project).putUserDataIfAbsent(KEY, new ReviewWorkbenchFile());
    }

    /** Sets the subject the tab title names; the caller refreshes the tab's presentation. */
    void setSubject(String subject) {
        this.subject = subject;
    }

    @Override
    public String getName() {
        return title(subject);
    }

    /** The tab title (pure): the bare title, or "title — subject". */
    static String title(String subject) {
        return subject == null || subject.isBlank() ? TITLE : TITLE + " — " + subject;
    }
}

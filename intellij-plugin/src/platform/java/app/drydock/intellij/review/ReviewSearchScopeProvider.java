package app.drydock.intellij.review;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.SearchScopeProvider;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "Drydock: files in this review" (spec §2): a search scope over the live
 * review's changed files, offered wherever the IDE's scope chooser is (Find
 * Usages, Find in Files), so a search can be narrowed to the change under
 * review. Deleted files are not in it: there is no file left to search. No
 * live review, no scope.
 */
public final class ReviewSearchScopeProvider implements SearchScopeProvider {

    static final String SCOPE_NAME = "Drydock: files in this review";

    @Override
    public String getDisplayName() {
        return "Drydock";
    }

    @Override
    public @NotNull List<SearchScope> getSearchScopes(@NotNull Project project, @NotNull DataContext dataContext) {
        return ReviewTabProvider.reviewFiles(project)
                .map(files -> List.<SearchScope>of(scopeOf(project, files)))
                .orElse(List.of());
    }

    /** The scope over one review's files that the VFS knows. */
    static GlobalSearchScope scopeOf(Project project, ReviewTabProvider.ReviewFiles files) {
        List<VirtualFile> found = new ArrayList<>();
        for (Path path : paths(files.root(), files.snapshot())) {
            // An in-memory VFS lookup, no refresh: the scope is offered while
            // a chooser opens, never blocked on the disk.
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
            if (file != null) {
                found.add(file);
            }
        }
        return GlobalSearchScope.filesScope(project, found, SCOPE_NAME);
    }

    /** The review's file paths (pure): every listed file under {@code root} that still exists in the diff's right side. */
    static List<Path> paths(Path root, ReviewTabModel.Snapshot snapshot) {
        Objects.requireNonNull(root, "root");
        return snapshot.files().stream()
                .filter(row -> !"D".equals(row.diff().kind()))
                .map(row -> root.resolve(row.path()))
                .toList();
    }
}

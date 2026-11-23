package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.InboxModel.GroupKind;
import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The inbox's view state for one IDE project, in its workspace file: the
 * groups the user collapsed or expanded, the groups showing all their
 * rows, the project filter, and whether the "Add open project" offer was
 * dismissed. Cosmetic state, so decoding is lenient: an unknown group name
 * or an unreadable filter path is dropped, never a failure. EDT.
 */
@State(name = "DrydockInbox", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class InboxViewState implements PersistentStateComponent<InboxViewState.Stored> {

    /** The serialized shape: plain public fields, as the platform's XML binding reads them. */
    public static final class Stored {
        public List<String> collapsed = new ArrayList<>();
        public List<String> expanded = new ArrayList<>();
        public List<String> showingAll = new ArrayList<>();
        public String filterRoot;
        public String filterName;
        public boolean bannerDismissed;
    }

    /** The list's group toggles: collapsed (of the open-by-default), expanded (of the closed-by-default), all rows. */
    record Toggles(Set<GroupKind> collapsed, Set<GroupKind> expanded, Set<GroupKind> showingAll) {
        Toggles {
            collapsed = copy(collapsed);
            expanded = copy(expanded);
            showingAll = copy(showingAll);
        }

        static final Toggles NONE = new Toggles(Set.of(), Set.of(), Set.of());

        private static Set<GroupKind> copy(Set<GroupKind> kinds) {
            return kinds.isEmpty() ? EnumSet.noneOf(GroupKind.class) : EnumSet.copyOf(kinds);
        }
    }

    private Stored stored = new Stored();

    public static InboxViewState getInstance(Project project) {
        return project.getService(InboxViewState.class);
    }

    @Override
    public @NotNull Stored getState() {
        return stored;
    }

    @Override
    public void loadState(@NotNull Stored state) {
        stored = state;
    }

    Toggles toggles() {
        return new Toggles(kinds(stored.collapsed), kinds(stored.expanded), kinds(stored.showingAll));
    }

    void setToggles(Toggles toggles) {
        stored.collapsed = names(toggles.collapsed());
        stored.expanded = names(toggles.expanded());
        stored.showingAll = names(toggles.showingAll());
    }

    /** The stored project filter, if one is stored and its path reads. */
    Optional<ProjectRef> filter() {
        if (stored.filterRoot == null || stored.filterName == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProjectRef(Path.of(stored.filterRoot), stored.filterName));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    void setFilter(Optional<ProjectRef> filter) {
        stored.filterRoot = filter.map(ref -> ref.root().toString()).orElse(null);
        stored.filterName = filter.map(ProjectRef::name).orElse(null);
    }

    boolean bannerDismissed() {
        return stored.bannerDismissed;
    }

    void setBannerDismissed(boolean dismissed) {
        stored.bannerDismissed = dismissed;
    }

    private static Set<GroupKind> kinds(List<String> names) {
        Set<GroupKind> kinds = EnumSet.noneOf(GroupKind.class);
        if (names == null) {
            return kinds;
        }
        for (String name : names) {
            for (GroupKind kind : GroupKind.values()) {
                if (kind.name().equals(name)) {
                    kinds.add(kind);
                }
            }
        }
        return kinds;
    }

    private static List<String> names(Set<GroupKind> kinds) {
        return new ArrayList<>(kinds.stream().map(Enum::name).toList());
    }
}

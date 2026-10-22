package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;

import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code Start ▸} on a worktree row (cross-project tabs, frame F1): start a
 * task there in this window — hosted, tagged with its repo — or in its own
 * window, or read-only here. Each item opens New task fixed to the worktree,
 * preset to that choice; a task still needs its name.
 */
final class StartMenu {

    /** One menu item: its text, the line under it, and what it presets. */
    record Item(String text, Optional<String> secondary, OpenIn openIn, SessionMode mode) {
        Item {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(secondary, "secondary");
            Objects.requireNonNull(openIn, "openIn");
            Objects.requireNonNull(mode, "mode");
        }
    }

    private StartMenu() {
    }

    /**
     * Pure: the items for a worktree of {@code repo} on {@code branch}. On
     * this window's own worktree, "its own window" is this window, so the
     * first item is a plain {@code Start} and the second is not offered.
     */
    static List<Item> items(String repo, String branch, boolean ownWorktree) {
        Item readOnly = new Item("Read-only, in this window", Optional.empty(), OpenIn.THIS_WINDOW,
                SessionMode.READ_ONLY);
        if (ownWorktree) {
            return List.of(new Item("Start with full access", Optional.empty(), OpenIn.THIS_WINDOW,
                    SessionMode.WRITES), readOnly);
        }
        return List.of(
                new Item("Full access, in this window", Optional.of("Tab opens here, tagged " + repo),
                        OpenIn.THIS_WINDOW, SessionMode.WRITES),
                new Item("Full access, in its own window", Optional.of("Opens " + repo + " · " + branch),
                        OpenIn.OWN_WINDOW, SessionMode.WRITES),
                readOnly);
    }

    /** Shows the menu at {@code where}; {@code chosen} gets the picked item. EDT. */
    static void show(RelativePoint where, List<Item> items, Consumer<Item> chosen) {
        JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<>(null, items) {
            @Override
            public @NotNull String getTextFor(Item item) {
                return item.secondary().map(line -> item.text() + "  —  " + line).orElse(item.text());
            }

            @Override
            public PopupStep<?> onChosen(Item item, boolean finalChoice) {
                return doFinalStep(() -> chosen.accept(item));
            }
        }).show(where);
    }
}

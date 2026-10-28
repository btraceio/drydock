package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The agent console tab's context-popup contribution (T15, A10): Drydock's
 * commands at the head of the stock {@code EditorTabPopupMenu}, built from
 * the same registered actions the tab header rides, so a right-click on a
 * console tab offers exactly what <em>that</em> tab's session supports — and
 * a right-click on any other tab contributes nothing at all (rejection 1:
 * no stock entry is touched; the group simply has no children).
 *
 * <p>The popup carries the target the platform sinks into it: the
 * right-clicked tab's own {@code VIRTUAL_FILE}, never the selected
 * editor's (A7) — {@code AgentTabCommands.resolve} answers that question
 * once for every command surface. {@link #getChildren} is one memory-only
 * EDT pass, O(1) per command: the catalog-index session lookup, the
 * registry's in-flight probes, the per-editor facts of the one file that
 * was right-clicked, and the activity monitor's single map read for
 * transfer eligibility. It performs no I/O and launches nothing.</p>
 */
public final class AgentTabPopupGroup extends ActionGroup implements DumbAware {

    private static final String OWN_PROJECT = "Drydock.AgentTab.OwnProject";
    private static final String CHANGES = "Drydock.AgentTab.Changes";
    private static final String LIFECYCLE = "Drydock.AgentTab.Lifecycle";
    private static final String TRANSFER = "Drydock.AgentTab.Transfer";
    private static final String RENAME = "Drydock.AgentTab.Rename";
    private static final String DONE = "Drydock.AgentTab.Done";

    /**
     * The sections, in popup order. A separator stands only between two
     * sections that both carry a visible member; a section with no visible
     * member contributes nothing — separator included — so the popup never
     * shows an empty section or a doubled separator.
     */
    private static final String[][] SECTIONS = {
            {OWN_PROJECT, CHANGES},
            {LIFECYCLE, TRANSFER},
            {RENAME},
            {DONE},
    };

    @Override
    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
        // The platform enumerates children of every registered group with
        // no event in some paths; with no event there is no target, and an
        // agent tab's popup commands apply to nothing.
        if (e == null) {
            return AnAction.EMPTY_ARRAY;
        }
        Optional<AgentTabCommands.Target> target = AgentTabCommands.resolve(e);
        if (target.isEmpty()) {
            return AnAction.EMPTY_ARRAY;
        }
        AgentTabCommands.Target tab = target.get();
        boolean hosted = HostedTab.placeIn(tab.file(), tab.project()).isPresent();
        // The same window-scoped state the header's update computes (A6):
        // the right-clicked tab's own window decides shown-here, so a
        // session that runs elsewhere offers Show agent here too — one
        // shared derivation, no second global snapshot.
        SessionCommands.State state = AgentTabCommands.stateOf(tab);

        ActionManager actions = ActionManager.getInstance();
        List<AnAction> children = new ArrayList<>();
        boolean emitted = false;
        for (String[] section : SECTIONS) {
            List<AnAction> members = null;
            for (String id : section) {
                AnAction action = actions.getAction(id);
                // An unregistered id contributes nothing; registration is
                // the XML's contract and T14's registration test proves it.
                if (action != null && shown(id, tab, hosted, state).visible()) {
                    if (members == null) {
                        members = new ArrayList<>(section.length);
                    }
                    members.add(action);
                }
            }
            if (members == null) {
                continue;
            }
            if (emitted) {
                children.add(Separator.getInstance());
            }
            emitted = true;
            children.addAll(members);
        }
        if (children.isEmpty()) {
            return AnAction.EMPTY_ARRAY;
        }
        // One trailing separator, so Drydock's block stands apart from the
        // stock popup entries that follow it (anchor="first" put us ahead).
        children.add(Separator.getInstance());
        return children.toArray(AnAction.EMPTY_ARRAY);
    }

    /**
     * The pure popup presentation of the command with this action id, for
     * THIS target — the same {@code AgentTabCommands} decision the action's
     * own {@code update()} applies, so the group's membership and the
     * action's visibility can never disagree.
     */
    private static AgentTabCommands.Shown shown(String id, AgentTabCommands.Target tab,
                                                boolean hosted, SessionCommands.State state) {
        String name = tab.session().displayName();
        return switch (id) {
            case OWN_PROJECT -> AgentTabCommands.ownProject(hosted, state, name, true);
            case CHANGES -> AgentTabCommands.changes(
                    AgentTabCommands.knownCheckoutGone(tab.project(), tab.file()),
                    hosted, state, name, true);
            case LIFECYCLE -> AgentTabCommands.lifecycle(state, name, true);
            case TRANSFER -> AgentTabCommands.transfer(transferAvailable(tab), state, name, true);
            case RENAME -> AgentTabCommands.rename(state, name, true);
            case DONE -> AgentTabCommands.done(state, name, true);
            default -> throw new IllegalStateException("unknown Drydock tab command id " + id);
        };
    }

    /**
     * Transfer eligibility, the strip's own rule: a single activity-monitor
     * map read plus {@link SessionTransfer#available}'s in-memory checks.
     * {@code resolve} already proved the plugin initialized (the session
     * lookup went through its state store), so the monitor is present.
     */
    private static boolean transferAvailable(AgentTabCommands.Target tab) {
        Optional<SessionActivity> activity = DrydockPluginService.getInstance()
                .activityMonitor().current(tab.session().id());
        return SessionTransfer.available(tab.session(), activity);
    }
}

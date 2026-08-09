package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.ui.model.SessionFilter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;

/**
 * The sidebar's status/harness filter chips, under the text filter.
 *
 * <p>A {@link FlowPane} so the row wraps as the sidebar narrows instead of
 * forcing a minimum width on the app's narrowest column, and deliberately
 * <em>no</em> {@code ToggleGroup}: a toggle group would give radio behavior
 * and silently break "facets OR within an axis".
 */
final class SessionFilterBar extends FlowPane {

    private final Map<SessionStatusFacet, ToggleButton> statusChips =
            new LinkedHashMap<>();
    private final Map<AgentKind, ToggleButton> agentChips = new LinkedHashMap<>();

    /** Suppresses per-chip notifications while {@link #clear()} resets them all. */
    private boolean suppressChange;

    private final Runnable onChanged;

    SessionFilterBar(AgentRegistry registry, Runnable onChanged) {
        super(4, 4);
        this.onChanged = onChanged;
        getStyleClass().add("session-filter-bar");

        for (SessionStatusFacet facet : SessionStatusFacet.values()) {
            statusChips.put(facet, addChip(facet.name().toLowerCase(java.util.Locale.ROOT), null));
        }
        // One chip per kind, not per registered provider: every build
        // registers all three, and gating on availability would hide the chip
        // for an agent whose CLI was uninstalled while its sessions remain.
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            String name = AgentLabels.displayName(registry, kind);
            agentChips.put(kind, addChip(AgentMarks.glyph(kind) + "  " + name,
                    AgentMarks.styleClass(kind)));
        }
    }

    private ToggleButton addChip(String text, String markClass) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().addAll("review-filter-button", "session-filter-chip");
        if (markClass != null) {
            chip.getStyleClass().add(markClass);
        }
        chip.setTooltip(new Tooltip(text));
        chip.selectedProperty().addListener((obs, was, is) -> {
            if (!suppressChange) {
                onChanged.run();
            }
        });
        getChildren().add(chip);
        return chip;
    }

    /** The live filter. Empty sets mean "no constraint"; see {@link SessionFilter}. */
    SessionFilter filter() {
        Set<SessionStatusFacet> statuses = EnumSet.noneOf(SessionStatusFacet.class);
        statusChips.forEach((facet, chip) -> {
            if (chip.isSelected()) {
                statuses.add(facet);
            }
        });
        Set<AgentKind> agents = EnumSet.noneOf(AgentKind.class);
        agentChips.forEach((kind, chip) -> {
            if (chip.isSelected()) {
                agents.add(kind);
            }
        });
        return new SessionFilter(statuses, agents);
    }

    /** Resets every chip, firing {@code onChanged} exactly once (not once per chip). */
    void clear() {
        suppressChange = true;
        try {
            statusChips.values().forEach(chip -> chip.setSelected(false));
            agentChips.values().forEach(chip -> chip.setSelected(false));
        } finally {
            suppressChange = false;
        }
        onChanged.run();
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript}): toggles one chip
     * by name, so the visual pass can drive filter combinations from a script
     * instead of by hand.
     */
    void diagToggleFacet(String name) {
        statusChips.forEach((facet, chip) -> {
            if (facet.name().equalsIgnoreCase(name)) {
                chip.setSelected(!chip.isSelected());
            }
        });
        agentChips.forEach((kind, chip) -> {
            if (kind.persistedName().equalsIgnoreCase(name)) {
                chip.setSelected(!chip.isSelected());
            }
        });
    }
}

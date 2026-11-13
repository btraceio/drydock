package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.InboxModel.ProjectRef;
import app.drydock.intellij.tasks.InboxModel.Suggestion;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.fields.ExtendableTextComponent.Extension;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The inbox's search field: plain text narrows titles, and a leading
 * {@code project:} opens a popup of registered projects (substring match,
 * with each one's active-item count). ↑/↓ move in the popup and Enter
 * picks; the pick becomes a removable {@code project: <name> ×} chip
 * inside the field. Esc clears the text, and Backspace in an empty field
 * removes the chip. The chip itself is the owner's state: the field only
 * reports picks and removals, and shows what {@link #showProject} says. EDT.
 */
final class InboxSearchField extends ExtendableTextField {

    /** What the owner does with the field's gestures. */
    interface Listener {
        /** The text the inbox filters by changed: the typed text, or empty while a {@code project:} is being typed. */
        void textChanged(String effectiveText);

        /** A project was picked from the suggestions. */
        void projectPicked(ProjectRef project);

        /** The chip was removed (its ×, or Backspace in an empty field). */
        void projectCleared();
    }

    static final JBColor CHIP_BACKGROUND = new JBColor(0xEDF3FF, 0x25324D);

    private final Listener listener;
    private final Function<String, List<Suggestion>> suggestions;
    private final DefaultListModel<Suggestion> shown = new DefaultListModel<>();
    private final JBList<Suggestion> list = new JBList<>(shown);
    private final Extension searchIcon = new Extension() {
        @Override
        public Icon getIcon(boolean hovered) {
            return AllIcons.Actions.Search;
        }

        @Override
        public boolean isIconBeforeText() {
            return true;
        }
    };
    private final Extension clear = Extension.create(AllIcons.Actions.Close, AllIcons.Actions.CloseHovered,
            "Clear", () -> setText(""));
    private Optional<ProjectRef> project = Optional.empty();
    private JBPopup popup;

    /**
     * @param suggestions the registered projects whose name contains the given text, for the popup
     */
    InboxSearchField(Listener listener, Function<String, List<Suggestion>> suggestions) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends Suggestion> source, Suggestion value,
                                                 int index, boolean selected, boolean focused) {
                append(value.project().name());
                append("  " + (value.active() > 0 ? value.active() + " active" : "quiet"),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0) {
                    pick(shown.get(index));
                }
            }
        });
        getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                refreshExtensions();
                refreshSuggestions();
                listener.textChanged(effectiveText(getText()));
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                // The suggestions, not the popup's visibility: the keys work the same whether or not
                // the popup could be shown (it is anchored to a showing field).
                boolean suggesting = !shown.isEmpty();
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_DOWN, KeyEvent.VK_UP -> {
                        if (suggesting) {
                            int step = event.getKeyCode() == KeyEvent.VK_DOWN ? 1 : -1;
                            int next = Math.max(0, Math.min(shown.size() - 1, list.getSelectedIndex() + step));
                            list.setSelectedIndex(next);
                            list.ensureIndexIsVisible(next);
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_ENTER -> {
                        if (suggesting) {
                            pick(list.getSelectedValue() != null ? list.getSelectedValue() : shown.get(0));
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        if (!getText().isEmpty()) {
                            setText("");
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_BACK_SPACE -> {
                        if (getText().isEmpty() && project.isPresent()) {
                            listener.projectCleared();
                            event.consume();
                        }
                    }
                    default -> {
                    }
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                hidePopup();
            }
        });
        refreshExtensions();
    }

    /**
     * The text the inbox filters by: empty while the user is typing a
     * {@code project:} (the popup answers that), else the text. Pure.
     */
    static String effectiveText(String text) {
        return InboxModel.projectPrefix(text).isPresent() ? "" : text == null ? "" : text;
    }

    /** Shows {@code next} as the chip, or no chip, and the matching placeholder. */
    void showProject(Optional<ProjectRef> next) {
        project = Objects.requireNonNull(next, "next");
        refreshExtensions();
    }

    /** The project the chip shows, if any. */
    Optional<ProjectRef> project() {
        return project;
    }

    /** The suggestions the popup lists now, in order. */
    List<Suggestion> suggestionsShown() {
        List<Suggestion> out = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            out.add(shown.get(i));
        }
        return out;
    }

    /** The highlighted suggestion, if the popup is open. */
    Optional<Suggestion> highlighted() {
        return Optional.ofNullable(list.getSelectedValue());
    }

    private void pick(Suggestion suggestion) {
        hidePopup();
        setText("");
        listener.projectPicked(suggestion.project());
    }

    private void refreshExtensions() {
        List<Extension> extensions = new ArrayList<>();
        extensions.add(searchIcon);
        project.ifPresent(ref -> extensions.add(chip(ref)));
        if (!getText().isEmpty()) {
            extensions.add(clear);
        }
        setExtensions(extensions);
        getEmptyText().setText(project.isPresent() ? "Search in project" : "Search, or type project:");
        revalidate();
        repaint();
    }

    private Extension chip(ProjectRef ref) {
        Icon icon = new ChipIcon("project: " + ref.name() + "  ×", this);
        return new Extension() {
            @Override
            public Icon getIcon(boolean hovered) {
                return icon;
            }

            @Override
            public boolean isIconBeforeText() {
                return true;
            }

            @Override
            public Runnable getActionOnClick() {
                return listener::projectCleared;
            }

            @Override
            public String getTooltip() {
                return "Show every project";
            }
        };
    }

    private void refreshSuggestions() {
        Optional<String> prefix = InboxModel.projectPrefix(getText());
        shown.clear();
        prefix.map(suggestions).orElse(List.of()).forEach(shown::addElement);
        if (shown.isEmpty()) {
            hidePopup();
            return;
        }
        list.setSelectedIndex(0);
        if (popup == null || !popup.isVisible()) {
            popup = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(ScrollPaneFactory.createScrollPane(list, true), null)
                    .setRequestFocus(false)
                    .setFocusable(false)
                    .setResizable(false)
                    .setMovable(false)
                    .setCancelOnClickOutside(true)
                    .createPopup();
            if (isShowing()) {
                popup.setMinimumSize(new Dimension(getWidth(), 0));
                popup.showUnderneathOf(this);
            }
        } else {
            popup.pack(true, true);
        }
    }

    private void hidePopup() {
        if (popup != null) {
            popup.cancel();
            popup = null;
        }
    }

    /** The chip: its text on the accent background, rounded, in the field's font. */
    private static final class ChipIcon implements Icon {
        private final String text;
        private final Component owner;

        ChipIcon(String text, Component owner) {
            this.text = text;
            this.owner = owner;
        }

        private FontMetrics metrics() {
            return owner.getFontMetrics(owner.getFont());
        }

        @Override
        public void paintIcon(Component component, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CHIP_BACKGROUND);
                int arc = JBUI.scale(6);
                g2.fillRoundRect(x, y, getIconWidth(), getIconHeight(), arc, arc);
                g2.setColor(UIUtil.getLabelForeground());
                g2.setFont(owner.getFont());
                FontMetrics metrics = metrics();
                int baseline = y + (getIconHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(text, x + JBUI.scale(6), baseline);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return metrics().stringWidth(text) + JBUI.scale(12);
        }

        @Override
        public int getIconHeight() {
            return metrics().getHeight() + JBUI.scale(2);
        }
    }
}

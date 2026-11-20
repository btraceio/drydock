package app.drydock.intellij.review;

import com.intellij.ui.components.JBScrollPane;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * The inlay-side container of one {@link FindingInlayCard}: it forces the
 * card to the editor's text width — the width the card's text blocks wrap
 * at — and caps the reserved height, so a long thread scrolls inside the
 * card instead of stretching the inlay past the editor viewport.
 *
 * <p>This mirrors what the platform's own review inlays do (the
 * collaboration tools' {@code EditorComponentInlaysManager} clamps every
 * inlay to the live editor text width and forwards editor resizes to the
 * wrapped component): our insertion path bypasses that manager, so the
 * clamp lives here. The width and the cap arrive as suppliers — both change
 * as the editor is resized — and {@link #editorResized()} is the owner's
 * hook: it re-applies the width to the card and revalidates, after which the
 * owner refreshes the inlay's own size.
 *
 * <p>EDT-only, like every component the diff owns.
 */
final class FindingInlayContainer extends JPanel {

    /** The horizontal room the card keeps to itself beyond the text width (scrollbar, breathing room). */
    private static final int HORIZONTAL_MARGIN = 8;

    private final JComponent card;
    private final IntSupplier textWidth;
    private final IntSupplier maxHeight;

    FindingInlayContainer(JComponent card, IntSupplier textWidth, IntSupplier maxHeight) {
        this.card = Objects.requireNonNull(card, "card");
        this.textWidth = Objects.requireNonNull(textWidth, "textWidth");
        this.maxHeight = Objects.requireNonNull(maxHeight, "maxHeight");
        setLayout(new BorderLayout());
        setOpaque(false);
        JScrollPane scroll = new JBScrollPane(card,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);
        applyWidth();
    }

    /** The owner's editor-resize hook: re-applies the width and revalidates. EDT. */
    void editorResized() {
        applyWidth();
        revalidate();
    }

    /**
     * The inlay's size: the editor's current text width — what the card's
     * text blocks wrap at, re-applied here so a resize re-wraps before the
     * height is read — and the card's wrapped height, capped at the
     * supplier's maximum (a fraction of the editor viewport) so a long
     * thread scrolls instead of stretching the inlay.
     */
    @Override
    public Dimension getPreferredSize() {
        applyWidth();
        int width = textWidth.getAsInt();
        if (width <= 0) {
            return card.getPreferredSize();
        }
        int height = card.getPreferredSize().height;
        int cap = maxHeight.getAsInt();
        return new Dimension(width, cap > 0 ? Math.min(height, cap) : height);
    }

    private void applyWidth() {
        int width = textWidth.getAsInt() - HORIZONTAL_MARGIN;
        if (width > 0 && card instanceof FindingInlayCard findingCard) {
            findingCard.setWrapWidth(width);
        }
    }
}

package app.drydock.intellij.agent;

import com.intellij.openapi.util.IconLoader;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A borderless, IDE-toolbar-style command button for the agent strip: no
 * border or fill at rest, the platform's action-button hover and pressed
 * backgrounds, a 4px arc, an icon and an optional label.
 *
 * <p>Still a real focusable {@link JButton}, so Enter and Space activate it
 * and callers can keep reading {@link #getText()}. In {@linkplain #setCompact
 * compact} mode it paints the icon only while {@code getText()} keeps the
 * label, and the label becomes the tooltip when none was set — the narrow
 * strip collapses labels this way instead of hiding a command.</p>
 */
final class StripToolbarButton extends JButton {

    private static final int HEIGHT = 26;
    private static final int ARC = 4;
    private static final int PAD = 8;
    private static final int GAP = 6;

    private boolean compact;
    private boolean hovered;

    StripToolbarButton(String text, Icon icon) {
        super(text, icon);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = false;
                repaint();
            }
        });
    }

    boolean isCompact() {
        return compact;
    }

    /** Icon only when {@code compact}; the text stays on the button for callers and the tooltip. */
    void setCompact(boolean compact) {
        if (this.compact != compact) {
            this.compact = compact;
            revalidate();
            repaint();
        }
    }

    /** The width this button needs with its label, whatever the current mode. */
    int expandedWidth() {
        return widthFor(true);
    }

    /** The width this button needs with the icon alone. */
    int compactWidth() {
        return widthFor(false);
    }

    private int widthFor(boolean withLabel) {
        Icon icon = getIcon();
        String text = getText();
        int width = JBUI.scale(PAD) * 2 + (icon == null ? 0 : icon.getIconWidth());
        if (withLabel && text != null && !text.isEmpty()) {
            FontMetrics metrics = getFontMetrics(getFont());
            width += (icon == null ? 0 : JBUI.scale(GAP)) + metrics.stringWidth(text);
        }
        return width;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(widthFor(!compact), JBUI.scale(HEIGHT));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public String getToolTipText() {
        String own = super.getToolTipText();
        if (own == null && compact && getText() != null && !getText().isEmpty()) {
            return getText();
        }
        return own;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = JBUI.scale(ARC);
            if (isEnabled() && (getModel().isPressed() || getModel().isArmed())) {
                g.setColor(JBUI.CurrentTheme.ActionButton.pressedBackground());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } else if (isEnabled() && hovered) {
                g.setColor(JBUI.CurrentTheme.ActionButton.hoverBackground());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            }
            if (isFocusOwner()) {
                g.setColor(JBUI.CurrentTheme.Focus.focusColor());
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            Icon icon = getIcon();
            int x = JBUI.scale(PAD);
            if (icon != null) {
                Icon shown = isEnabled() ? icon : IconLoader.getDisabledIcon(icon);
                shown.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2);
                x += icon.getIconWidth() + JBUI.scale(GAP);
            }
            String text = getText();
            if (!compact && text != null && !text.isEmpty()) {
                g.setFont(getFont());
                g.setColor(isEnabled() ? getForeground() : UIUtil.getLabelDisabledForeground());
                FontMetrics metrics = g.getFontMetrics();
                int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(text, x, baseline);
            }
        } finally {
            g.dispose();
        }
    }

    /** Kept so a theme change repaints with the new colours. */
    @Override
    public void updateUI() {
        super.updateUI();
        setBorder(null);
        setContentAreaFilled(false);
        setOpaque(false);
    }
}

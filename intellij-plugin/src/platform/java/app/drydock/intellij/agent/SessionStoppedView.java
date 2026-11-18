package app.drydock.intellij.agent;

import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.plaf.LayerUI;

import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.util.Objects;

/**
 * The Not-running body for a session that stopped in this window: the
 * retained console's last output at half strength with a footer line under
 * it. EDT only.
 *
 * <p>Before, the dead terminal stayed on screen exactly as it was — its
 * prompt line and all — with nothing saying the agent was gone, and the
 * keyboard was still in the terminal. The dimming layer swallows keys and
 * clicks, so nothing typed into a dead agent looks as if it went somewhere.
 * What is shown is the console the execution already holds in memory; no
 * transcript is read back from disk and replayed.</p>
 *
 * <p>The console belongs to the execution, not to this view: the view only
 * borrows its component and never disposes it. Discarding the view, or
 * {@link #release() releasing} the console, leaves it intact for the next
 * view or live host.</p>
 */
final class SessionStoppedView {

    private final TerminalExecutionConsole console;
    private final JLayer<JComponent> layer;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JBLabel footer = new JBLabel("", SwingConstants.CENTER);

    SessionStoppedView(TerminalExecutionConsole console, String footerText) {
        this.console = Objects.requireNonNull(console, "console");
        this.layer = new JLayer<>(console.getComponent(), new DimmedLayer());
        footer.setForeground(UIUtil.getContextHelpForeground());
        footer.setBorder(JBUI.Borders.empty(8));
        panel.add(layer, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        setFooter(footerText);
    }

    /** The component to place in a host; it stays this view's for the view's life. */
    JComponent component() {
        return panel;
    }

    /** The retained console this view wraps; never disposed by the view. */
    TerminalExecutionConsole console() {
        return console;
    }

    /** The input-swallowing layer over the console, for tests and focus decisions. */
    JLayer<JComponent> layer() {
        return layer;
    }

    void setFooter(String text) {
        footer.setText(Objects.requireNonNull(text, "text"));
    }

    String footerText() {
        return footer.getText();
    }

    /**
     * Detaches the console's component from the dimming layer so another
     * host can show it. The console itself is not disposed.
     */
    void release() {
        layer.setView(null);
    }

    /** Paints its view at half strength and lets no input through to it. */
    private static final class DimmedLayer extends LayerUI<JComponent> {
        @Override
        public void paint(Graphics graphics, JComponent component) {
            super.paint(graphics, component);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setComposite(AlphaComposite.SrcOver.derive(0.5f));
                g.setColor(component.getBackground() != null ? component.getBackground() : UIUtil.getPanelBackground());
                g.fillRect(0, 0, component.getWidth(), component.getHeight());
            } finally {
                g.dispose();
            }
        }

        @Override
        public void installUI(JComponent component) {
            super.installUI(component);
            ((JLayer<?>) component).setLayerEventMask(AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        }

        @Override
        public void uninstallUI(JComponent component) {
            ((JLayer<?>) component).setLayerEventMask(0);
            super.uninstallUI(component);
        }

        @Override
        public void eventDispatched(AWTEvent event, JLayer<? extends JComponent> layer) {
            if (event instanceof InputEvent input) {
                input.consume();
            }
        }
    }
}

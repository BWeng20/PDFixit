package com.bw.jPdfTool.toast;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.ui.UI;
import com.formdev.flatlaf.ui.FlatLineBorder;

import javax.swing.*;
import javax.swing.plaf.PanelUI;
import javax.swing.text.Element;
import javax.swing.text.ParagraphView;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;

/**
 * A panel that shows toast.<br>
 * <pre>
 *    +-----------------------+
 *    |       MULTI-LINE     X|
 *    | ICON  HTML-           |
 *    |       MESSAGE         |
 *    +-----------------------+
 * </pre>
 * For the used icon see {@link #getIconForType(ToastType)}.
 */
public class Toast extends JPanel {

    protected Animation animation;
    public final ToastType type;
    protected final JLabel label = new JLabel();

    protected JEditorPane textArea;


    /**
     * EditorKit inheritance to enable also one-char paragraphs that will enable the kit to break inside words.
     */
    private static class HTMLEditorKitWrapSupport extends HTMLEditorKit {

        @Override
        public ViewFactory getViewFactory() {

            return new HTMLFactory() {
                public View create(Element e) {
                    View v = super.create(e);
                    if (v instanceof ParagraphView pv) {
                        javax.swing.text.html.ParagraphView pvExtended = new javax.swing.text.html.ParagraphView(e) {
                            protected SizeRequirements calculateMinorAxisRequirements(int axis, SizeRequirements r) {
                                SizeRequirements rs = super.calculateMinorAxisRequirements(axis, r);
                                rs.minimum = 20;
                                return rs;
                            }
                        };
                        v = pvExtended;
                    }
                    return v;
                }
            };
        }
    }

    @Override
    public void setUI(PanelUI ui) {
        super.setUI(ui);
        setToastBorder();
    }

    /**
     * Sets borders and icons according to current LAF.
     */
    protected void setToastBorder() {
        // Check if we are called by base class ctor,
        if (type == null)
            return;

        Color bc = UIManager.getColor("Component.borderColor");
        label.setIcon(getIconForType(type));
        if (UI.isFlatLaf()) {
            setBorder(new FlatLineBorder(new Insets(5, 5, 5, 6), bc, 2, 20));
            setOpaque(false);
            setBackground(UIManager.getColor("TextField.background"));
        } else {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(bc, 2, true),
                    BorderFactory.createEmptyBorder(5, 5, 5, 3)));
            setOpaque(true);
            setBackground(UIManager.getColor("TextField.background"));
        }
    }

    public void setMessage(String message) {
        textArea.setText(message);
    }


    /**
     * Initialize a new toast message.
     *
     * @param message The text (should be html)
     * @param type    The type (determines the icon, see {@link #getIconForType(ToastType)})
     * @param onClose The callback to be triggered if the toast should close.
     * @param width   The desired width of the message box. Height is calculated to fit the message text.
     */
    public Toast(ToastType type, int durationMS, String message, Runnable onClose, int width) {
        setLayout(new GridBagLayout());
        this.type = type;
        setToastBorder();

        JButton close = new ToastButton(UI.getIcon("close"));

        if (onClose != null) {
            close.addActionListener(e -> {
                if (this.closeTimer != null) {
                    onClose.run();
                }

            });
            // Auto-close after 5 seconds
            closeTimer = new Timer(durationMS, e -> {
                if (this.closeTimer != null) {
                    onClose.run();
                }
            });
            closeTimer.start();
        }

        textArea = new JEditorPane();
        textArea.setEditorKit(new HTMLEditorKitWrapSupport());
        textArea.setText(message);

        textArea.setOpaque(false);
        textArea.setEditable(false);

        GridBagConstraints gc = new GridBagConstraints();

        gc.insets = new Insets(0, 0, 0, 0);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.anchor = GridBagConstraints.LINE_START;
        gc.weightx = 0;
        label.setBackground(Color.RED);
        gc.gridheight = 2;
        add(label, gc);
        gc.gridx = 1;
        gc.weightx = 1;
        gc.weighty = 0;
        add(textArea, gc);
        gc.gridx = 2;
        gc.gridheight = 1;
        gc.anchor = GridBagConstraints.NORTHEAST;
        gc.weightx = 0;
        gc.weighty = 0;
        add(close, gc);

        JButton openDialog = new ToastButton(UI.getIcon("openToast"));
        JButton copy = new ToastButton(UI.getIcon("copy"));
        copy.setToolTipText(I18n.t("toast.copy.tip"));
        copy.addActionListener(e -> copyToClipboard());

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(copy);
        actions.add(openDialog);
        gc.gridy = 1;
        gc.anchor = GridBagConstraints.SOUTHEAST;
        add(actions, gc);

        openDialog.addActionListener(e -> {
            int optype = switch (type) {
                case INFO -> JOptionPane.INFORMATION_MESSAGE;
                case WARNING -> JOptionPane.WARNING_MESSAGE;
                case ERROR -> JOptionPane.ERROR_MESSAGE;
                case SUCCESS -> JOptionPane.INFORMATION_MESSAGE;
            };

            JOptionPane.showMessageDialog(this,
                    new JLabel(textArea.getText()),
                    null, optype);
        });

        // Find the height for the fixed width
        setSize(new Dimension(width, 500));
        doLayout();
        setSize(new Dimension(width,
                Math.max(label.getPreferredSize().height, textArea.getPreferredSize().height) + 10));

        enableEvents(AWTEvent.MOUSE_EVENT_MASK);

    }

    /**
     * A flat icon button with a clearly visible hover and pressed state: the background is the text color with
     * some alpha (so it is visible in light and dark themes), the pressed icon is moved a pixel.
     */
    private static final class ToastButton extends JButton {
        ToastButton(Icon icon) {
            super(icon);
            setFocusable(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setRolloverEnabled(true);
            setMargin(new Insets(3, 3, 3, 3));
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            boolean pressed = getModel().isPressed() && getModel().isArmed();
            if (pressed || getModel().isRollover()) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = getForeground();
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), pressed ? 90 : 35));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                if (pressed) {
                    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 160));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.dispose();
            }
            if (pressed)
                g.translate(1, 1);
            super.paintComponent(g);
            if (pressed)
                g.translate(-1, -1);
        }
    }

    /**
     * Copies the text of the message (without the HTML markup) to the clipboard.
     */
    private void copyToClipboard() {
        try {
            String text = textArea.getDocument().getText(0, textArea.getDocument().getLength()).strip();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Exception e) {
            Log.error("Can't copy to clipboard: %s", e.getMessage());
        }
    }

    private Timer closeTimer;

    public void cleanUp() {
        removeAll();
        if (animation != null)
            animation.abort();
        if (closeTimer != null) {
            closeTimer.stop();
            closeTimer = null;
        }

    }

    static {
        // Register a global event listener to catch all sub-elements.
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                int id = event.getID();
                if ((id == MouseEvent.MOUSE_ENTERED || id == MouseEvent.MOUSE_EXITED) && event.getSource() instanceof Component component) {
                    Toast t;
                    if (component instanceof Toast toast)
                        t = toast;
                    else
                        t = (Toast) SwingUtilities.getAncestorOfClass(Toast.class, component);
                    if (t != null && t.closeTimer != null) {
                        if (id == MouseEvent.MOUSE_ENTERED) {
                            t.closeTimer.stop();
                        } else {
                            t.closeTimer.start();
                        }
                    }
                }
            }, AWTEvent.MOUSE_EVENT_MASK);
        } catch (SecurityException securityException) {
            Log.error("Can't register AWT Event Listener: %s", securityException.getMessage());
        }
    }

    /**
     * Determines the icon for the message type. Currently, OptionPane icons are returned.
     *
     * @param type The type.
     * @return The icon.
     */
    public static Icon getIconForType(ToastType type) {
        return switch (type) {
            case INFO -> UIManager.getIcon("OptionPane.informationIcon");
            case WARNING -> UIManager.getIcon("OptionPane.warningIcon");
            case ERROR -> UIManager.getIcon("OptionPane.errorIcon");
            case SUCCESS -> UIManager.getIcon("OptionPane.questionIcon");
        };
    }

    public void animateTo(int delayMS, int x, int y) {
        if (animation != null)
            animation.abort();
        animation = new Animation(this, delayMS, x, y);
    }

}
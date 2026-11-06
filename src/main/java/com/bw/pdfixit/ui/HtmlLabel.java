package com.bw.pdfixit.ui;

import javax.swing.JEditorPane;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.DefaultCaret;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A "Label" that wraps the text, supports html and can be selected to copy the text.
 * <p>
 * Like a label it is no stop of the focus traversal: it gets the focus only by a click, so the selection can be
 * copied with the keyboard, and gives it up for the traversal again when it loses it.
 */
public class HtmlLabel extends JEditorPane {

    /**
     * Shared by all labels.
     */
    private final static Handler handler = new Handler();

    public HtmlLabel() {
        init();
    }

    public HtmlLabel(String txt) {
        init();
        setText(txt);
    }

    protected void init() {
        this.setEditable(false);
        this.setOpaque(false);
        this.setBorder(null);
        this.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        this.setFont(UIManager.getFont("Label.font"));
        this.setForeground(UIManager.getColor("Label.foreground"));
        this.setCaret(new DefaultCaret() {
            @Override
            public void setVisible(boolean visible) {
                super.setVisible(false);
            }
        });
        // Text components use only Ctrl+Tab for the traversal: restore Tab, as for a label.
        this.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        this.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        this.setFocusable(false);
        this.addMouseListener(handler);
        this.addFocusListener(handler);
    }

    @Override
    public void setText(String txt) {
        final boolean isHtml = BasicHTML.isHTMLString(txt);
        final String newCT = isHtml ? "text/html" : "text/plain";
        if (!newCT.equals(getContentType()))
            setContentType(newCT);
        super.setText(txt);
    }

    /**
     * Gives the label the focus, so the selection is visible and can be copied with the keyboard: Ctrl+C works only
     * with the focus, and the {@link DefaultCaret} hides the selection without it. The label is focusable only until
     * it loses the focus again, see {@link Handler#focusLost(FocusEvent)}.
     */
    private void focusByUser() {
        setFocusable(true);
        requestFocusInWindow();
    }

    /**
     * The listeners of all labels.
     */
    private static final class Handler extends MouseAdapter implements FocusListener {

        @Override
        public void mousePressed(MouseEvent e) {
            ((HtmlLabel) e.getComponent()).focusByUser();
        }

        @Override
        public void focusGained(FocusEvent e) {
        }

        /**
         * Makes the label not focusable again after a click made it focusable (see {@link HtmlLabel#focusByUser()}).
         * Otherwise every label the user once clicked would stay a stop of the focus traversal (Tab).
         * <p>
         * Not for a temporary loss, e.g. by a switch to another window: the focus comes back to the label, and the
         * selection can still be copied with Ctrl+C.
         */
        @Override
        public void focusLost(FocusEvent e) {
            if (!e.isTemporary())
                e.getComponent().setFocusable(false);
        }
    }
}

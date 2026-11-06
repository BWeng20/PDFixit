package com.bw.pdfixit.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * A box with a titled border and a small component (a "badge", e.g. a help button) right behind the title, or at the
 * right corner of the box, with its center on the line of the frame. A border cannot hold a component, so the box is a
 * container of the content, that has the border, and the badge.<br>
 * The box is a little higher than the content if the badge is higher than the title.
 */
public final class BadgedBox extends JPanel {

    /**
     * Distance of the badge to the end of the title in pixels.
     */
    private static final int TITLE_GAP = 4;
    /**
     * The border of the title: space left of the text and the space around it. These are the constants of
     * {@link TitledBorder} (EDGE_SPACING + TEXT_INSET_H, TEXT_SPACING), which are private there. They hold for the
     * titles at the top left, as every box of this application has.
     */
    private static final int TITLE_START = 2 + 5;
    private static final int TITLE_SPACING = 2;

    /**
     * Distance of a badge at the right corner to the right edge of the box in pixels.
     */
    private static final int CORNER_GAP = 10;

    private final JComponent content;
    private final JComponent badge;
    private final boolean atRight;

    /**
     * A box with the badge behind the title.
     *
     * @param content The content, it has the border.
     * @param badge   The badge, it is painted above the content.
     */
    public BadgedBox(JComponent content, JComponent badge) {
        this(content, badge, false);
    }

    /**
     * @param content The content, it has the border.
     * @param badge   The badge, it is painted above the content.
     * @param atRight True to put the badge at the right corner of the box, False behind the title.
     */
    public BadgedBox(JComponent content, JComponent badge, boolean atRight) {
        super(null);
        this.content = content;
        this.badge = badge;
        this.atRight = atRight;
        setOpaque(false);
        setLayout(new BadgeLayout());
        // The first component is painted last, the badge is on top.
        add(badge);
        add(content);
    }

    private Font titleFont() {
        Font font = null;
        Border border = content.getBorder();
        if (border instanceof TitledBorder titled)
            font = titled.getTitleFont();
        if (font == null)
            font = UIManager.getFont("TitledBorder.font");
        if (font == null)
            font = content.getFont();
        return font;
    }

    /**
     * The distance of the frame line to the upper edge of the content.
     */
    int frameLineY() {
        return content.getFontMetrics(titleFont()).getHeight() / 2;
    }

    /**
     * The distance of the end of the title text to the left edge of the content.
     */
    int titleEndX() {
        Border border = content.getBorder();
        String title = border instanceof TitledBorder titled && titled.getTitle() != null ? titled.getTitle() : "";
        return TITLE_START + content.getFontMetrics(titleFont()).stringWidth(title) + TITLE_SPACING;
    }

    /**
     * The space that the badge needs above the frame line.
     */
    int topPad() {
        return Math.max(0, badge.getPreferredSize().height / 2 - frameLineY());
    }

    private final class BadgeLayout implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Dimension d = content.getPreferredSize();
            Insets in = parent.getInsets();
            return new Dimension(d.width + in.left + in.right, d.height + topPad() + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Dimension d = content.getMinimumSize();
            Insets in = parent.getInsets();
            return new Dimension(d.width + in.left + in.right, d.height + topPad() + in.top + in.bottom);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int w = parent.getWidth() - in.left - in.right;
            int h = parent.getHeight() - in.top - in.bottom;
            int pad = topPad();
            content.setBounds(in.left, in.top + pad, w, h - pad);

            Dimension b = badge.getPreferredSize();
            int x = atRight ? parent.getWidth() - in.right - CORNER_GAP - b.width : in.left + titleEndX() + TITLE_GAP;
            badge.setBounds(x, in.top + pad + frameLineY() - b.height / 2, b.width, b.height);
        }
    }
}

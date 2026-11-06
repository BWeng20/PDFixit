package com.bw.pdfixit.ui;

import com.bw.jtools.shape.Context;
import com.bw.jtools.shape.ShapePainter;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A little page with the "this way up" arrows of parcels (an SVG), turned like the selected page. Nothing is shown if
 * the page is not turned.<br>
 * It is in the {@link PageToolsStrip} above the rotate buttons. It keeps its place (it is only empty if the page is not
 * turned), so the buttons below don't move when a page is rotated.
 */
final class PageOrientation extends JComponent {

    /**
     * Space above the little page in pixels: more than the space below it (see PageToolsStrip), so it belongs to the rotate button.
     */
    private static final int GAP_ABOVE = 8;
    /**
     * Height of the little page in text lines.
     */
    private static final double GLYPH_LINES = 1.6;
    private static final String ORIENTATION_SVG = "/svg/page-orientation.svg";

    private int rotation;

    PageOrientation() {
        setOpaque(false);
    }

    /**
     * @param rotation The rotation of the selected page in degrees, 0 if no page is selected.
     */
    void setRotation(int rotation) {
        int r = Math.floorMod(rotation, 360);
        if (this.rotation != r) {
            this.rotation = r;
            repaint();
        }
    }

    /**
     * Edge of the square of the little page. Turned by 90° it needs this as width as well.
     */
    private int glyphSize() {
        Font f = UIManager.getFont("Label.font");
        FontMetrics fm = getFontMetrics(f != null ? f : new Font(Font.DIALOG, Font.PLAIN, 12));
        return (int) Math.round(fm.getHeight() * GLYPH_LINES);
    }

    @Override
    public Dimension getPreferredSize() {
        int size = glyphSize();
        return new Dimension(size, GAP_ABOVE + size);
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
    protected void paintComponent(Graphics g) {
        if (rotation == 0)
            return;
        Color color = UIManager.getColor("Label.foreground");
        Color background = UIManager.getColor("Panel.background");
        int size = glyphSize();
        double cx = getWidth() / 2.0;
        double cy = GAP_ABOVE + size / 2.0;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Context.initGraphics(g2);
            ShapePainter painter = RoundSvgButton.painter(ORIENTATION_SVG);

            g2.rotate(Math.toRadians(rotation), cx, cy);
            // The SVG is a portrait page, its height is the size.
            double scale = size / painter.getAreaHeight();
            g2.translate(cx - painter.getAreaWidth() * scale / 2.0, cy - size / 2.0);
            g2.scale(scale, scale);
            painter.paint(g2, color == null ? Color.BLACK : color, background == null ? Color.WHITE : background, false, false);
        } finally {
            g2.dispose();
        }
    }
}

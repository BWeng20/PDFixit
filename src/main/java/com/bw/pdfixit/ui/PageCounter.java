package com.bw.pdfixit.ui;

import com.bw.jtools.shape.Context;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/**
 * Shows which page is selected and how many there are, like a fraction:
 * <pre>
 *    3
 *  ─────
 *   12
 * </pre>
 * It is meant for the top of the {@link PageToolsStrip}, between the buttons for the previous and the next page. Whether
 * the page is turned shows the {@link PageOrientation} below.
 */
final class PageCounter extends JComponent {

    private static final double NUMBER_SCALE = 1.7;
    /**
     * Space above and below the line between the number and the count.
     */
    private static final int LINE_GAP = 2;
    /**
     * How far the line is wider than the widest text, on each side.
     */
    private static final int LINE_OVERHANG = 2;

    private final JComponent widthReference;
    private int page;
    private int count;

    /**
     * @param widthReference A component (a button of the strip) that gives the minimal width.
     */
    PageCounter(JComponent widthReference) {
        this.widthReference = widthReference;
        setOpaque(false);
    }

    /**
     * @param page  The selected page (1 based), 0 if none.
     * @param count The number of pages, 0 if there is no document.
     */
    void setState(int page, int count) {
        if (this.page == page && this.count == count)
            return;
        this.page = page;
        this.count = count;
        revalidate();
        repaint();
    }

    private Font baseFont() {
        Font f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.DIALOG, Font.PLAIN, 12);
    }

    private Font numberFont() {
        Font f = baseFont();
        return f.deriveFont(Font.BOLD, f.getSize2D() * (float) NUMBER_SCALE);
    }

    private String countText() {
        return count > 0 ? Integer.toString(count) : "";
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(baseFont());
        FontMetrics nm = getFontMetrics(numberFont());
        int width = Math.max(nm.stringWidth("000"), fm.stringWidth("000")) + 2 * LINE_OVERHANG;
        if (widthReference != null)
            width = Math.max(width, widthReference.getPreferredSize().width);
        return new Dimension(width, nm.getHeight() + 2 * LINE_GAP + 1 + fm.getHeight());
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
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Context.initGraphics(g2);
            Font base = baseFont();
            Font numberFont = numberFont();
            FontMetrics fm = g2.getFontMetrics(base);
            FontMetrics nm = g2.getFontMetrics(numberFont);
            Color fg = UIManager.getColor("Label.foreground");
            if (fg == null)
                fg = Color.BLACK;
            Color weak = UIManager.getColor("Label.disabledForeground");
            if (weak == null)
                weak = Color.GRAY;
            int w = getWidth();

            Color color = page > 0 ? fg : weak;

            // Row 1: the number of the page.
            String number = page > 0 ? Integer.toString(page) : "–";
            g2.setColor(color);
            Rectangle2D numberInk = drawCentered(g2, numberFont, number, w, nm.getAscent());

            // A line between the number and the page count, like a fraction.
            int y = nm.getHeight() + LINE_GAP;
            String of = countText();
            Rectangle2D countInk = baseFont().createGlyphVector(g2.getFontRenderContext(), of).getVisualBounds();
            int lineWidth = (int) Math.ceil(Math.max(numberInk.getWidth(), countInk.getWidth())) + 2 * LINE_OVERHANG;
            g2.fillRect((w - lineWidth) / 2, y, lineWidth, 1);
            y += 1 + LINE_GAP;

            // Row 2: the page count.
            drawCentered(g2, base, of, w, y + fm.getAscent());
        } finally {
            g2.dispose();
        }
    }

    /**
     * Draws a text so that its glyphs (not its advance with the side bearings of the first and last glyph)
     * are in the middle.
     *
     * @param baseline The y of the baseline.
     * @return The bounds of the glyphs as drawn.
     */
    private static Rectangle2D drawCentered(Graphics2D g2, Font font, String text, int width, int baseline) {
        Rectangle2D ink = font.createGlyphVector(g2.getFontRenderContext(), text).getVisualBounds();
        g2.setFont(font);
        g2.drawString(text, (float) ((width - ink.getWidth()) / 2 - ink.getX()), baseline);
        return ink;
    }
}

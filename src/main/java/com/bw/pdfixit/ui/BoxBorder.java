package com.bw.pdfixit.ui;

import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Insets;

/**
 * The border of a box with a title. All boxes of the application use it, so they look the same: the title is bold and
 * a bit bigger than the text, and the frame has more contrast than the one of the look and feel.<br>
 * Font and colors are read from the look and feel each time they are needed, so they follow a change of the look and
 * feel or of the font size. The font of the titles is {@link #titleFont()}, use it wherever the size of a title matters.
 */
public final class BoxBorder extends TitledBorder {

    /**
     * Size of the title as a multiple of the size of the text.
     */
    static final float TITLE_SIZE = 1.15f;
    /**
     * Share of the text color in the color of the frame, the rest is the background.
     */
    static final double FRAME_CONTRAST = 0.45;

    /**
     * Space between the title and the first line of the content as a multiple of the height of the title font, so the
     * content doesn't stick to the title.
     */
    static final double CONTENT_GAP = 0.3;

    private BoxBorder(String title) {
        super(title);
    }

    /**
     * @return A border with the title, for a box.
     */
    public static BoxBorder create(String title) {
        return new BoxBorder(title);
    }

    /**
     * @return The font of the titles of the boxes: bold and {@link #TITLE_SIZE} times the size of the text.
     */
    public static Font titleFont() {
        Font base = UIManager.getFont("TitledBorder.font");
        if (base == null)
            base = UIManager.getFont("Label.font");
        if (base == null)
            base = new Font(Font.DIALOG, Font.PLAIN, 12);
        return base.deriveFont(Font.BOLD, base.getSize2D() * TITLE_SIZE);
    }

    private static Color foreground() {
        Color fg = UIManager.getColor("Panel.foreground");
        return fg == null ? Color.BLACK : fg;
    }

    private static Color background() {
        Color bg = UIManager.getColor("Panel.background");
        return bg == null ? Color.LIGHT_GRAY : bg;
    }

    /**
     * @return The color of the frame of a box.
     */
    static Color frameColor() {
        return SideTabs.mix(background(), foreground(), FRAME_CONTRAST);
    }

    /**
     * @return The gap between the title and the content in pixels.
     */
    static int contentGap(Component c) {
        Font font = titleFont();
        int height = c == null ? 16 : c.getFontMetrics(font).getHeight();
        return (int) Math.round(height * CONTENT_GAP);
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return getBorderInsets(c, new Insets(0, 0, 0, 0));
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        Insets result = super.getBorderInsets(c, insets);
        result.top += contentGap(c);
        return result;
    }

    @Override
    public Font getTitleFont() {
        return titleFont();
    }

    @Override
    public Color getTitleColor() {
        return foreground();
    }

    @Override
    public Border getBorder() {
        return new LineBorder(frameColor(), 1);
    }
}

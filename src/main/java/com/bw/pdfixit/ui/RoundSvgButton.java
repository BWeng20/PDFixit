package com.bw.pdfixit.ui;

import com.bw.jtools.shape.Context;
import com.bw.jtools.shape.ShapePainter;
import com.bw.jtools.svg.SVGConverter;
import com.bw.jtools.ui.ShapeIcon;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A round button with a symbol from an SVG, e.g. the page tools beside the page preview.<br>
 * The SVG is drawn on a grid of 24 x 24 units with the foreground color ("currentColor" in the SVG).
 * Colors come from the current look and feel, so they follow light and dark mode.
 * The size is relative to the font height, see {@link #SIZE_IN_FONT_HEIGHTS}.
 * With {@code setBorderPainted(false)} the button doesn't paint its circle: the SVG is the whole button.
 * If the mouse is over the button, the whole button grows (into reserved space around it), unless motion is reduced (see
 * {@link Preferences#USER_PREF_REDUCED_MOTION}).
 */
public final class RoundSvgButton extends JButton {

    /**
     * The diameter of the button at rest as a multiple of the font height. The component is {@link #HOVER_SCALE} bigger, it has room to grow.
     */
    public static final double SIZE_IN_FONT_HEIGHTS = 2.2;
    /**
     * The diameter of a help button at rest, see {@link #createHelp()}.
     */
    static final double HELP_SIZE_IN_FONT_HEIGHTS = 2.0;
    /**
     * The diameter of the buttons for the previous and the next page, around the {@link PageCounter}. They are
     * smaller than the page tools: they belong to the counter and don't change the document.
     */
    static final double NAVIGATION_SIZE_IN_FONT_HEIGHTS = 1.5;
    /**
     * The diameter of a help button beside the title of a box, see {@link BadgedBox}. It is not higher than the title.
     */
    static final double BADGE_SIZE_IN_FONT_HEIGHTS = 1.0;
    /**
     * The drawing of the badge: a ring with a question mark or a filled circle with the question mark as a hole.
     */
    static final String BADGE_RESOURCE = "/svg/button-help-ring.svg";
    /**
     * Scale of the button while the mouse is over the button.
     */
    static final double HOVER_SCALE = 1.25;
    /**
     * Time for the full growth (or shrink) in milliseconds.
     */
    static final double HOVER_MS = 120;

    private static final Map<String, ShapePainter> PAINTERS = new HashMap<>();

    /**
     * @param resource The SVG, a 24 x 24 drawing.
     * @return The painter of the SVG, cached.
     */
    static synchronized ShapePainter painter(String resource) {
        return PAINTERS.computeIfAbsent(resource, r -> {
            try (InputStream in = RoundSvgButton.class.getResourceAsStream(r)) {
                return new ShapeIcon(SVGConverter.convert(in)).getPainter();
            } catch (Exception e) {
                Log.error("Can't load %s: %s", r, e.getMessage());
                return new ShapePainter();
            }
        });
    }

    /**
     * Size of the drawings of the SVGs.
     */
    static final double GRID = 24;

    private final String resource;
    private double sizeInFontHeights = SIZE_IN_FONT_HEIGHTS;
    /**
     * The font whose height the size is relative to, the font of the button if null.
     */
    private java.util.function.Supplier<java.awt.Font> sizeFont;
    private String symbolColorKey;
    private Color symbolColorFallback;
    /**
     * True if the focus came from the mouse, then no focus ring is shown.
     */
    private boolean focusFromMouse;
    private double symbolScale = 1;
    private final Timer animation = new Timer(15, null);
    private long lastStep;

    protected Color uiForeground;
    protected Color uiBackground;
    protected Color uiPressedBackground;
    protected Color uiHoverBackground;
    protected Color uiBorderColor;
    protected Color uiAccentColor;
    protected Color uiFocusColor;
    protected Color uiDisabledText;
    protected Color uiSymbolColor;

    /**
     * @param resource Absolute class path resource of the SVG, e.g. "/svg/page-tool-move-up.svg".
     */
    public RoundSvgButton(String resource) {
        this(resource, SIZE_IN_FONT_HEIGHTS);
    }

    /**
     * @param resource          Absolute class path resource of the SVG.
     * @param sizeInFontHeights The diameter at rest as a multiple of the font height.
     */
    public RoundSvgButton(String resource, double sizeInFontHeights) {
        this.resource = resource;
        this.sizeInFontHeights = sizeInFontHeights;
        installDefaults();
        animation.addActionListener(e -> {
            long now = System.nanoTime();
            advance((now - lastStep) / 1_000_000d);
            lastStep = now;
        });
        getModel().addChangeListener(e -> startAnimation());
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                focusFromMouse = true;
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                focusFromMouse = false;
                repaint();
            }
        });
    }

    /**
     * A small help button. Its SVG is the whole button (a circle with a question mark as hole), in the blue of
     * the question icon of FlatLaf ("Actions.Blue", in every look and feel).
     */
    public static RoundSvgButton createHelp() {
        return createHelp(HELP_SIZE_IN_FONT_HEIGHTS);
    }

    /**
     * A help button for the title of a {@link BadgedBox}. Its size is relative to the font of the titles, see
     * {@link BoxBorder#titleFont()}.
     */
    public static RoundSvgButton createBadge() {
        RoundSvgButton b = createHelp(BADGE_SIZE_IN_FONT_HEIGHTS, BADGE_RESOURCE);
        b.sizeFont = BoxBorder::titleFont;
        return b;
    }

    /**
     * @param sizeInFontHeights The diameter at rest as a multiple of the font height.
     * @see #createHelp()
     */
    public static RoundSvgButton createHelp(double sizeInFontHeights) {
        return createHelp(sizeInFontHeights, "/svg/button-help.svg");
    }

    /**
     * @param sizeInFontHeights The diameter at rest as a multiple of the font height.
     * @param resource          The SVG, it is the whole button.
     * @see #createHelp()
     */
    public static RoundSvgButton createHelp(double sizeInFontHeights, String resource) {
        RoundSvgButton b = new RoundSvgButton(resource);
        b.setSymbolColor("Actions.Blue", new Color(0x389FD6));
        // The SVG has the circle itself.
        b.setBorderPainted(false);
        b.sizeInFontHeights = sizeInFontHeights;
        return b;
    }

    /**
     * Draws the symbol in a color of the look and feel instead of the foreground color.
     *
     * @param key      The key in the {@link UIManager}, e.g. "Actions.Red".
     * @param fallback Used if the look and feel has no such color.
     */
    public void setSymbolColor(String key, Color fallback) {
        symbolColorKey = key;
        symbolColorFallback = fallback;
        if (symbolColorKey != null) {
            uiSymbolColor = UIManager.getColor(symbolColorKey);
            if (uiSymbolColor == null)
                uiSymbolColor = symbolColorFallback;
        } else
            uiSymbolColor = null;
        repaint();
    }

    private double targetScale() {
        return isEnabled() && getModel().isRollover() && !getModel().isPressed() ? HOVER_SCALE : 1;
    }

    private void startAnimation() {
        if (Preferences.getInstance().getBoolean(Preferences.USER_PREF_REDUCED_MOTION,
                Preferences.USER_PREF_REDUCED_MOTION_DEFAULT)) {
            animation.stop();
            symbolScale = targetScale();
            repaint();
        } else if (symbolScale != targetScale() && !animation.isRunning()) {
            lastStep = System.nanoTime();
            animation.start();
        }
    }

    /**
     * Moves the symbol scale towards its target.
     *
     * @param ms The time since the last step.
     */
    void advance(double ms) {
        double target = targetScale();
        double step = (HOVER_SCALE - 1) * ms / HOVER_MS;
        symbolScale = symbolScale < target ? Math.min(target, symbolScale + step) : Math.max(target, symbolScale - step);
        if (symbolScale == target)
            animation.stop();
        repaint();
    }

    double getSymbolScale() {
        return symbolScale;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        startAnimation();
    }

    @Override
    public void removeNotify() {
        animation.stop();
        super.removeNotify();
    }

    private int diameter() {
        java.awt.Font font = sizeFont == null ? getFont() : sizeFont.get();
        return (int) Math.round(getFontMetrics(font).getHeight() * sizeInFontHeights * HOVER_SCALE);
    }

    @Override
    public Dimension getPreferredSize() {
        int d = diameter();
        return new Dimension(d, d);
    }

    /**
     * The attributes that make the button round and flat. A look and feel installs its own values when it is
     * set (e.g. the Windows look and feel its own border), so this is done again after every {@link #updateUI()}.
     */
    private void installDefaults() {
        setContentAreaFilled(false);
        // The circle and its outline are the "border". Swing's own border is not used.
        setBorder(BorderFactory.createEmptyBorder());
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setMargin(new java.awt.Insets(0, 0, 0, 0));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        installDefaults();

        uiForeground = UIManager.getColor("Button.foreground");
        uiBackground = UIManager.getColor("Button.background");
        uiPressedBackground = UIManager.getColor("Button.pressedBackground");
        uiHoverBackground = UIManager.getColor("Button.hoverBackground");
        uiBorderColor = UIManager.getColor("Button.borderColor");
        uiFocusColor = UIManager.getColor("Component.focusColor");
        uiAccentColor = UIManager.getColor("Component.accentColor");
        uiDisabledText = UIManager.getColor("Button.disabledText");

        setSymbolColor(symbolColorKey, symbolColorFallback);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /**
     * Only the circle (at rest) reacts to the mouse.
     */
    @Override
    public boolean contains(int x, int y) {
        double r = Math.min(getWidth(), getHeight()) / HOVER_SCALE / 2d;
        return Point2D.distance(x, y, getWidth() / 2d, getHeight() / 2d) <= r;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Context.initGraphics(g2);

            // The component has room for the grown button, the button at rest is smaller.
            double size = Math.min(getWidth(), getHeight()) / HOVER_SCALE * symbolScale;
            double x = (getWidth() - size) / 2;
            double y = (getHeight() - size) / 2;
            double outline = Math.max(1, size / 32);
            Ellipse2D circle = new Ellipse2D.Double(x + outline / 2, y + outline / 2, size - outline, size - outline);

            boolean enabled = isEnabled();
            Color foreground = (uiForeground == null ? Color.DARK_GRAY : uiForeground);
            Color fill = (uiBackground == null ? Color.LIGHT_GRAY : uiBackground);
            if (enabled && getModel().isPressed() && getModel().isArmed())
                fill = colorOrMix(uiPressedBackground, fill, foreground, 0.20);
            else if (enabled && getModel().isRollover())
                fill = colorOrMix(uiHoverBackground, fill, foreground, 0.10);

            if (isBorderPainted()) {
                g2.setColor(fill);
                g2.fill(circle);
                g2.setColor(colorOrMix(uiBorderColor, fill, foreground, 0.35));
                g2.setStroke(new BasicStroke((float) outline));
                g2.draw(circle);
            }

            if (enabled && isFocusOwner() && !focusFromMouse) {
                double ring = Math.max(1.5, size / 16);
                g2.setColor(uiFocusColor == null ? (uiAccentColor == null ? foreground : uiAccentColor) : uiFocusColor);
                g2.setStroke(new BasicStroke((float) ring));
                g2.draw(new Ellipse2D.Double(x + ring / 2, y + ring / 2, size - ring, size - ring));
            }

            Color symbol = foreground;
            if (!enabled)
                symbol = colorOrMix(uiDisabledText, fill, foreground, 0.4);
            else if (uiSymbolColor != null)
                symbol = uiSymbolColor;
            g2.translate(x, y);
            g2.scale(size / GRID, size / GRID);
            // A button that is only its SVG has the background of its parent, so "currentBackground" in the SVG hides what is behind.
            Color background = isBorderPainted() || getParent() == null ? fill : getParent().getBackground();
            painter(resource).paint(g2, symbol, background, false, false);
        } finally {
            g2.dispose();
        }
    }

    private static Color colorOrMix(Color uiColor, Color a, Color b, double share) {
        if (uiColor == null) {
            return new Color(
                    (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * share),
                    (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * share),
                    (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * share));
        } else {
            return uiColor;
        }
    }


}

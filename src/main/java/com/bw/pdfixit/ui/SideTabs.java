package com.bw.pdfixit.ui;

import com.bw.jtools.shape.Context;

import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A vertical strip of colored tabs, each one opens a panel in a {@link TabDrawer} beside the strip. These tabs are the only
 * colored part of the application.<br>
 * A click opens the panel of the tab and closes the others. With the Ctrl key a tab is added to or removed from the
 * open ones. One tab is always open: the first one at the start, and the last open tab can't be closed.
 * Each tab shows a symbol (an SVG) above its text.
 */
final class SideTabs extends JPanel {

    /**
     * Description of a tab.
     *
     * @param id       Identifier, used in the preferences.
     * @param title    The text on the tab.
     * @param resource The SVG of the symbol, a 24 x 24 drawing.
     * @param color    The color of the tab.
     */
    record Spec(String id, String title, String resource, Color color) {
    }

    /**
     * Part of the width that an inactive tab is shorter than the active one, as a multiple of the font height.
     */
    private static final double INACTIVE_INSET = 0.3;
    private static final double ARC_IN_FONT_HEIGHTS = 0.7;
    /**
     * Share of the tab color in an inactive tab, and while the mouse is over it.
     */
    private static final double INACTIVE_SHARE = 0.35;
    private static final double HOVER_SHARE = 0.55;

    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    /**
     * The open tabs, the last one is the one that was opened last.
     */
    private final LinkedHashSet<String> open = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    /**
     * The tab of the last click, if the click opened it or left it open. Null if the click closed it.
     */
    private String clicked;

    SideTabs(List<Spec> specs) {
        super(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(0, 0, 3, 0);
        int row = 0;
        for (Spec spec : specs) {
            Tab tab = new Tab(spec);
            tabs.put(spec.id(), tab);
            if (open.isEmpty())
                open.add(spec.id());
            tab.setSelected(open.contains(spec.id()));
            gc.gridy = row++;
            add(tab, gc);
        }
        // The tabs keep their height, the space below is empty.
        gc.gridy = row;
        gc.weighty = 1;
        gc.fill = GridBagConstraints.BOTH;
        add(javax.swing.Box.createGlue(), gc);
        setOpaque(true);
    }

    /**
     * @return The background of the strip: a little different from the panels, so the tabs stand out.
     */
    static Color stripBackground() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null)
            bg = Color.LIGHT_GRAY;
        Color fg = UIManager.getColor("Panel.foreground");
        if (fg == null)
            fg = Color.BLACK;
        return mix(bg, fg, 0.06);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setBackground(stripBackground());
    }

    /**
     * @return The identifiers of the open tabs, in the order of the tabs.
     */
    Set<String> open() {
        Set<String> result = new LinkedHashSet<>();
        for (String id : tabs.keySet())
            if (open.contains(id))
                result.add(id);
        return result;
    }

    /**
     * @return The identifier of the tab the user clicked last, if it is open, null if the click closed it.
     */
    String clickedTab() {
        return clicked;
    }

    /**
     * Opens exactly these tabs (the unknown ones are ignored, the first tab is opened if none is left). No listener
     * is called.
     */
    void setOpen(Collection<String> ids) {
        open.clear();
        for (String id : ids)
            if (tabs.containsKey(id))
                open.add(id);
        if (open.isEmpty())
            open.add(tabs.keySet().iterator().next());
        syncButtons();
    }

    /**
     * A click on a tab.
     *
     * @param additive True if the other tabs keep their state (the Ctrl key).
     */
    void click(String id, boolean additive) {
        if (!tabs.containsKey(id))
            return;
        Set<String> before = new LinkedHashSet<>(open);
        if (additive) {
            // The last open tab stays.
            if (open.contains(id)) {
                if (open.size() > 1)
                    open.remove(id);
            } else {
                open.add(id);
            }
        } else {
            open.clear();
            open.add(id);
        }
        clicked = open.contains(id) ? id : null;
        syncButtons();
        if (before.equals(open))
            return;
        for (Runnable l : new ArrayList<>(listeners))
            l.run();
    }

    /**
     * Sets a small label on a tab, e.g. what the panel of the tab does at the moment.
     *
     * @param text The label, null or empty for none.
     */
    void setBadge(String id, String text) {
        Tab tab = tabs.get(id);
        if (tab != null) {
            tab.badge = text == null || text.isBlank() ? null : text;
            tab.revalidate();
            tab.repaint();
        }
    }

    /**
     * @param listener Called if the user changed the open tabs.
     */
    void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private void syncButtons() {
        tabs.forEach((id, tab) -> tab.setSelected(open.contains(id)));
        repaint();
    }

    static Color mix(Color a, Color b, double shareOfB) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * shareOfB),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * shareOfB),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * shareOfB));
    }

    /**
     * @return Black or white, whatever is better readable on the color.
     */
    static Color textOn(Color background) {
        double luminance = (0.2126 * background.getRed() + 0.7152 * background.getGreen() + 0.0722 * background.getBlue()) / 255;
        return luminance > 0.55 ? new Color(0x1E1E1E) : Color.WHITE;
    }

    private final class Tab extends JToggleButton {
        private final Spec spec;
        private boolean focusFromMouse;
        /**
         * A small label in the upper right corner, null for none.
         */
        private String badge;

        Tab(Spec spec) {
            this.spec = spec;
            setText(spec.title());
            setToolTipText(spec.title());
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setRolloverEnabled(true);
            setOpaque(false);
            setMargin(new Insets(0, 0, 0, 0));
            getAccessibleContext().setAccessibleName(spec.title());
            // The state is managed by the strip, the click only tells what the user wants.
            addActionListener((ActionEvent e) ->
                    click(spec.id(), (e.getModifiers() & (ActionEvent.CTRL_MASK | ActionEvent.META_MASK)) != 0));
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

        private Font labelFont() {
            Font f = getFont();
            return f.deriveFont(f.getSize2D() * 0.9f);
        }

        private int iconSize() {
            return (int) Math.round(getFontMetrics(getFont()).getHeight() * 1.6);
        }

        private Font badgeFont() {
            Font font = labelFont();
            return font.deriveFont(Font.BOLD, font.getSize2D() * 0.8f);
        }

        /**
         * @return The height of the room above the symbol that the badge needs, 0 without a badge. The badge doesn't
         * cover the symbol.
         */
        private int badgeSpace() {
            return badge == null ? 0 : getFontMetrics(badgeFont()).getHeight() + 3;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(labelFont());
            int pad = fm.getHeight() / 2;
            int chipW = badge == null ? 0 : getFontMetrics(badgeFont()).stringWidth(badge) + getFontMetrics(badgeFont()).getHeight() * 2 / 3 + 6;
            int w = Math.max(Math.max(fm.stringWidth(spec.title()), iconSize()), chipW) + 2 * pad;
            int h = iconSize() + fm.getHeight() + 2 * pad + badgeSpace();
            return new Dimension(w, h);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        /**
         * The label in the upper right corner: a chip in the colors of the text, so it stands out from the tab.
         */
        private void paintBadge(Graphics2D g2, int right, Color fill, Color text) {
            Font font = badgeFont();
            FontMetrics bm = getFontMetrics(font);
            int padX = bm.getHeight() / 3;
            int chipW = bm.stringWidth(badge) + 2 * padX;
            int chipH = bm.getHeight();
            int x = right - chipW - 3;
            int y = 3;
            g2.setColor(text);
            g2.fillRoundRect(x, y, chipW, chipH, chipH, chipH);
            g2.setColor(fill);
            g2.setFont(font);
            g2.drawString(badge, x + padX, y + bm.getAscent());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Context.initGraphics(g2);
                int w = getWidth();
                int h = getHeight();
                FontMetrics fm = getFontMetrics(labelFont());
                double fontHeight = fm.getHeight();
                boolean selected = isSelected();
                Color strip = stripBackground();

                // An inactive tab is a bit shorter: the active one reaches into the drawer.
                int right = selected ? w : w - (int) Math.round(fontHeight * INACTIVE_INSET);
                Color fill = selected ? spec.color()
                        : mix(strip, spec.color(), getModel().isRollover() ? HOVER_SHARE : INACTIVE_SHARE);
                double arc = fontHeight * ARC_IN_FONT_HEIGHTS;
                g2.setColor(fill);
                // The right corners are outside of the clip: only the left corners are round.
                g2.setClip(0, 0, right, h);
                g2.fill(new RoundRectangle2D.Double(0, 0, right + arc, h, arc, arc));
                g2.setClip(null);

                Color text = textOn(fill);
                if (!selected) {
                    // A thin line of the tab color on the left: the color is clear even in the pale state.
                    g2.setColor(spec.color());
                    g2.fillRect(0, 0, Math.max(2, (int) Math.round(fontHeight / 5)), h);
                }

                int size = iconSize();
                int contentHeight = size + fm.getHeight();
                // The badge has its own room on top, the symbol and the text are centered in the rest.
                int space = badgeSpace();
                int top = space + (h - space - contentHeight) / 2;
                int iconX = (right - size) / 2;
                g2.translate(iconX, top);
                g2.scale(size / RoundSvgButton.GRID, size / RoundSvgButton.GRID);
                RoundSvgButton.painter(spec.resource()).paint(g2, text, fill, false, false);
                g2.scale(RoundSvgButton.GRID / size, RoundSvgButton.GRID / size);
                g2.translate(-iconX, -top);

                g2.setFont(labelFont());
                g2.setColor(text);
                g2.drawString(spec.title(), (right - fm.stringWidth(spec.title())) / 2, top + size + fm.getAscent());

                if (badge != null)
                    paintBadge(g2, right, fill, text);

                if (isFocusOwner() && !focusFromMouse) {
                    Color focus = UIManager.getColor("Component.focusColor");
                    g2.setColor(focus == null ? text : focus);
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRect(2, 2, right - 4, h - 4);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

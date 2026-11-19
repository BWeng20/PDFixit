package com.bw.pdfixit.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The content beside the {@link SideTabs}: the panels of the open tabs below each other, the closed ones are hidden.
 * Each open panel has a line in the color of its tab on top and on the left, so it is clear which tab it belongs to,
 * also if several tabs are open.<br>
 * One panel can have a bar (e.g. the buttons that apply the invoice) below it, inside its lines. The bar sticks to the
 * bottom of the view while the end of its panel is scrolled out of view, so it stays in reach; it doesn't leave its
 * panel. Call {@link #placeBar()} if the view was scrolled.<br>
 * The drawer is as wide as the widest of the open panels, that is the room the open tabs need. The user interface
 * decides how wide it really is (see {@link UI}), so the width doesn't jump if another tab is opened.
 * {@link #widestWidth()} is the width of the widest of all panels.
 */
final class TabDrawer extends JPanel {

    /**
     * Space between two open panels.
     */
    private static final int GAP = 5;
    /**
     * Thickness of the colored lines on top and on the left of a panel.
     */
    private static final int TOP_LINE = 3;
    private static final int LEFT_LINE = 6;
    /**
     * Room between the colored lines and the panel.
     */
    private static final int TOP_ROOM = 4;
    private static final int LEFT_ROOM = 6;

    private final Map<String, JComponent> panels = new LinkedHashMap<>();
    private final Map<Component, Color> colors = new LinkedHashMap<>();
    /**
     * Identifier of the panel that gets the height that is left if the drawer is higher than its panels need, null for
     * none. The other panels keep their height.
     */
    private String fillsHeight;
    /**
     * The bar below a panel and the panel it belongs to, null for none.
     */
    private JComponent bar;
    private JComponent barOwner;

    TabDrawer() {
        super(null);
        setLayout(new DrawerLayout());
    }

    /**
     * Adds a panel, it is hidden until it is opened.
     *
     * @param color The color of the tab of the panel, for the lines beside it.
     */
    void addPanel(String id, JComponent panel, Color color) {
        panels.put(id, panel);
        colors.put(panel, color);
        panel.setVisible(false);
        add(panel);
    }

    /**
     * Puts a bar below the panel with this identifier. It is shown with its panel.
     */
    void setBar(String id, JComponent bar) {
        this.bar = bar;
        this.barOwner = panels.get(id);
        // The first child is painted last: the bar is on top of the panels, it covers the end of its panel while it
        // sticks to the bottom of the view.
        add(bar, 0);
        bar.setVisible(barOwner.isVisible());
        revalidate();
    }

    /**
     * Shows the panels with these identifiers, hides the others.
     */
    void setOpen(Collection<String> ids) {
        panels.forEach((id, panel) -> panel.setVisible(ids.contains(id)));
        if (bar != null)
            bar.setVisible(barOwner.isVisible());
        revalidate();
        repaint();
    }

    /**
     * Sets the panel that is as high as the room that is left, if the drawer is higher than the open panels need: its
     * content (e.g. a table) can use it.
     *
     * @param id Identifier of the panel, null for none.
     */
    void setFillsHeight(String id) {
        this.fillsHeight = id;
        revalidate();
    }

    /**
     * @return True if the panel with this identifier is open.
     */
    boolean isOpen(String id) {
        JComponent panel = panels.get(id);
        return panel != null && panel.isVisible();
    }

    /**
     * @return The width the drawer would have if its widest panel was open, whatever is open now.
     */
    int widestWidth() {
        int widest = 0;
        for (JComponent panel : panels.values())
            widest = Math.max(widest, panel.getPreferredSize().width);
        if (bar != null)
            widest = Math.max(widest, bar.getPreferredSize().width);
        Insets in = getInsets();
        return widest + LEFT_LINE + LEFT_ROOM + in.left + in.right;
    }

    /**
     * Scrolls the panel with this identifier into view, with its colored line on top and its bar. A panel that is
     * higher than the view is shown from its top.
     */
    void scrollToPanel(String id) {
        JComponent panel = panels.get(id);
        if (panel == null || !panel.isVisible())
            return;
        int top = panel.getY() - TOP_ROOM - TOP_LINE;
        int height = panel.getY() + panel.getHeight() + barHeight(panel) - top;
        int visible = getVisibleRect().height;
        if (visible > 0)
            height = Math.min(height, visible);
        scrollRectToVisible(new Rectangle(0, top, 1, height));
    }

    /**
     * Puts the bar below its panel, or at the bottom of the view if the end of the panel is scrolled out of view. It
     * leaves the top of its panel (as high as the bar) uncovered, so the title of the panel shows above it, even if
     * the bar then goes below the view.
     */
    void placeBar() {
        if (bar == null || !bar.isVisible())
            return;
        int h = bar.getPreferredSize().height;
        int below = barOwner.getY() + barOwner.getHeight();
        int y = below;
        Rectangle view = getVisibleRect();
        if (view.height > 0)
            y = Math.min(y, view.y + view.height - h);
        y = Math.max(y, Math.min(barOwner.getY() + h, below));
        bar.setBounds(barOwner.getX(), y, barOwner.getWidth(), h);
    }

    /**
     * @return The height of the bar below this panel, 0 if it has none.
     */
    private int barHeight(Component panel) {
        return bar != null && panel == barOwner ? bar.getPreferredSize().height : 0;
    }

    /**
     * The bar overlaps its panel: the children must be painted in their order.
     */
    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;
    }

    /**
     * Paints the colored lines on top and on the left of each open panel. The left line ends with the panel, or with
     * its bar.
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Insets in = getInsets();
        int width = getWidth() - in.left - in.right;
        for (Component c : getComponents()) {
            Color color = colors.get(c);
            if (!c.isVisible() || color == null)
                continue;
            int top = c.getY() - TOP_ROOM - TOP_LINE;
            g.setColor(color);
            g.fillRect(in.left, top, width, TOP_LINE);
            g.fillRect(in.left, top, LEFT_LINE, c.getY() + c.getHeight() + barHeight(c) - top);
        }
    }

    private final class DrawerLayout implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int width = 0;
            int height = 0;
            int visible = 0;
            for (Component c : parent.getComponents()) {
                if (c.isVisible() && c != bar) {
                    Dimension d = c.getPreferredSize();
                    width = Math.max(width, d.width);
                    if (c == barOwner)
                        width = Math.max(width, bar.getPreferredSize().width);
                    height += TOP_LINE + TOP_ROOM + d.height + barHeight(c);
                    visible++;
                }
            }
            if (visible > 1)
                height += (visible - 1) * GAP;
            Insets in = parent.getInsets();
            return new Dimension(width + LEFT_LINE + LEFT_ROOM + in.left + in.right, height + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int x = in.left + LEFT_LINE + LEFT_ROOM;
            int w = parent.getWidth() - x - in.right;
            int y = in.top;
            // The room that is left: it goes to the panel that fills, if it is open.
            int extra = Math.max(0, parent.getHeight() - preferredLayoutSize(parent).height);
            Component fills = fillsHeight == null ? null : panels.get(fillsHeight);
            for (Component c : parent.getComponents()) {
                if (!c.isVisible() || c == bar)
                    continue;
                y += TOP_LINE + TOP_ROOM;
                int h = c.getPreferredSize().height + (c == fills ? extra : 0);
                c.setBounds(x, y, w, h);
                y += h + barHeight(c) + GAP;
            }
            placeBar();
        }
    }
}

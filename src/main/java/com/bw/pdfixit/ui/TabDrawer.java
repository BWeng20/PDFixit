package com.bw.pdfixit.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The content beside the {@link SideTabs}: the panels of the open tabs below each other, the closed ones are hidden.
 * The drawer is as wide as the widest of the open panels, that is the room the open tabs need. The user interface
 * decides how wide it really is (see {@link UI}), so the width doesn't jump if another tab is opened.
 * {@link #widestWidth()} is the width of the widest of all panels.
 */
final class TabDrawer extends JPanel {

    /**
     * Space between two open panels.
     */
    private static final int GAP = 5;

    private final Map<String, JComponent> panels = new LinkedHashMap<>();
    /**
     * Identifier of the panel that gets the height that is left if the drawer is higher than its panels need, null for
     * none. The other panels keep their height.
     */
    private String fillsHeight;

    TabDrawer() {
        super(null);
        setLayout(new DrawerLayout());
    }

    /**
     * Adds a panel, it is hidden until it is opened.
     */
    void addPanel(String id, JComponent panel) {
        panels.put(id, panel);
        panel.setVisible(false);
        add(panel);
    }

    /**
     * Shows the panels with these identifiers, hides the others.
     */
    void setOpen(Collection<String> ids) {
        panels.forEach((id, panel) -> panel.setVisible(ids.contains(id)));
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
        Insets in = getInsets();
        return widest + in.left + in.right;
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
                if (c.isVisible()) {
                    Dimension d = c.getPreferredSize();
                    width = Math.max(width, d.width);
                    height += d.height;
                    visible++;
                }
            }
            if (visible > 1)
                height += (visible - 1) * GAP;
            Insets in = parent.getInsets();
            return new Dimension(width + in.left + in.right, height + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int w = parent.getWidth() - in.left - in.right;
            int y = in.top;
            // The room that is left: it goes to the panel that fills, if it is open.
            int extra = Math.max(0, parent.getHeight() - preferredLayoutSize(parent).height);
            Component fills = fillsHeight == null ? null : panels.get(fillsHeight);
            for (Component c : parent.getComponents()) {
                if (!c.isVisible())
                    continue;
                int h = c.getPreferredSize().height + (c == fills ? extra : 0);
                c.setBounds(in.left, y, w, h);
                y += h + GAP;
            }
        }
    }
}

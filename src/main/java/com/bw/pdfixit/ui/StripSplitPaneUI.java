package com.bw.pdfixit.ui;

import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The UI of the split pane of the window for the look and feels that paint their own divider. Their
 * dividers have the color of the content, beside the strips of the window (see {@link SideTabs}) they look like a gap.
 * This divider has the color of the strips and a grip of three dots. The behavior (dragging, keyboard) is the one of
 * the basic UI. FlatLaf has a transparent divider, it needs no replacement.
 */
final class StripSplitPaneUI extends BasicSplitPaneUI {

    private static final int DOTS = 3;

    @Override
    public BasicSplitPaneDivider createDefaultDivider() {
        return new BasicSplitPaneDivider(this) {
            @Override
            public void paint(Graphics g) {
                Color strip = SideTabs.stripBackground();
                g.setColor(strip);
                g.fillRect(0, 0, getWidth(), getHeight());

                Color fg = UIManager.getColor("Panel.foreground");
                g.setColor(SideTabs.mix(strip, fg == null ? Color.BLACK : fg, 0.45));
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    boolean horizontalSplit = orientation == javax.swing.JSplitPane.HORIZONTAL_SPLIT;
                    int dot = Math.max(2, Math.min(getWidth(), getHeight()) / 3);
                    int step = dot * 2;
                    int length = DOTS * step - dot;
                    int x = horizontalSplit ? (getWidth() - dot) / 2 : (getWidth() - length) / 2;
                    int y = horizontalSplit ? (getHeight() - length) / 2 : (getHeight() - dot) / 2;
                    for (int i = 0; i < DOTS; ++i) {
                        g2.fillOval(x, y, dot, dot);
                        if (horizontalSplit)
                            y += step;
                        else
                            x += step;
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
    }
}

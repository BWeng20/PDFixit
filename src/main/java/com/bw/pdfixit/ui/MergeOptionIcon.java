package com.bw.pdfixit.ui;

import com.bw.pdfixit.model.MergeOptions;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

public final class MergeOptionIcon implements Icon {

    private final int w;
    private final int h;
    private MergeOptions options;

    private final int pageWidth;
    private final int pageHeight;
    private final int gap = 2;

    public MergeOptionIcon(Component c) {
        FontMetrics fm = c.getFontMetrics(c.getFont());
        pageWidth = 4 + fm.stringWidth("…00");
        pageHeight = fm.getHeight() + 4;
        w = (pageWidth + gap) * 7 - gap;
        h = pageHeight * 2 + gap;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {

        Graphics2D g2 = (Graphics2D) g;
        g2.setPaint(c.getBackground());
        g2.fillRect(x, y, w, h);
        g2.setFont(c.getFont());

        if (options != null) {

            int yMerged = y + pageHeight + gap;

            int pw = pageWidth + gap;
            Color color = c.getForeground();
            Color colorMerged = UIManager.getColor("List.selectionForeground");
            Color colorMergedBk = UIManager.getColor("List.selectionBackground");

            FontMetrics fm = g2.getFontMetrics();
            int textY = fm.getAscent() + (pageHeight - fm.getHeight()) / 2;

            int ellipseW = fm.stringWidth("…");

            int[] pgNbs = new int[]{1, 1};
            int[] xpos = new int[]{x, x};

            Runnable drawOrgPage = () -> {
                g2.setPaint(color);
                g2.drawRect(xpos[0], y, pageWidth, pageHeight);
                String txt = Integer.toString(pgNbs[0]++);
                g2.drawString(txt, xpos[0] + (pageWidth - fm.stringWidth(txt)) / 2, y + textY);
                xpos[0] += pw;
            };

            Runnable drawMergePage = () -> {
                g2.setPaint(colorMergedBk);
                g2.fillRect(xpos[1], yMerged, pageWidth, pageHeight);
                g2.setPaint(colorMerged);
                String txt = Integer.toString(pgNbs[1]++);
                g2.drawString(txt, xpos[1] + (pageWidth - fm.stringWidth(txt)) / 2, yMerged + textY);
                xpos[1] += pw;
            };

            // Draw prefix (original pages before first inserted page, possibly empty)
            if (options.startPageNb > 1) {
                drawOrgPage.run();
                if (options.startPageNb > 2) {
                    if (options.startPageNb > 3) {
                        g2.drawString("…", xpos[0], y + textY);
                        xpos[0] += ellipseW;
                    }
                    pgNbs[0] = options.startPageNb;
                    if (options.segmentLength > 0)
                        pgNbs[0]--;
                    drawOrgPage.run();
                }
                xpos[1] = xpos[0] - pw / 2 + gap;
            } else {
                xpos[0] += pw / 2;
            }
            // Draw sequence of inserted and original pages if mixing is enabled.
            if (options.segmentLength > 0) {
                while (xpos[0] < w || xpos[1] < w) {

                    // Draw inserted segment (1 to n)
                    drawMergePage.run();
                    if (options.segmentLength > 1) {
                        if (options.segmentLength > 2) {
                            g2.setPaint(colorMergedBk);
                            g2.drawString("…", xpos[1], yMerged + textY);
                            xpos[0] += ellipseW;
                            xpos[1] += ellipseW;
                            pgNbs[1] += options.segmentLength - 2;
                        }
                        drawMergePage.run();
                        xpos[0] += pw;
                    }

                    if (options.gapLength > 0) {
                        xpos[0] += gap;
                        xpos[1] += gap;
                        // Draw gab before next segment (1 to n)
                        drawOrgPage.run();
                        if (options.gapLength > 1) {
                            if (options.gapLength > 2) {
                                g2.drawString("…", xpos[0], y + textY);
                                xpos[0] += ellipseW;
                                xpos[1] += ellipseW;
                                pgNbs[0] += options.gapLength - 2;
                            }
                            drawOrgPage.run();
                            xpos[1] += pw;
                        }
                    } else {
                        // Should not happen.
                        // Abort to prevent endless loop.
                        break;
                    }
                }
            } else if (options.segmentLength < 0) {
                // Simple append. Draw some "1..n" at the end.
                drawMergePage.run();
                g2.setPaint(colorMergedBk);
                g2.drawString("…", xpos[1], yMerged + textY);
                xpos[1] += ellipseW;
                g2.setPaint(colorMergedBk);
                g2.fillRect(xpos[1], yMerged, pageWidth, pageHeight);
                g2.setPaint(colorMerged);
                g2.drawString("∞", xpos[1] + (pageWidth - fm.stringWidth("∞")) / 2, yMerged + textY);
            }
        }
    }


    public void setMergeOptions(MergeOptions options) {
        this.options = options;
    }

    @Override
    public int getIconWidth() {
        return w;
    }

    @Override
    public int getIconHeight() {
        return h;
    }
}

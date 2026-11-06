package com.bw.pdfixit.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;

/**
 * Vertical strip beside the page preview. On top the page counter between the small buttons for the previous and the
 * next page (they only select, like a spinner), then the round buttons: rotate, rotate all, move up, move down
 * (a page with an arrow: they change the order of the pages), images, redact, delete. At the bottom the zoom of the
 * pages: there are no controls above or below the pages, they keep the full height.
 * It has the background of the strip of the {@link SideTabs}, like the {@link ActionBar}: the controls around the
 * content are one family. The controls and what they do belong to the {@link UI}.
 */
final class PageToolsStrip extends JPanel {

    PageToolsStrip(UI ui) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
        // The buttons have room around them to grow on hover, so they have a gap already. Only the groups get more.
        int groupGap = ui.rotateClockwiseButton.getPreferredSize().height * 2 / 5;

        add(ui.previousPageButton);
        add(ui.pageCounter);
        add(ui.nextPageButton);
        // The little page for the rotation belongs to the rotate buttons: small gap.
        add(ui.pageOrientation);
        add(Box.createVerticalStrut(groupGap / 5));
        add(ui.rotateClockwiseButton);
        add(ui.rotateAllClockwiseButton);
        add(Box.createVerticalStrut(groupGap));
        add(ui.moveUp);
        add(ui.moveDown);
        add(Box.createVerticalStrut(groupGap));
        add(ui.imagesButton);
        add(ui.redactButton);
        // Delete is destructive, it is apart from the others.
        add(Box.createVerticalStrut(groupGap));
        add(ui.deleteButton);
        add(Box.createVerticalGlue());
        // The zoom of the pages at the bottom, apart from the tools that change pages: bigger, the percent, smaller
        // (like the page counter between its buttons), then fit to the width.
        add(ui.zoomInButton);
        add(ui.zoomLabel);
        add(ui.zoomOutButton);
        add(Box.createVerticalStrut(groupGap / 5));
        add(ui.zoomFitButton);
        add(Box.createVerticalStrut(groupGap / 5));

        for (Component c : getComponents())
            if (c instanceof RoundSvgButton || c instanceof PageCounter || c instanceof PageOrientation
                    || c == ui.zoomLabel)
                ((JComponent) c).setAlignmentX(Component.CENTER_ALIGNMENT);
        setOpaque(true);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of the panel, too.
        setBackground(SideTabs.stripBackground());
    }
}

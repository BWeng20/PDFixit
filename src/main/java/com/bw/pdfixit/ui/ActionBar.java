package com.bw.pdfixit.ui;

import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The bottom line below the side tabs and the drawer: open and add a document on the left, save on the right.
 * It has the background of the strip of the {@link SideTabs}, so both look like one frame around the drawer. It is always in reach. The controls and what they do belong to the {@link UI}.
 */
final class ActionBar extends JPanel {

    ActionBar(UI ui) {
        super(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.fill = GridBagConstraints.VERTICAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(5, 5, 5, 0);

        gc.gridx = 0;
        add(ui.browseButton, gc);
        gc.gridx = 1;
        gc.insets = new Insets(5, 5, 5, 5);
        add(ui.browseAppendButton, gc);

        // The space between: the save button stays at the right edge.
        gc.gridx = 2;
        gc.weightx = 1;
        add(javax.swing.Box.createGlue(), gc);

        gc.gridx = 3;
        gc.weightx = 0;
        gc.anchor = GridBagConstraints.EAST;
        add(ui.saveButton, gc);
        setOpaque(true);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of the panel, too.
        setBackground(SideTabs.stripBackground());
    }
}

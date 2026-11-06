package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;

import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Layout of splitting the document into parts. The controls and what they do belong to the {@link UI}.
 */
final class SplitPanel extends JPanel {

    SplitPanel(UI ui) {
        super(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.weighty = 0;

        JLabel pagesLabel = new JLabel(I18n.t("ui.pageCount"));
        pagesLabel.setLabelFor(ui.pagesPerDocument);
        add(pagesLabel, gc);
        gc.gridx++;
        add(ui.pagesPerDocument, gc);

        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1;
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        add(Box.createGlue(), gc);

        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        gc.gridy++;
        add(ui.splitDocument, gc);

        setBorder(BoxBorder.create(I18n.t("ui.border.splitting")));
    }
}

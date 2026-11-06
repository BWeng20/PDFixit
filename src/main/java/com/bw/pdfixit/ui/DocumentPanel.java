package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;

import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Layout of the information of the document (title, author, ...) with the choice where it comes from. The settings of
 * the file (standard, compression) are in the {@link StandardPanel}.
 * The controls and what they do belong to the {@link UI}.
 */
final class DocumentPanel extends JPanel {

    DocumentPanel(UI ui) {
        super(new GridBagLayout());
        setBorder(BoxBorder.create(I18n.t("ui.border.document")));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        gc.gridheight = 1;
        gc.weightx = 0;
        gc.weighty = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;

        // Directly in the grid, not in a row panel: it starts where the labels and the buttons below start.
        add(ui.useDocumentInformation, gc);

        gc.gridy++;
        gc.gridwidth = 1;
        field(gc, I18n.t("info.title"), ui.title);
        gc.gridy++;
        field(gc, I18n.t("info.author"), ui.author);
        gc.gridy++;
        field(gc, I18n.t("info.creator"), ui.creator);
        gc.gridy++;
        field(gc, I18n.t("info.producer"), ui.producer);
        gc.gridy++;
        field(gc, I18n.t("info.subject"), ui.subject);
        gc.gridy++;
        field(gc, I18n.t("info.keywords"), ui.keywords);

        // Actions for the information: bottom left, below the fields.
        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 3;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;
        JPanel infoButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        infoButtons.setOpaque(false);
        infoButtons.add(ui.clearInformation);
        infoButtons.add(Box.createHorizontalStrut(5));
        infoButtons.add(ui.exportXmp);
        add(infoButtons, gc);
    }

    /**
     * Adds a label in the first column and its field over the other two, in the row of {@code gc}.
     */
    private void field(GridBagConstraints gc, String label, JTextField field) {
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        gc.gridx = 0;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        add(l, gc);
        gc.gridx = 1;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        add(field, gc);
    }
}

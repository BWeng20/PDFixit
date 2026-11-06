package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;

import javax.swing.Box;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Layout of the settings of the PDF file: the PDF standard of the document with its check, and the compression
 * (which depends on the standard). The information of the document (title, author, ...) is in the {@link DocumentPanel}.
 * The controls and what they do belong to the {@link UI}.
 */
final class StandardPanel extends JPanel {

    StandardPanel(UI ui) {
        super(new GridBagLayout());
        setBorder(BoxBorder.create(I18n.t("ui.border.pdfSettings")));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;

        // The PDF standard the document declares, and the check if it keeps it. (The page count is beside the pages, see PageCounter.)
        JPanel standardRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        standardRow.setOpaque(false);
        standardRow.add(ui.standardButton);
        standardRow.add(Box.createHorizontalStrut(10));
        standardRow.add(ui.pdfStandard);
        standardRow.add(Box.createHorizontalStrut(10));
        standardRow.add(ui.validatePdfaButton);
        add(standardRow, gc);

        // How the file is written. It depends on the standard (PDF/A-1 forbids compression), so it is below it.
        gc.gridy = 1;
        add(ui.compression, gc);
    }
}

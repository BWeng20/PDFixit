package com.bw.pdfixit.ui;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Layout of the e-invoice of the document, in two boxes (see {@link com.bw.pdfixit.ui.invoice.InvoiceEditorPanel}): the
 * invoice in the document, with the status line (the result of the automatic check of its invoice), and the editor to
 * create or edit the invoice. The controls and what they do belong to the {@link UI} and the {@link InvoiceActions}.
 */
final class InvoicePanel extends JPanel {

    InvoicePanel(UI ui) {
        super(new BorderLayout());

        // The status line of the invoice in the document: it takes the row, the text may be long.
        JPanel status = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        status.add(ui.invoiceStatus, gc);

        gc.gridx = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.insets = new Insets(0, 5, 0, 0);
        status.add(ui.invoiceDetailsButton, gc);

        ui.invoiceEditor.setHeader(status);
        // The help is in the box of the invoice in the document, like the help of the signing is in its box.
        ui.invoiceEditor.setHelpBadge(ui.invoiceHelp);
        add(ui.invoiceEditor, BorderLayout.CENTER);
    }
}

package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Layout of signing. The controls and what they do belong to the {@link UI}.
 */
final class SigningPanel extends JPanel {

    SigningPanel(UI ui) {
        super(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.weighty = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.NORTHWEST;

        // The checkbox and the button keep their size, they don't grow with the box.
        JPanel signButtons = new JPanel(new GridBagLayout());
        signButtons.add(ui.sign, gc);
        gc.gridx = 1;
        signButtons.add(ui.browseSignature, gc);

        // The components of the row have the left inset already: not twice, so the checkbox starts where the labels
        // below start.
        gc.gridwidth = 2;
        gc.gridx = 0;
        gc.insets = new Insets(0, 0, 5, 0);
        add(signButtons, gc);
        gc.insets = new Insets(0, 5, 5, 5);

        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        gc.gridy = 1;
        gc.gridwidth = 1;
        add(new JLabel(I18n.t("ui.keyFile")), gc);
        gc.gridy++;
        JLabel label = new JLabel(I18n.t("ui.password"));
        label.setLabelFor(ui.signaturePasswordField);
        add(label, gc);

        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        gc.gridy = 1;
        gc.gridx = 1;
        add(ui.signatureKeyFileName, gc);
        gc.gridy++;
        add(ui.signaturePasswordField, gc);

        setBorder(BoxBorder.create(I18n.t("ui.border.signing")));
    }
}

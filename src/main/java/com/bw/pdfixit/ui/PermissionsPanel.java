package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;

/**
 * Layout of passwords and permissions. The controls and what they do belong to the {@link UI}.
 * (The compression of the file is not a permission, it is in the {@link StandardPanel}.)
 */
final class PermissionsPanel extends JPanel {

    PermissionsPanel(UI ui) {
        super(new GridBagLayout());

        JLabel ownerPasswordLabel = new JLabel(I18n.t("ui.ownerPassword"));
        ownerPasswordLabel.setLabelFor(ui.ownerPasswordField);

        JLabel userPasswordLabel = new JLabel(I18n.t("ui.userPassword"));
        userPasswordLabel.setLabelFor(ui.userPasswordField);

        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST;

        // Why passwords and permissions can't be set (PDF/A forbids encryption), or what to do to enable the permissions.
        // On top: it is about everything below.
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.insets = new Insets(0, 5, 8, 5);
        gc.anchor = GridBagConstraints.NORTHWEST;
        add(ui.permissionsHint, gc);

        // Owner password: the label is right above its field,
        // the generate button is beside the field, like a browse button beside a path.
        gc.gridy = 1;
        gc.insets = new Insets(0, 5, 1, 5);
        gc.anchor = GridBagConstraints.SOUTHWEST;
        add(ownerPasswordLabel, gc);
        gc.gridwidth = 1;

        gc.gridx = 0;
        gc.gridy = 2;
        gc.weightx = 1;
        gc.insets = new Insets(0, 5, 5, 0);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        add(ui.ownerPasswordField, gc);
        // 3 pixels beside the field, as high as the field.
        gc.gridx = 1;
        gc.weightx = 0;
        gc.insets = new Insets(0, 3, 5, 5);
        gc.fill = GridBagConstraints.VERTICAL;
        add(ui.generateOwner, gc);

        // User password: more space above, so the label belongs to its own field.
        gc.gridx = 0;
        gc.gridy = 3;
        gc.weightx = 1;
        gc.insets = new Insets(12, 5, 1, 5);
        gc.anchor = GridBagConstraints.SOUTHWEST;
        gc.fill = GridBagConstraints.NONE;
        add(userPasswordLabel, gc);
        gc.gridy = 4;
        gc.insets = new Insets(0, 5, 5, 0);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        add(ui.userPasswordField, gc);

        // The permissions: two columns, "Fill Form" is below "Annotations" (it is not possible with annotations).
        JPanel permissions = new JPanel(new GridLayout(3, 2));
        permissions.add(ui.allowPrinting);
        permissions.add(ui.allowAnnotations);
        permissions.add(ui.allowModification);
        permissions.add(ui.allowFillIn);
        permissions.add(ui.allowExtraction);
        permissions.add(ui.allowAssembly);

        gc.gridy = 5;
        gc.gridwidth = 2;
        gc.insets = new Insets(10, 5, 5, 5);
        gc.anchor = GridBagConstraints.NORTHWEST;
        add(permissions, gc);

        setBorder(BoxBorder.create(I18n.t("ui.border.permissions")));
    }
}

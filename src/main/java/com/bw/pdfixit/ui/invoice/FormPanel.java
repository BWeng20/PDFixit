package com.bw.pdfixit.ui.invoice;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * A form with captions and fields. A row has one field over the full width or two caption/field pairs.
 */
class FormPanel extends JPanel {
    private int row;
    /**
     * Takes the free height below the last row, otherwise the layout centers the rows vertically.
     */
    private final Component filler = Box.createGlue();

    FormPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }

    void row(String caption, JComponent field) {
        row(caption, field, null, null);
    }

    /**
     * A row whose field keeps its natural width instead of filling the row, e.g. for a date.
     */
    void compactRow(String caption, JComponent field) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row++;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(3, 0, 3, 10);
        add(new JLabel(caption), gc);
        gc.gridx = 1;
        gc.gridwidth = 4;
        gc.weightx = 1;
        gc.insets = new Insets(3, 0, 3, 0);
        // The cell takes the free width, the field keeps its size at the left border.
        gc.fill = GridBagConstraints.NONE;
        add(field, gc);
        placeFiller();
    }

    void row(String caption, JComponent field, String caption2, JComponent field2) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row++;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(3, 0, 3, 10);
        gc.gridx = 0;
        add(new JLabel(caption), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        if (field2 == null) {
            gc.gridwidth = 4;
            gc.insets = new Insets(3, 0, 3, 0);
        }
        else
            gc.insets = new Insets(3, 0, 3, 18);
        add(field, gc);
        if (field2 != null) {
            gc.gridwidth = 1;
            gc.gridx = 2;
            gc.fill = GridBagConstraints.NONE;
            gc.weightx = 0;
            gc.insets = new Insets(3, 0, 3, 10);
            add(new JLabel(caption2), gc);
            gc.gridx = 3;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
            gc.insets = new Insets(3, 0, 3, 0);
            add(field2, gc);
        }
        placeFiller();
    }

    /**
     * Moves the filler below the last row.
     */
    private void placeFiller() {
        remove(filler);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row;
        gc.weighty = 1;
        gc.fill = GridBagConstraints.VERTICAL;
        add(filler, gc);
    }

    /**
     * A bold title with a line, to start a group of rows.
     */
    void section(String title) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row++;
        gc.gridx = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(10, 0, 3, 10);
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        add(l, gc);
        gc.gridx = 1;
        gc.gridwidth = 3;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(10, 0, 3, 0);
        add(new JSeparator(), gc);
        placeFiller();
    }
}

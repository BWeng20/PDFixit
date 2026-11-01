package com.bw.pdfixit.ui.invoice;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Insets;
import java.math.BigDecimal;

/**
 * Marks the fields of the invoice editors: errors and values that differ from the original.<br>
 * Fields get the outline of the look and feel (FlatLaf), table cells a border in the same color.
 */
final class Outline {

    private static final String PROPERTY = "JComponent.outline";

    private Outline() {
    }

    /**
     * Sets the outline of a field. An error is shown before a modified value.
     */
    static void apply(JComponent field, boolean error, boolean modified) {
        field.putClientProperty(PROPERTY, error ? "error" : modified ? "warning" : null);
    }

    /**
     * Color of the border of a modified table cell, the same the "warning" outline of the look and feel has.
     */
    static Color modifiedColor() {
        Color c = UIManager.getColor("Component.warning.focusedBorderColor");
        return c != null ? c : new Color(0xe2a53a);
    }

    /**
     * Border for a modified table cell: a line inside the space of the normal border, so the cell keeps its size.
     */
    static Border cellBorder(Border base, Component cell) {
        Insets in = base == null ? new Insets(0, 0, 0, 0) : base.getBorderInsets(cell);
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(modifiedColor()),
                BorderFactory.createEmptyBorder(Math.max(0, in.top - 1), Math.max(0, in.left - 1),
                        Math.max(0, in.bottom - 1), Math.max(0, in.right - 1)));
    }

    /**
     * Compares two values of a field: null and blank text are the same, text is compared without surrounding blanks,
     * numbers by value.
     */
    static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y)
            return x.compareTo(y) == 0;
        return normalize(a) == null ? normalize(b) == null : normalize(a).equals(normalize(b));
    }

    private static Object normalize(Object o) {
        if (o instanceof String s)
            return s.isBlank() ? null : s.trim();
        return o;
    }
}

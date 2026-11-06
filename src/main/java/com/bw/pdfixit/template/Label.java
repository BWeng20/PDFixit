package com.bw.pdfixit.template;

/**
 * The label of a field in the layout, e.g. "Rechnungsnummer" in front of the invoice number.<br>
 * A label is an element of its own: it can be deleted, get another text, and be moved. A field without a
 * {@code Label} object uses the defaults (see {@link Element#isLabelShown()}).
 */
public class Label {

    /**
     * Where the label of a flowing field stands relative to its value.
     */
    public enum Position {
        /**
         * In front of the value, on the same line (the default).
         */
        LEFT,
        /**
         * On a line of its own above the value.
         */
        ABOVE
    }

    /**
     * False if the label was deleted: the field prints its value alone.
     */
    public boolean shown = true;

    /**
     * The text, null for the default text of the field in the printed language.
     */
    public String text;

    /**
     * The rectangle of a label that was moved, for a fixed field only. Null: the label stands at the default place.
     */
    public Rect rect;

    /**
     * The position of the label of a flowing field, null for the default ({@link Position#LEFT}).
     */
    public Position position;

    /**
     * Horizontal offset of the label of a flowing field in mm.
     */
    public double dx;

    public Label() {
    }

    public Label(boolean shown) {
        this.shown = shown;
    }

    /**
     * True if only {@link #shown} and maybe the text differ from the defaults.
     */
    boolean isSimple() {
        return rect == null && position == null && dx == 0;
    }
}

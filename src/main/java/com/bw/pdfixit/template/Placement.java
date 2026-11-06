package com.bw.pdfixit.template;

import java.awt.geom.Rectangle2D;

/**
 * Where the engine drew a part of an element: the label or the value (text, picture) of a fixed element or of a
 * flowing part, on one page. Used by the layout editor to show and move what was drawn.
 *
 * @param fixed True for an element of {@link Layout#fields}, false for one of {@link Layout#parts}.
 * @param index The index of the element in that list.
 * @param label True for the label (for the table the header row, for the totals the captions), false for the value.
 * @param page  The page of the invoice (1-based).
 * @param area  The area in points from the top left corner of the displayed page: the boxes of the glyphs, united.
 */
public record Placement(boolean fixed, int index, boolean label, int page, Rectangle2D area) {
}

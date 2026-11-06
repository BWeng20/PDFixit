package com.bw.pdfixit.template;

/**
 * A body region: where the engine lays out the flowing parts of the invoice.
 *
 * @param next False for the first page, true for every following page.
 * @param rect The region in mm.
 */
public record Region(boolean next, Rect rect) {
}

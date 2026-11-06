package com.bw.pdfixit.template;

import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.geom.Rectangle2D;

/**
 * A rectangle of the layout in millimetres from the top left corner of the page, as in DIN 5008.
 *
 * @param x      Distance of the left edge from the left edge of the page.
 * @param y      Distance of the upper edge from the upper edge of the page.
 * @param width  The width.
 * @param height The height.
 */
public record Rect(double x, double y, double width, double height) {

    /**
     * Points (1/72 inch) per millimetre.
     */
    public static final double PT_PER_MM = 72 / 25.4;

    public static double toPoints(double mm) {
        return mm * PT_PER_MM;
    }

    public static double toMm(double points) {
        return points / PT_PER_MM;
    }

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    /**
     * The rectangle in points, still from the top left corner of the page.
     */
    public Rectangle2D toPoints() {
        return new Rectangle2D.Double(toPoints(x), toPoints(y), toPoints(width), toPoints(height));
    }

    /**
     * The rectangle in PDF coordinates (points, origin in the lower left corner).
     *
     * @param page The box of the page the rectangle is on (its lower left corner may not be 0, 0).
     */
    public PDRectangle toPdf(PDRectangle page) {
        float w = (float) toPoints(width);
        float h = (float) toPoints(height);
        return new PDRectangle(page.getLowerLeftX() + (float) toPoints(x),
                page.getUpperRightY() - (float) toPoints(y) - h, w, h);
    }

    /**
     * Checks if the rectangle lies inside a page of the given size (in mm). A tenth of a millimetre is tolerated for
     * rounding.
     */
    public boolean isInside(double pageWidth, double pageHeight) {
        final double tolerance = 0.1;
        return x >= -tolerance && y >= -tolerance && right() <= pageWidth + tolerance
                && bottom() <= pageHeight + tolerance;
    }
}

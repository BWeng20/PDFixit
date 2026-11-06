package com.bw.pdfixit.ui;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * A rectangle that is rotated around its center, in points of the displayed page. It is what the user draws in the
 * {@link RedactionDialog}. All editing is done here, so it can be tested without a window.<br>
 * The rectangle may reach beyond the page.
 *
 * @param rect  The rectangle before the rotation.
 * @param angle Rotation around the center of the rectangle in radians, clockwise (the y axis points down).
 */
public record ViewArea(Rectangle2D rect, double angle) {

    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int TOP = 4;
    static final int BOTTOM = 8;

    /**
     * Angles snap to multiples of this, if requested.
     */
    static final double SNAP = Math.toRadians(15);

    public ViewArea(Rectangle2D rect) {
        this(rect, 0);
    }

    Point2D center() {
        return new Point2D.Double(rect.getCenterX(), rect.getCenterY());
    }

    /**
     * @return The rectangle with the rotation applied.
     */
    public Shape shape() {
        return angle == 0 ? rect : AffineTransform.getRotateInstance(angle, rect.getCenterX(), rect.getCenterY())
                .createTransformedShape(rect);
    }

    /**
     * Transforms a point to the system of the unrotated rectangle.
     */
    Point2D toLocal(Point2D p) {
        return AffineTransform.getRotateInstance(-angle, rect.getCenterX(), rect.getCenterY()).transform(p, null);
    }

    /**
     * Inverse of {@link #toLocal}.
     */
    Point2D toWorld(Point2D local) {
        return AffineTransform.getRotateInstance(angle, rect.getCenterX(), rect.getCenterY()).transform(local, null);
    }

    boolean contains(Point2D p) {
        return rect.contains(toLocal(p));
    }

    ViewArea moved(double dx, double dy) {
        return new ViewArea(new Rectangle2D.Double(rect.getX() + dx, rect.getY() + dy, rect.getWidth(), rect.getHeight()), angle);
    }

    /**
     * Which edges are grabbed at a position.
     *
     * @param p         The position.
     * @param tolerance Distance to an edge that grabs it, in points.
     * @return Bit mask of LEFT, RIGHT, TOP, BOTTOM or 0.
     */
    int handleAt(Point2D p, double tolerance) {
        Point2D l = toLocal(p);
        if (l.getX() < rect.getMinX() - tolerance || l.getX() > rect.getMaxX() + tolerance
                || l.getY() < rect.getMinY() - tolerance || l.getY() > rect.getMaxY() + tolerance)
            return 0;
        int mask = 0;
        if (Math.abs(l.getX() - rect.getMinX()) <= tolerance)
            mask |= LEFT;
        else if (Math.abs(l.getX() - rect.getMaxX()) <= tolerance)
            mask |= RIGHT;
        if (Math.abs(l.getY() - rect.getMinY()) <= tolerance)
            mask |= TOP;
        else if (Math.abs(l.getY() - rect.getMaxY()) <= tolerance)
            mask |= BOTTOM;
        return mask;
    }

    /**
     * The position of the rotate handle: above the middle of the upper edge, in the system of the unrotated rectangle.
     *
     * @param distance Distance to the upper edge in points.
     */
    Point2D rotateHandle(double distance) {
        return new Point2D.Double(rect.getCenterX(), rect.getMinY() - distance);
    }

    boolean onRotateHandle(Point2D p, double distance, double tolerance) {
        return toLocal(p).distance(rotateHandle(distance)) <= tolerance;
    }

    /**
     * Moves the grabbed edges to the position. The edges the handle doesn't hold keep their place on the page,
     * also if the rectangle is rotated. The edges may cross, the rectangle is normalized.
     */
    ViewArea resized(int handle, Point2D mouse) {
        Point2D l = toLocal(mouse);
        double x1 = rect.getMinX(), x2 = rect.getMaxX();
        double y1 = rect.getMinY(), y2 = rect.getMaxY();
        if ((handle & LEFT) != 0)
            x1 = l.getX();
        if ((handle & RIGHT) != 0)
            x2 = l.getX();
        if ((handle & TOP) != 0)
            y1 = l.getY();
        if ((handle & BOTTOM) != 0)
            y2 = l.getY();
        Rectangle2D r = new Rectangle2D.Double();
        r.setFrameFromDiagonal(x1, y1, x2, y2);
        // The new center in the old system moves with the rotation, the rectangle is rotated around its own center.
        Point2D center = toWorld(new Point2D.Double(r.getCenterX(), r.getCenterY()));
        return new ViewArea(new Rectangle2D.Double(center.getX() - r.getWidth() / 2, center.getY() - r.getHeight() / 2,
                r.getWidth(), r.getHeight()), angle);
    }

    /**
     * Rotates so that the rotate handle points to the position.
     *
     * @param snap If true, the angle is a multiple of {@link #SNAP}.
     */
    ViewArea rotatedTo(Point2D mouse, boolean snap) {
        double a = Math.atan2(mouse.getY() - rect.getCenterY(), mouse.getX() - rect.getCenterX()) + Math.PI / 2;
        if (snap)
            a = Math.round(a / SNAP) * SNAP;
        // Between -180 and 180 degrees.
        a = Math.atan2(Math.sin(a), Math.cos(a));
        return new ViewArea(rect, a);
    }
}

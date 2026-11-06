package com.bw.pdfixit.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows a page image and lets the user draw, select, move, resize and rotate rectangles.
 * Areas are reported in points of the displayed page.
 */
public class AreaCanvas extends JComponent implements Scrollable {

    /**
     * Notifications of the canvas. Areas are in points of the displayed page,
     * indices refer to the list given to {@link AreaCanvas#setAreas}.
     */
    public interface AreaListener {
        void added(ViewArea area);

        void changed(int index, ViewArea area);

        void removed(int index);

        void selectionChanged();
    }


    private static final int LEFT = ViewArea.LEFT;
    private static final int RIGHT = ViewArea.RIGHT;
    private static final int TOP = ViewArea.TOP;
    private static final int BOTTOM = ViewArea.BOTTOM;

    /**
     * Distance in pixels to an edge that grabs the edge.
     */
    private static final int GRAB = 5;
    private static final int HANDLE_SIZE = 6;
    /**
     * Distance in pixels of the rotate handle to the upper edge.
     */
    private static final int ROTATE_DISTANCE = 22;
    private static final int ROTATE_RADIUS = 5;

    private enum Mode {NONE, CREATE, MOVE, RESIZE, ROTATE, PAN}

    private BufferedImage image;
    /**
     * Width of the page on the screen in pixels, 0 if the page is fitted to the component.
     */
    private int displayWidth;
    private Color color = Color.BLACK;
    private boolean antialiasing;
    private final Color selectColor = Color.RED;
    private final BasicStroke stroke = new BasicStroke(2f);
    private double pixelsPerPoint = 1;
    private final List<ViewArea> areas = new ArrayList<>();
    private int selected = -1;
    /**
     * False to offer no rotate handle: areas stay upright.
     */
    private boolean rotationEnabled = true;
    private AreaListener areaListener;

    private Mode mode = Mode.NONE;
    private ViewArea dragOriginal;
    private Point2D dragStartPoint;
    private int dragHandle;
    private boolean dragChanged;

    /**
     * Rubber band for new areas in component coordinates.
     */
    private Point dragStart;
    private Point dragEnd;

    /**
     * True while the Space key is held: dragging with the left mouse button moves the page, like in image editors.
     */
    private boolean spacePan;
    /**
     * While the page is moved: where the mouse was on the screen, and the view position then.
     */
    private Point panStart;
    private Point panOrigin;

    public AreaCanvas() {
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                // Space and the arrow keys work when the mouse is over the page, but not while the user types.
                Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (!(owner instanceof javax.swing.text.JTextComponent))
                    requestFocusInWindow();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (image == null)
                    return;

                // The page is moved with the right (or middle) mouse button, or with Space and the left one.
                if (spacePan || !SwingUtilities.isLeftMouseButton(e)) {
                    startPan(e);
                    return;
                }
                if (rotationEnabled && selected >= 0 && areas.get(selected).onRotateHandle(toPoints(e.getPoint()), rotateDistance(), rotateTolerance())) {
                    startDrag(Mode.ROTATE, e.getPoint(), selected, 0);
                    return;
                }
                int handle = selected >= 0 && isResizable(selected)
                        ? areas.get(selected).handleAt(toPoints(e.getPoint()), grabTolerance()) : 0;
                if (handle != 0) {
                    startDrag(Mode.RESIZE, e.getPoint(), selected, handle);
                    return;
                }
                int hit = areaAt(e.getPoint());
                if (hit >= 0) {
                    select(hit);
                    startDrag(Mode.MOVE, e.getPoint(), hit, 0);
                } else {
                    select(-1);
                    mode = Mode.CREATE;
                    dragStart = e.getPoint();
                    dragEnd = e.getPoint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                switch (mode) {
                    case CREATE -> dragEnd = e.getPoint();
                    case MOVE -> move(e.getPoint());
                    case RESIZE -> resize(e.getPoint());
                    case ROTATE -> rotate(e.getPoint(), e.isShiftDown());
                    case PAN -> pan(e);
                    default -> {
                    }
                }
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (mode == Mode.CREATE && image != null && areaListener != null) {
                    Rectangle2D r = new Rectangle2D.Double();
                    // The area may reach beyond the page.
                    r.setFrameFromDiagonal(toPoints(dragStart), toPoints(e.getPoint()));
                    // Ignore accidental clicks.
                    if (r.getWidth() * getScale() * pixelsPerPoint >= 3
                            && r.getHeight() * getScale() * pixelsPerPoint >= 3) {
                        areaListener.added(new ViewArea(r));
                    }
                } else if ((mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.ROTATE) && dragChanged && areaListener != null) {
                    areaListener.changed(selected, areas.get(selected));
                }
                mode = Mode.NONE;
                dragStart = null;
                dragEnd = null;
                dragOriginal = null;
                dragChanged = false;
                repaint();
                updateCursor(e.getPoint());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateCursor(e.getPoint());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);

        Action delete = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (selected >= 0 && areaListener != null)
                    areaListener.removed(selected);
            }
        };
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteArea");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteArea");
        getActionMap().put("deleteArea", delete);

        // The arrow keys move the page (Shift: further). Space makes the left mouse button move it.
        bindScroll(KeyEvent.VK_LEFT, -1, 0);
        bindScroll(KeyEvent.VK_RIGHT, 1, 0);
        bindScroll(KeyEvent.VK_UP, 0, -1);
        bindScroll(KeyEvent.VK_DOWN, 0, 1);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "panOn");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "panOff");
        getActionMap().put("panOn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setSpacePan(true);
            }
        });
        getActionMap().put("panOff", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setSpacePan(false);
            }
        });
        // Space is not held any more if the focus goes elsewhere.
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                setSpacePan(false);
            }
        });
    }

    private void bindScroll(int key, int dx, int dy) {
        for (int step : new int[]{48, 192}) {
            String name = "scroll" + key + "_" + step;
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, step == 48 ? 0 : InputEvent.SHIFT_DOWN_MASK), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    scrollBy(dx * step, dy * step);
                }
            });
        }
    }

    private void setSpacePan(boolean on) {
        spacePan = on;
        if (mode == Mode.NONE)
            setCursor(Cursor.getPredefinedCursor(on ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
    }

    /**
     * Moves the page in the scroll pane, as far as it can go.
     */
    private void scrollBy(int dx, int dy) {
        if (getParent() instanceof JViewport viewport) {
            Point position = viewport.getViewPosition();
            setViewPosition(viewport, position.x + dx, position.y + dy);
        }
    }

    private void setViewPosition(JViewport viewport, int x, int y) {
        viewport.setViewPosition(new Point(
                Math.max(0, Math.min(x, getWidth() - viewport.getWidth())),
                Math.max(0, Math.min(y, getHeight() - viewport.getHeight()))));
    }

    private void startPan(MouseEvent e) {
        if (getParent() instanceof JViewport viewport) {
            mode = Mode.PAN;
            panStart = e.getLocationOnScreen();
            panOrigin = viewport.getViewPosition();
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
    }

    /**
     * The page follows the mouse. The position on the screen is used, the component moves while it is dragged.
     */
    private void pan(MouseEvent e) {
        if (panStart != null && getParent() instanceof JViewport viewport) {
            Point now = e.getLocationOnScreen();
            setViewPosition(viewport, panOrigin.x - (now.x - panStart.x), panOrigin.y - (now.y - panStart.y));
        }
    }

    /**
     * @param enabled False to offer no rotate handle (default true).
     */
    public void setRotationEnabled(boolean enabled) {
        this.rotationEnabled = enabled;
        repaint();
    }

    /**
     * Whether an area can be resized with its handles. All can by default; a subclass may keep some at their size
     * (they can still be moved).
     */
    protected boolean isResizable(int index) {
        return true;
    }

    public void setColor(Color color) {
        this.color = color;
        repaint();
    }

    public void setAntialiasing(boolean antialiasing) {
        this.antialiasing = antialiasing;
        repaint();
    }

    /**
     * Image pixels per point of the page.
     */
    public double getPixelsPerPoint() {
        return pixelsPerPoint;
    }

    public void setImage(BufferedImage image, double pixelsPerPoint) {
        this.image = image;
        this.pixelsPerPoint = pixelsPerPoint;
        repaint();
    }

    /**
     * Sets the areas. The selection is kept if possible.
     */
    public void setAreas(List<ViewArea> newAreas) {
        areas.clear();
        areas.addAll(newAreas);
        if (selected >= areas.size())
            select(-1);
        repaint();
    }

    public int getAreaCount() {
        return areas.size();
    }

    public int getSelected() {
        return selected;
    }

    public void select(int index) {
        if (index >= areas.size())
            index = -1;
        if (index != selected) {
            selected = index;
            repaint();
            if (areaListener != null)
                areaListener.selectionChanged();
        }
    }

    public void setAreaListener(AreaListener areaListener) {
        this.areaListener = areaListener;
    }

    private void startDrag(Mode mode, Point p, int index, int handle) {
        this.mode = mode;
        this.dragOriginal = areas.get(index);
        this.dragStartPoint = toPoints(p);
        this.dragHandle = handle;
        this.dragChanged = false;
    }

    private void move(Point p) {
        Point2D now = toPoints(p);
        double dx = now.getX() - dragStartPoint.getX();
        double dy = now.getY() - dragStartPoint.getY();
        areas.set(selected, dragOriginal.moved(dx, dy));
        dragChanged = dx != 0 || dy != 0;
    }

    private void resize(Point p) {
        areas.set(selected, dragOriginal.resized(dragHandle, toPoints(p)));
        dragChanged = true;
    }

    private void rotate(Point p, boolean snap) {
        areas.set(selected, dragOriginal.rotatedTo(toPoints(p), snap));
        dragChanged = true;
    }

    /**
     * Distances in pixels are converted to points of the page.
     */
    private double grabTolerance() {
        return GRAB / (getScale() * pixelsPerPoint);
    }

    private double rotateDistance() {
        return ROTATE_DISTANCE / (getScale() * pixelsPerPoint);
    }

    private double rotateTolerance() {
        return (ROTATE_RADIUS + 2) / (getScale() * pixelsPerPoint);
    }

    /**
     * The topmost area (last drawn) at the position.
     */
    protected int areaAt(Point p) {
        for (int i = areas.size() - 1; i >= 0; --i) {
            if (areas.get(i).contains(toPoints(p)))
                return i;
        }
        return -1;
    }

    private void updateCursor(Point p) {
        if (spacePan || mode == Mode.PAN) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        int type = Cursor.CROSSHAIR_CURSOR;
        if (image != null) {
            Point2D pt = toPoints(p);
            ViewArea sel = selected >= 0 ? areas.get(selected) : null;
            int handle = sel != null && isResizable(selected) ? sel.handleAt(pt, grabTolerance()) : 0;
            if (rotationEnabled && sel != null && sel.onRotateHandle(pt, rotateDistance(), rotateTolerance())) {
                type = Cursor.HAND_CURSOR;
            } else if (handle != 0) {
                type = resizeCursor(handle, sel.angle());
            } else if (areaAt(p) >= 0) {
                type = Cursor.MOVE_CURSOR;
            }
        }
        setCursor(Cursor.getPredefinedCursor(type));
    }

    /**
     * The resize cursor that fits the direction of the handle on the rotated area.
     */
    private static int resizeCursor(int handle, double angle) {
        double dx = ((handle & RIGHT) != 0 ? 1 : 0) - ((handle & LEFT) != 0 ? 1 : 0);
        double dy = ((handle & BOTTOM) != 0 ? 1 : 0) - ((handle & TOP) != 0 ? 1 : 0);
        // Direction on the screen in steps of 45 degrees, 0 is east, clockwise.
        double a = Math.atan2(dy, dx) + angle;
        int octant = (int) Math.floorMod(Math.round(a / (Math.PI / 4)), 8L);
        return switch (octant) {
            case 0 -> Cursor.E_RESIZE_CURSOR;
            case 1 -> Cursor.SE_RESIZE_CURSOR;
            case 2 -> Cursor.S_RESIZE_CURSOR;
            case 3 -> Cursor.SW_RESIZE_CURSOR;
            case 4 -> Cursor.W_RESIZE_CURSOR;
            case 5 -> Cursor.NW_RESIZE_CURSOR;
            case 6 -> Cursor.N_RESIZE_CURSOR;
            default -> Cursor.NE_RESIZE_CURSOR;
        };
    }

    /**
     * Component pixels per image pixel.
     */
    public double getScale() {
        if (image == null)
            return 1;
        if (displayWidth > 0)
            return (double) displayWidth / image.getWidth();
        return Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
    }

    /**
     * Sets how big the page is shown.
     *
     * @param width The width on the screen in pixels, 0 to fit the page to the component.
     */
    public void setDisplayWidth(int width) {
        this.displayWidth = width;
        revalidate();
        repaint();
    }

    /**
     * @return The point of the component where the point of the page (in points) is shown.
     */
    public Point toComponentPoint(Point2D page) {
        double s = getScale() * pixelsPerPoint;
        return new Point((int) Math.round(offsetX() + page.getX() * s), (int) Math.round(offsetY() + page.getY() * s));
    }

    @Override
    public Dimension getPreferredSize() {
        if (displayWidth <= 0 || image == null)
            return super.getPreferredSize();
        return new Dimension(displayWidth,
                (int) Math.round(displayWidth * (double) image.getHeight() / image.getWidth()));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(24, (orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width) - 24);
    }

    /**
     * The page is fitted to the viewport, or it is smaller than the viewport: the component fills it, so the
     * page is in the middle.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return displayWidth <= 0 || (getParent() instanceof JViewport v && v.getWidth() >= getPreferredSize().width);
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return displayWidth <= 0 || (getParent() instanceof JViewport v && v.getHeight() >= getPreferredSize().height);
    }

    private double offsetX() {
        return (getWidth() - image.getWidth() * getScale()) / 2;
    }

    private double offsetY() {
        return (getHeight() - image.getHeight() * getScale()) / 2;
    }

    public Point2D toPoints(Point p) {
        double s = getScale() * pixelsPerPoint;
        return new Point2D.Double((p.x - offsetX()) / s, (p.y - offsetY()) / s);
    }

    private Rectangle2D toComponentRect(Rectangle2D r) {
        double s = getScale() * pixelsPerPoint;
        return new Rectangle2D.Double(offsetX() + r.getX() * s, offsetY() + r.getY() * s, r.getWidth() * s, r.getHeight() * s);
    }

    /**
     * The area as it is drawn in component coordinates.
     */
    private Shape toComponent(ViewArea area) {
        return toComponentFrame(area).shape();
    }

    private ViewArea toComponentFrame(ViewArea area) {
        return new ViewArea(toComponentRect(area.rect()), area.angle());
    }


    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (image == null)
                return;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, (int) offsetX(), (int) offsetY(),
                    (int) (image.getWidth() * getScale()), (int) (image.getHeight() * getScale()), null);

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    antialiasing ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            for (int i = 0; i < areas.size(); ++i) {
                ViewArea frame = toComponentFrame(areas.get(i));
                paintArea(g2, i, frame, i == selected);
                if (i == selected)
                    paintHandles(g2, frame, isResizable(i), rotationEnabled);
            }
            if (mode == Mode.CREATE && dragStart != null && dragEnd != null) {
                Rectangle2D r = new Rectangle2D.Double();
                r.setFrameFromDiagonal(dragStart, dragEnd);
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
                g2.draw(r);
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paints an area, the handles of a selected one are painted afterwards. By default a semi transparent fill in the
     * colour of the canvas, so the user still sees what is going to be removed, and a border.
     *
     * @param index    The index of the area.
     * @param frame    The area in component coordinates.
     * @param selected True if the area is selected.
     */
    protected void paintArea(Graphics2D g2, int index, ViewArea frame, boolean selected) {
        Shape c = frame.shape();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        g2.setColor(color);
        g2.fill(c);
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setStroke(new BasicStroke(1f));
        if (selected) {
            g2.setColor(selectColor);
            g2.setStroke(stroke);
            g2.draw(c);
        } else {
            // Visible on bright pages even if the color is white.
            g2.setColor(color.darker());
            g2.draw(c);
        }
    }

    /**
     * Paints the handles of a selected area, they are turned with the area.
     *
     * @param area      The area in component coordinates.
     * @param resizable False to paint no resize handles.
     * @param rotatable False to paint no rotate handle.
     */
    private static void paintHandles(Graphics2D g2, ViewArea area, boolean resizable, boolean rotatable) {
        Rectangle2D c = area.rect();
        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.rotate(area.angle(), c.getCenterX(), c.getCenterY());
            double[] xs = {c.getMinX(), c.getCenterX(), c.getMaxX()};
            double[] ys = {c.getMinY(), c.getCenterY(), c.getMaxY()};
            g.setStroke(new BasicStroke(1f));
            for (int ix = 0; ix < 3 && resizable; ++ix) {
                for (int iy = 0; iy < 3; ++iy) {
                    if (ix == 1 && iy == 1)
                        continue;
                    Rectangle2D h = new Rectangle2D.Double(xs[ix] - HANDLE_SIZE / 2.0, ys[iy] - HANDLE_SIZE / 2.0, HANDLE_SIZE, HANDLE_SIZE);
                    g.setColor(Color.WHITE);
                    g.fill(h);
                    g.setColor(Color.RED);
                    g.draw(h);
                }
            }
            if (!rotatable)
                return;
            // The rotate handle: a circle on a short line above the upper edge.
            double hx = c.getCenterX();
            double hy = c.getMinY() - ROTATE_DISTANCE;
            g.setColor(Color.RED);
            g.draw(new Line2D.Double(hx, c.getMinY() - HANDLE_SIZE / 2.0, hx, hy + ROTATE_RADIUS));
            Ellipse2D circle = new Ellipse2D.Double(hx - ROTATE_RADIUS, hy - ROTATE_RADIUS, 2 * ROTATE_RADIUS, 2 * ROTATE_RADIUS);
            g.setColor(Color.WHITE);
            g.fill(circle);
            g.setColor(Color.RED);
            g.draw(circle);
        } finally {
            g.dispose();
        }
    }
}

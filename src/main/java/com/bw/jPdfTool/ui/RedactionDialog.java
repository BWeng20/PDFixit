package com.bw.jPdfTool.ui;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.Preferences;
import com.bw.jPdfTool.Redactor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dialog to select the areas of a page that shall be permanently removed.<br>
 * Areas are drawn with the mouse or found by a text search.
 * Nothing is changed in the document, the dialog only returns the selected regions.
 */
public final class RedactionDialog extends JDialog {

    private static final int PREVIEW_DPI = 100;

    private final PDDocument document;
    private final int pageNb;
    private final PDRectangle cropBox;
    private final int rotation;

    /**
     * All selected regions in unrotated page coordinates, for all pages.
     */
    private final List<Redactor.Region> regions = new ArrayList<>();
    private boolean confirmed = false;

    private final AreaCanvas canvas = new AreaCanvas();
    private final JTextField searchField = new JTextField(20);
    private final JCheckBox allPages = new JCheckBox(I18n.t("redact.allPages"));
    private final JLabel info = new JLabel();
    private final JButton colorButton = new JButton(I18n.t("redact.color"));
    private final JButton removeButton = new JButton(I18n.t("redact.remove"));
    private final JButton resetButton = new JButton(I18n.t("redact.reset"));
    private final JButton applyButton = new JButton(I18n.t("ui.redact"));

    private RedactionDialog(Window owner, PDDocument document, int pageNb) throws java.io.IOException {
        super(owner, I18n.t("redact.title", pageNb), Dialog.ModalityType.APPLICATION_MODAL);
        this.document = document;
        this.pageNb = pageNb;

        PDPage page = document.getPage(pageNb - 1);
        this.cropBox = page.getCropBox();
        this.rotation = page.getRotation();

        // The viewer shows the page as it is displayed, i.e. rotated.
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNb - 1, PREVIEW_DPI);
        canvas.setImage(image, PREVIEW_DPI / 72.0);
        canvas.setColor(Preferences.getInstance().getRedactionColor());
        canvas.setPreferredSize(new Dimension(
                Math.min(image.getWidth(), 700) + 10, Math.min(image.getHeight(), 800) + 10));
        canvas.setAreaListener(new AreaListener() {
            @Override
            public void added(Rectangle2D area) {
                addViewArea(area);
            }

            @Override
            public void changed(int index, Rectangle2D area) {
                changeViewArea(index, area);
            }

            @Override
            public void removed(int index) {
                removeViewArea(index);
            }

            @Override
            public void selectionChanged() {
                removeButton.setEnabled(canvas.getSelected() >= 0);
            }
        });

        setContentPane(createContent());
        updateState();
        pack();
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private JPanel createContent() {
        JPanel main = new JPanel(new BorderLayout(0, 5));
        main.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JLabel hint = new JLabel(I18n.t("redact.hint"), SwingConstants.CENTER);
        hint.setIconTextGap(0);
        // Help button in the upper right corner, like in the main window.
        JButton help = new JButton(javax.swing.UIManager.getIcon("OptionPane.questionIcon"));
        help.setToolTipText(I18n.t("redact.help.tip"));
        help.addActionListener(e -> showHelp());

        JPanel north = new JPanel(new BorderLayout(0,0));
        north.add(hint, BorderLayout.CENTER);
        north.add(help, BorderLayout.EAST);
        main.add(north, BorderLayout.NORTH);
        main.add(canvas, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 5));

        JPanel search = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JLabel searchLabel = new JLabel(I18n.t("redact.text"));
        searchLabel.setLabelFor(searchField);
        JButton searchButton = new JButton(I18n.t("redact.find"));
        searchButton.setToolTipText(I18n.t("redact.find.tip"));
        searchButton.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());
        allPages.setToolTipText(I18n.t("redact.allPages.tip"));
        search.add(searchLabel);
        search.add(searchField);
        search.add(allPages);
        search.add(searchButton);
        south.add(search, BorderLayout.NORTH);

        colorButton.setToolTipText(I18n.t("redact.color.tip"));
        colorButton.addActionListener(e -> {
            if (chooseColor(this))
                canvas.setColor(Preferences.getInstance().getRedactionColor());
        });
        removeButton.setToolTipText(I18n.t("redact.remove.tip"));
        removeButton.setEnabled(false);
        removeButton.addActionListener(e -> {
            int selected = canvas.getSelected();
            if (selected >= 0)
                removeViewArea(selected);
        });
        resetButton.addActionListener(e -> {
            regions.clear();
            canvas.select(-1);
            updateState();
        });
        applyButton.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        JButton cancel = new JButton(I18n.t("common.cancel"));
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(info);
        buttons.add(colorButton);
        buttons.add(removeButton);
        buttons.add(resetButton);
        buttons.add(applyButton);
        buttons.add(cancel);
        south.add(buttons, BorderLayout.SOUTH);

        main.add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(applyButton);
        return main;
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this,
                I18n.t("redact.help.text"),
                I18n.t("common.help"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void addViewArea(Rectangle2D viewArea) {
        regions.add(new Redactor.Region(pageNb, Redactor.viewToPage(viewArea, cropBox, rotation)));
        updateState();
        canvas.select(canvas.getAreaCount() - 1);
    }

    private void changeViewArea(int canvasIndex, Rectangle2D viewArea) {
        regions.set(regionIndexOnPage.get(canvasIndex),
                new Redactor.Region(pageNb, Redactor.viewToPage(viewArea, cropBox, rotation)));
        updateState();
    }

    private void removeViewArea(int canvasIndex) {
        regions.remove((int) regionIndexOnPage.get(canvasIndex));
        canvas.select(-1);
        updateState();
    }

    private void doSearch() {
        String text = searchField.getText();
        if (text.isEmpty())
            return;
        try {
            List<Redactor.Region> found = Redactor.findText(document, Pattern.compile(text));
            String warning = Redactor.textSearchWarning(Redactor.pagesWithoutText(document));
            if (warning != null)
                JOptionPane.showMessageDialog(this, warning, I18n.t("redact.find"), JOptionPane.WARNING_MESSAGE);
            int added = 0;
            for (Redactor.Region r : found) {
                if (allPages.isSelected() || r.pageNb == pageNb) {
                    regions.add(r);
                    ++added;
                }
            }
            updateState();
            if (added == 0) {
                JOptionPane.showMessageDialog(this, I18n.t("redact.noMatch"), I18n.t("redact.find"), JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (PatternSyntaxException e) {
            JOptionPane.showMessageDialog(this, e.getDescription(), I18n.t("redact.illegalExpression"), JOptionPane.ERROR_MESSAGE);
        } catch (Exception e) {
            Log.exception(e);
            JOptionPane.showMessageDialog(this, e.getMessage(), I18n.t("redact.searchFailed"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Index in regions for each area shown by the canvas.
     */
    private final List<Integer> regionIndexOnPage = new ArrayList<>();

    private void updateState() {
        List<Rectangle2D> onPage = new ArrayList<>();
        regionIndexOnPage.clear();
        for (int i = 0; i < regions.size(); ++i) {
            Redactor.Region r = regions.get(i);
            if (r.pageNb == pageNb) {
                onPage.add(Redactor.pageToView(r.area, cropBox, rotation));
                regionIndexOnPage.add(i);
            }
        }
        canvas.setAreas(onPage);

        int others = regions.size() - onPage.size();
        info.setText(others == 0 ? I18n.t("redact.areas", onPage.size())
                : I18n.t("redact.areasOthers", onPage.size(), others));
        resetButton.setEnabled(!onPage.isEmpty());
        applyButton.setEnabled(!regions.isEmpty());
    }

    /**
     * Lets the user choose the color of redacted areas and stores it in the preferences.
     *
     * @param parent The parent component.
     * @return True if the user chose a color.
     */
    public static boolean chooseColor(Component parent) {
        Preferences prefs = Preferences.getInstance();
        Color color = JColorChooser.showDialog(parent, I18n.t("redact.colorTitle"), prefs.getRedactionColor());
        if (color == null)
            return false;
        prefs.setRedactionColor(color);
        return true;
    }

    /**
     * Shows the dialog for a page.
     *
     * @param owner    The owner window.
     * @param document The document.
     * @param pageNb   The 1-based page number.
     * @return The selected regions in unrotated page coordinates or null if the user canceled or nothing was selected.
     */
    public static List<Redactor.Region> showDialog(Window owner, PDDocument document, int pageNb) {
        RedactionDialog dialog;
        try {
            dialog = new RedactionDialog(owner, document, pageNb);
        } catch (Exception e) {
            Log.exception(e);
            JOptionPane.showMessageDialog(owner, e.getMessage(), I18n.t("redact.renderFailed"), JOptionPane.ERROR_MESSAGE);
            return null;
        }
        dialog.setVisible(true);
        return dialog.confirmed && !dialog.regions.isEmpty() ? new ArrayList<>(dialog.regions) : null;
    }

    /**
     * Notifications of the canvas. Areas are in points of the displayed page,
     * indices refer to the list given to {@link AreaCanvas#setAreas}.
     */
    private interface AreaListener {
        void added(Rectangle2D area);

        void changed(int index, Rectangle2D area);

        void removed(int index);

        void selectionChanged();
    }

    /**
     * Shows a page image and lets the user draw, select, move and resize rectangles.
     * Areas are reported in points of the displayed page.
     */
    private static final class AreaCanvas extends JComponent {

        private static final int LEFT = 1;
        private static final int RIGHT = 2;
        private static final int TOP = 4;
        private static final int BOTTOM = 8;

        /**
         * Distance in pixels to an edge that grabs the edge.
         */
        private static final int GRAB = 5;
        private static final int HANDLE_SIZE = 6;

        private enum Mode {NONE, CREATE, MOVE, RESIZE}

        private BufferedImage image;
        private Color color = Color.BLACK;
        private final Color selectColor = Color.RED;
        private final BasicStroke stroke = new BasicStroke(2f);
        private double pixelsPerPoint = 1;
        private final List<Rectangle2D> areas = new ArrayList<>();
        private int selected = -1;
        private AreaListener areaListener;

        private Mode mode = Mode.NONE;
        private Rectangle2D dragOriginal;
        private Point2D dragStartPoint;
        private int dragHandle;
        private boolean dragChanged;

        /**
         * Rubber band for new areas in component coordinates.
         */
        private Point dragStart;
        private Point dragEnd;

        AreaCanvas() {
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (image == null)
                        return;

                    int handle = selected >= 0 ? handleAt(e.getPoint(), toComponent(areas.get(selected))) : 0;
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
                        default -> {
                        }
                    }
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (mode == Mode.CREATE && image != null && areaListener != null) {
                        Rectangle2D r = new Rectangle2D.Double();
                        r.setFrameFromDiagonal(toPoints(dragStart), toPoints(e.getPoint()));
                        r = clipToImage(r);
                        // Ignore accidental clicks.
                        if (r.getWidth() * getScale() * pixelsPerPoint >= 3
                                && r.getHeight() * getScale() * pixelsPerPoint >= 3) {
                            areaListener.added(r);
                        }
                    } else if ((mode == Mode.MOVE || mode == Mode.RESIZE) && dragChanged && areaListener != null) {
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
        }

        void setColor(Color color) {
            this.color = color;
            repaint();
        }

        void setImage(BufferedImage image, double pixelsPerPoint) {
            this.image = image;
            this.pixelsPerPoint = pixelsPerPoint;
            repaint();
        }

        /**
         * Sets the areas. The selection is kept if possible.
         */
        void setAreas(List<Rectangle2D> newAreas) {
            areas.clear();
            areas.addAll(newAreas);
            if (selected >= areas.size())
                select(-1);
            repaint();
        }

        int getAreaCount() {
            return areas.size();
        }

        int getSelected() {
            return selected;
        }

        void select(int index) {
            if (index >= areas.size())
                index = -1;
            if (index != selected) {
                selected = index;
                repaint();
                if (areaListener != null)
                    areaListener.selectionChanged();
            }
        }

        void setAreaListener(AreaListener areaListener) {
            this.areaListener = areaListener;
        }

        private void startDrag(Mode mode, Point p, int index, int handle) {
            this.mode = mode;
            this.dragOriginal = (Rectangle2D) areas.get(index).clone();
            this.dragStartPoint = toPoints(p);
            this.dragHandle = handle;
            this.dragChanged = false;
        }

        private void move(Point p) {
            Point2D now = toPoints(p);
            Rectangle2D r = dragOriginal;
            Rectangle2D page = pageBounds();
            // Keep the area inside the page.
            double dx = Math.max(page.getMinX() - r.getMinX(), Math.min(page.getMaxX() - r.getMaxX(), now.getX() - dragStartPoint.getX()));
            double dy = Math.max(page.getMinY() - r.getMinY(), Math.min(page.getMaxY() - r.getMaxY(), now.getY() - dragStartPoint.getY()));
            areas.set(selected, new Rectangle2D.Double(r.getX() + dx, r.getY() + dy, r.getWidth(), r.getHeight()));
            dragChanged = dx != 0 || dy != 0;
        }

        private void resize(Point p) {
            Point2D now = toPoints(p);
            Rectangle2D page = pageBounds();
            double x1 = dragOriginal.getMinX(), x2 = dragOriginal.getMaxX();
            double y1 = dragOriginal.getMinY(), y2 = dragOriginal.getMaxY();
            double nx = Math.max(page.getMinX(), Math.min(page.getMaxX(), now.getX()));
            double ny = Math.max(page.getMinY(), Math.min(page.getMaxY(), now.getY()));
            if ((dragHandle & LEFT) != 0)
                x1 = nx;
            if ((dragHandle & RIGHT) != 0)
                x2 = nx;
            if ((dragHandle & TOP) != 0)
                y1 = ny;
            if ((dragHandle & BOTTOM) != 0)
                y2 = ny;
            // The edges may cross, the rectangle is normalized.
            Rectangle2D r = new Rectangle2D.Double();
            r.setFrameFromDiagonal(x1, y1, x2, y2);
            areas.set(selected, r);
            dragChanged = true;
        }

        /**
         * The topmost area (last drawn) at the position.
         */
        private int areaAt(Point p) {
            for (int i = areas.size() - 1; i >= 0; --i) {
                if (toComponent(areas.get(i)).contains(p))
                    return i;
            }
            return -1;
        }

        /**
         * Which edges of the (selected) area in component coordinates are grabbed at the position.
         *
         * @return Bit mask of LEFT, RIGHT, TOP, BOTTOM or 0.
         */
        private static int handleAt(Point p, Rectangle2D c) {
            if (p.x < c.getMinX() - GRAB || p.x > c.getMaxX() + GRAB
                    || p.y < c.getMinY() - GRAB || p.y > c.getMaxY() + GRAB)
                return 0;
            int mask = 0;
            if (Math.abs(p.x - c.getMinX()) <= GRAB)
                mask |= LEFT;
            else if (Math.abs(p.x - c.getMaxX()) <= GRAB)
                mask |= RIGHT;
            if (Math.abs(p.y - c.getMinY()) <= GRAB)
                mask |= TOP;
            else if (Math.abs(p.y - c.getMaxY()) <= GRAB)
                mask |= BOTTOM;
            return mask;
        }

        private void updateCursor(Point p) {
            int type = Cursor.CROSSHAIR_CURSOR;
            if (image != null) {
                int handle = selected >= 0 ? handleAt(p, toComponent(areas.get(selected))) : 0;
                if (handle != 0) {
                    type = switch (handle) {
                        case LEFT -> Cursor.W_RESIZE_CURSOR;
                        case RIGHT -> Cursor.E_RESIZE_CURSOR;
                        case TOP -> Cursor.N_RESIZE_CURSOR;
                        case BOTTOM -> Cursor.S_RESIZE_CURSOR;
                        case LEFT | TOP -> Cursor.NW_RESIZE_CURSOR;
                        case RIGHT | TOP -> Cursor.NE_RESIZE_CURSOR;
                        case LEFT | BOTTOM -> Cursor.SW_RESIZE_CURSOR;
                        default -> Cursor.SE_RESIZE_CURSOR;
                    };
                } else if (areaAt(p) >= 0) {
                    type = Cursor.MOVE_CURSOR;
                }
            }
            setCursor(Cursor.getPredefinedCursor(type));
        }

        /**
         * Component pixels per image pixel.
         */
        private double getScale() {
            if (image == null)
                return 1;
            return Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
        }

        private double offsetX() {
            return (getWidth() - image.getWidth() * getScale()) / 2;
        }

        private double offsetY() {
            return (getHeight() - image.getHeight() * getScale()) / 2;
        }

        private Point2D toPoints(Point p) {
            double s = getScale() * pixelsPerPoint;
            return new Point2D.Double((p.x - offsetX()) / s, (p.y - offsetY()) / s);
        }

        private Rectangle2D pageBounds() {
            return new Rectangle2D.Double(0, 0, image.getWidth() / pixelsPerPoint, image.getHeight() / pixelsPerPoint);
        }

        private Rectangle2D clipToImage(Rectangle2D r) {
            return r.createIntersection(pageBounds());
        }

        private Rectangle2D toComponent(Rectangle2D r) {
            double s = getScale() * pixelsPerPoint;
            return new Rectangle2D.Double(offsetX() + r.getX() * s, offsetY() + r.getY() * s, r.getWidth() * s, r.getHeight() * s);
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

                // Semi transparent, so the user still sees what is going to be removed.
                for (int i = 0; i < areas.size(); ++i) {
                    Rectangle2D c = toComponent(areas.get(i));
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
                    g2.setColor(color);
                    g2.fill(c);
                    g2.setComposite(AlphaComposite.SrcOver);
                    g2.setStroke(new BasicStroke(1f));
                    if (i == selected) {
                        g2.setColor(selectColor);
                        g2.setStroke(stroke);
                        g2.draw(c);
                        paintHandles(g2, c);
                    } else {
                        // Visible on bright pages even if the color is white.
                        g2.setColor(color.darker());
                        g2.draw(c);
                    }
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

        private static void paintHandles(Graphics2D g2, Rectangle2D c) {
            double[] xs = {c.getMinX(), c.getCenterX(), c.getMaxX()};
            double[] ys = {c.getMinY(), c.getCenterY(), c.getMaxY()};
            g2.setStroke(new BasicStroke(1f));
            for (int ix = 0; ix < 3; ++ix) {
                for (int iy = 0; iy < 3; ++iy) {
                    if (ix == 1 && iy == 1)
                        continue;
                    Rectangle2D h = new Rectangle2D.Double(xs[ix] - HANDLE_SIZE / 2.0, ys[iy] - HANDLE_SIZE / 2.0, HANDLE_SIZE, HANDLE_SIZE);
                    g2.setColor(Color.WHITE);
                    g2.fill(h);
                    g2.setColor(Color.RED);
                    g2.draw(h);
                }
            }
        }
    }
}

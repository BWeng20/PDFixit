package com.bw.pdfixit.ui;

import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.Page;
import org.apache.pdfbox.pdmodel.PDDocument;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Hosts Page-Widgets for all pages of the document.
 */
public class PageWidgetContainer extends JComponent {

    private final java.util.List<PageWidget> widgets = new ArrayList<>();
    private final List<ListSelectionListener> selectionListenerList = new ArrayList<>();
    private DocumentProxy document;
    private int orgPageCount = -1;
    private int orgWidth = -1;
    private Dimension orgSize;
    private int space = 5;
    private boolean refreshing = false;
    private PageWidget selectedPage;
    private final RenderingHints renderingHints = new RenderingHints(null);
    /**
     * The zoom in percent, 0 if the pages are fitted to the width of the view.
     */
    private int zoom;
    /**
     * The last {@link #getShownZoom()} after a layout, the property "shownZoom" is fired when it changes.
     */
    private int shownZoom;
    /**
     * True while the Space key is held: dragging with the left mouse button moves the pages, like in the
     * {@link RedactionDialog}.
     */
    private boolean spacePan;
    /**
     * While the pages are moved: where the mouse was on the screen, and the view position then. Null otherwise.
     */
    private Point panStart;
    private Point panOrigin;

    /**
     * Moves the pages with the right (or middle) mouse button, or with Space and the left one, like in the
     * {@link RedactionDialog}. It is on the container and on each page: the pages get the mouse events over them.
     */
    private final MouseAdapter panner = new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
            // Space works when the mouse is over the pages (and doesn't press a button that has the focus), but the
            // focus is not taken from a text field the user types in.
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (!(owner instanceof javax.swing.text.JTextComponent))
                requestFocusInWindow();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (spacePan || !SwingUtilities.isLeftMouseButton(e))
                startPan(e);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            pan(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (panStart != null) {
                panStart = null;
                updatePanCursor();
            }
        }
    };

    private final DocumentProxy.PageConsumer pageConsumer = page -> {
        PageWidget pw = getPageWidget(page.pageNb);
        if (pw != null) {
            pw.setPage(page);
            orgSize = null;
            refresh();
            if (pw == selectedPage)
                fireSelectionEvent(page.pageNb - 1);
        }
    };

    private final DocumentProxy.DocumentConsumer docConsumer = new DocumentProxy.DocumentConsumer() {
        @Override
        public void documentLoaded(PDDocument document) {
            int idx = getSelectedPageIndex();
            removeAll();
            widgets.clear();
            selectedPage = null;
            orgPageCount = -1;
            int newPageCount = document.getNumberOfPages();
            ensurePage(newPageCount);
            // The widgets know their pages before the images are rendered (again, e.g. after a rotation).
            // Otherwise the selected page counts as "none" until its image is ready: the page buttons
            // and the page tools were disabled and enabled again, they blinked.
            if (PageWidgetContainer.this.document != null) {
                for (int i = 0; i < widgets.size(); ++i) {
                    Page page = PageWidgetContainer.this.document.getPage(i + 1);
                    if (page != null)
                        widgets.get(i).setPage(page);
                }
            }
            if (widgets.isEmpty()) {
                if (idx >= 0)
                    setSelectedPage(null, true);
            } else {
                // Nothing selected before (e.g. a document was just loaded): the first page.
                setSelectedPage(widgets.get(Math.max(0, Math.min(idx, widgets.size() - 1))));
            }
            repaint();
        }

        @Override
        public void failed(String error) {
            setErrorText(error);
        }
    };

    public PageWidgetContainer() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refresh();
            }
        });
        // With Ctrl the wheel zooms around the mouse, like in the redaction dialog.
        addMouseWheelListener(e -> {
            JScrollPane scrollPane = getScrollPane();
            if (scrollPane == null)
                return;
            if (e.isControlDown()) {
                if (e.getPreciseWheelRotation() == 0)
                    return;
                int target = nextZoom(e.getPreciseWheelRotation() < 0);
                if (target != zoom)
                    setZoom(target, SwingUtilities.convertPoint(this, e.getPoint(), scrollPane.getViewport()));
            } else {
                // Without Ctrl the wheel scrolls as before: the scroll pane gets the event.
                scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, scrollPane));
            }
        });

        addMouseListener(panner);
        addMouseMotionListener(panner);
        // Space makes the left mouse button move the pages. The arrow keys move them (Shift: further), like in the
        // redaction dialog: the scroll pane would move them only by a pixel.
        setFocusable(true);
        bindScroll(KeyEvent.VK_LEFT, -1, 0);
        bindScroll(KeyEvent.VK_RIGHT, 1, 0);
        bindScroll(KeyEvent.VK_UP, 0, -1);
        bindScroll(KeyEvent.VK_DOWN, 0, 1);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "panOn");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "panOff");
        getActionMap().put("panOn", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setSpacePan(true);
            }
        });
        getActionMap().put("panOff", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setSpacePan(false);
            }
        });
        // Space is not held any more if the focus goes elsewhere.
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                setSpacePan(false);
            }
        });

        updateRenderingHints();
    }

    // ---------------------------------------------------------------- moving the pages with the mouse

    private void setSpacePan(boolean on) {
        if (spacePan != on) {
            spacePan = on;
            updatePanCursor();
        }
    }

    /**
     * The hand while the pages are moved or Space is held, else the default cursor.
     */
    private void updatePanCursor() {
        Cursor cursor = spacePan || panStart != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null;
        setCursor(cursor);
        // The pages have no cursor of their own: they show the one of the container.
    }

    private void bindScroll(int key, int dx, int dy) {
        for (int step : new int[]{48, 192}) {
            String name = "scroll" + key + "_" + step;
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, step == 48 ? 0 : java.awt.event.InputEvent.SHIFT_DOWN_MASK), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    JScrollPane scrollPane = getScrollPane();
                    if (scrollPane != null) {
                        Point position = scrollPane.getViewport().getViewPosition();
                        setViewPosition(scrollPane.getViewport(), position.x + dx * step, position.y + dy * step);
                    }
                }
            });
        }
    }

    /**
     * Moves the view, as far as the pages go.
     */
    private void setViewPosition(JViewport viewport, int x, int y) {
        Dimension extent = viewport.getExtentSize();
        viewport.setViewPosition(new Point(
                Math.max(0, Math.min(x, getWidth() - extent.width)),
                Math.max(0, Math.min(y, getHeight() - extent.height))));
    }

    private void startPan(MouseEvent e) {
        JScrollPane scrollPane = getScrollPane();
        if (scrollPane == null)
            return;
        panStart = e.getLocationOnScreen();
        panOrigin = scrollPane.getViewport().getViewPosition();
        updatePanCursor();
    }

    /**
     * The pages follow the mouse, as far as they can go. The position on the screen is used: the page under the mouse
     * moves while it is dragged.
     */
    private void pan(MouseEvent e) {
        JScrollPane scrollPane = getScrollPane();
        if (panStart == null || scrollPane == null)
            return;
        Point now = e.getLocationOnScreen();
        setViewPosition(scrollPane.getViewport(), panOrigin.x - (now.x - panStart.x), panOrigin.y - (now.y - panStart.y));
    }

    private static final String[] HINT_PREFS = {
            Preferences.USER_PREF_VIEWER_ANTIALIASING,
            Preferences.USER_PREF_VIEWER_RENDER_QUALITY,
            Preferences.USER_PREF_VIEWER_INTERPOLATE_BI_CUBIC};

    private final java.beans.PropertyChangeListener hintListener = evt -> {
        Log.debug("PWC: changed " + evt.getPropertyName());
        updateRenderingHints();
        repaint();
    };

    // The listener lives only while the component is displayed, so a disposed window is not kept by the preferences.
    @Override
    public void addNotify() {
        super.addNotify();
        Preferences.getInstance().addPropertyChangeListener(hintListener, HINT_PREFS);
        updateRenderingHints();
    }

    @Override
    public void removeNotify() {
        Preferences.getInstance().removePropertyChangeListener(hintListener, HINT_PREFS);
        super.removeNotify();
    }

    protected void updateRenderingHints() {
        Preferences preferences = Preferences.getInstance();
        renderingHints.put(RenderingHints.KEY_RENDERING, preferences.getBoolean(Preferences.USER_PREF_VIEWER_RENDER_QUALITY, true)
                ? RenderingHints.VALUE_RENDER_QUALITY : RenderingHints.VALUE_RENDER_SPEED);
        renderingHints.put(RenderingHints.KEY_ANTIALIASING, preferences.getBoolean(Preferences.USER_PREF_VIEWER_ANTIALIASING, true)
                ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        renderingHints.put(RenderingHints.KEY_INTERPOLATION, preferences.getBoolean(Preferences.USER_PREF_VIEWER_INTERPOLATE_BI_CUBIC, true)
                ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    public int getSelectedPageIndex() {
        return widgets.indexOf(getSelectedPage());
    }

    public PageWidget getSelectedPage() {
        return selectedPage;
    }

    public void setSelectedPage(PageWidget selectedPage) {
        setSelectedPage(selectedPage, false);
    }

    protected void setSelectedPage(PageWidget selectedPage, boolean forced) {
        if (this.selectedPage != selectedPage || forced) {

            PageWidget old = this.selectedPage;

            if (this.selectedPage != null && this.selectedPage != selectedPage)
                this.selectedPage.setSelected(false);

            this.selectedPage = selectedPage;

            int idx = widgets.indexOf(selectedPage);
            if (selectedPage != null) {
                if (idx < 0)
                    throw new IllegalArgumentException("Selected page is not in list");
                selectedPage.setSelected(true);
            }

            if (selectedPage != null) {
                if (selectedPage.getPage() != null) {
                    fireSelectionEvent(idx);
                } else if (old != null) {
                    // Delay event until page is loaded
                    fireSelectionEvent(-1);
                }
                SwingUtilities.invokeLater(() -> {
                    var currentSelectedPage = getSelectedPage();
                    if (currentSelectedPage != null) {
                        var bounds = currentSelectedPage.getBounds();
                        // Check if the page has already some content.
                        // (to prevent us from "jumping" during initialisation)
                        if (!bounds.isEmpty()) {
                            // Show the selected page. Some Viewport-parent will handle this.
                            currentSelectedPage.scrollRectToVisible(
                                    new Rectangle(0, 0, currentSelectedPage.getWidth(), currentSelectedPage.getHeight()));
                        }
                    }
                });
            } else {
                fireSelectionEvent(-1);
            }
        }
    }

    private void fireSelectionEvent(int idx) {
        ListSelectionEvent e = new ListSelectionEvent(this, idx, idx, false);
        List<ListSelectionListener> tmp = new ArrayList<>(selectionListenerList);
        for (ListSelectionListener l : tmp) {
            l.valueChanged(e);
        }
    }

    public void addSelectionListener(ListSelectionListener l) {
        selectionListenerList.remove(l);
        selectionListenerList.add(l);
    }

    public void removeSelectionListener(ListSelectionListener l) {
        selectionListenerList.remove(l);
    }

    public PageWidget getPageWidget(int pageNb) {
        ensurePage(pageNb);
        return widgets.get(pageNb - 1);
    }


    private void ensurePage(int pageNb) {
        int cc = widgets.size();
        if (cc < pageNb) {
            while (cc < pageNb) {
                PageWidget widget = new PageWidget(++cc);
                widget.setRenderingHints(renderingHints);
                widget.addMouseListener(new MouseAdapter() {

                    final PageWidget w = widget;

                    @Override
                    public void mousePressed(MouseEvent e) {
                        // The right and middle button and Space with the left one move the pages, they don't select.
                        if (SwingUtilities.isLeftMouseButton(e) && !spacePan)
                            setSelectedPage(w);
                    }
                });
                widget.addMouseListener(panner);
                widget.addMouseMotionListener(panner);
                widgets.add(widget);
                add(widget);
            }
            refresh();
        }
    }


    public void clear() {
        removeAll();
        widgets.clear();
        if (document != null) {
            document.removePageConsumer(pageConsumer);
            document.removeDocumentConsumer(docConsumer);
            document = null;
        }
        orgPageCount = -1;
        setSelectedPage(null);
        revalidate();

    }

    public void setDocument(DocumentProxy file) {
        clear();
        document = file;
        document.addDocumentConsumer(docConsumer);
        document.addPageConsumer(pageConsumer);
    }

    @Override
    public Dimension getPreferredSize() {

        final int availableWidth = getAvailableWidth();
        int wc = widgets.size();
        if (orgSize == null ||
                wc != orgPageCount ||
                availableWidth != orgWidth) {

            int fitWidth = availableWidth - 8 - (2 * space);
            orgPageCount = wc;
            orgWidth = availableWidth;
            int x = 5;
            int y = 5;
            int w = 5;
            int h = 5;
            int widest = 0;
            for (int i = 0; i < wc; ++i) {

                PageWidget c = widgets.get(i);
                Page page = c.getPage();
                Insets insets = c.getBorder().getBorderInsets(this);
                int drawWidth;
                int drawHeight;
                if (page != null && page.image != null) {
                    // Fitted, each page is as wide as the view. Zoomed, each page has its size times the zoom.
                    page.scale = zoom > 0 ? zoom * 0.72 / dpiOf(page) : ((double) fitWidth) / page.image.getWidth();
                    c.setScale(page.scale);
                    c.setImage(page.image);
                    drawWidth = zoom > 0 ? (int) (0.5 + (page.scale * page.image.getWidth())) : fitWidth;
                    drawHeight = (int) (0.5 + (page.scale * page.image.getHeight()));
                } else {
                    // Not rendered yet: an A4 page (595 points wide).
                    drawWidth = zoom > 0 ? (int) (0.5 + 595 * zoom / 100.0) : fitWidth;
                    drawHeight = (int) (0.5 + drawWidth * (297f / 210f));
                }
                drawHeight += insets.top + insets.bottom;

                // A page narrower than the view is in its middle.
                c.setLocation(Math.max(x, (availableWidth - drawWidth - 8) / 2), y);
                c.setSize(drawWidth + 8, drawHeight + 4);

                widest = Math.max(widest, c.getWidth());
                h = y + c.getHeight();
                y = h + space;
            }
            // Fitted, the view gives the width. Zoomed, the pages may be wider: the view scrolls horizontally.
            if (zoom > 0)
                w = widest + 2 * space;
            orgSize = new Dimension(w, h);
            // The scale of a page is known only now (e.g. its image just came, the size of the view changed).
            firePropertyChange("shownZoom", shownZoom, shownZoom = getShownZoom());
        }
        return orgSize;
    }

    /**
     * The resolution of the image of a page. The image may be there a moment before its resolution is set.
     */
    private static int dpiOf(Page page) {
        return page.dpi > 0 ? page.dpi : Preferences.getInstance().getDpi();
    }

    // ---------------------------------------------------------------- zoom

    /**
     * @return The zoom in percent, 0 if the pages are fitted to the width of the view.
     */
    public int getZoom() {
        return zoom;
    }

    /**
     * The zoom that is shown now in percent: the zoom, or what the selected page (else the first) is fitted to.
     *
     * @return The percent, 0 if no page is shown yet.
     */
    public int getShownZoom() {
        if (zoom > 0)
            return zoom;
        PageWidget pw = selectedPage != null ? selectedPage : (widgets.isEmpty() ? null : widgets.get(0));
        if (pw == null || pw.getPage() == null || pw.getPage().image == null || pw.getScale() <= 0)
            return 0;
        // Pixels per point (72 dpi) in percent.
        return (int) Math.round(pw.getScale() * dpiOf(pw.getPage()) / 0.72);
    }

    /**
     * The next zoom step, see {@link ZoomSteps}. The pages are not rendered again: the resolution of their images is
     * the one from the preferences.
     *
     * @param bigger True for the next bigger, false for the next smaller step.
     * @return The zoom in percent, the one that is shown now if there is no such step.
     */
    public int nextZoom(boolean bigger) {
        return ZoomSteps.next(zoom, getShownZoom(), ZoomSteps.largest(), bigger);
    }

    /**
     * Zooms the pages. The point of the page that is below the given point stays below it. Fires the property
     * "zoom".
     *
     * @param percent The zoom in percent, 0 to fit the pages to the width of the view.
     * @param anchor  A point in the coordinates of the viewport, null for its center.
     */
    public void setZoom(int percent, Point anchor) {
        int old = zoom;
        JScrollPane scrollPane = getScrollPane();
        if (scrollPane == null) {
            zoom = percent;
            orgSize = null;
            refresh();
            firePropertyChange("zoom", old, zoom);
            return;
        }
        JViewport viewport = scrollPane.getViewport();
        Point at = anchor == null ? new Point(viewport.getWidth() / 2, viewport.getHeight() / 2) : anchor;
        Point position = viewport.getViewPosition();
        int anchorX = position.x + at.x;
        int anchorY = position.y + at.y;
        // The page below the anchor (or the last above it) and where on it, as a share of its size.
        PageWidget below = null;
        for (PageWidget pw : widgets) {
            below = pw;
            if (pw.getY() + pw.getHeight() > anchorY)
                break;
        }
        double shareX = 0;
        double shareY = 0;
        if (below != null && below.getWidth() > 0 && below.getHeight() > 0) {
            shareX = (anchorX - below.getX()) / (double) below.getWidth();
            shareY = (anchorY - below.getY()) / (double) below.getHeight();
        }
        zoom = percent;
        orgSize = null;
        revalidate();
        // The layout now, so the page can be put back below the anchor.
        scrollPane.validate();
        if (below != null) {
            Dimension extent = viewport.getExtentSize();
            int x = (int) Math.round(below.getX() + shareX * below.getWidth()) - at.x;
            int y = (int) Math.round(below.getY() + shareY * below.getHeight()) - at.y;
            x = Math.max(0, Math.min(x, getWidth() - extent.width));
            y = Math.max(0, Math.min(y, getHeight() - extent.height));
            viewport.setViewPosition(new Point(x, y));
        }
        repaint();
        firePropertyChange("zoom", old, zoom);
    }

    /**
     * Gets the width for the pages: the width of the viewport, with room for the vertical scroll bar even if it
     * is hidden.<br>
     * The height of the pages follows their width. If the width depended on the scroll bar, a page a bit taller
     * than the viewport would switch the scroll bar on and off with each layout, the preview flickered.
     */
    private int getAvailableWidth() {
        JScrollPane scrollPane = getScrollPane();
        if (scrollPane == null)
            return getWidth();
        int width = scrollPane.getViewport().getWidth();
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar != null && !bar.isVisible())
            width -= bar.getPreferredSize().width;
        return width;
    }

    /**
     * Triggers a refresh of scales and layout.
     */
    private void refresh() {
        if (!refreshing) {
            refreshing = true;
            SwingUtilities.invokeLater(() -> {
                refreshing = false;
                revalidate();
                repaint();
            });
        }
    }

    /**
     * The part of the document at the top of the viewport as a share of the height of the pages, while the position
     * is held, see {@link #holdPosition()}. Negative if it is not held.
     */
    private double heldPosition = -1;
    private boolean restoringPosition;

    /**
     * Keeps the same part of the document at the top of the viewport while the width changes. The pages are as wide as
     * the viewport, so a change of the width changes the height of all pages and the scroll offset would show something
     * else. Call {@link #releasePosition()} when the width is stable again.
     */
    public void holdPosition() {
        JScrollPane scrollPane = getScrollPane();
        if (scrollPane == null || getHeight() <= 0)
            return;
        JViewport viewport = scrollPane.getViewport();
        heldPosition = (double) viewport.getViewPosition().y / getHeight();
        // Not added twice if the position is held again.
        viewport.removeChangeListener(positionHolder);
        viewport.addChangeListener(positionHolder);
    }

    public void releasePosition() {
        heldPosition = -1;
        JScrollPane scrollPane = getScrollPane();
        if (scrollPane != null)
            scrollPane.getViewport().removeChangeListener(positionHolder);
    }

    /**
     * The viewport reports each change of the size of the pages (and of its own size): put the held part of the
     * document at the top again. This is part of the layout, so there is no flicker between.
     */
    private final ChangeListener positionHolder = e -> {
        JScrollPane scrollPane = getScrollPane();
        if (heldPosition < 0 || restoringPosition || scrollPane == null)
            return;
        JViewport viewport = scrollPane.getViewport();
        int maximum = Math.max(0, getHeight() - viewport.getExtentSize().height);
        int y = Math.min(maximum, (int) Math.round(heldPosition * getHeight()));
        Point position = viewport.getViewPosition();
        if (position.y != y) {
            restoringPosition = true;
            try {
                viewport.setViewPosition(new Point(position.x, y));
            } finally {
                restoringPosition = false;
            }
        }
    };

    protected JScrollPane getScrollPane() {
        return ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this));
    }

    public void setErrorText(String error) {
        Log.error("Failed to load the document: %s", error);
    }

}

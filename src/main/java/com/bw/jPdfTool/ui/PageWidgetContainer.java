package com.bw.jPdfTool.ui;

import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.Preferences;
import com.bw.jPdfTool.model.DocumentProxy;
import com.bw.jPdfTool.model.Page;
import org.apache.pdfbox.pdmodel.PDDocument;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
    private Dimension orgVS;
    private Dimension orgSize;
    private int space = 5;
    private boolean refreshing = false;
    private PageWidget selectedPage;
    private final RenderingHints renderingHints = new RenderingHints(null);

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
            if (idx >= 0) {
                if (widgets.isEmpty())
                    setSelectedPage(null, true);
                else
                    setSelectedPage(widgets.get(Math.min(idx, widgets.size() - 1)));
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

        updateRenderingHints();
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
                        setSelectedPage(w);
                    }
                });
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

        Dimension vs = getScrollPane().getViewport().getSize();
        int wc = widgets.size();
        if (orgSize == null ||
                wc != orgPageCount ||
                !vs.equals(orgVS)) {

            int drawWidth = vs.width - 8 - (2 * space);
            orgPageCount = wc;
            orgVS = vs;
            int x = 5;
            int y = 5;
            int w = 5;
            int h = 5;
            for (int i = 0; i < wc; ++i) {

                PageWidget c = widgets.get(i);
                Page page = c.getPage();
                Insets insets = c.getBorder().getBorderInsets(this);
                int drawHeight;
                if (page != null && page.image != null) {
                    page.scale = ((double) drawWidth) / page.image.getWidth();
                    c.setScale(page.scale);
                    c.setImage(page.image);
                    drawHeight = (int) (0.5 + (page.scale * page.image.getHeight()));
                } else {
                    drawHeight = (int) (0.5 + drawWidth * (297f / 210f));
                }
                drawHeight += insets.top + insets.bottom;

                c.setLocation(x, y);
                c.setSize(drawWidth + 8, drawHeight + 4);

                h = y + c.getHeight();
                y = h + space;
            }
            orgSize = new Dimension(w, h);
        }
        return orgSize;
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

    protected JScrollPane getScrollPane() {
        return ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this));
    }

    public void setErrorText(String error) {
        Log.error("Failed to load the document: %s", error);
    }

}

package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.Redactor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dialog to select the areas of a page that shall be permanently removed.<br>
 * Areas are drawn with the mouse or found by a text search. The page can be zoomed, changes of the areas can be undone.
 * Nothing is changed in the document, the dialog only returns the selected regions.
 */
public final class RedactionDialog extends JDialog {

    /**
     * Resolution of the first picture of the page, it is shown fitted to the window.
     */
    private static final int PREVIEW_DPI = 100;
    /**
     * A picture of more pixels is not rendered: the biggest zoom steps are not offered for a big page.
     */
    private static final long MAX_PIXELS = 40_000_000L;
    /**
     * Changes that can be undone.
     */
    private static final int MAX_UNDO = 100;

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
    private final JCheckBox antialiasing = new JCheckBox(I18n.t("redact.aa"));
    private final JLabel info = new JLabel();
    private final JButton colorButton = new JButton(I18n.t("redact.color"));
    private final JButton removeButton = new JButton(I18n.t("redact.remove"));
    private final JButton resetButton = new JButton(I18n.t("redact.reset"));
    private final JButton applyButton = new JButton(I18n.t("ui.redact"));
    private final JButton undoButton = new JButton(I18n.t("redact.undo"));
    private final JButton zoomFitButton = new JButton(I18n.t("zoom.fit"));
    private final JButton zoomOutButton = new JButton("\u2212");
    private final JButton zoomInButton = new JButton("+");
    private final JLabel zoomLabel = new JLabel(" ");
    private final JScrollPane scroll = new JScrollPane(canvas);

    /**
     * Size of the displayed page (rotated) in points.
     */
    private final double pageWidth;
    private final double pageHeight;
    /**
     * The zoom in percent, 0 if the page is fitted to the window.
     */
    private int zoom;
    /**
     * Resolution of the picture of the canvas.
     */
    private double imageDpi = PREVIEW_DPI;
    /**
     * Counts the requests to render the page again, an older result is dropped.
     */
    private int renderRequest;
    /**
     * The areas before each change, the last one first at the end.
     */
    private final List<List<Redactor.Region>> undoStack = new ArrayList<>();

    private RedactionDialog(Window owner, PDDocument document, int pageNb) throws java.io.IOException {
        super(owner, I18n.t("redact.title", pageNb), Dialog.ModalityType.APPLICATION_MODAL);
        this.document = document;
        this.pageNb = pageNb;

        PDPage page = document.getPage(pageNb - 1);
        this.cropBox = page.getCropBox();
        this.rotation = page.getRotation();

        // The viewer shows the page as it is displayed, i.e. rotated.
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNb - 1, PREVIEW_DPI);
        this.pageWidth = image.getWidth() * 72.0 / PREVIEW_DPI;
        this.pageHeight = image.getHeight() * 72.0 / PREVIEW_DPI;
        canvas.setImage(image, PREVIEW_DPI / 72.0);
        canvas.setColor(Preferences.getInstance().getRedactionColor());
        canvas.setAntialiasing(Preferences.getInstance().isRedactionAntialiasing());
        // Not higher than the screen can show: the window has to fit (the page scrolls or is fitted).
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        scroll.setPreferredSize(new Dimension(
                Math.min(image.getWidth(), 700) + 10, Math.min(image.getHeight(), usable.height * 65 / 100) + 10));
        canvas.setAreaListener(new AreaCanvas.AreaListener() {
            @Override
            public void added(ViewArea area) {
                addViewArea(area);
            }

            @Override
            public void changed(int index, ViewArea area) {
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
        updateZoom();
        pack();
        // All the controls must be on the screen, also on a small one.
        Dimension size = getSize();
        setSize(Math.min(size.width, usable.width), Math.min(size.height, usable.height));
        Dimension min = getMinimumSize();
        setMinimumSize(new Dimension(Math.min(min.width, usable.width), Math.min(min.height, usable.height)));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private JPanel createContent() {
        JPanel main = new JPanel(new BorderLayout(0, 5));
        main.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JLabel hint = new JLabel(I18n.t("redact.hint"), SwingConstants.CENTER);
        hint.setIconTextGap(0);
        // Help button in the upper right corner, like in the main window.
        RoundSvgButton help = RoundSvgButton.createHelp();
        help.setToolTipText(I18n.t("redact.help.tip"));
        help.addActionListener(e -> showHelp());

        JPanel north = new JPanel(new BorderLayout(0, 0));
        north.add(hint, BorderLayout.CENTER);
        north.add(help, BorderLayout.EAST);

        // The zoom: fit the page to the window, or a step. Ctrl and the mouse wheel zoom too.
        zoomFitButton.setToolTipText(I18n.t("zoom.fit.tip"));
        zoomFitButton.addActionListener(e -> setZoom(0, null));
        zoomOutButton.setToolTipText(I18n.t("zoom.out.tip"));
        zoomOutButton.addActionListener(e -> setZoom(nextZoom(false), null));
        zoomInButton.setToolTipText(I18n.t("zoom.in.tip"));
        zoomInButton.addActionListener(e -> setZoom(nextZoom(true), null));
        Insets small = new Insets(2, 10, 2, 10);
        zoomOutButton.setMargin(small);
        zoomInButton.setMargin(small);
        JPanel zoomRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        zoomRow.add(zoomFitButton);
        zoomRow.add(zoomOutButton);
        zoomRow.add(zoomInButton);
        zoomRow.add(zoomLabel);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(north, BorderLayout.NORTH);
        top.add(zoomRow, BorderLayout.SOUTH);
        main.add(top, BorderLayout.NORTH);

        // The wheel scrolls, with Ctrl it zooms around the mouse.
        scroll.setWheelScrollingEnabled(false);
        scroll.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                int target = nextZoom(e.getWheelRotation() < 0);
                if (target != zoom)
                    setZoom(target, SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), scroll.getViewport()));
            } else {
                JScrollBar bar = e.isShiftDown() ? scroll.getHorizontalScrollBar() : scroll.getVerticalScrollBar();
                bar.setValue(bar.getValue() + e.getUnitsToScroll() * 24);
            }
        });
        // The fitted page changes with the window: so does the percent that is shown.
        canvas.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (zoom == 0)
                    updateZoom();
            }
        });
        main.add(scroll, BorderLayout.CENTER);

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

        antialiasing.setToolTipText(I18n.t("redact.aa.tip"));
        antialiasing.setSelected(Preferences.getInstance().isRedactionAntialiasing());
        antialiasing.addActionListener(e -> {
            Preferences.getInstance().setRedactionAntialiasing(antialiasing.isSelected());
            canvas.setAntialiasing(antialiasing.isSelected());
        });
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
        undoButton.setToolTipText(I18n.t("redact.undo.tip"));
        undoButton.setEnabled(false);
        undoButton.addActionListener(e -> undo());
        getRootPane().registerKeyboardAction(e -> undo(),
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        resetButton.setToolTipText(I18n.t("redact.reset.tip"));
        resetButton.addActionListener(e -> {
            rememberForUndo();
            // Only the areas of this page: the ones on other pages (e.g. from a search in all pages) are kept.
            regions.removeIf(r -> r.pageNb == pageNb);
            canvas.select(-1);
            updateState();
        });
        applyButton.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        JButton cancel = new JButton(I18n.t("common.cancel"));
        cancel.addActionListener(e -> dispose());
        // The main action of the dialog.
        UI.setPrimary(applyButton);

        // The settings left, what is done with the areas right, the main action last.
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEADING));
        settings.add(info);
        settings.add(antialiasing);
        settings.add(colorButton);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        actions.add(undoButton);
        actions.add(removeButton);
        actions.add(resetButton);
        actions.add(cancel);
        actions.add(applyButton);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(settings, BorderLayout.WEST);
        buttons.add(actions, BorderLayout.EAST);
        south.add(buttons, BorderLayout.SOUTH);

        main.add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(applyButton);
        return main;
    }

    private void showHelp() {
        HelpDialog.show(this, "redact.help.text", true);
    }

    private Redactor.Region toRegion(ViewArea viewArea) {
        // The angle is the same on the displayed and on the unrotated page, see Redactor.viewToPage.
        return new Redactor.Region(pageNb, Redactor.viewToPage(viewArea.rect(), cropBox, rotation), viewArea.angle());
    }

    /**
     * Remembers the areas before a change.
     */
    private void rememberForUndo() {
        undoStack.add(new ArrayList<>(regions));
        if (undoStack.size() > MAX_UNDO)
            undoStack.remove(0);
    }

    /**
     * Takes back the last change of the areas.
     */
    private void undo() {
        if (undoStack.isEmpty())
            return;
        regions.clear();
        regions.addAll(undoStack.remove(undoStack.size() - 1));
        canvas.select(-1);
        updateState();
    }

    private void addViewArea(ViewArea viewArea) {
        rememberForUndo();
        regions.add(toRegion(viewArea));
        updateState();
        canvas.select(canvas.getAreaCount() - 1);
    }

    private void changeViewArea(int canvasIndex, ViewArea viewArea) {
        rememberForUndo();
        regions.set(regionIndexOnPage.get(canvasIndex), toRegion(viewArea));
        updateState();
    }

    private void removeViewArea(int canvasIndex) {
        rememberForUndo();
        regions.remove((int) regionIndexOnPage.get(canvasIndex));
        canvas.select(-1);
        updateState();
    }

    private void doSearch() {
        String text = searchField.getText();
        if (text.isEmpty())
            return;
        try {
            List<Redactor.Region> found;
            String warning;
            // Not while the page is rendered again: the document is read by one thread at a time.
            synchronized (document) {
                found = Redactor.findText(document, Pattern.compile(text));
                warning = Redactor.textSearchWarning(Redactor.pagesWithoutText(document));
            }
            if (warning != null)
                JOptionPane.showMessageDialog(this, warning, I18n.t("redact.find"), JOptionPane.WARNING_MESSAGE);
            List<Redactor.Region> matches = new ArrayList<>();
            for (Redactor.Region r : found) {
                if (allPages.isSelected() || r.pageNb == pageNb)
                    matches.add(r);
            }
            int added = matches.size();
            if (added > 0) {
                rememberForUndo();
                regions.addAll(matches);
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
        List<ViewArea> onPage = new ArrayList<>();
        regionIndexOnPage.clear();
        for (int i = 0; i < regions.size(); ++i) {
            Redactor.Region r = regions.get(i);
            if (r.pageNb == pageNb) {
                onPage.add(new ViewArea(Redactor.pageToView(r.area, cropBox, rotation), r.angle));
                regionIndexOnPage.add(i);
            }
        }
        canvas.setAreas(onPage);

        int others = regions.size() - onPage.size();
        info.setText(others == 0 ? I18n.t("redact.areas", onPage.size())
                : I18n.t("redact.areasOthers", onPage.size(), others));
        resetButton.setEnabled(!onPage.isEmpty());
        undoButton.setEnabled(!undoStack.isEmpty());
        applyButton.setEnabled(!regions.isEmpty());
    }

    // ---------------------------------------------------------------- zoom

    /**
     * The zoom that is shown now in percent: the zoom, or what the fitted page is.
     */
    private int currentZoom() {
        return zoom > 0 ? zoom : (int) Math.round(canvas.getScale() * canvas.getPixelsPerPoint() * 100);
    }

    /**
     * The biggest zoom that renders no more than {@link #MAX_PIXELS} pixels.
     */
    private int maxZoom() {
        int most = (int) Math.floor(100 * Math.sqrt(MAX_PIXELS / (pageWidth * pageHeight)));
        return Math.max(ZoomSteps.smallest(), Math.min(ZoomSteps.largest(), most));
    }

    /**
     * The next zoom step, see {@link ZoomSteps}. The page is rendered again for a zoom that needs a higher resolution
     * than the picture has.
     *
     * @param bigger True for the next bigger, false for the next smaller step.
     * @return The zoom in percent, the one that is shown now if there is no such step.
     */
    private int nextZoom(boolean bigger) {
        return ZoomSteps.next(zoom, currentZoom(), maxZoom(), bigger);
    }

    /**
     * Zooms the page. The point of the page that is below the given point stays below it.
     *
     * @param percent The zoom in percent, 0 to fit the page to the window.
     * @param anchor  A point in the coordinates of the viewport, null for its center.
     */
    private void setZoom(int percent, Point anchor) {
        JViewport viewport = scroll.getViewport();
        Point at = anchor == null ? new Point(viewport.getWidth() / 2, viewport.getHeight() / 2) : anchor;
        Point2D onPage = canvas.toPoints(new Point(viewport.getViewPosition().x + at.x, viewport.getViewPosition().y + at.y));
        zoom = percent;
        if (percent == 0) {
            canvas.setDisplayWidth(0);
        } else {
            canvas.setDisplayWidth((int) Math.round(pageWidth * percent / 100.0));
            // A sharper picture is rendered if the zoom needs it, the one we have is scaled until it is there.
            if (72.0 * percent / 100.0 > imageDpi)
                renderAt(72.0 * percent / 100.0);
        }
        // The layout now, so the page can be put back below the anchor.
        scroll.validate();
        Point now = canvas.toComponentPoint(onPage);
        int x = Math.max(0, Math.min(now.x - at.x, canvas.getWidth() - viewport.getWidth()));
        int y = Math.max(0, Math.min(now.y - at.y, canvas.getHeight() - viewport.getHeight()));
        viewport.setViewPosition(new Point(x, y));
        updateZoom();
    }

    private void updateZoom() {
        int current = currentZoom();
        zoomLabel.setText(zoom == 0 ? I18n.t("zoom.fitted", current) : zoom + " %");
        zoomFitButton.setEnabled(zoom != 0);
        zoomOutButton.setEnabled(nextZoom(false) != (zoom == 0 ? 0 : zoom));
        zoomInButton.setEnabled(nextZoom(true) != (zoom == 0 ? 0 : zoom));
    }

    /**
     * Renders the page at a higher resolution in the background and shows it when it is there.
     */
    private void renderAt(final double dpi) {
        final int request = ++renderRequest;
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // The document is read by one thread at a time, the search of the dialog reads it, too.
                synchronized (document) {
                    return new PDFRenderer(document).renderImageWithDPI(pageNb - 1, (float) dpi);
                }
            }

            @Override
            protected void done() {
                // Another zoom was asked for meanwhile: this picture is no longer needed.
                if (request != renderRequest)
                    return;
                try {
                    canvas.setImage(get(), dpi / 72.0);
                    imageDpi = dpi;
                } catch (Exception e) {
                    // The picture we have stays, it is only less sharp.
                    Log.exception(e);
                }
            }
        }.execute();
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
}

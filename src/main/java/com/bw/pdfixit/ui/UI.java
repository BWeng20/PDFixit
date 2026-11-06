package com.bw.pdfixit.ui;


import com.bw.jtools.svg.SVGConverter;
import com.bw.jtools.ui.ShapeIcon;
import com.bw.jtools.ui.ShapeMultiResolutionImage;
import com.bw.pdfixit.*;
import com.bw.pdfixit.fonts.FontEmbedException;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.model.Page;
import com.bw.pdfixit.model.RenderQueue;
import com.bw.pdfixit.toast.Toast;
import com.bw.pdfixit.toast.ToastType;
import com.bw.pdfixit.toast.Toaster;
import com.bw.pdfixit.ui.fonts.FontsDialog;
import com.bw.pdfixit.ui.fonts.ImportedFontsDialog;
import com.bw.pdfixit.ui.invoice.InvoiceEditorPanel;
import com.bw.pdfixit.ui.wizard.StartPage;
import com.bw.pdfixit.ui.wizard.Wizard;
import com.bw.pdfixit.ui.wizard.Wizards;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.roboto.FlatRobotoFont;
import com.formdev.flatlaf.util.SystemInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.beans.PropertyChangeListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Main panel to show the pdf tool. Wrap this panel into a frame to create an app.
 * See {@link Main#main(String[])}.<br>
 * The UI holds the controls, the document and all actions. The panels ({@link DocumentPanel},
 * {@link StandardPanel}, {@link PageToolsStrip}, ...) only arrange the controls, they get them from here.
 */
public class UI extends JPanel {

    public static JFrame mainWindow;

    private static final Map<String, Icon> icons = new HashMap<>();

    private int encryptionKeyLength = 256;

    private final Toaster toaster = new Toaster();

    protected static JFileChooser savePdfChooser;
    protected static JFileChooser pdfChooser;
    protected static JFileChooser imageChooser;
    protected static JFileChooser signatureChooser;

    protected static FileNameExtensionFilter pdfFilter = new FileNameExtensionFilter(I18n.t("filter.pdf"), "pdf");
    protected static FileNameExtensionFilter imageFilter = new FileNameExtensionFilter(I18n.t("filter.image"), "jpeg", "jpg", "png");
    protected static FileNameExtensionFilter sigFilter = new FileNameExtensionFilter(I18n.t("filter.p12"), "p12");

    final JTextField ownerPasswordField = new JTextField();
    final JTextField userPasswordField = new JTextField();

    final JButton saveButton = new JButton(I18n.t("ui.save"));
    final JCheckBox compression = new JCheckBox(I18n.t("ui.compression"));

    final PageWidgetContainer pages = new PageWidgetContainer();
    /**
     * The zoom of the pages, at the bottom of the {@link PageToolsStrip} (the pages keep the full height), like in
     * the {@link RedactionDialog}: a step bigger or smaller, the percent between, fit the pages to the width.
     * Ctrl and the mouse wheel zoom too.
     */
    final RoundSvgButton zoomInButton = new RoundSvgButton("/svg/page-tool-zoom-in.svg",
            RoundSvgButton.NAVIGATION_SIZE_IN_FONT_HEIGHTS);
    final RoundSvgButton zoomOutButton = new RoundSvgButton("/svg/page-tool-zoom-out.svg",
            RoundSvgButton.NAVIGATION_SIZE_IN_FONT_HEIGHTS);
    final RoundSvgButton zoomFitButton = new RoundSvgButton("/svg/page-tool-zoom-fit.svg",
            RoundSvgButton.NAVIGATION_SIZE_IN_FONT_HEIGHTS);
    final JLabel zoomLabel = new JLabel(" ", SwingConstants.CENTER);
    private static final String CARD_START = "start";
    private static final String CARD_PAGES = "pages";
    /**
     * Shows the start page while there is no document, otherwise the pages.
     */
    private final JPanel pagesCards = new JPanel(new java.awt.CardLayout());
    private StartPage startPage;
    protected DocumentProxy documentProxy;
    final InvoiceActions invoiceActions = new InvoiceActions(this, toaster);
    private PageWidget selectedPage;

    final JLabel pdfStandard = new JLabel() {
        @Override
        public void updateUI() {
            super.updateUI();
            // Gray like other hints. Set here, so it follows a change of the look and feel.
            setForeground(UIManager.getColor("Label.disabledForeground"));
        }
    };
    final JButton standardButton = new JButton(I18n.t("ui.standardButton"));
    final JButton generateOwner = new JButton(getIcon("svg/button-generate"));
    /**
     * If the effective document declares PDF/A. Everything that would violate PDF/A is disabled then.
     */
    private volatile boolean pdfaMode;
    /**
     * Set for a new main document until it is loaded: a loaded document is not repaired.
     */
    private boolean initialLoad;
    /**
     * If the effective document contained an invoice at the last update. Adding one repairs the document.
     */
    private boolean hadInvoice;
    /**
     * True while the standard is switched: the check of the result is part of the switch.
     */
    private volatile boolean switchingStandard;
    /**
     * True while the PDF/A check runs.
     */
    private final java.util.concurrent.atomic.AtomicBoolean pdfaChecking = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * The violations the user was told about, so they are not shown again for the same document.
     */
    private DocumentProxy reportedProxy;
    private ValidationResult reportedResult = ValidationResult.conform();
    /**
     * The document state the last PDF/A check was done for, see {@link DocumentProxy#getContentGeneration()}.
     * Rotating, moving or deleting pages doesn't change it, so they start no check.
     */
    private DocumentProxy checkedProxy;
    private int checkedGeneration;
    final JCheckBox useDocumentInformation = new JCheckBox(I18n.t("ui.useDocInfo"));
    final JButton clearInformation = new JButton(I18n.t("ui.clearInfo"));
    final JButton exportXmp = new JButton(I18n.t("ui.exportXmp"));
    final JTextField title = new JTextField(15);
    final JTextField author = new JTextField(15);
    final JTextField creator = new JTextField(15);
    final JTextField producer = new JTextField(15);
    final JTextField subject = new JTextField(15);

    final JTextField keywords = new JTextField(15);
    /**
     * The error toasts of the current document. They stay until the user closes them or a new document is loaded.
     */
    private final java.util.List<Toast> documentErrors = new java.util.ArrayList<>();
    /**
     * The "Loading…" toast of the main document while it is loaded, closed if the loading fails.
     */
    private final Toast[] loadingToast = new Toast[1];

    /////////////////////////////////////////////
    // Signing
    /// //////////////////////////////////////////
    final JCheckBox sign = new JCheckBox(I18n.t("ui.sign"));
    final JLabel signatureKeyFileName = new JLabel();
    private File signatureKeyFile;
    final JPasswordField signaturePasswordField = new JPasswordField();
    final JButton browseSignature = new JButton(I18n.t("ui.selectP12"));


    /////////////////////////////////////////////
    // Permissions
    /// //////////////////////////////////////////

    final JCheckBox allowPrinting = new JCheckBox(I18n.t("perm.print"));
    final JCheckBox allowExtraction = new JCheckBox(I18n.t("perm.extract"));
    final JCheckBox allowModification = new JCheckBox(I18n.t("perm.modify"));
    final JCheckBox allowFillIn = new JCheckBox(I18n.t("perm.fillIn"));
    final JCheckBox allowAssembly = new JCheckBox(I18n.t("perm.assemble"));
    final JCheckBox allowAnnotations = new JCheckBox(I18n.t("perm.annotations"));

    /////////////////////////////////////////////
    // Page manipulation
    /// //////////////////////////////////////////

    final RoundSvgButton deleteButton = new RoundSvgButton("/svg/page-tool-delete.svg");
    final JMenuItem renderToImageButton = new JMenuItem(I18n.t("ui.toImage.item"));
    final JMenuItem renderAllToImageButton = new JMenuItem(I18n.t("ui.allToImages.item"));
    final RoundSvgButton redactButton = new RoundSvgButton("/svg/page-tool-redact.svg");
    final RoundSvgButton rotateClockwiseButton = new RoundSvgButton("/svg/page-tool-rotate.svg");
    final RoundSvgButton rotateAllClockwiseButton = new RoundSvgButton("/svg/page-tool-rotate-all.svg");
    /**
     * Change the order of the pages. Their symbols show a page with an arrow, so they don't look like the
     * {@link #previousPageButton} and the {@link #nextPageButton}, which only select.
     */
    final RoundSvgButton moveUp = new RoundSvgButton("/svg/page-tool-move-up.svg");
    final RoundSvgButton moveDown = new RoundSvgButton("/svg/page-tool-move-down.svg");
    /**
     * Select the previous and the next page, above and below the {@link #pageCounter}.
     */
    final RoundSvgButton previousPageButton = new RoundSvgButton("/svg/page-tool-previous.svg",
            RoundSvgButton.NAVIGATION_SIZE_IN_FONT_HEIGHTS);
    final RoundSvgButton nextPageButton = new RoundSvgButton("/svg/page-tool-next.svg",
            RoundSvgButton.NAVIGATION_SIZE_IN_FONT_HEIGHTS);
    final JMenuItem exportImages = new JMenuItem(I18n.t("ui.exportImages.item"));
    /**
     * Opens the menu with the image actions of the pages.
     */
    final RoundSvgButton imagesButton = new RoundSvgButton("/svg/page-tool-images.svg");
    private final JPopupMenu imagesMenu = new JPopupMenu();
    /**
     * Tells why passwords and permissions can't be set, on top of the {@link PermissionsPanel}. Empty if they can. It
     * always has the height of two lines, so the controls below don't move when the text changes.
     */
    final JLabel permissionsHint = new JLabel(" ") {
        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.height = Math.max(size.height, 2 * getFontMetrics(getFont()).getHeight());
            return size;
        }
    };
    /**
     * Selected page and page count, on top of the {@link PageToolsStrip}.
     */
    final PageCounter pageCounter = new PageCounter(rotateClockwiseButton);
    /**
     * The rotation of the selected page, below the {@link #pageCounter}.
     */
    final PageOrientation pageOrientation = new PageOrientation();
    final JButton browseButton = new JButton(I18n.t("menu.file.open"), getIcon("openPdf"));
    final JButton browseAppendButton = new JButton(I18n.t("menu.file.add"), getIcon("addPdf"));
    /**
     * The editor of the e-invoice: creates the invoice of the document or edits it, see {@link InvoicePanel}.
     */
    final InvoiceEditorPanel invoiceEditor = new InvoiceEditorPanel(toaster);
    /**
     * The result of the automatic check of the invoice (of the document, or of the draft of the editor), or empty if
     * there is none, see {@link InvoiceActions}.
     */
    final JLabel invoiceStatus = new JLabel(" ");
    /**
     * Shows the messages and the report of the check of the invoice.
     */
    final JButton invoiceDetailsButton = new JButton(I18n.t("ui.invoice.details"));
    final JButton validatePdfaButton = new JButton(I18n.t("menu.validation.pdfa"));
    /**
     * Identifiers of the side tabs, see {@link SideTabs}. They are stored in the preferences.
     */
    private static final String TAB_DOCUMENT = "document";
    private static final String TAB_INVOICE = "invoice";
    private static final String TAB_PERMISSIONS = "permissions";

    final RoundSvgButton invoiceHelp = RoundSvgButton.createBadge();

    final RoundSvgButton permissionHelp = RoundSvgButton.createBadge();
    final RoundSvgButton signingHelp = RoundSvgButton.createBadge();

    Font normalFont;
    Font hintFont;

    /////////////////////////////////////////////
    // Splitting
    /// //////////////////////////////////////////
    final JSpinner pagesPerDocument = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    final JButton splitDocument = new JButton(I18n.t("ui.split"));

    protected final RenderQueue renderQueue = new RenderQueue();

    /**
     * The controls on the left, the page preview on the right, see {@link #createLayout()}.
     */
    private JSplitPane splitPane;
    /**
     * The panels of the open tabs, left of the divider.
     */
    private TabDrawer drawer;
    /**
     * Where the user put the divider (pixels from the left edge of the split pane), 0 if the user never moved it. The
     * divider is there, but never left of what the open tabs need, see {@link #dividerTarget(int)}. This value, not the
     * place of the divider, is what is stored.
     */
    private int userDivider;
    /**
     * The width of the controls as long as the user has no choice: the most that a tab other than the invoice needed so
     * far (the content grows, e.g. with the standard of a document). So the divider doesn't move between these tabs,
     * only for the invoice, which needs more. It is not stored.
     */
    private int defaultDivider;
    /**
     * Where the running move of the divider ends.
     */
    private int dividerMovesTo;
    /**
     * True while the program moves the divider, so it is not taken for a move of the user.
     */
    private boolean adjustingDivider;
    private javax.swing.Timer dividerAnimation;
    /**
     * Duration of the move of the divider if another tab needs more or less room.
     */
    private static final int DIVIDER_MOVE_MS = 150;
    /**
     * Writes the window state a moment after the last change of the size or the divider, see {@link #applyWindowState}.
     */
    private javax.swing.Timer windowStateTimer;
    /**
     * What the preferences hold, or what the window had when it was shown if nothing is stored. Only a change is written,
     * so a user who never changes the window has nothing stored and gets the default size at each start.
     */
    private int storedWidth;
    private int storedHeight;
    private int storedDivider;
    private boolean storedMaximized;

    protected final Deque<UIPdfLoadWorker> loadWorker = new ArrayDeque<>();
    protected UIPdfLoadWorker currentLoader;

    public void handleDrop(DropTargetDropEvent dtde, boolean append) {
        // Not files (e.g. text dragged over the window): nothing to do.
        if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            dtde.rejectDrop();
            return;
        }
        try {
            dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
            if (dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor) instanceof java.util.List<?> droppedFiles) {
                for (Object item : droppedFiles) {
                    if (item instanceof File file) {
                        if (append && documentProxy != null) {
                            appendPdf(file, new MergeOptions());
                        } else {
                            if (!confirmInvoiceDiscard(I18n.t("ui.confirm.load")) || !confirmDiscard(I18n.t("ui.confirm.load")))
                                break;
                            selectPdf(file);
                            append = true;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.exception(ex);
        }

    }

    public UI() {
        super(new BorderLayout());

        normalFont = UIManager.getFont("Panel.font");
        hintFont = normalFont.deriveFont(Font.ITALIC);

        saveButton.setEnabled(false);
        saveButton.setFont(normalFont.deriveFont(Font.BOLD, normalFont.getSize2D() * 1.5f));
        setPrimary(saveButton);
        browseAppendButton.setEnabled(false);
        splitDocument.setEnabled(false);

        wireDocumentControls();
        pages.addSelectionListener(e -> setSelectedPage(e.getFirstIndex() < 0 ? null : pages.getSelectedPage()));
        wirePageControls();
        wireZoomControls();
        wirePermissionControls();
        wireSigningControls();

        browseButton.addActionListener(e -> doBrowsePdf());
        browseAppendButton.addActionListener(e -> runInBusy(() -> {
            if (documentProxy == null)
                doBrowsePdf();
            else
                doBrowseAdditionalPdf();
        }));

        saveButton.addActionListener(e -> doSave());
        splitDocument.addActionListener(e -> doSplit());

        add(createLayout(), BorderLayout.CENTER);

        updatePermissions();
        setSelectedPage(null);

        pages.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updatePageCounter();
                updateZoomControls();
            }
        });
        renderQueue.start();

        SwingUtilities.invokeLater(() -> {
                    toaster.attachFrame((JFrame) SwingUtilities.getWindowAncestor(this));

                    String sigFile = getPref(Preferences.USER_PREF_SIG_FILE, null);
                    if (sigFile != null)
                        loadSignature(new File(sigFile));
                }
        );
    }

    /**
     * Colored tabs on the left open the controls (document with signing and splitting, invoice, permissions) in a drawer
     * beside them, one is always open (the document at the start), the pages are on the right. The tabs are described in {@link SideTabs}.
     */
    private JSplitPane createLayout() {
        final FontMetrics fm = getFontMetrics(normalFont);
        final int charWith = fm.charWidth('W');

        drawer = new TabDrawer();
        drawer.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
        // The drawer grows if the open tabs need more room than the controls have (e.g. the standard of a loaded
        // document is a longer text): the divider follows, nothing is cut off.
        drawer.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (splitPane != null)
                        updateDivider();
                });
            }
        });
        // The document: the settings of the file (standard, compression), its information, and what is done with it as
        // a whole: signing and splitting.
        JPanel documentTab = new JPanel(new GridBagLayout());
        GridBagConstraints dgc = new GridBagConstraints();
        dgc.gridx = 0;
        dgc.weightx = 1;
        dgc.fill = GridBagConstraints.HORIZONTAL;
        dgc.anchor = GridBagConstraints.NORTHWEST;
        dgc.insets = new Insets(0, 0, 5, 0);
        dgc.gridy = 0;
        documentTab.add(new StandardPanel(this), dgc);
        dgc.gridy = 1;
        documentTab.add(new DocumentPanel(this), dgc);
        dgc.gridy = 2;
        documentTab.add(new BadgedBox(new SigningPanel(this), signingHelp), dgc);
        dgc.gridy = 3;
        documentTab.add(new SplitPanel(this), dgc);
        drawer.addPanel(TAB_DOCUMENT, documentTab);
        // The e-invoice: create or edit it and check it.
        drawer.addPanel(TAB_INVOICE, new InvoicePanel(this));
        // The editor uses the height of the window: the items table grows with it.
        drawer.setFillsHeight(TAB_INVOICE);
        drawer.addPanel(TAB_PERMISSIONS, new BadgedBox(new PermissionsPanel(this), permissionHelp));

        SideTabs tabs = new SideTabs(List.of(
                new SideTabs.Spec(TAB_DOCUMENT, I18n.t("ui.tab.document"), "/svg/tab-document.svg", new Color(0x4A7FC1)),
                new SideTabs.Spec(TAB_INVOICE, I18n.t("ui.tab.invoice"), "/svg/tab-invoice.svg", new Color(0x2E9C94)),
                new SideTabs.Spec(TAB_PERMISSIONS, I18n.t("ui.tab.permissions"), "/svg/tab-permissions.svg", new Color(0xD9962B))));
        drawer.setOpen(tabs.open());
        // In a small window or with a big font the controls scroll, they are not cut off.
        // The divider can't be moved over the controls: the scroll pane is not narrower than they need.
        JScrollPane controlsScroll = new ProtectedScrollPane(drawer);
        controlsScroll.setBorder(BorderFactory.createEmptyBorder());
        controlsScroll.getVerticalScrollBar().setUnitIncrement(fm.getHeight());
        JPanel drawerFrame = new JPanel(new BorderLayout());
        drawerFrame.add(controlsScroll, BorderLayout.CENTER);

        // Open, add and save are always in reach, also if all tabs are closed.
        JPanel actionBar = new ActionBar(this);

        // Below the drawer: the bar of the open tab that must stay in sight (the invoice tab has the buttons to apply
        // the invoice), it is not scrolled away with the controls.
        JPanel barHolder = new JPanel(new BorderLayout());
        JPanel drawerArea = new JPanel(new BorderLayout());
        drawerArea.add(drawerFrame, BorderLayout.CENTER);
        drawerArea.add(barHolder, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout());
        left.add(tabs, BorderLayout.WEST);
        left.add(drawerArea, BorderLayout.CENTER);
        left.add(actionBar, BorderLayout.SOUTH);

        // The margin of the window. On the split pane itself: its divider location counts from its outer edge.
        // The divider has the background of the strips beside it (the action bar and the page tools), not the one of
        // the window: it would show as a gap between them. The divider of FlatLaf is transparent, so the split pane
        // paints the background behind it. The dividers of the other look and feels paint their own background, they
        // are replaced, see StripSplitPaneUI.
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT) {
            @Override
            public void updateUI() {
                super.updateUI();
                if (!isFlatLaf())
                    setUI(new StripSplitPaneUI());
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
                    Rectangle r = basic.getDivider().getBounds();
                    g.setColor(SideTabs.stripBackground());
                    g.fillRect(r.x, r.y, r.width, r.height);
                }
            }
        };
        split.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        split.setLeftComponent(left);

        // The tab of the invoice says what it does at the moment: create the invoice or edit the one of the document.
        // The divider follows if the controls need more room (e.g. the editor was created), see updateDivider().
        // The window follows: it can't be smaller than the controls and the pages need.
        invoiceEditor.setModeListener(mode -> {
            tabs.setBadge(TAB_INVOICE, switch (mode) {
                case NONE -> null;
                case CREATE -> I18n.t("ui.tab.invoice.create");
                case EDIT -> I18n.t("ui.tab.invoice.edit");
            });
            SwingUtilities.invokeLater(() -> {
                updateMinimumWindowSize();
                updateDivider();
            });
        });
        invoiceEditor.setShown(tabs.open().contains(TAB_INVOICE));

        Runnable showDrawer = () -> {
            // The line on top has the color of the tab that was opened last.
            drawerFrame.setBorder(BorderFactory.createMatteBorder(3, 0, 0, 0, tabs.activeColor()));
            drawer.setOpen(tabs.open());
            invoiceEditor.setShown(tabs.open().contains(TAB_INVOICE));
            barHolder.removeAll();
            if (tabs.open().contains(TAB_INVOICE))
                barHolder.add(invoiceEditor.saveBar(), BorderLayout.CENTER);
            barHolder.revalidate();
            barHolder.repaint();
            left.revalidate();
        };
        showDrawer.run();
        tabs.addChangeListener(() -> {
            showDrawer.run();
            // The divider stays where the user put it. It only moves if the open tabs need more room than that, and
            // back if they need less again.
            updateDivider();
            SwingUtilities.invokeLater(this::updateMinimumWindowSize);
        });

        JScrollPane scrollPane = new JScrollPane(pages);
        scrollPane.getViewport().setPreferredSize(new Dimension(charWith * 50, fm.getHeight() * 40));
        // The window can't be made smaller than this for the pages: a usable preview.
        scrollPane.setMinimumSize(new Dimension(charWith * 20, fm.getHeight() * 10));
        // The selected page, the page count and the rotation are on top of the strip with the page tools.
        JPanel pagesArea = new JPanel(new BorderLayout());
        pagesArea.add(new PageToolsStrip(this), BorderLayout.WEST);
        // Without a document the start page takes the place of the pages.
        startPage = new StartPage(startCards());
        pagesCards.add(startPage, CARD_START);
        pagesCards.add(scrollPane, CARD_PAGES);
        pagesArea.add(pagesCards, BorderLayout.CENTER);
        split.setRightComponent(pagesArea);
        // A bigger window gives the room to the pages, the controls keep their width.
        split.setResizeWeight(0);
        splitPane = split;
        // As wide as the first tab needs, also with a big font, until the user chooses another width.
        split.setDividerLocation(dividerTarget(0));
        defaultDivider = split.getDividerLocation();
        return split;
    }

    /**
     * Sets up the zoom of the pages, see {@link PageToolsStrip}.
     */
    private void wireZoomControls() {
        for (RoundSvgButton b : new RoundSvgButton[]{zoomInButton, zoomOutButton, zoomFitButton})
            b.setOpaque(false);
        zoomInButton.setToolTipText(I18n.t("zoom.in.tip"));
        zoomInButton.addActionListener(e -> pages.setZoom(pages.nextZoom(true), null));
        zoomOutButton.setToolTipText(I18n.t("zoom.out.tip"));
        zoomOutButton.addActionListener(e -> pages.setZoom(pages.nextZoom(false), null));
        zoomFitButton.setToolTipText(I18n.t("zoom.fitWidth.tip"));
        zoomFitButton.addActionListener(e -> pages.setZoom(0, null));
        // The strip is narrow: a smaller font for the percent.
        zoomLabel.setFont(zoomLabel.getFont().deriveFont(zoomLabel.getFont().getSize2D() * 0.85f));
        // Ctrl and the mouse wheel zoom in the pages, the buttons and the percent follow.
        pages.addPropertyChangeListener("zoom", e -> updateZoomControls());
        pages.addPropertyChangeListener("shownZoom", e -> updateZoomControls());
        updateZoomControls();
    }

    /**
     * Shows the zoom of the pages. Fitted, the percent changes with the width of the view and the selected page; the
     * tooltip tells whether the pages are fitted.
     */
    private void updateZoomControls() {
        // Without pages there is nothing to zoom; the zoom stays for the next document.
        boolean hasPages = documentProxy != null && documentProxy.getPageCount() > 0;
        int zoom = pages.getZoom();
        int shown = hasPages ? pages.getShownZoom() : 0;
        zoomLabel.setText(shown > 0 ? shown + " %" : " ");
        zoomLabel.setEnabled(shown > 0);
        zoomLabel.setToolTipText(shown <= 0 ? null : zoom > 0 ? zoom + " %" : I18n.t("zoom.fitted", shown));
        zoomFitButton.setEnabled(hasPages && zoom != 0);
        zoomOutButton.setEnabled(shown > 0 && pages.nextZoom(false) != zoom);
        zoomInButton.setEnabled(shown > 0 && pages.nextZoom(true) != zoom);
    }

    /**
     * Where the divider is: where the user put it (at the start: where it was at the start), but not left of what the
     * open tabs need (also with a big font) and not so far right that the preview gets smaller than its minimum. The
     * tabs that are not open don't count. So the divider only moves if a tab needs more room than that, and goes back
     * if the next tab needs less.
     *
     * @param splitWidth The width of the split pane, 0 if it is not known yet.
     */
    private int dividerTarget(int splitWidth) {
        Insets in = splitPane.getInsets();
        int needed = neededDivider();
        int target = Math.max(userDivider > 0 ? userDivider : defaultDivider, needed);
        if (splitWidth > 0) {
            int most = splitWidth - in.right - splitPane.getDividerSize()
                    - splitPane.getRightComponent().getMinimumSize().width;
            target = Math.min(target, most);
        }
        return Math.max(target, needed);
    }

    /**
     * Moves the divider to where it belongs, see {@link #dividerTarget(int)}. Call it if the open tabs or their content
     * changed.
     */
    private void updateDivider() {
        if (!drawer.isOpen(TAB_INVOICE))
            defaultDivider = Math.max(defaultDivider, neededDivider());
        moveDivider(dividerTarget(splitPane.getWidth()));
    }

    /**
     * @return The place of the divider that gives the open tabs the room they need.
     */
    private int neededDivider() {
        return splitPane.getLeftComponent().getPreferredSize().width + splitPane.getInsets().left;
    }

    /**
     * Moves the divider. The pages are as wide as the preview, so they are scaled: the part of the document at the top
     * stays at the top, and the move is animated (not if the user wants reduced motion): the controls slide in and out,
     * the preview doesn't jump.
     */
    private void moveDivider(int target) {
        // The move is already on its way (each step changes the size of the controls, which asks for the divider again).
        if (dividerAnimation != null && dividerAnimation.isRunning() && dividerMovesTo == target)
            return;
        if (dividerAnimation != null)
            dividerAnimation.stop();
        final int from = splitPane.getDividerLocation();
        if (from == target) {
            // Also ends the hold of a move that was stopped.
            SwingUtilities.invokeLater(pages::releasePosition);
            return;
        }
        dividerMovesTo = target;
        pages.holdPosition();
        boolean still = !splitPane.isShowing() || Preferences.getInstance().getBoolean(
                Preferences.USER_PREF_REDUCED_MOTION, Preferences.USER_PREF_REDUCED_MOTION_DEFAULT);
        if (still) {
            setDivider(target);
            // After the layout that follows.
            SwingUtilities.invokeLater(pages::releasePosition);
            return;
        }
        final long start = System.nanoTime();
        final javax.swing.Timer timer = new javax.swing.Timer(15, null);
        timer.addActionListener(e -> {
            double t = Math.min(1.0, (System.nanoTime() - start) / (DIVIDER_MOVE_MS * 1_000_000.0));
            // Fast at the start, slow at the end.
            double eased = 1 - (1 - t) * (1 - t) * (1 - t);
            setDivider((int) Math.round(from + (target - from) * eased));
            if (t >= 1.0) {
                timer.stop();
                SwingUtilities.invokeLater(pages::releasePosition);
            }
        });
        dividerAnimation = timer;
        timer.start();
    }

    private void setDivider(int location) {
        adjustingDivider = true;
        try {
            splitPane.setDividerLocation(location);
        } finally {
            adjustingDivider = false;
        }
    }

    // ---------------------------------------------------------------- size of the window

    /**
     * The size of the window if nothing is stored: half of the usable screen, but not smaller than the controls need.
     *
     * @param usable The usable area of the screen (without the task bar).
     * @param min    The minimum size of the window.
     */
    static Dimension defaultWindowSize(Rectangle usable, Dimension min) {
        return fitWindowSize(usable.width / 2, (int)(usable.height * 0.75), usable, min);
    }

    /**
     * The stored size of the window, or the default size if nothing is stored (a width or height of 0 or less).
     */
    static Dimension restoredWindowSize(int width, int height, Rectangle usable, Dimension min) {
        if (width <= 0 || height <= 0)
            return defaultWindowSize(usable, min);
        return fitWindowSize(width, height, usable, min);
    }

    /**
     * Not smaller than the minimum and not bigger than the screen. The screen wins if it is smaller than the minimum.
     */
    private static Dimension fitWindowSize(int width, int height, Rectangle usable, Dimension min) {
        return new Dimension(Math.min(Math.max(width, min.width), usable.width),
                Math.min(Math.max(height, min.height), usable.height));
    }

    /**
     * Sets size, state and divider of the window before it is shown, from the preferences or, if nothing is stored
     * there, half of the usable screen. After that the changes of the user are stored. Call it after the window is packed
     * and its minimum size is set, see {@link #updateMinimumWindowSize()}.
     */
    private void applyWindowState(JFrame frame) {
        Preferences prefs = Preferences.getInstance();
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension size = restoredWindowSize(prefs.getInt(Preferences.USER_PREF_WINDOW_WIDTH, 0),
                prefs.getInt(Preferences.USER_PREF_WINDOW_HEIGHT, 0), usable, frame.getMinimumSize());
        frame.setSize(size);
        // The position is not stored: the window is in the middle of the screen, it can't be off the screen.
        frame.setLocationRelativeTo(null);
        boolean maximized = prefs.getBoolean(Preferences.USER_PREF_WINDOW_MAXIMIZED, false);
        if (maximized)
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);

        // The divider the user chose last time, see dividerTarget(). The split pane has the width of the window, it
        // has no size yet.
        userDivider = prefs.getInt(Preferences.USER_PREF_DIVIDER, 0);
        Insets frameInsets = frame.getInsets();
        setDivider(dividerTarget((maximized ? usable.width : size.width) - frameInsets.left - frameInsets.right));

        storedWidth = size.width;
        storedHeight = size.height;
        storedMaximized = maximized;
        storedDivider = userDivider;
        // Not for each pixel of a drag: the preferences are the registry on Windows.
        windowStateTimer = new javax.swing.Timer(500, e -> saveWindowState());
        windowStateTimer.setRepeats(false);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // A narrow window pushed the divider to the left (see below): it goes back to where the user put it
                // if there is room again. Without animation, the window is being resized.
                SwingUtilities.invokeLater(() -> {
                    int target = dividerTarget(splitPane.getWidth());
                    if (target != splitPane.getDividerLocation() && (dividerAnimation == null || !dividerAnimation.isRunning()))
                        setDivider(target);
                });
                windowStateTimer.restart();
            }
        });
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            // Only a drag of the user counts. Not a move of the program, and not the one of the split pane itself: a
            // window that gets narrow pushes the divider to the left, that is where dividerTarget() puts it, too.
            if (!adjustingDivider && splitPane.getDividerLocation() != dividerTarget(splitPane.getWidth())) {
                userDivider = splitPane.getDividerLocation();
                windowStateTimer.restart();
            }
        });
    }

    /**
     * Stores what the user changed: the size of the window (not while it is maximized, the size is the one of the
     * screen then), if it is maximized, and where the user put the divider (see {@link #userDivider}). It is also called
     * before the program exits or restarts, so the last change is not lost to the delay.
     */
    private void saveWindowState() {
        if (!(SwingUtilities.getWindowAncestor(this) instanceof JFrame frame) || !frame.isShowing() || splitPane == null)
            return;
        Preferences prefs = Preferences.getInstance();
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        if (maximized != storedMaximized) {
            prefs.set(Preferences.USER_PREF_WINDOW_MAXIMIZED, maximized);
            storedMaximized = maximized;
        }
        if (userDivider > 0 && userDivider != storedDivider) {
            prefs.set(Preferences.USER_PREF_DIVIDER, userDivider);
            storedDivider = userDivider;
        }
        Dimension size = frame.getSize();
        if (!maximized && (size.width != storedWidth || size.height != storedHeight)) {
            prefs.set(Preferences.USER_PREF_WINDOW_WIDTH, size.width);
            prefs.set(Preferences.USER_PREF_WINDOW_HEIGHT, size.height);
            storedWidth = size.width;
            storedHeight = size.height;
        }
    }

    /**
     * Sets up the signing controls, see {@link SigningPanel}.
     */
    private void wireSigningControls() {
        browseSignature.addActionListener(e -> doBrowseSignatureFile());

        signaturePasswordField.setText(getPref(Preferences.USER_PREF_SIG_PASSWORD, ""));

        signingHelp.addActionListener(e -> HelpDialog.show(this, "help.signing.text", true));

        Preferences prefs = Preferences.getInstance();

        this.signaturePasswordField.setText(prefs.getString(Preferences.USER_PREF_SIG_PASSWORD, ""));
        this.sign.setSelected(prefs.getBoolean(Preferences.USER_PREF_SIG_ENABLED, false));

        this.sign.addActionListener(e -> Preferences.getInstance().set(Preferences.USER_PREF_SIG_ENABLED, this.sign.isSelected()));
    }

    /**
     * Sets up the password, compression and permission controls, see {@link PermissionsPanel}.
     */
    private void wirePermissionControls() {
        permissionHelp.addActionListener(e -> HelpDialog.show(this, "help.permissions.text", true));

        compression.setSelected(true);
        allowPrinting.setSelected(true);

        allowAnnotations.addItemListener(e -> SwingUtilities.invokeLater(this::updatePermissions));
        String ownerPassword = null;

        Preferences prefs = Preferences.getInstance();
        generateOwner.setToolTipText(I18n.t("ui.generateOwner.tip"));
        generateOwner.setBorderPainted(false);
        generateOwner.setOpaque(false);
        generateOwner.setFocusPainted(false);
        generateOwner.addActionListener(e -> ownerPasswordField.setText(UUID.randomUUID().toString()));
        if (prefs.getBoolean(Preferences.USER_PREF_STORE_PASSWORDS, false)) {
            ownerPassword = prefs.getString(Preferences.USER_PREF_OWNER_PASSWORD, null);
            userPasswordField.setText(prefs.getString(Preferences.USER_PREF_USER_PASSWORD, ""));
            signaturePasswordField.setText(prefs.getString(Preferences.USER_PREF_SIG_PASSWORD, ""));
        }
        if (ownerPassword == null)
            ownerPassword = UUID.randomUUID().toString();
        ownerPasswordField.setText(ownerPassword);

        DocumentListener passwordChecker = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePermissions();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePermissions();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePermissions();
            }
        };

        ownerPasswordField.getDocument().addDocumentListener(passwordChecker);
        userPasswordField.getDocument().addDocumentListener(passwordChecker);
    }

    /**
     * Sets up the document controls: open and add files (see {@link ActionBar}), standard and compression (see {@link StandardPanel}), document information (see {@link DocumentPanel}).
     */
    private void wireDocumentControls() {
        browseButton.setToolTipText(I18n.t("ui.browse.tip"));
        new DropTarget(browseButton, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleDrop(dtde, false);
            }
        });

        browseAppendButton.setToolTipText(I18n.t("ui.browseAppend.tip"));
        new DropTarget(browseAppendButton, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleDrop(dtde, true);
            }
        });

        // Dropped on the rest of the window: the first document is opened, later ones are added, nothing is replaced.
        // The buttons above replace the document, they keep their own targets.
        new DropTarget(this, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleDrop(dtde, true);
            }
        });

        browseButton.setOpaque(false);
        browseAppendButton.setOpaque(false);
        invoiceDetailsButton.setToolTipText(I18n.t("ui.invoice.details.tip"));
        invoiceDetailsButton.addActionListener(e -> invoiceActions.doShowValidationDetails());
        invoiceDetailsButton.setVisible(false);
        validatePdfaButton.setToolTipText(I18n.t("menu.validation.pdfa.tip"));
        validatePdfaButton.addActionListener(e -> invoiceActions.doValidatePdfa());
        invoiceHelp.setToolTipText(I18n.t("common.help"));
        invoiceHelp.addActionListener(e -> HelpDialog.show(this, "help.invoice.text", true));
        // The invoice needs PDF/A-3: the locked editor offers the dialog of the standard.
        invoiceEditor.setConvertAction(() -> doChangeStandard(documentProxy == null || documentProxy.getDocument() == null
                ? PdfStandardProfile.PDFA_3B : PdfStandard.pdfa3Level(documentProxy.getDocument())));
        invoiceActions.documentChanged(documentProxy,  true);

        pdfStandard.setFont(hintFont);
        pdfStandard.setToolTipText(I18n.t("ui.pdfStandard.tip"));
        standardButton.setEnabled(false);
        standardButton.setToolTipText(I18n.t("ui.standardButton.tip"));
        standardButton.addActionListener(e -> doChangeStandard());

        exportXmp.setToolTipText(I18n.t("ui.exportXmp.tip"));
        exportXmp.setEnabled(false);
        exportXmp.addActionListener(e -> doExportXmp());

        useDocumentInformation.setSelected(true);
        initDocumentInformation();
        useDocumentInformation.addItemListener(e -> initDocumentInformation());

        clearInformation.addActionListener(e -> {
            useDocumentInformation.setSelected(false);

            SwingUtilities.invokeLater(() -> {
                // Shall work also without invokeLater.
                // Only for the case we get some issue with checkbox change handling.
                title.setText("");
                author.setText("");
                creator.setText("");
                producer.setText("");
                subject.setText("");
                keywords.setText("");
            });
        });
    }

    /**
     * Sets up the buttons for the selected page, see {@link PageToolsStrip}.
     */
    private void wirePageControls() {
        deleteButton.setSymbolColor("Actions.Red", new Color(0xC0504D));
        deleteButton.setOpaque(false);
        deleteButton.setToolTipText(I18n.t("ui.delete.tip"));
        deleteButton.addActionListener(e -> doDeletePage());

        renderToImageButton.setToolTipText(I18n.t("ui.toImage.tip"));
        renderToImageButton.addActionListener(e -> doRenderToImage());

        renderAllToImageButton.setEnabled(false);
        renderAllToImageButton.setToolTipText(I18n.t("ui.allToImages.tip"));
        renderAllToImageButton.addActionListener(e -> {
            if (imagesAllowed())
                runInBusy(this::doRenderAllToImage);
        });

        redactButton.setOpaque(false);
        redactButton.setEnabled(false);
        redactButton.setToolTipText(I18n.t("ui.redact.tip"));
        redactButton.addActionListener(e -> doRedact());

        rotateClockwiseButton.setOpaque(false);
        rotateClockwiseButton.setToolTipText(I18n.t("ui.rotate.tip"));
        rotateClockwiseButton.addActionListener(e -> doRotateClockwise());

        rotateAllClockwiseButton.setEnabled(false);
        rotateAllClockwiseButton.setOpaque(false);
        rotateAllClockwiseButton.setToolTipText(I18n.t("ui.rotateAll.tip"));
        rotateAllClockwiseButton.addActionListener(e -> runInBusy(this::doRotateAllClockwise));

        moveUp.setOpaque(false);
        moveUp.setToolTipText(I18n.t("ui.moveUp.tip"));
        moveUp.addActionListener(e -> doMoveUp());

        moveDown.setOpaque(false);
        moveDown.setToolTipText(I18n.t("ui.moveDown.tip"));
        moveDown.addActionListener(e -> doMoveDown());

        previousPageButton.setOpaque(false);
        previousPageButton.setToolTipText(I18n.t("ui.previousPage.tip"));
        previousPageButton.addActionListener(e -> showPage(pages.getSelectedPageIndex()));

        nextPageButton.setOpaque(false);
        nextPageButton.setToolTipText(I18n.t("ui.nextPage.tip"));
        nextPageButton.addActionListener(e -> showPage(pages.getSelectedPageIndex() + 2));

        exportImages.setToolTipText(I18n.t("ui.exportImages.tip"));
        exportImages.addActionListener(e -> {
            PageWidget pw = pages.getSelectedPage();
            if (pw != null) {
                showImageExtractor(pw.getPage());
            }
        });

        imagesMenu.add(renderToImageButton);
        imagesMenu.add(renderAllToImageButton);
        imagesMenu.addSeparator();
        imagesMenu.add(exportImages);
        imagesButton.setToolTipText(I18n.t("ui.images.tip"));
        imagesButton.addActionListener(e -> imagesMenu.show(imagesButton, imagesButton.getWidth(), 0));
        // The button is usable if the menu has something to do.
        imagesButton.setEnabled(false);
        PropertyChangeListener updateImagesButton = e -> imagesButton.setEnabled(renderToImageButton.isEnabled()
                || renderAllToImageButton.isEnabled() || exportImages.isEnabled());
        renderToImageButton.addPropertyChangeListener("enabled", updateImagesButton);
        renderAllToImageButton.addPropertyChangeListener("enabled", updateImagesButton);
        exportImages.addPropertyChangeListener("enabled", updateImagesButton);

    }

    private final JRadioButtonMenuItem encryptionKeyLength40 = new JRadioButtonMenuItem("40-Bit");
    private final JRadioButtonMenuItem encryptionKeyLength128 = new JRadioButtonMenuItem("128-Bit");
    private final JRadioButtonMenuItem encryptionKeyLength256 = new JRadioButtonMenuItem("256-Bit");


    private final JRadioButtonMenuItem lafLight = new JRadioButtonMenuItem(I18n.t("laf.light"));
    private final JRadioButtonMenuItem lafDark = new JRadioButtonMenuItem(I18n.t("laf.dark"));
    private final JRadioButtonMenuItem lafSystem = new JRadioButtonMenuItem(I18n.t("laf.system"));
    private final JRadioButtonMenuItem lafCross = new JRadioButtonMenuItem(I18n.t("laf.cross"));

    private final JPrefCheckBoxMenuItem reducedMotion = new JPrefCheckBoxMenuItem(I18n.t("menu.reducedMotion"),
            Preferences.USER_PREF_REDUCED_MOTION, Preferences.USER_PREF_REDUCED_MOTION_DEFAULT);

    private final JCheckBoxMenuItem storePasswords = new JCheckBoxMenuItem(I18n.t("ui.storePasswords"));
    private final JCheckBoxMenuItem confirmExit = new JCheckBoxMenuItem(I18n.t("ui.confirmExit"));

    private final JPrefCheckBoxMenuItem viewQualityAA
            = new JPrefCheckBoxMenuItem("Antialiasing", Preferences.USER_PREF_VIEWER_ANTIALIASING,
            Preferences.USER_PREF_VIEWER_ANTIALIASING_DEFAULT);

    private final JRadioButtonMenuItem viewQualityInterpolBiCubic = new JPrefRadioButtonMenuItem(
            "Interpolation bi-cubic", Preferences.USER_PREF_VIEWER_INTERPOLATE_BI_CUBIC, false, true);
    private final JRadioButtonMenuItem viewQualityInterpolBiLinear = new JPrefRadioButtonMenuItem(
            "Interpolation bi-linear", Preferences.USER_PREF_VIEWER_INTERPOLATE_BI_CUBIC, true, false);

    private final JRadioButtonMenuItem viewQualityRenderFast = new JPrefRadioButtonMenuItem(
            "Rendering for speed", Preferences.USER_PREF_VIEWER_RENDER_QUALITY, true, false);
    private final JRadioButtonMenuItem viewQualityRenderQuality = new JPrefRadioButtonMenuItem(
            "Rendering for quality", Preferences.USER_PREF_VIEWER_RENDER_QUALITY, false, true);

    private JMenuBar menuBar;

    /**
     * Makes a button the main button of a window: filled with the accent color of the look and feel, white text.
     * FlatLaf fills a default button only in the dark theme, and a default button of the window is no option: Enter in
     * a text field would press it. Other look and feels keep the normal look. Call it again after
     * the look and feel changed, the colors are set now.
     */
    static void setPrimary(javax.swing.AbstractButton button) {
        Color accent = UIManager.getColor("Component.accentColor");
        if (!isFlatLaf() || accent == null) {
            button.putClientProperty(FlatClientProperties.STYLE, null);
            return;
        }
        // Lighter on dark themes, darker on light ones.
        boolean dark = SideTabs.textOn(UIManager.getColor("Panel.background")) == Color.WHITE;
        Color toward = dark ? Color.WHITE : Color.BLACK;
        String hover = hex(SideTabs.mix(accent, toward, 0.12));
        String pressed = hex(SideTabs.mix(accent, toward, 0.24));
        String fill = hex(accent);
        button.putClientProperty(FlatClientProperties.STYLE, "background: " + fill + "; foreground: #FFFFFF; "
                + "focusedBackground: " + fill + "; hoverBackground: " + hover + "; pressedBackground: " + pressed + "; "
                + "borderColor: " + fill + "; hoverBorderColor: " + hover + "; pressedBorderColor: " + pressed);
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static boolean isFlatLaf() {
        return UIManager.getLookAndFeel() instanceof FlatLaf;
    }

    protected void setLaf(String laf) {
        try {
            if (!Objects.equals(UIManager.getLookAndFeel().getClass().getName(), laf)) {
                UIManager.setLookAndFeel(laf);
                FlatLaf.updateUILater();
                if (pdfChooser != null)
                    SwingUtilities.updateComponentTreeUI(pdfChooser);
                if (imageChooser != null)
                    SwingUtilities.updateComponentTreeUI(imageChooser);
                if (savePdfChooser != null)
                    SwingUtilities.updateComponentTreeUI(savePdfChooser);
                if (signatureChooser != null)
                    SwingUtilities.updateComponentTreeUI(signatureChooser);
                SwingUtilities.updateComponentTreeUI(mergeOptions);
                Preferences.getInstance().set(Preferences.USER_PREF_LAF, laf);
                // FlatLaf.updateUILater() updates the windows later (it is queued before this): what needs the
                // new fonts, colors and sizes (pack, minimum size) has to wait for it.
                SwingUtilities.invokeLater(() -> {
                    normalFont = UIManager.getFont("Panel.font");
                    hintFont = normalFont.deriveFont(Font.ITALIC);
                    saveButton.setFont(normalFont.deriveFont(Font.BOLD, normalFont.getSize2D() * 1.5f));
                    setPrimary(saveButton);
                    setSelectedPage(selectedPage);
                    Window window = SwingUtilities.getWindowAncestor(this);
                    if (window instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                        window.validate();
                    } else if (window != null) {
                        // The new fonts may need more room, but the window keeps the size the user gave it.
                        Dimension before = window.getSize();
                        window.pack();
                        window.setSize(Math.max(before.width, window.getWidth()), Math.max(before.height, window.getHeight()));
                    }
                    updateMinimumWindowSize();
                    // The controls need more or less room with other fonts.
                    updateDivider();
                    Log.info("Switched to %s", laf);
                });
            }
        } catch (Exception e) {
            System.err.println("Failed to switch LAF to " + laf);
            Log.exception(e);
        }
    }

    public static String getPref(String key, String defaultValue) {
        return Preferences.getInstance().getString(key, defaultValue);
    }


    public void setEncryptionKeyLength(int bits) {
        if (bits != 40 && bits != 128 && bits != 256)
            throw new IllegalArgumentException("Encryption Key Length must be 40, 128 or 256");
        if (this.encryptionKeyLength != bits) {
            this.encryptionKeyLength = bits;
            Preferences.getInstance().set(Preferences.USER_ENCRYPTION_KEY_LENGTH, bits);
        }
    }

    public synchronized JMenuBar getMenu() {
        if (menuBar == null) {

            encryptionKeyLength40.addActionListener(e -> setEncryptionKeyLength(40));
            encryptionKeyLength128.addActionListener(e -> setEncryptionKeyLength(128));
            encryptionKeyLength256.addActionListener(e -> setEncryptionKeyLength(256));

            lafDark.addActionListener(e -> setLaf(Preferences.LAF_DARK_CLASSNAME));
            lafLight.addActionListener(e -> setLaf(Preferences.LAF_LIGHT_CLASSNAME));
            lafSystem.addActionListener(e -> setLaf(UIManager.getSystemLookAndFeelClassName()));
            lafCross.addActionListener(e -> setLaf(UIManager.getCrossPlatformLookAndFeelClassName()));

            menuBar = new JMenuBar();

            JMenu encryptionKeyLengthMenu = new JMenu(I18n.t("menu.keyLength"));
            encryptionKeyLengthMenu.add(encryptionKeyLength40);
            encryptionKeyLengthMenu.add(encryptionKeyLength128);
            encryptionKeyLengthMenu.add(encryptionKeyLength256);
            ButtonGroup encryptionKeyLengthGroup = new ButtonGroup();
            encryptionKeyLengthGroup.add(encryptionKeyLength40);
            encryptionKeyLengthGroup.add(encryptionKeyLength128);
            encryptionKeyLengthGroup.add(encryptionKeyLength256);

            JMenu laf = new JMenu(I18n.t("menu.laf"));
            laf.add(lafLight);
            laf.add(lafDark);
            laf.add(lafCross);
            laf.add(lafSystem);
            ButtonGroup lafGroup = new ButtonGroup();
            lafGroup.add(lafLight);
            lafGroup.add(lafDark);
            lafGroup.add(lafCross);
            lafGroup.add(lafSystem);

            storePasswords.setToolTipText(
                    I18n.t("ui.storePasswords.tip"));
            confirmExit.setToolTipText(I18n.t("ui.confirmExit.tip"));
            confirmExit.addActionListener(e ->
                    Preferences.getInstance().set(Preferences.USER_PREF_CONFIRM_EXIT, confirmExit.isSelected()));

            storePasswords.addActionListener(e -> {
                boolean sop = storePasswords.isSelected();
                var prefs = Preferences.getInstance();
                if (sop != prefs.getBoolean(Preferences.USER_PREF_STORE_PASSWORDS, false)) {
                    prefs.set(Preferences.USER_PREF_STORE_PASSWORDS, sop);
                    if (!sop) {
                        prefs.remove(Preferences.USER_PREF_OWNER_PASSWORD);
                        prefs.remove(Preferences.USER_PREF_USER_PASSWORD);
                        prefs.remove(Preferences.USER_PREF_SIG_PASSWORD);
                    }
                }
            });

            JMenu renderQuality = new JMenu(I18n.t("menu.renderQuality"));

            int[] dpis = new int[]{150, 300, 600, 1200};

            ButtonGroup dpiGroup = new ButtonGroup();

            int dpiSetting = Preferences.getInstance().getDpi();

            for (int dpi : dpis) {
                JRadioButtonMenuItem dpiItem = new JRadioButtonMenuItem(String.format("%d dpi", dpi));
                dpiItem.setSelected(dpi == dpiSetting);
                dpiGroup.add(dpiItem);
                renderQuality.add(dpiItem);
                final int dpiFinal = dpi;
                dpiItem.addActionListener(e -> setDpi(dpiFinal));
            }

            this.encryptionKeyLength = Preferences.getInstance().getInt(Preferences.USER_ENCRYPTION_KEY_LENGTH, 256);
            switch (this.encryptionKeyLength) {
                case 40 -> encryptionKeyLength40.setSelected(true);
                case 128 -> encryptionKeyLength128.setSelected(true);
                case 256 -> encryptionKeyLength256.setSelected(true);
                default -> {
                    this.encryptionKeyLength = 256;
                    encryptionKeyLength256.setSelected(true);
                }
            }

            JMenuItem redactionColor = new JMenuItem(I18n.t("menu.redactionColor"));
            redactionColor.addActionListener(e -> RedactionDialog.chooseColor(this));

            JMenu options = new JMenu(I18n.t("menu.options"));
            options.add(storePasswords);
            options.add(confirmExit);
            options.add(encryptionKeyLengthMenu);
            options.add(laf);
            options.add(createLanguageMenu());
            options.add(renderQuality);
            reducedMotion.setToolTipText(I18n.t("menu.reducedMotion.tip"));
            options.add(reducedMotion);
            options.add(redactionColor);

            if (Log.isDebug()) {
                JMenu viewerQuality = new JMenu("Viewer Quality (debug mode)");

                viewerQuality.add(viewQualityAA);
                viewerQuality.addSeparator();

                ButtonGroup interpolateGroup = new ButtonGroup();
                interpolateGroup.add(viewQualityInterpolBiLinear);
                viewerQuality.add(viewQualityInterpolBiLinear);
                interpolateGroup.add(viewQualityInterpolBiCubic);
                viewerQuality.add(viewQualityInterpolBiCubic);

                viewerQuality.addSeparator();

                ButtonGroup qualityRenderGroup = new ButtonGroup();
                qualityRenderGroup.add(viewQualityRenderFast);
                viewerQuality.add(viewQualityRenderFast);
                qualityRenderGroup.add(viewQualityRenderQuality);
                viewerQuality.add(viewQualityRenderQuality);

                options.add(viewerQuality);
            }
            menuBar.add(createFileMenu());
            menuBar.add(options);
            menuBar.add(createFontsMenu());
            menuBar.add(invoiceActions.createValidationMenu());
            menuBar.add(createWizardsMenu());
            JMenu help = new JMenu(I18n.t("common.help"));
            JMenuItem manual = new JMenuItem(I18n.t("menu.manual"));
            manual.setToolTipText(I18n.t("menu.manual.tip"));
            manual.addActionListener(e -> openManual());
            help.add(manual);
            help.addSeparator();
            JMenuItem about = new JMenuItem(I18n.t("menu.about", AboutInfo.NAME));
            about.addActionListener(e -> AboutDialog.show(this));
            help.add(about);
            menuBar.add(help);
        }

        LookAndFeel currentLaf = UIManager.getLookAndFeel();
        String currentLafClassName = currentLaf == null ? null : currentLaf.getClass().getName();

        if (Preferences.LAF_DARK_CLASSNAME.equals(currentLafClassName))
            lafDark.setSelected(true);
        else if (Preferences.LAF_LIGHT_CLASSNAME.equals(currentLafClassName))
            lafLight.setSelected(true);
        else if (UIManager.getCrossPlatformLookAndFeelClassName().equals(currentLafClassName))
            lafCross.setSelected(true);
        else if (UIManager.getSystemLookAndFeelClassName().equals(currentLafClassName))
            lafSystem.setSelected(true);

        storePasswords.setSelected(Preferences.getInstance().getBoolean(
                Preferences.USER_PREF_STORE_PASSWORDS, false));
        confirmExit.setSelected(Preferences.getInstance().getBoolean(Preferences.USER_PREF_CONFIRM_EXIT, true));
        return menuBar;
    }

    /**
     * The file menu: open, add and write, as in the {@link ActionBar}, with keyboard shortcuts. The items click the
     * buttons, so they do the same and are disabled when the buttons are.
     */
    private JMenu createFileMenu() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenu file = new JMenu(I18n.t("menu.file"));
        file.add(createFileItem(I18n.t("menu.file.open"), browseButton,
                KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut)));
        file.add(createFileItem(I18n.t("menu.file.add"), browseAppendButton,
                KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut | InputEvent.SHIFT_DOWN_MASK)));
        file.add(createFileItem(I18n.t("ui.save"), saveButton,
                KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut)));
        // On macOS the system menu has Quit.
        if (!SystemInfo.isMacOS) {
            file.addSeparator();
            JMenuItem exit = new JMenuItem(I18n.t("menu.file.exit"));
            exit.addActionListener(e -> {
                if (confirmExit()) {
                    saveWindowState();
                    System.exit(0);
                }
            });
            file.add(exit);
        }
        return file;
    }

    private static JMenuItem createFileItem(String text, JButton button, KeyStroke accelerator) {
        JMenuItem item = new JMenuItem(text);
        item.setAccelerator(accelerator);
        item.setEnabled(button.isEnabled());
        item.addActionListener(e -> button.doClick());
        button.addPropertyChangeListener("enabled", e -> item.setEnabled(button.isEnabled()));
        return item;
    }

    protected void setDpi(int dpi) {
        Preferences.getInstance().set(Preferences.USER_PREF_DPI, dpi);
    }

    protected void doBrowseSignatureFile() {
        JFileChooser chooser = getSignatureChooser();
        chooser.setDialogTitle(I18n.t("chooser.signature"));
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            loadSignature(selectedFile);
        }
    }

    /**
     * Opens file browser to load another pdf.
     */
    protected void doBrowsePdf() {
        JFileChooser chooser = getPdfChooser();
        chooser.setDialogTitle(I18n.t("chooser.load"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = chooser.getSelectedFiles();
            MergeOptions mo = new MergeOptions();
            if (selectedFiles.length > 0 && confirmInvoiceDiscard(I18n.t("ui.confirm.load"))
                    && confirmDiscard(I18n.t("ui.confirm.load"))) {
                selectPdf(selectedFiles[0]);
                for (int i = 1; i < selectedFiles.length; ++i)
                    appendPdf(selectedFiles[i], mo);
            }
        }
    }

    private final MergeOptionPanel mergeOptions = new MergeOptionPanel();

    /**
     * Opens file browser to load another pdf.
     */
    protected void doBrowseAdditionalPdf() {
        JFileChooser chooser = getPdfChooser();
        chooser.setDialogTitle(I18n.t("chooser.append"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        try {
            mergeOptions.setOriginalPageCount(documentProxy.getPageCount());
            mergeOptions.install(chooser);
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File[] selectedFiles = chooser.getSelectedFiles();
                MergeOptions mo = mergeOptions.getMergeOptions();
                for (File selectedFile : selectedFiles) appendPdf(selectedFile, mo);
            }
        } finally {
            mergeOptions.uninstall(chooser);
        }
    }

    /**
     * Moves the selected page.
     */
    protected void doMoveDown() {
        PageWidget pw = pages.getSelectedPage();
        if (pw != null) {
            int pageCount = documentProxy.getPageCount();
            int pageNb = pw.getPageNumber() + 1;
            if (pageNb > pageCount)
                pageNb = pageCount;
            pw.getPage().movePage(1);
            pages.setSelectedPage(pages.getPageWidget(pageNb));
        }
    }

    /**
     * Moves the selected page.
     */
    protected void doMoveUp() {
        PageWidget pw = pages.getSelectedPage();
        if (pw != null) {
            int pageNb = pw.getPageNumber() - 1;
            if (pageNb <= 0)
                pageNb = 1;
            pw.getPage().movePage(-1);
            pages.setSelectedPage(pages.getPageWidget(pageNb));
        }
    }

    /**
     * Rotates the selected page.
     */
    protected void doRotateClockwise() {
        PageWidget pw = pages.getSelectedPage();
        if (pw != null) {
            Page page = pw.getPage();
            page.rotatePage(90);
        }
    }

    /**
     * Rotates all pages.
     */
    protected void doRotateAllClockwise() {
        if (documentProxy != null)
            documentProxy.rotatePages(90);
    }

    /**
     * Deletes the selected page.
     */
    protected void doDeletePage() {
        PageWidget pw = pages.getSelectedPage();
        if (pw != null) {
            Page page = pw.getPage();
            page.document.deletePage(page.pageNb);
        }
    }

    /**
     * Renders a page as image.
     */
    protected void doRenderToImage() {
        PageWidget pw = pages.getSelectedPage();
        if (pw != null && imagesAllowed()) {
            Page page = pw.getPage();
            page.document.renderPageToImage(page.pageNb);
            toaster.toast(ToastType.SUCCESS, 5000, I18n.t("toast.imageConverted"),
                    Preferences.getInstance().getDpi());
        }
    }

    /**
     * Opens the redaction dialog for the selected page and redacts the selected regions.
     */
    protected void doRedact() {
        PageWidget pw = pages.getSelectedPage();
        if (pw != null && documentProxy != null && documentProxy.getDocument() != null && imagesAllowed()) {
            Page page = pw.getPage();
            List<Redactor.Region> regions = RedactionDialog.showDialog(
                    SwingUtilities.getWindowAncestor(this), documentProxy.getDocument(), page.pageNb);
            if (regions != null && EInvoice.hasInvoice(documentProxy.getDocument())) {
                int choice = JOptionPane.showOptionDialog(this,
                        I18n.t("ui.redact.invoiceWarning"),
                        I18n.t("common.invoice"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                        new Object[]{I18n.t("ui.invoice.remove"), I18n.t("ui.invoice.keep"), I18n.t("common.cancel")}, I18n.t("ui.invoice.remove"));
                if (choice == 0) {
                    try {
                        documentProxy.removeInvoice();
                    } catch (IOException e) {
                        invoiceActions.invoiceError(I18n.t("ui.invoice.removeFailed"), e);
                        return;
                    }
                } else if (choice != 1) {
                    return;
                }
            }
            if (regions != null) {
                runInBusy(() -> {
                    try {
                        documentProxy.redact(regions);
                        toaster.toast(ToastType.SUCCESS, 5000, I18n.t("toast.redacted"), regions.size());
                    } catch (Exception e) {
                        Log.exception(e);
                        toaster.toast(ToastType.ERROR, 20000,
                                I18n.t("toast.redactionFailed"), e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Creates the menu of the wizards: tasks that start from a goal, not from the open document.
     */
    private JMenu createWizardsMenu() {
        JMenu menu = new JMenu(I18n.t("menu.wizards"));
        for (Wizard wizard : Wizards.ALL) {
            JMenuItem item = new JMenuItem(wizard.title());
            item.setToolTipText(wizard.description());
            item.addActionListener(e -> wizard.start(this));
            menu.add(item);
        }
        if (!Wizards.ALL.isEmpty())
            menu.addSeparator();
        JMenuItem layout = new JMenuItem(I18n.t("menu.wizards.layout"));
        layout.setToolTipText(I18n.t("menu.wizards.layout.tip"));
        layout.addActionListener(e -> com.bw.pdfixit.ui.template.LayoutEditorDialog.chooseAndEdit(SwingUtilities.getWindowAncestor(this)));
        menu.add(layout);
        JMenu recent = new JMenu(I18n.t("menu.wizards.recent"));
        recent.setToolTipText(I18n.t("menu.wizards.recent.tip"));
        // The list is made when the menu opens: templates are added by the editor and by the wizards.
        recent.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                fillRecentTemplates(recent);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        menu.add(recent);
        return menu;
    }

    /**
     * Fills the submenu with the last templates. One that doesn't exist any more looks disabled, and is dropped from
     * the list when the user clicks it (a disabled item could not be clicked).
     */
    private void fillRecentTemplates(JMenu menu) {
        menu.removeAll();
        List<String> recent = Preferences.getInstance().getRecentTemplates();
        if (recent.isEmpty()) {
            JMenuItem none = new JMenuItem(I18n.t("menu.wizards.recent.none"));
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (String path : recent) {
            final Path file = Path.of(path);
            JMenuItem item = new JMenuItem(file.getFileName() == null ? path : file.getFileName().toString());
            item.setToolTipText(path);
            if (!Files.isRegularFile(file))
                item.setForeground(UIManager.getColor("MenuItem.disabledForeground"));
            item.addActionListener(e -> {
                if (Files.isRegularFile(file)) {
                    com.bw.pdfixit.ui.template.LayoutEditorDialog.edit(SwingUtilities.getWindowAncestor(this), file);
                } else {
                    Preferences.getInstance().removeRecentTemplate(path);
                    JOptionPane.showMessageDialog(this, I18n.t("menu.wizards.recent.missing", path),
                            I18n.t("menu.wizards"), JOptionPane.WARNING_MESSAGE);
                }
            });
            menu.add(item);
        }
    }

    /**
     * The cards of the start page: the wizards, then the layout editor.
     */
    private List<StartPage.Card> startCards() {
        List<StartPage.Card> cards = new ArrayList<>();
        for (Wizard wizard : Wizards.ALL)
            cards.add(new StartPage.Card(wizard.title(), wizard.description(), wizard.icon(), () -> wizard.start(this)));
        cards.add(new StartPage.Card(I18n.t("start.layout"), I18n.t("menu.wizards.layout.tip"), "svg/card-layout",
                () -> com.bw.pdfixit.ui.template.LayoutEditorDialog.chooseAndEdit(SwingUtilities.getWindowAncestor(this))));
        return cards;
    }

    /**
     * Hands a PDF that a wizard generated to the window: it becomes the current document, marked as changed, so the user
     * is asked to save it before it is lost. The same questions as for loading a file are asked first.
     *
     * @return False if the user declined to replace the current document, nothing happened then.
     */
    public boolean openGenerated(byte[] pdf) {
        if (!confirmInvoiceDiscard(I18n.t("ui.confirm.load")) || !confirmDiscard(I18n.t("ui.confirm.load")))
            return false;
        adopt(new Carry(pdf, true));
        return true;
    }

    /**
     * Creates the menu to show the fonts of the document and to replace them.
     */
    private JMenu createFontsMenu() {
        JMenu menu = new JMenu(I18n.t("menu.fonts"));
        JMenuItem show = new JMenuItem(I18n.t("menu.fonts.show"));
        show.setToolTipText(I18n.t("menu.fonts.show.tip"));
        show.addActionListener(e -> {
            if (documentProxy != null && documentProxy.getDocument() != null)
                FontsDialog.show(this, documentProxy);
        });
        menu.add(show);
        JMenuItem imported = new JMenuItem(I18n.t("menu.fonts.manage"));
        imported.setToolTipText(I18n.t("menu.fonts.manage.tip"));
        imported.addActionListener(e -> ImportedFontsDialog.show(this));
        menu.add(imported);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                show.setEnabled(documentProxy != null && documentProxy.getDocument() != null);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        return menu;
    }


    /**
     * Renders all pages as images.
     */
    protected void doRenderAllToImage() {
        if (documentProxy != null) {
            documentProxy.renderAllPagesToImage();
            toaster.toast(ToastType.SUCCESS, 5000, I18n.t("toast.allImagesConverted"),
                    Preferences.getInstance().getDpi());
        }
    }

    /**
     * Gets the passwords for saving.<br>
     * A document that declares PDF/A is written without encryption, the password fields are disabled then.
     *
     * @return {owner password, user password}, empty strings for no encryption.
     */
    protected String[] passwordsForSave() {
        if (pdfaMode())
            return new String[]{"", ""};
        return new String[]{ownerPasswordField.getText().trim(), userPasswordField.getText().trim()};
    }

    /**
     * Requests a file template and creates multiple documents.
     */
    protected void doSplit() {
        if (pagesPerDocument.getValue() instanceof Number number) {
            int pagePerDocument = number.intValue();
            if (pagePerDocument > 0 && documentProxy != null) {
                if (pdfaMode() && JOptionPane.showConfirmDialog(this, I18n.t("ui.split.pdfaWarning"),
                        I18n.t("ui.split"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
                    return;

                String[] passwords = passwordsForSave();
                if (passwords == null)
                    return;
                String ownerPwd = passwords[0];
                String userPwd = passwords[1];

                JFileChooser chooser = getSavePdfChooser();
                chooser.setMultiSelectionEnabled(false);
                chooser.setDialogTitle(I18n.t("chooser.split"));
                JLabel info = new JLabel(I18n.t("ui.split.info"));
                info.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
                info.setVerticalAlignment(JLabel.TOP);
                chooser.setAccessory(info);
                int result = chooser.showSaveDialog(this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = chooser.getSelectedFile();
                    if (!invoiceEditor.prepareForSave())
                        return;

                    String fprefix;
                    String fpostfix;
                    String fname = selectedFile.getName();
                    int idx = fname.lastIndexOf('.');
                    if (idx < 0) {
                        fprefix = selectedFile.getAbsolutePath();
                        fpostfix = ".pdf";
                    } else {
                        fprefix = new File(selectedFile.getParent(), fname.substring(0, idx)).getAbsolutePath();
                        fpostfix = fname.substring(idx);
                    }

                    int fileCount = 0;
                    try {
                        final PDDocument source = documentProxy.getCopy();
                        final byte[] invoiceXml = EInvoice.extractXml(source).orElse(null);
                        Splitter splitter = new Splitter();
                        splitter.setSplitAtPage(pagePerDocument);
                        List<PDDocument> splittedDocs = splitter.split(source);
                        if (!splittedDocs.isEmpty())
                            documentProxy.reportRisks(I18n.t("ui.split.risk"), PdfRisk.tagsLost(PdfRisk.isTagged(source), splittedDocs.get(0)));
                        for (PDDocument part : splittedDocs) {
                            PDDocument doc = part;
                            if (invoiceXml != null && fileCount == 0) {
                                Log.error("The document contains an invoice. It is only kept in the first of the split documents.");
                                doc = EInvoice.attach(doc, invoiceXml);
                            }
                            String docFile = String.format("%s%03d%s", fprefix, ++fileCount, fpostfix);
                            saveDocument(doc, Paths.get(docFile), ownerPwd, userPwd);
                            Log.info("Stored file '%s'", docFile);
                        }
                        source.close();

                        updatePreferences();

                        toaster.toast(ToastType.SUCCESS, 5000, I18n.t("toast.split"),
                                fname, fileCount, fpostfix);
                    } catch (IOException e) {
                        toaster.toast(ToastType.ERROR, 20000,
                                I18n.t("toast.pdfError"), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Updates preferences that shall be updated only after a save operation.
     */
    protected void updatePreferences() {
        var prefs = Preferences.getInstance();
        prefs.set(Preferences.USER_PREF_SIG_ENABLED, this.sign.isSelected());
        if (prefs.getBoolean(Preferences.USER_PREF_STORE_PASSWORDS, false)) {
            prefs.set(Preferences.USER_PREF_OWNER_PASSWORD, this.ownerPasswordField.getText().trim());
            prefs.set(Preferences.USER_PREF_USER_PASSWORD, this.userPasswordField.getText().trim());
            prefs.set(Preferences.USER_PREF_SIG_PASSWORD, new String(this.signaturePasswordField.getPassword()));
        } else {
            prefs.remove(Preferences.USER_PREF_OWNER_PASSWORD);
            prefs.remove(Preferences.USER_PREF_USER_PASSWORD);
            prefs.remove(Preferences.USER_PREF_SIG_PASSWORD);
        }
    }

    /**
     * Triggers loading of the document in background
     */
    protected synchronized void load(Path file, MergeOptions mo, Consumer<PDDocument> r) {
        documentProxy.ensuredNotClosed();
        synchronized (this) {
            String pw = ownerPasswordField.getText().trim();
            if (pw.isEmpty())
                pw = null;

            UIPdfLoadWorker lw = new UIPdfLoadWorker(documentProxy, file, pw, mo, r);
            loadWorker.addLast(lw);
            startNextLoader();
        }
    }


    /**
     * Starts the next background loader task.
     * Document for document and if all documents are loaded, renders all pages.<br>
     * This is done parallel to the UI that shows the known pages.
     */
    protected synchronized void startNextLoader() {
        if (currentLoader == null || currentLoader.isFinished()) {
            do {
                currentLoader = loadWorker.pollFirst();
            } while (currentLoader != null && currentLoader.isFinished());
            if (currentLoader != null)
                currentLoader.execute();
        }
        if (currentLoader != null && documentProxy.getDocument() != null) {
            int pageCount = documentProxy.getDocument().getNumberOfPages();
            while (documentProxy.pages.size() < pageCount) {
                Page page = new Page(documentProxy, documentProxy.pages.size() + 1, pageCount);
                documentProxy.pages.add(page);
            }
            renderQueue.addDocument(documentProxy);
        }
    }

    /**
     * The menu to choose the language of the user interface. A change is applied by a restart, which is offered.
     */
    private JMenu createLanguageMenu() {
        JMenu menu = new JMenu(I18n.t("menu.language"));
        ButtonGroup group = new ButtonGroup();
        String current = I18n.getPreferredLanguage();
        List<String> codes = new ArrayList<>();
        codes.add(I18n.SYSTEM);
        codes.addAll(I18n.availableLanguages());
        for (String code : codes) {
            // Language names are shown in their own language, so they can be found even if the language is unknown.
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    code.equals(I18n.SYSTEM) ? I18n.t("menu.language.system") : I18n.languageName(code));
            item.setSelected(code.equals(current));
            item.addActionListener(e -> changeLanguage(code));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private void changeLanguage(String code) {
        if (code.equals(I18n.getPreferredLanguage()))
            return;
        I18n.setPreferredLanguage(code);
        Object[] options = {I18n.t("language.restart.now"), I18n.t("language.restart.later")};
        int choice = JOptionPane.showOptionDialog(this, I18n.t("language.restart.message"),
                I18n.t("language.restart.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice != 0)
            return;
        restartUI(I18n.t("language.restart.title"));
    }

    /**
     * Creates the user interface anew. The document, with its unwritten changes, is carried over.
     *
     * @param title The title of an error message.
     */
    private void restartUI(String title) {
        final Carry carry;
        try {
            carry = capture();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, I18n.t("common.errorPrefix", e.getMessage()),
                    title, JOptionPane.ERROR_MESSAGE);
            return;
        }
        shutdown();
        Main.restartUI(carry);
    }

    /**
     * The state that is carried over when the user interface is created anew, e.g. after the language was changed.
     *
     * @param pdf      The effective document, or null if there is none.
     * @param modified True if the document has unwritten changes.
     */
    public record Carry(byte[] pdf, boolean modified) {
    }

    /**
     * Takes the document of this user interface.
     *
     * @return The state, or null if there is no document.
     */
    private Carry capture() throws IOException {
        if (documentProxy == null || documentProxy.getDocument() == null)
            return null;
        return new Carry(documentProxy.getBytes(), documentProxy.isModified());
    }

    /**
     * Releases the resources of this user interface and closes its window.
     */
    private void shutdown() {
        // The new window gets the size of this one.
        saveWindowState();
        if (windowStateTimer != null)
            windowStateTimer.stop();
        if (dividerAnimation != null)
            dividerAnimation.stop();
        renderQueue.stop();
        if (documentProxy != null)
            documentProxy.close();
        documentProxy = null;
        toaster.clear();
        if (imageExtractorDialog != null)
            imageExtractorDialog.dispose();
        // Also closes the dialogs that belong to the window.
        mainWindow.dispose();
    }

    /**
     * Loads the document of {@link #capture()}. The file is only a vehicle: it is deleted when the document is loaded.
     */
    private void adopt(Carry carry) {
        try {
            final Path tmp = Files.createTempFile("PDFixit-", ".pdf");
            Files.write(tmp, carry.pdf());
            selectPdf(tmp.toFile(), false);
            documentProxy.addDocumentConsumer(new DocumentProxy.DocumentConsumer() {
                private boolean done;

                private void finish(boolean loaded) {
                    if (done)
                        return;
                    done = true;
                    if (loaded && carry.modified())
                        documentProxy.setModified();
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException e) {
                        Log.error("Failed to delete %s: %s", tmp, e.getMessage());
                    }
                }

                @Override
                public void documentLoaded(PDDocument document) {
                    finish(true);
                }

                @Override
                public void failed(String error) {
                    finish(false);
                }
            });
        } catch (IOException e) {
            toaster.toast(ToastType.ERROR, 10000, I18n.t("toast.readFailed"), e.getMessage());
        }
    }

    /**
     * The texts of these objects are created when they are needed. Called if the language has changed.
     */
    public static void resetLocalizedState() {
        savePdfChooser = null;
        pdfChooser = null;
        imageChooser = null;
        signatureChooser = null;
        pdfFilter = new FileNameExtensionFilter(I18n.t("filter.pdf"), "pdf");
        imageFilter = new FileNameExtensionFilter(I18n.t("filter.image"), "jpeg", "jpg", "png");
        sigFilter = new FileNameExtensionFilter(I18n.t("filter.p12"), "p12");
    }

    /**
     * Asks to save if the option is set and the document has unsaved changes.
     *
     * @return True if the program may exit.
     */
    protected boolean confirmExit() {
        return confirmInvoiceDiscard(I18n.t("ui.confirm.exit")) && confirmDiscard(I18n.t("ui.confirm.exit"));
    }

    /**
     * Asks before the changes of the invoice editor are lost by exiting or by loading another document, if the option
     * to confirm is set.
     *
     * @param title The title of the question.
     * @return True if there are no changes or they may be discarded.
     */
    private boolean confirmInvoiceDiscard(String title) {
        return !invoiceEditor.isDirty() || !Preferences.getInstance().getBoolean(Preferences.USER_PREF_CONFIRM_EXIT, true)
                || JOptionPane.showConfirmDialog(this, I18n.t("ui.confirm.invoiceUnsaved"), title,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /**
     * Asks to save if the option is set and the document has unsaved changes, before they are lost
     * by exiting or by loading another document.
     *
     * @param title The title of the question dialog.
     * @return True if the changes may be discarded.
     */
    protected boolean confirmDiscard(String title) {
        if (documentProxy == null || !documentProxy.isModified()
                || !Preferences.getInstance().getBoolean(Preferences.USER_PREF_CONFIRM_EXIT, true))
            return true;
        Object[] options = {I18n.t("ui.confirm.write"), I18n.t("ui.confirm.dontWrite"), I18n.t("common.cancel")};
        int choice = JOptionPane.showOptionDialog(this, I18n.t("ui.confirm.unsaved"), title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            doSave();
            // Still modified if the file dialog was cancelled or writing failed.
            return !documentProxy.isModified();
        }
        return choice == 1;
    }

    /**
     * Requests a file name and saves the document.
     */
    protected void doSave() {
        if (documentProxy != null && documentProxy.getDocument() != null) {

            try {
                JFileChooser chooser = getSavePdfChooser();
                int result = chooser.showSaveDialog(this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = chooser.getSelectedFile();
                    Path selectedFilePath = selectedFile.toPath();

                    String fname = selectedFilePath.getFileName().toString();
                    int idx = fname.lastIndexOf('.');
                    if (idx < 0) {
                        selectedFilePath = selectedFilePath.getParent().resolve(fname + ".pdf");
                    }

                    if (Files.exists(selectedFilePath) && !askOverwrite(this, selectedFilePath)) {
                        return;
                    }

                    // The invoice of the editor is part of the document like the document information.
                    if (!invoiceEditor.prepareForSave())
                        return;

                    String[] passwords = passwordsForSave();
                    if (passwords == null)
                        return;
                    String ownerPwd = passwords[0];
                    String userPwd = passwords[1];

                    saveDocument(documentProxy.getCopy(), selectedFilePath, ownerPwd, userPwd);
                    documentProxy.clearModified();

                    updatePreferences();

                    List<Object> options = new ArrayList<>(2);
                    options.add("OK");

                    // If supported, give buttons to browse/view the result
                    // in the system default application (vie Desktop-support)
                    if (Desktop.isDesktopSupported()) {
                        try {
                            final URI finalFile = selectedFilePath.toUri();
                            JButton open = new JButton(I18n.t("ui.openExternal"));
                            open.setToolTipText(I18n.t("ui.openExternal.tip"));
                            open.addActionListener(e -> {
                                try {
                                    Desktop.getDesktop().browse(finalFile);
                                } catch (Exception ex) {
                                    JOptionPane.showMessageDialog(this, I18n.t("common.errorPrefix", ex.getMessage()), I18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                                }
                            });
                            options.add(open);
                            final URI finalParent = selectedFilePath.getParent().toUri();
                            JButton openDir = new JButton(I18n.t("ui.openDir"));
                            openDir.setToolTipText(I18n.t("ui.openDir.tip"));
                            openDir.addActionListener(e -> {
                                try {
                                    Desktop.getDesktop().browse(finalParent);
                                } catch (Exception ex) {
                                    JOptionPane.showMessageDialog(this, I18n.t("common.errorPrefix", ex.getMessage()), I18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                                }
                            });
                            options.add(openDir);
                        } catch (Exception ex) {
                            // Will possibly never happen
                            Log.error("Error in preparation of save-info buttons: %s", ex.getMessage());
                        }
                    }

                    // toaster.toast("<html>Stored as<br><font size='+1'><i>%s</i></font></html>", selectedFilePath.getFileName());
                    JOptionPane.showOptionDialog(this,
                            I18n.t("ui.stored.message", selectedFilePath), I18n.t("ui.stored.title"),
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE, null, options.toArray(), null);


                }
            } catch (Exception ex) {
                Log.exception(ex);
                toaster.toast(ToastType.ERROR, 20000, I18n.t("toast.error"), ex.getMessage());
                // JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Saves one document. The document is closed during the call.
     */
    protected void saveDocument(PDDocument document, Path file, String ownerPwd, String userPwd) throws IOException {
        try {

            document = EInvoice.repair(document);
            setDocumentInformation(document);
            final boolean doSign = this.sign.isSelected();

            if ((!ownerPwd.isEmpty()) || (!userPwd.isEmpty())) {
                if (EInvoice.hasInvoice(document))
                    Log.error("The document contains an invoice. Encryption is not allowed for PDF/A-3, so it is no valid ZUGFeRD/Factur-X document any more.");
                AccessPermission ap = new AccessPermission();
                ap.setCanPrint(allowPrinting.isSelected());
                ap.setCanModify(allowModification.isSelected());
                ap.setCanExtractContent(allowExtraction.isSelected());
                ap.setCanExtractForAccessibility(allowExtraction.isSelected());
                ap.setCanFillInForm(allowFillIn.isSelected());
                ap.setCanAssembleDocument(allowAssembly.isSelected());
                ap.setCanModifyAnnotations(allowAnnotations.isSelected());

                StandardProtectionPolicy spp = new StandardProtectionPolicy(ownerPwd, userPwd, ap);
                Log.info("Encryption-key-length %d bits", this.encryptionKeyLength);
                spp.setEncryptionKeyLength(this.encryptionKeyLength);
                document.protect(spp);

                if (doSign) {
                    // For some reason the encryption breaks if we sign fresh protected document.
                    // If we reload it (in protected state) it works.
                    ByteArrayOutputStream os = new ByteArrayOutputStream(5 * 1024 * 1024);
                    document.save(os, CompressParameters.NO_COMPRESSION);
                    document.close();
                    document = Loader.loadPDF(os.toByteArray(), ownerPwd.isEmpty() ? userPwd : ownerPwd);
                }
            }

            // Signing happens while saving, if it fails, an existing file must stay as it is.
            final PDDocument toSave = document;
            if (doSign)
                sign(toSave);
            SafeFile.write(file, os -> {
                if (doSign)
                    toSave.saveIncremental(os);
                else
                    toSave.save(os);
            });
        } finally {
            if (document != null)
                document.close();
        }

    }

    /**
     * Prepares the signature, it is created by the next incremental save.
     *
     * @throws IOException If the key can't be used.
     */
    protected void sign(PDDocument document) throws IOException {
        char[] pw = signaturePasswordField.getPassword();
        try {
            SignatureTool createSignature = new SignatureTool();

            try (InputStream is = Files.newInputStream(signatureKeyFile.toPath())) {
                String alias = createSignature.addKey(is, pw);
                createSignature.addSignature(document, alias, pw);
            }
        } catch (Exception ex) {
            throw new IOException(I18n.t("ui.signingFailed", ex.getMessage()), ex);
        }
        Preferences pref = Preferences.getInstance();
        pref.set(Preferences.USER_PREF_SIG_PASSWORD, new String(pw));
    }

    public static boolean askOverwrite(Component comp, Path file) {
        int overwrite = JOptionPane.showConfirmDialog(
                comp,
                I18n.t("ui.overwrite.message", file),
                I18n.t("ui.overwrite.title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        return (overwrite == JOptionPane.YES_OPTION);
    }

    /**
     * Gets a svg icon by name. The svg file must exist in root folder of resources.
     *
     * @param name The name (filename without extension)
     * @return the icon, null if not found.
     */
    public static Icon getIcon(String name) {
        synchronized (icons) {
            Icon i = icons.get(name);
            if (i == null) {
                try (InputStream is = UI.class.getResourceAsStream("/" + name + ".svg")) {
                    ShapeIcon si = new ShapeIcon(SVGConverter.convert(is));
                    icons.put(name, si);
                    i = si;
                } catch (Exception e) {
                    Log.error("Error in getting icon '%s': %s", name, e.getMessage());
                }
            }
            return i;
        }
    }

    /**
     * The window is not smaller than the controls need (their width is protected in the split pane, they scroll only
     * vertically) plus a usable preview of the pages. Call it after the window is packed and after a change of the
     * font or the look and feel.
     */
    void updateMinimumWindowSize() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null)
            return;
        Dimension min = new Dimension(getMinimumSize());
        // The controls need the room of the open tabs. The window keeps its minimum if another tab is opened, it is the
        // one for the widest tab: only the divider moves then, never the window.
        if (drawer != null)
            min.width += Math.max(0, drawer.widestWidth() - drawer.getPreferredSize().width);
        Insets in = window.getInsets();
        int menuHeight = window instanceof JFrame f && f.getJMenuBar() != null ? f.getJMenuBar().getPreferredSize().height : 0;
        window.setMinimumSize(new Dimension(min.width + in.left + in.right, min.height + menuHeight + in.top + in.bottom));
    }

    private void setSelectedPage(PageWidget page) {
        selectedPage = page;
        boolean enabled = page != null;
        deleteButton.setEnabled(enabled);
        renderToImageButton.setEnabled(enabled);
        redactButton.setEnabled(enabled);
        rotateClockwiseButton.setEnabled(enabled);
        exportImages.setEnabled(enabled);
        updatePageCounter();
        updateZoomControls();
    }

    /**
     * Shows the selected page, the page count and the rotation. The resolution and the scale of the page are
     * only in the tooltip, and only in debug mode.<br>
     * The buttons for the previous and the next page and for moving the page are only enabled if there is a page in
     * that direction.
     */
    private void updatePageCounter() {
        final PageWidget page = selectedPage;
        final int count = documentProxy == null ? 0 : documentProxy.getPageCount();
        final int pageNb = page == null ? 0 : page.getPageNumber();
        previousPageButton.setEnabled(pageNb > 1);
        moveUp.setEnabled(pageNb > 1);
        nextPageButton.setEnabled(pageNb > 0 && pageNb < count);
        moveDown.setEnabled(pageNb > 0 && pageNb < count);
        if (page == null) {
            pageCounter.setState(0, count);
            pageOrientation.setRotation(0);
            pageCounter.setToolTipText(I18n.t("ui.selectPage"));
            pageOrientation.setToolTipText(null);
            return;
        }
        final int rotation = page.getPage().getRotation();
        pageCounter.setState(pageNb, count);
        pageOrientation.setRotation(rotation);
        String tip = rotation % 360 == 0
                ? I18n.t("ui.pageCounter.tip", pageNb, count)
                : I18n.t("ui.pageCounter.rotated.tip", pageNb, count, Math.floorMod(rotation, 360));
        pageOrientation.setToolTipText(rotation % 360 == 0 ? null : tip);
        if (Log.isDebug())
            tip = String.format("%s (%d dpi, x %.2f)", tip, page.getPage().dpi, page.getScale());
        pageCounter.setToolTipText(tip);
    }

    /**
     * Selects a page and scrolls to its top, like the page keys of a viewer.
     *
     * @param pageNb The page (1 based). Nothing happens if there is no such page.
     */
    private void showPage(int pageNb) {
        if (documentProxy == null || pageNb < 1 || pageNb > documentProxy.getPageCount())
            return;
        PageWidget pw = pages.getPageWidget(pageNb);
        pages.setSelectedPage(pw);
        // The pages scroll the selected page into view later, but a page taller than the view is shown from its bottom
        // if it is above. This comes after that.
        SwingUtilities.invokeLater(() -> {
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pw);
            if (viewport != null && !pw.getBounds().isEmpty())
                pw.scrollRectToVisible(new Rectangle(0, 0, pw.getWidth(),
                        Math.min(pw.getHeight(), viewport.getExtentSize().height)));
        });
    }

    protected void loadSignature(File file) {
        this.signatureKeyFileName.setText(file == null ? "" : file.getName());
        this.signatureKeyFile = file;
        Preferences pref = Preferences.getInstance();
        if (file != null) {
            String parent = file.getParent();
            if (parent != null)
                pref.set(Preferences.USER_PREF_LAST_SIG_DIR, parent);
            pref.set(Preferences.USER_PREF_SIG_FILE, file.toString());
        } else {
            pref.remove(Preferences.USER_PREF_SIG_FILE);
            pref.set(Preferences.USER_PREF_SIG_ENABLED, false);
            this.sign.setSelected(false);
        }
    }

    protected void appendPdf(File file, MergeOptions mo) {
        if (documentProxy == null)
            selectPdf(file);
        else {
            String parent = file.getParent();
            if (parent != null)
                Preferences.getInstance().set(Preferences.USER_PREF_LAST_PDF_DIR, parent);

            final String fileName = file.getName();
            Toast toast = toaster.toast(I18n.t("toast.appending"), fileName);
            long start = System.currentTimeMillis();
            load(file.toPath(), mo, document -> {
                if (toast != null)
                    toast.setMessage(String.format(I18n.t("toast.appended"),
                            System.currentTimeMillis() - start,
                            fileName));
            });
        }
    }

    private boolean busy;

    private final MouseAdapter busyMouseAdapter = new MouseAdapter() {
    };

    private final KeyAdapter busyKeyAdapter = new KeyAdapter() {
    };

    /**
     * The windows that are blocked while busy.
     */
    private final List<Window> busyWindows = new ArrayList<>();

    public void setBusy(boolean busy) {
        if (this.busy != busy) {
            Log.info("setBusy(%s)", busy);
            this.busy = busy;
            if (busy) {
                // The main window and the windows it owns (e.g. the invoice window).
                List<Window> windows = new ArrayList<>();
                windows.add(UI.mainWindow);
                windows.addAll(List.of(UI.mainWindow.getOwnedWindows()));
                for (Window w : windows) {
                    if (!w.isShowing() || !(w instanceof RootPaneContainer rpc))
                        continue;
                    w.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                    Component glassPane = rpc.getGlassPane();
                    glassPane.setVisible(true);
                    glassPane.addMouseListener(busyMouseAdapter);
                    glassPane.addKeyListener(busyKeyAdapter);
                    busyWindows.add(w);
                }
                UI.mainWindow.getGlassPane().requestFocusInWindow();
            } else {
                for (Window w : busyWindows) {
                    w.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    Component glassPane = ((RootPaneContainer) w).getGlassPane();
                    glassPane.setVisible(false);
                    glassPane.removeMouseListener(busyMouseAdapter);
                    glassPane.removeKeyListener(busyKeyAdapter);
                }
                busyWindows.clear();
            }
        }
    }

    /**
     * Runs the code on the event dispatch thread: directly if already there, otherwise later.
     */
    private static void runOnDispatch(Runnable r) {
        if (SwingUtilities.isEventDispatchThread())
            r.run();
        else
            SwingUtilities.invokeLater(r);
    }

    public void runInBusy(final Runnable r) {
        setBusy(true);
        new SwingWorker<>() {
            @Override
            protected Object doInBackground() {
                r.run();
                return null;
            }

            @Override
            protected void done() {
                setBusy(false);

            }
        }.execute();
    }

    /**
     * Select a pdf file for preview and processing.
     */
    protected void selectPdf(File selectedFile) {
        selectPdf(selectedFile, true);
    }

    /**
     * Select a pdf file for preview and processing.
     *
     * @param rememberDirectory True to store the directory of the file as the one to start in next time.
     */
    protected void selectPdf(File selectedFile, boolean rememberDirectory) {
        if (selectedFile != null) {
            selectedFile = selectedFile.getAbsoluteFile();
            String parent = selectedFile.getParent();
            if (parent != null && rememberDirectory)
                Preferences.getInstance().set(Preferences.USER_PREF_LAST_PDF_DIR, parent);
        }
        if (documentProxy != null) {
            documentProxy.close();
        }
        documentProxy = null;
        pages.clear();
        ((java.awt.CardLayout) pagesCards.getLayout()).show(pagesCards, selectedFile == null ? CARD_START : CARD_PAGES);
        // The errors belong to the old document.
        documentErrors.forEach(toaster::close);
        documentErrors.clear();

        if (selectedFile != null) {
            documentProxy = new DocumentProxy(renderQueue);
            initialLoad = true;
            hadInvoice = false;
            documentProxy.invoiceStandardCheck = this::checkInvoiceStandard;
            documentProxy.pdfaFixListener = changes -> runOnDispatch(() -> notifyPdfaFixes(changes));
            documentProxy.riskListener = (fileName, risks) -> runOnDispatch(() -> {
                StringBuilder list = new StringBuilder();
                risks.forEach(r -> list.append("&bull; ").append(r).append("<br>"));
                toaster.toast(ToastType.WARNING, 15000,
                        I18n.t("toast.risks"), fileName, list);
            });
            documentProxy.appendCheck = doc -> {
                if (!pdfaMode || PdfStandard.pdfaPart(doc) > 0)
                    return null;
                runOnDispatch(() -> documentError(I18n.t("toast.notAppended")));
                return I18n.t("ui.notAppended");
            };
            final java.util.function.Function<PDDocument, String> pdfaCheck = documentProxy.appendCheck;
            documentProxy.appendCheck = doc -> {
                String refused = pdfaCheck.apply(doc);
                if (refused != null || !EInvoice.hasInvoice(doc))
                    return refused;
                refused = checkInvoiceStandard();
                if (refused != null) {
                    // Declined by the user (no error) or the question failed.
                    final String reason = refused;
                    runOnDispatch(() -> toaster.toast(ToastType.WARNING, 10000, I18n.t("toast.notAppendedReason"),
                            escapeHtml(reason)));
                }
                return refused;
            };
            documentProxy.addDocumentConsumer(new DocumentProxy.DocumentConsumer() {

                @Override
                public void documentLoaded(PDDocument document) {
                    // This method is called each time some file wasloaded.
                    // "document" is always the main effective document.

                    renderAllToImageButton.setEnabled(true);
                    rotateAllClockwiseButton.setEnabled(document.getNumberOfPages() > 0);
                    saveButton.setEnabled(true);
                    browseAppendButton.setEnabled(true);
                    standardButton.setEnabled(true);
                    // May be called from background tasks (e.g. attaching an invoice).
                    final boolean hasInvoice = EInvoice.hasInvoice(document);
                    runOnDispatch(() -> {
                        // Only an added invoice is repaired automatically, not a loaded document.
                        final boolean invoiceAdded = hasInvoice && !hadInvoice && !initialLoad;
                        // The invoice editor shows the loaded document, not what was edited for the one before.
                        final boolean loaded = initialLoad;
                        initialLoad = false;
                        hadInvoice = hasInvoice;
                        invoiceActions.documentChanged(documentProxy, loaded);
                        updatePdfAState(invoiceAdded);
                    });

                    initDocumentInformation();
                }

                @Override
                public void failed(String error) {
                    if (documentProxy == null)
                        // Force initialisation for case where loading the main file failed.
                        selectPdf(null);
                    runOnDispatch(() -> {
                        // The main document is not loading any more.
                        toaster.close(loadingToast[0]);
                        loadingToast[0] = null;
                        documentError(String.format(I18n.t("toast.loadFailed"), toastText(error)));
                    });
                }
            });
            pages.setDocument(documentProxy);
            try {
                final String fileName = selectedFile.getName();
                Toast toast = toaster.toast(I18n.t("toast.loading"), fileName);
                loadingToast[0] = toast;
                long start = System.currentTimeMillis();
                load(selectedFile.toPath(), new MergeOptions(), document -> {
                    loadingToast[0] = null;
                    if (toast != null)
                        toast.setMessage(String.format(I18n.t("toast.loaded"),
                                System.currentTimeMillis() - start, fileName));
                });
            } catch (Exception e) {
                toaster.close(loadingToast[0]);
                loadingToast[0] = null;
                selectPdf(null);
                // After the reset above, which closes the errors of the old document.
                documentError(String.format(I18n.t("toast.loadFailed"),
                        toastText(e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        } else {
            initDocumentInformation();

            standardButton.setEnabled(false);
            pdfaMode = false;
            renderAllToImageButton.setEnabled(false);
            rotateAllClockwiseButton.setEnabled(false);
            saveButton.setEnabled(false);
            splitDocument.setEnabled(false);
            browseAppendButton.setEnabled(false);
            runOnDispatch(() -> invoiceActions.documentChanged(documentProxy,  true));
        }
    }

    /**
     * Writes the document information from the UI fields. The XMP metadata is kept and updated,
     * removing it would break PDF/A documents and e-invoices.
     */
    protected void setDocumentInformation(PDDocument pdDocument) {
        DocumentInformation.apply(pdDocument, new DocumentInformation.Values(
                title.getText(), author.getText(), subject.getText(), keywords.getText(),
                creator.getText(), producer.getText()));
    }

    /**
     * Saves the XMP metadata of the document to a file.
     */
    protected void doExportXmp() {
        final PDDocument document = documentProxy == null ? null : documentProxy.getDocument();
        if (document == null)
            return;
        try {
            byte[] xmp = DocumentInformation.extractXmp(document).orElse(null);
            if (xmp == null) {
                toaster.toast(ToastType.ERROR, 5000, I18n.t("toast.noXmp"));
                return;
            }
            JFileChooser chooser = new JFileChooser();
            String lastDir = getPref(Preferences.USER_PREF_LAST_PDF_DIR, null);
            if (lastDir != null)
                chooser.setCurrentDirectory(new File(lastDir));
            chooser.setDialogTitle(I18n.t("chooser.saveXmp"));
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setMultiSelectionEnabled(false);
            chooser.setFileFilter(new FileNameExtensionFilter(I18n.t("filter.xmp"), "xmp", "xml"));
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), "metadata.xmp"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
                return;
            File file = chooser.getSelectedFile();
            if (!file.getName().contains("."))
                file = new File(file.getParentFile(), file.getName() + ".xmp");
            if (file.exists() && !askOverwrite(this, file.toPath()))
                return;
            java.nio.file.Files.write(file.toPath(), xmp);
            toaster.toast(ToastType.SUCCESS, 5000, I18n.t("toast.xmpStored"), file.getName());
        } catch (Exception e) {
            Log.exception(e);
            toaster.toast(ToastType.ERROR, 20000, I18n.t("toast.xmpFailed"),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    protected void initDocumentInformation() {

        final boolean useDoc = useDocumentInformation.isSelected();
        final PDDocument document = documentProxy == null ? null : documentProxy.getDocument();
        final boolean hasXmp = document != null && document.getDocumentCatalog().getMetadata() != null;
        runOnDispatch(() -> exportXmp.setEnabled(hasXmp));
        if (document != null) {
            runOnDispatch(this::updatePageCounter);
            final String standard = PdfStandard.describe(document);
            SwingUtilities.invokeLater(() -> pdfStandard.setText(standard));

            if (useDoc) {
                PDDocumentInformation info = document.getDocumentInformation();

                String a = info.getTitle();
                title.setText(a == null ? "" : a);
                a = info.getAuthor();
                author.setText(a == null ? "" : a);
                a = info.getCreator();
                creator.setText(a == null ? "" : a);
                a = info.getProducer();
                producer.setText(a == null ? "" : a);
                a = info.getSubject();
                subject.setText(a == null ? "" : a);
                a = info.getKeywords();
                keywords.setText(a == null ? "" : a);
            }
        } else {
            runOnDispatch(this::updatePageCounter);
            pdfStandard.setText("");
            if (useDoc) {
                title.setText("");
                author.setText("");
                creator.setText("");
                producer.setText("");
                subject.setText("");
                keywords.setText("");
            }
        }
        if (title.isEditable() == useDoc) {
            title.setEditable(!useDoc);
            author.setEditable(!useDoc);
            creator.setEditable(!useDoc);
            producer.setEditable(!useDoc);
            subject.setEditable(!useDoc);
            keywords.setEditable(!useDoc);
        }

    }


    /**
     * @return True if the document declares PDF/A: everything that would violate it is disabled.
     */
    protected boolean pdfaMode() {
        return pdfaMode;
    }

    /**
     * Enables or disables everything that would violate PDF/A, depending on the PDF/A level the document declares.<br>
     * A loaded document is not repaired. A document that declares PDF/A is validated, the user is asked to repair
     * it if that finds problems, see {@link #reportPdfaViolations}. Conversions are done by the user, see
     * {@link #doChangeStandard}.
     *
     * @param repair True to repair the document first. That is only done when an invoice was added.
     */
    protected void updatePdfAState(boolean repair) {
        boolean hasDocument = documentProxy != null && documentProxy.getDocument() != null;
        if (hasDocument && repair) {
            try {
                // Declares PDF/A-3 if the document declares nothing.
                var changes = documentProxy.fixPdfa();
                if (!changes.isEmpty())
                    notifyPdfaFixes(changes);
            } catch (FontEmbedException e) {
                // The document is not changed.
                Log.error("PDF/A: %s", e.getMessage());
                toaster.toast(ToastType.ERROR, 20000,
                        I18n.t("toast.pdfaNotPossible"), e.getMessage());
            } catch (IOException e) {
                Log.error("Failed to fix PDF/A issues: %s", e.getMessage());
            }
        }
        if (hasDocument && PdfStandard.pdfaPart(documentProxy.getDocument()) > 0) {
            // The result of a switch is shown together with its changes, see doChangeStandard.
            if (!switchingStandard)
                reportPdfaViolations();
        }
        pdfaMode = hasDocument && PdfStandard.pdfaPart(documentProxy.getDocument()) > 0;
        // Compressed object streams are not allowed by every standard: off and not selectable then.
        PdfStandardProfile declared = hasDocument ? PdfStandard.profile(documentProxy.getDocument()) : null;
        final boolean compressionAllowed = declared == null || declared.allowsCompression();
        if (!compressionAllowed && compression.isSelected()) {
            compression.setSelected(false);
            toaster.toast(ToastType.INFO, 10000, I18n.t("toast.compressionOff", declared));
        }
        compression.setEnabled(compressionAllowed);
        compression.setToolTipText(compressionAllowed ? I18n.t("ui.compression.tip") : I18n.t("toast.compressionOff", declared));
        final boolean pdfa = pdfaMode;
        final String notAllowed = I18n.t("ui.pdfaNotAllowed");

        ownerPasswordField.setEnabled(!pdfa);
        userPasswordField.setEnabled(!pdfa);
        generateOwner.setEnabled(!pdfa);
        for (JRadioButtonMenuItem item : new JRadioButtonMenuItem[]{encryptionKeyLength40, encryptionKeyLength128, encryptionKeyLength256})
            item.setEnabled(!pdfa);
        ownerPasswordField.setToolTipText(pdfa ? notAllowed : null);
        userPasswordField.setToolTipText(pdfa ? notAllowed : null);

        // Splitting is possible for PDF/A too, but the parts are not PDF/A: it warns, see doSplit.
        splitDocument.setEnabled(hasDocument);
        splitDocument.setToolTipText(pdfa ? I18n.t("ui.split.pdfaWarning") : null);

        updatePermissions();
    }

    /**
     * Opens the dialog to change the PDF/A level of the document and converts it.
     */
    protected void doChangeStandard() {
        doChangeStandard(null);
    }

    /**
     * Opens the dialog to change the PDF standard of the document and converts it.
     *
     * @param preselected The level that is selected when the dialog opens, null for the current one.
     */
    protected void doChangeStandard(PdfStandardProfile preselected) {
        final DocumentProxy proxy = documentProxy;
        if (proxy == null || proxy.getDocument() == null)
            return;
        final byte[] bytes;
        try {
            bytes = proxy.getBytes();
        } catch (IOException e) {
            Log.exception(e);
            toaster.toast(ToastType.ERROR, 10000, I18n.t("toast.readFailed"), e.getMessage());
            return;
        }
        StandardDialog.Choice choice = StandardDialog.showDialog(this, bytes, PdfStandard.profile(proxy.getDocument()),
                proxy.getDocument().getVersion(), preselected);
        if (choice == null)
            return;
        reportedResult = ValidationResult.conform();
        // The document is announced as changed: that must not start a second check.
        switchingStandard = true;
        runInBusy(() -> {
            try {
                var changes = proxy.convertToStandard(choice.profile(), choice.version());
                final int generation = proxy.getContentGeneration();
                // Wait for veraPDF, the result is shown together with the changes.
                ValidationResult verification = ValidationResult.notDeclared();
                if (choice.profile() != null && choice.profile().verifiable()) {
                    try {
                        verification = proxy.validatePdfa();
                    } catch (Exception e) {
                        Log.error("PDF/A validation failed: %s", e.getMessage());
                        verification = ValidationResult.failed(e.getMessage());
                    }
                }
                final ValidationResult result = verification;
                runOnDispatch(() -> {
                    reportedProxy = proxy;
                    reportedResult = result;
                    checkedProxy = proxy;
                    checkedGeneration = generation;
                    showSwitchResult(choice.profile(), changes, result);
                });
            } catch (Exception e) {
                Log.error("PDF standard: %s", e.getMessage());
                runOnDispatch(() -> toaster.toast(ToastType.ERROR, 20000,
                        I18n.t("toast.notPossible"), e.getMessage()));
            } finally {
                // After the update the announcement caused.
                runOnDispatch(() -> switchingStandard = false);
            }
        });
    }

    /**
     * Shows what switching the standard changed and the result of the veraPDF check.
     */
    private void showSwitchResult(PdfStandardProfile target, java.util.Map<String, Integer> changes,
                                  ValidationResult verification) {
        if (GraphicsEnvironment.isHeadless())
            return;
        // Copy: the fixer of veraPDF adds its changes while the dialog is open.
        final java.util.Map<String, Integer> shown = new java.util.LinkedHashMap<>(changes);
        final JLabel changesLabel = new JLabel(switchChangesHtml(target, shown));
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(changesLabel, BorderLayout.NORTH);
        if (target != null && target.verifiable()) {
            VerificationResultPanel result = new VerificationResultPanel();
            result.setResult(verification);
            content.add(result, BorderLayout.CENTER);
            // Only offered if the verification found violations.
            if (verification.getState() == ValidationResult.State.NOT_CONFORM)
                content.add(createVeraPdfFixerRow(result, actions -> {
                    actions.forEach(a -> shown.merge(a, 1, Integer::sum));
                    changesLabel.setText(switchChangesHtml(target, shown));
                }), BorderLayout.SOUTH);
        } else if (target != null) {
            content.add(new HtmlLabel("<html><i>" + escapeHtml(target.notVerifiableReason()) + "</i></html>"),
                    BorderLayout.CENTER);
        }
        JOptionPane.showMessageDialog(this, content, I18n.t("ui.standard.title"),
                verification.isConform() || target == null || !target.verifiable() ? JOptionPane.INFORMATION_MESSAGE
                        : JOptionPane.WARNING_MESSAGE);
    }

    /**
     * The text above the verification result of the switch: the headline and the changes.
     */
    private static String switchChangesHtml(PdfStandardProfile target, java.util.Map<String, Integer> changes) {
        StringBuilder html = new StringBuilder("<html><b>")
                .append(target == null ? I18n.t("ui.switch.removed") : I18n.t("ui.switch.switched", target)).append("</b><br><br>");
        if (changes.isEmpty()) {
            html.append(I18n.t("ui.switch.noChanges"));
        } else {
            html.append(I18n.t("ui.switch.changes"));
            changes.forEach((what, times) -> html.append("&bull; ").append(escapeHtml(what))
                    .append(times > 1 ? " (" + times + "x)" : "").append("<br>"));
        }
        return html.append("</html>").toString();
    }

    /**
     * Creates the row with the button that repairs the document ({@link PdfStandard#repairPdfa}) and verifies again. The result panel shows the
     * new verification.
     *
     * @param onActions Called with the lines for the list of changes: what the fixer did, also if it could not
     *                  repair the document or failed.
     */
    JPanel createVeraPdfFixerRow(VerificationResultPanel result,
                                 java.util.function.Consumer<java.util.List<String>> onActions) {
        final DocumentProxy proxy = documentProxy;
        JButton run = new JButton(I18n.t("ui.fixer.run"));
        run.setToolTipText(I18n.t("ui.fixer.run.tip"));
        run.addActionListener(e -> {
            // Switching the standard needs the consent of the user.
            boolean upgrade = false;
            if (proxy.pdfaUpgradeOffered()) {
                Object[] options = {I18n.t("ui.upgrade.yes"), I18n.t("ui.upgrade.no"), I18n.t("common.cancel")};
                int answer = JOptionPane.showOptionDialog(run, I18n.t("ui.upgrade.question",
                                PdfStandard.pdfaLabel(proxy.getDocument())), I18n.t("common.pdfa"),
                        JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (answer != 0 && answer != 1)
                    return;
                upgrade = answer == 0;
            }
            final boolean switchStandard = upgrade;
            run.setEnabled(false);
            run.setText(I18n.t("ui.fixer.running"));
            new SwingWorker<Object[], Void>() {
                @Override
                protected Object[] doInBackground() throws Exception {
                    var changes = proxy.fixPdfa(switchStandard);
                    final int generation = proxy.getContentGeneration();
                    ValidationResult verification;
                    try {
                        verification = proxy.validatePdfa();
                    } catch (Exception ex) {
                        verification = ValidationResult.failed(ex.getMessage());
                    }
                    return new Object[]{changes, verification, generation};
                }

                @Override
                protected void done() {
                    java.util.List<String> actions = new java.util.ArrayList<>();
                    try {
                        @SuppressWarnings("unchecked")
                        var changes = (java.util.Map<String, Integer>) get()[0];
                        ValidationResult verification = (ValidationResult) get()[1];
                        result.setResult(verification);
                        reportedProxy = proxy;
                        reportedResult = verification;
                        checkedProxy = proxy;
                        checkedGeneration = (Integer) get()[2];
                        changes.forEach((what, times) -> actions.add(what + (times > 1 ? " (" + times + "x)" : "")));
                        if (changes.isEmpty())
                            actions.add(I18n.t("ui.fixer.nothing"));
                    } catch (Exception ex) {
                        Log.error("PDF/A repair failed: %s", ex.getMessage());
                        actions.add(I18n.t("ui.fixer.failed", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    }
                    onActions.accept(actions);
                    run.setText(I18n.t("ui.fixer.run"));
                    run.setEnabled(true);
                    // Nothing left to repair: the button is no longer offered.
                    if (result.getResult() == null || result.getResult().getState() != ValidationResult.State.NOT_CONFORM)
                        run.getParent().setVisible(false);
                    // The dialog may need more room for the new result and the list.
                    Window window = SwingUtilities.getWindowAncestor(run);
                    if (window != null)
                        window.pack();
                }
            }.execute();
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(run);
        return row;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /**
     * A plain text for a toast: escaped, line breaks kept.
     */
    private static String toastText(String s) {
        return s == null ? "" : escapeHtml(s).replace("\n", "<br>");
    }

    /**
     * Shows an error of the current document. It stays until the user closes it or a new document is loaded.
     */
    private void documentError(String html) {
        Toast t = toaster.toast(ToastType.ERROR, Toaster.UNTIL_CLOSED, html);
        if (t != null)
            documentErrors.add(t);
    }

    /**
     * Result of the PDF/A check: the violations and what a repair would change.
     */
    private record PdfaCheck(ValidationResult result, java.util.Map<String, Integer> repairs) {
    }

    /**
     * Starts a PDF/A check if the document changed (or another one was loaded) while the last check ran:
     * the announcements of these changes found the check running and were dropped.
     */
    private void checkPdfaAgainIfChanged() {
        final DocumentProxy proxy = documentProxy;
        if (proxy == null || proxy.getDocument() == null || switchingStandard)
            return;
        if (proxy == checkedProxy && proxy.getContentGeneration() == checkedGeneration)
            return;
        if (PdfStandard.pdfaPart(proxy.getDocument()) > 0)
            reportPdfaViolations();
    }

    /**
     * Checks the document with veraPDF in the background. If it finds violations and a repair would change
     * something, the user is asked to repair the document. Otherwise the violations are shown.
     */
    private void reportPdfaViolations() {
        final DocumentProxy proxy = documentProxy;
        if (proxy == null)
            return;
        final int generation = proxy.getContentGeneration();
        // Already checked: nothing changed that matters for the standard (e.g. only pages were rotated).
        if (proxy == checkedProxy && generation == checkedGeneration)
            return;
        // The document is announced as loaded many times: check one at a time.
        // A change during the check is checked when it is done, see checkPdfaAgainIfChanged.
        if (!pdfaChecking.compareAndSet(false, true))
            return;
        checkedProxy = proxy;
        checkedGeneration = generation;
        new SwingWorker<PdfaCheck, Void>() {
            @Override
            protected PdfaCheck doInBackground() throws Exception {
                ValidationResult result = proxy.validatePdfa();
                java.util.Map<String, Integer> repairs = java.util.Map.of();
                if (result.getState() == ValidationResult.State.NOT_CONFORM) {
                    try {
                        repairs = proxy.previewPdfaRepair();
                    } catch (Exception e) {
                        // E.g. a font that can't be embedded: the violations are shown without repair.
                        Log.error("PDF/A: no repair possible: %s", e.getMessage());
                    }
                }
                return new PdfaCheck(result, repairs);
            }

            @Override
            protected void done() {
                pdfaChecking.set(false);
                try {
                    showCheck();
                } finally {
                    checkPdfaAgainIfChanged();
                }
            }

            private void showCheck() {
                try {
                    PdfaCheck check = get();
                    if (check.result().getState() != ValidationResult.State.NOT_CONFORM) {
                        Log.info("PDF/A: veraPDF: %s", check.result().getState());
                        reportedResult = ValidationResult.conform();
                        return;
                    }
                    if (proxy == reportedProxy && check.result().equals(reportedResult))
                        // Already shown (or declined).
                        return;
                    reportedProxy = proxy;
                    reportedResult = check.result();
                    check.result().toLines().forEach(v -> Log.error("PDF/A: %s", v));
                    if (proxy != documentProxy || GraphicsEnvironment.isHeadless())
                        // The violations are logged.
                        return;
                    VerificationResultPanel result = new VerificationResultPanel();
                    result.setResult(check.result());
                    if (check.repairs().isEmpty()) {
                        JOptionPane.showMessageDialog(UI.this, result, I18n.t("ui.notConform"), JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    StringBuilder changes = new StringBuilder(I18n.t("ui.repair.wouldChange"));
                    check.repairs().forEach((what, times) ->
                            changes.append("&bull; ").append(escapeHtml(what)).append(" (").append(times).append("x)<br>"));
                    changes.append("</html>");
                    final JLabel changesLabel = new JLabel(changes.toString());
                    JPanel message = new JPanel(new BorderLayout(0, 8));
                    message.add(new JLabel(I18n.t("ui.repair.notConform")), BorderLayout.NORTH);
                    message.add(result, BorderLayout.CENTER);
                    JPanel south = new JPanel(new BorderLayout(0, 8));
                    south.add(changesLabel, BorderLayout.NORTH);
                    // Like the dialogs of the standard switch and the verification: the repair is a button.
                    south.add(createVeraPdfFixerRow(result, actions -> {
                        StringBuilder done = new StringBuilder("<html>").append(I18n.t("ui.switch.changes"));
                        actions.forEach(a -> done.append("&bull; ").append(escapeHtml(a)).append("<br>"));
                        changesLabel.setText(done.append("</html>").toString());
                    }), BorderLayout.SOUTH);
                    message.add(south, BorderLayout.SOUTH);
                    final String close = I18n.t("common.close");
                    JOptionPane.showOptionDialog(UI.this, message, I18n.t("common.pdfa"), JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, new Object[]{close}, close);
                } catch (Exception e) {
                    Log.error("PDF/A validation failed: %s", e.getMessage());
                }
            }
        }.execute();
    }

    /**
     * Checks before an invoice is added that the document is PDF/A-3, as e-invoices need. Otherwise the user is asked
     * to switch the document. May be called from any thread.
     *
     * @return Null to continue, otherwise the reason to abort the operation.
     */
    private String checkInvoiceStandard() {
        final DocumentProxy proxy = documentProxy;
        PDDocument document = proxy == null ? null : proxy.getDocument();
        if (document == null || PdfStandard.pdfaPart(document) == 3)
            return null;
        final boolean pdf2 = document.getVersion() > 1.7f;
        final String label = PdfStandard.pdfaLabel(document);
        final String message = pdf2 ? I18n.t("ui.invoice.needPdf17", String.format(java.util.Locale.ROOT, "%.1f", document.getVersion()))
                : I18n.t("ui.invoice.needPdfa3", label == null ? I18n.t("ui.invoice.noPdfa") : label);
        final int[] answer = {JOptionPane.NO_OPTION};
        try {
            if (SwingUtilities.isEventDispatchThread())
                answer[0] = JOptionPane.showConfirmDialog(this, message, I18n.t("common.invoice"), JOptionPane.YES_NO_OPTION);
            else
                SwingUtilities.invokeAndWait(() -> answer[0] = JOptionPane.showConfirmDialog(this, message, I18n.t("common.invoice"),
                        JOptionPane.YES_NO_OPTION));
        } catch (Exception e) {
            return I18n.t("ui.aborted", e.getMessage());
        }
        if (answer[0] != JOptionPane.YES_OPTION)
            return I18n.t("ui.invoice.abort.notPdfa3");
        try {
            // Without telling the listeners: the invoice operation continues with the document.
            // The conformance letter of the document is kept (a stays a, u stays u).
            proxy.convertToStandardQuietly(PdfStandard.pdfa3Level(document), pdf2 ? Float.valueOf(1.7f) : null);
        } catch (IOException e) {
            return I18n.t("ui.aborted", e.getMessage());
        }
        return null;
    }

    /**
     * Tells the user that the document was changed to be PDF/A-3 conform.
     */
    private void notifyPdfaFixes(java.util.Map<String, Integer> changes) {
        StringBuilder list = new StringBuilder();
        changes.forEach((what, times) -> list.append("&bull; ").append(what).append(" (").append(times).append("x)<br>"));
        final String message = I18n.t("ui.pdfaFixes.message", list);
        if (GraphicsEnvironment.isHeadless())
            toaster.toast(ToastType.INFO, 10000, message);
        else
            // Later: this may be called while a document is loading.
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, I18n.t("common.pdfa"),
                    JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * Opens the manual (the README as PDF, which lies beside the jar) in the PDF viewer of the system.
     */
    private void openManual() {
        java.nio.file.Path pdf = Manual.find();
        if (pdf == null) {
            toaster.toast(ToastType.ERROR, 10000, I18n.t("toast.manualMissing"));
            return;
        }
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(pdf.toFile());
            } else {
                toaster.toast(ToastType.INFO, 10000, I18n.t("toast.manualNoViewer"), pdf);
            }
        } catch (Exception e) {
            toaster.toast(ToastType.ERROR, 10000, I18n.t("toast.manualFailed"), e.getMessage());
        }
    }

    /**
     * Checks if pages may be converted to images. The images use device RGB colors, which PDF/A
     * allows only with an RGB output intent.
     *
     * @return True if allowed, otherwise the reason is shown.
     */
    protected boolean imagesAllowed() {
        PDDocument document = documentProxy == null ? null : documentProxy.getDocument();
        if (!pdfaMode || document == null || PdfStandard.hasRgbOutputIntent(document))
            return true;
        toaster.toast(ToastType.ERROR, 10000,
                I18n.t("toast.imagesNotPossible"));
        return false;
    }

    protected void updatePermissions() {
        boolean rightsPossible = !pdfaMode && !ownerPasswordField.getText().trim().isEmpty();
        permissionsHint.setText(rightsPossible ? " " : I18n.t(pdfaMode ? "perm.hint.pdfa" : "perm.hint.password"));
        allowPrinting.setEnabled(rightsPossible);
        allowExtraction.setEnabled(rightsPossible);
        allowModification.setEnabled(rightsPossible);
        allowFillIn.setEnabled(rightsPossible && !allowAnnotations.isSelected());
        allowAssembly.setEnabled(rightsPossible);
        allowAnnotations.setEnabled(rightsPossible);
    }

    public static JFileChooser getImageChooser() {
        if (imageChooser == null) {
            imageChooser = new JFileChooser();
        }
        imageChooser.setFileFilter(imageFilter);
        String lastDir = getPref(Preferences.USER_PREF_LAST_IMAGE_DIR, null);
        if (lastDir != null) {
            imageChooser.setCurrentDirectory(new File(lastDir));
        }
        imageChooser.setMultiSelectionEnabled(false);
        return imageChooser;
    }

    public static JFileChooser getPdfChooser() {
        if (pdfChooser == null) {
            pdfChooser = new JFileChooser();
        }
        pdfChooser.setFileFilter(pdfFilter);
        String lastDir = getPref(Preferences.USER_PREF_LAST_PDF_DIR, null);
        if (lastDir != null) {
            Log.debug("Last PDF Directory '%s'", lastDir);
            pdfChooser.setCurrentDirectory(new File(lastDir));
        }
        return pdfChooser;
    }

    public static JFileChooser getSignatureChooser() {
        if (signatureChooser == null) {
            signatureChooser = new JFileChooser();
        }
        String lastDir = getPref(Preferences.USER_PREF_LAST_SIG_DIR, null);
        if (lastDir != null) {
            Log.debug("Last Signature Directory '%s'", lastDir);
            signatureChooser.setCurrentDirectory(new File(lastDir));
        }
        signatureChooser.setDialogTitle(I18n.t("chooser.signatureFile"));
        signatureChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        signatureChooser.setMultiSelectionEnabled(false);
        signatureChooser.setFileFilter(sigFilter);
        signatureChooser.setAccessory(null);
        return signatureChooser;

    }

    public static JFileChooser getSavePdfChooser() {
        if (savePdfChooser == null) {
            savePdfChooser = new JFileChooser();
        }
        String lastDir = getPref(Preferences.USER_PREF_LAST_PDF_DIR, null);
        if (lastDir != null) {
            Log.debug("Last PDF Directory '%s'", lastDir);
            savePdfChooser.setCurrentDirectory(new File(lastDir));
        }
        savePdfChooser.setDialogTitle(I18n.t("chooser.write"));
        savePdfChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        savePdfChooser.setMultiSelectionEnabled(false);
        savePdfChooser.setFileFilter(pdfFilter);
        savePdfChooser.setAccessory(null);
        return savePdfChooser;
    }

    PageImageViewer pageImageViewer;
    JDialog imageExtractorDialog;

    protected void showImageExtractor(Page page) {
        if (pageImageViewer == null) {
            pageImageViewer = new PageImageViewer();
            imageExtractorDialog = new JDialog(SwingUtilities.getWindowAncestor(this));

            JPanel main = new JPanel(new BorderLayout(0, 5));
            JPanel buttons = new JPanel(new FlowLayout());
            buttons.add(new JButton(I18n.t("about.close")));

            main.add(BorderLayout.CENTER, pageImageViewer);
            main.add(BorderLayout.SOUTH, buttons);

            imageExtractorDialog.setContentPane(pageImageViewer);
            imageExtractorDialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
            imageExtractorDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            imageExtractorDialog.setLocationRelativeTo(this);

            imageExtractorDialog.pack();
        }
        pageImageViewer.setPage(page);
        imageExtractorDialog.setVisible(true);
    }

    /**
     * Creates the user interface
     *
     * @param files Optional files.
     */
    public static void createUI(List<Path> files) {
        createUI(files, null);
    }

    /**
     * Creates the user interface.
     *
     * @param files Optional files.
     * @param carry Optional document of a user interface that was closed before. Replaces the files.
     */
    public static void createUI(List<Path> files, Carry carry) {

        SwingUtilities.invokeLater(() -> {

            FlatRobotoFont.installLazy();
            if (SystemInfo.isLinux) {
                JFrame.setDefaultLookAndFeelDecorated(true);
                JDialog.setDefaultLookAndFeelDecorated(true);
            }

            String lafClassName = UI.getPref(Preferences.USER_PREF_LAF, Preferences.DEFAULT_LAF);
            try {
                UIManager.setLookAndFeel(lafClassName);
            } catch (Exception e) {
                System.err.println("Failed to set LAF " + lafClassName);
            }

            UIManager.put("Panel.arc", 8);
            UI ui = new UI();

            UI.mainWindow = new JFrame(AboutInfo.NAME + " – " + I18n.t("app.subtitle"));

            try {
                ShapeMultiResolutionImage icon = new ShapeMultiResolutionImage(
                        SVGConverter.convert(Main.class.getResourceAsStream("/icon.svg"))
                );
                UI.mainWindow.setIconImage(icon);
            } catch (Exception ignored) {
            }
            // The window closes in confirmExit(), which may ask to save first.
            UI.mainWindow.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            UI.mainWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    if (ui.confirmExit()) {
                        ui.saveWindowState();
                        System.exit(0);
                    }
                }
            });

            // System quit (e.g. Cmd+Q on macOS, Dock menu) bypasses windowClosing.
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
                    Desktop.getDesktop().setQuitHandler((e, response) -> {
                        if (ui.confirmExit()) {
                            ui.saveWindowState();
                            response.performQuit();
                        } else
                            response.cancelQuit();
                    });
            } catch (Exception ex) {
                Log.error("Cannot install quit handler: %s", ex.getMessage());
            }

            UI.mainWindow.setJMenuBar(ui.getMenu());
            UI.mainWindow.setContentPane(ui);
            UI.mainWindow.pack();
            ui.updateMinimumWindowSize();
            ui.applyWindowState(UI.mainWindow);
            UI.mainWindow.setVisible(true);

            if (carry != null && carry.pdf() != null) {
                ui.adopt(carry);
            } else if (!files.isEmpty()) {
                MergeOptions mo = new MergeOptions();
                ui.selectPdf(files.get(0).toFile());
                for (int i = 1; i < files.size(); ++i)
                    ui.appendPdf(files.get(i).toFile(), mo);
            }
        });
    }

}

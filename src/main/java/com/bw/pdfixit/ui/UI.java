package com.bw.pdfixit.ui;



import com.bw.pdfixit.ui.fonts.ImportedFontsDialog;
import com.bw.pdfixit.ui.fonts.FontsDialog;
import com.bw.pdfixit.AboutInfo;
import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Manual;
import com.bw.pdfixit.DocumentInformation;
import com.bw.pdfixit.fonts.FontEmbedException;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfRisk;
import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.Main;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.Redactor;
import com.bw.pdfixit.SafeFile;
import com.bw.pdfixit.SignatureTool;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.model.Page;
import com.bw.pdfixit.model.RenderQueue;
import com.bw.pdfixit.toast.Toast;
import com.bw.pdfixit.toast.ToastType;
import com.bw.pdfixit.toast.Toaster;
import com.bw.pdfixit.ValidationResult;
import com.bw.jtools.svg.SVGConverter;
import com.bw.jtools.ui.ShapeIcon;
import com.bw.jtools.ui.ShapeMultiResolutionImage;
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
import java.awt.event.MouseAdapter;
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
 * See {@link Main#main(String[])}.
 */
public class UI extends JSplitPane {

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

    private final JTextField ownerPasswordField = new JTextField();
    private final JTextField userPasswordField = new JTextField();

    private final JButton saveButton = new JButton(I18n.t("ui.save"));
    private final JCheckBox compression = new JCheckBox(I18n.t("ui.compression"));

    private final PageWidgetContainer pages = new PageWidgetContainer();
    protected DocumentProxy documentProxy;
    private final InvoiceActions invoiceActions = new InvoiceActions(this, () -> documentProxy, () -> toaster);
    private PageWidget selectedPage;

    private final JTextField pageCount = new JTextField(4);
    private final JLabel pdfStandard = new JLabel() {
        @Override
        public void updateUI() {
            super.updateUI();
            // Gray like other hints. Set here, so it follows a change of the look and feel.
            setForeground(UIManager.getColor("Label.disabledForeground"));
        }
    };
    private final JButton standardButton = new JButton(I18n.t("ui.standardButton"));
    private final JButton generateOwner = new JButton(I18n.t("ui.generateOwner"));
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
    private final JCheckBox useDocumentInformation = new JCheckBox(I18n.t("ui.useDocInfo"));
    private final JButton clearInformation = new JButton(I18n.t("ui.clearInfo"));
    private final JTextField title = new JTextField(15);
    private final JTextField author = new JTextField(15);
    private final JTextField creator = new JTextField(15);
    private final JTextField producer = new JTextField(15);
    private final JTextField subject = new JTextField(15);

    private final JTextField keywords = new JTextField(15);
    private final JLabel statusMessage = new JLabel();

    /////////////////////////////////////////////
    // Signing
    /// //////////////////////////////////////////
    private final JCheckBox sign = new JCheckBox(I18n.t("ui.sign"));
    private final JLabel signatureKeyFileName = new JLabel();
    private File signatureKeyFile;
    private final JPasswordField signaturePasswordField = new JPasswordField();


    /////////////////////////////////////////////
    // Permissions
    /// //////////////////////////////////////////

    private final JCheckBox allowPrinting = new JCheckBox(I18n.t("perm.print"));
    private final JCheckBox allowExtraction = new JCheckBox(I18n.t("perm.extract"));
    private final JCheckBox allowModification = new JCheckBox(I18n.t("perm.modify"));
    private final JCheckBox allowFillIn = new JCheckBox(I18n.t("perm.fillIn"));
    private final JCheckBox allowAssembly = new JCheckBox(I18n.t("perm.assemble"));
    private final JCheckBox allowAnnotations = new JCheckBox(I18n.t("perm.annotations"));

    /////////////////////////////////////////////
    // Page manipulation
    /// //////////////////////////////////////////

    private final JButton deleteButton = new JButton(getIcon("delete"));
    private final JButton renderToImageButton = new JButton(I18n.t("ui.toImage"));
    private final JButton renderAllToImageButton = new JButton(I18n.t("ui.allToImages"));
    private final JButton redactButton = new JButton(I18n.t("ui.redact"));
    private final JButton rotateClockwiseButton = new JButton(getIcon("rotateClockwise"));
    private final JButton rotateAllClockwiseButton = new JButton(getIcon("rotateAllClockwise"));
    private final JButton moveLeft = new JButton(getIcon("moveLeft"));
    private final JButton moveRight = new JButton(getIcon("moveRight"));
    private final JButton exportImages = new JButton(I18n.t("ui.exportImages"));
    private final JTextField rotation = new JTextField(4);
    private final JLabel pageNb = new JLabel();
    private final JLabel quality = new JLabel();
    private final JButton browseButton = new JButton(getIcon("openPdf"));
    private final JButton browseAppendButton = new JButton(getIcon("addPdf"));

    private final String HELP_ICON_NAME = "OptionPane.questionIcon";

    private final JButton permissionHelp = new JButton(UIManager.getIcon(HELP_ICON_NAME));
    private final JButton signingHelp = new JButton(UIManager.getIcon(HELP_ICON_NAME));

    private Font normalFont;
    private Font hintFont;

    /////////////////////////////////////////////
    // Splitting
    /// //////////////////////////////////////////
    private final JSpinner pagesPerDocument = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JButton splitDocument = new JButton(I18n.t("ui.split"));

    protected final RenderQueue renderQueue = new RenderQueue();

    protected final Deque<UIPdfLoadWorker> loadWorker = new ArrayDeque<>();
    protected UIPdfLoadWorker currentLoader;

    public void handleDrop(DropTargetDropEvent dtde, boolean append) {
        try {
            dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
            if (dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor) instanceof java.util.List<?> droppedFiles) {
                for (Object item : droppedFiles) {
                    if (item instanceof File file) {
                        if (append && documentProxy != null) {
                            appendPdf(file, new MergeOptions());
                        } else {
                            if (!confirmDiscard(I18n.t("ui.confirm.load")))
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
        super(JSplitPane.HORIZONTAL_SPLIT);

        normalFont = UIManager.getFont("Panel.font");
        hintFont = normalFont.deriveFont(Font.ITALIC);
        final FontMetrics fm = getFontMetrics(normalFont);
        final int charWith = fm.charWidth('W');

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));

        saveButton.setEnabled(false);
        saveButton.setFont(normalFont.deriveFont(Font.BOLD, normalFont.getSize2D() * 1.5f));
        browseAppendButton.setEnabled(false);
        splitDocument.setEnabled(false);
        rotation.setEditable(false);

        GridBagConstraints gcLabel = new GridBagConstraints();
        gcLabel.anchor = GridBagConstraints.NORTHWEST;
        gcLabel.fill = GridBagConstraints.NONE;
        gcLabel.insets = new Insets(0, 0, 5, 5);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 0, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(createDocumentPanel(), gc);

        pages.addSelectionListener(e -> setSelectedPage(e.getFirstIndex() < 0 ? null : pages.getSelectedPage()));

        JPanel manipulations = new JPanel(new GridBagLayout());
        GridBagConstraints pmGc = new GridBagConstraints();
        pmGc.anchor = GridBagConstraints.NORTHWEST;
        pmGc.gridx = 0;
        pmGc.gridy = 0;
        pmGc.weightx = 0.5;
        pmGc.fill = GridBagConstraints.BOTH;
        manipulations.add(createPageManipulationPanel(), pmGc);
        pmGc.gridx++;
        manipulations.add(createSplitterPanel(), pmGc);

        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.weightx = 1;
        gc.weighty = 0;
        gc.gridwidth = 3;
        gc.gridx = 0;
        gc.gridy = 2;
        panel.add(manipulations, gc);

        gc.gridy = 3;
        JPanel security = new JPanel(new GridBagLayout());
        pmGc.gridx = 0;
        security.add(createPermissionsPanel(), pmGc);
        pmGc.gridx++;
        security.add(createSigningPanel(), pmGc);

        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.gridy = 3;
        panel.add(security, gc);

        gcLabel.gridx = 0;
        gcLabel.gridy = 3;

        gc.gridx = 0;
        gc.gridy = 4;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 0;
        gc.gridwidth = 1;
        panel.add(saveButton, gc);

        gc.insets.bottom = 5;

        gcLabel.gridy = gc.gridy + 1;
        gcLabel.anchor = GridBagConstraints.SOUTHWEST;
        gcLabel.weighty = 0.5;
        gcLabel.gridwidth = 1;
        gcLabel.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), gcLabel);

        browseButton.addActionListener(e -> doBrowsePdf());
        browseAppendButton.addActionListener(e -> runInBusy(() -> {
            if (documentProxy == null)
                doBrowsePdf();
            else
                doBrowseAdditionalPdf();
        }));

        saveButton.addActionListener(e -> doSave());
        splitDocument.addActionListener(e -> doSplit());

        setLeftComponent(panel);
        JScrollPane scrollPane = new JScrollPane(pages);
        scrollPane.getViewport().setPreferredSize(new Dimension(charWith * 50, fm.getHeight() * 40));
        setRightComponent(scrollPane);

        setDividerLocation(charWith * 65);
        updatePermissions();
        setSelectedPage(null);

        pages.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (selectedPage != null) {
                    quality.setText(String.format("%d dpi (x %.2f)", selectedPage.getPage().dpi, selectedPage.getScale()));
                }
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

    protected JPanel createSigningPanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.weighty = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.NORTHWEST;

        JPanel signButtons = new JPanel(new GridBagLayout());

        signButtons.add(sign, gc);
        JButton browseSignature = new JButton(I18n.t("ui.selectP12"));
        browseSignature.addActionListener(e -> doBrowseSignatureFile());
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        signButtons.add(browseSignature, gc);

        gc.gridwidth = 2;
        gc.gridx = 0;
        panel.add(signButtons, gc);

        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        gc.gridy = 1;
        gc.gridwidth = 1;
        JLabel label = new JLabel(I18n.t("ui.keyFile"));
        panel.add(label, gc);
        gc.gridy++;
        label = new JLabel(I18n.t("ui.password"));
        label.setLabelFor(signaturePasswordField);
        panel.add(label, gc);

        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        gc.gridy = 1;
        gc.gridx = 1;
        panel.add(signatureKeyFileName, gc);
        gc.gridy++;

        signaturePasswordField.setText(getPref(Preferences.USER_PREF_SIG_PASSWORD, ""));
        panel.add(signaturePasswordField, gc);

        signingHelp.addActionListener(e -> JOptionPane.showMessageDialog(this,
                I18n.t("help.signing.text"), "Help", JOptionPane.INFORMATION_MESSAGE));

        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.NORTHEAST;
        gc.gridy++;
        panel.add(signingHelp, gc);


        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.fill = GridBagConstraints.BOTH;
        gc.gridwidth = 2;
        gc.weighty = 1;
        gc.gridx = 0;
        gc.gridy++;
        panel.add(Box.createGlue(), gc);

        panel.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.border.signing")));

        Preferences prefs = Preferences.getInstance();

        this.signaturePasswordField.setText(prefs.getString(Preferences.USER_PREF_SIG_PASSWORD, ""));
        this.sign.setSelected(prefs.getBoolean(Preferences.USER_PREF_SIG_ENABLED, false));

        this.sign.addActionListener(e -> Preferences.getInstance().set(Preferences.USER_PREF_SIG_ENABLED, this.sign.isSelected()));

        return panel;
    }

    protected JPanel createPermissionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        permissionHelp.addActionListener(e -> JOptionPane.showMessageDialog(this,
                I18n.t("help.permissions.text"),
                I18n.t("common.help"), JOptionPane.INFORMATION_MESSAGE));

        compression.setSelected(true);
        allowPrinting.setSelected(true);
        saveButton.setEnabled(false);
        saveButton.setFont(normalFont.deriveFont(Font.BOLD, normalFont.getSize2D() * 1.5f));
        browseAppendButton.setEnabled(false);
        splitDocument.setEnabled(false);
        rotation.setEditable(false);

        JLabel ownerPasswordLabel = new JLabel(I18n.t("ui.ownerPassword"));
        ownerPasswordLabel.setLabelFor(ownerPasswordField);

        JLabel userPasswordLabel = new JLabel(I18n.t("ui.userPassword"));
        userPasswordLabel.setLabelFor(userPasswordField);

        GridBagConstraints gcLabel = new GridBagConstraints();
        gcLabel.anchor = GridBagConstraints.NORTHWEST;
        gcLabel.fill = GridBagConstraints.NONE;
        gcLabel.insets = new Insets(0, 5, 5, 5);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 0, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        gc.weightx = 1;
        gc.weighty = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;

        gcLabel.gridx = 0;
        gcLabel.gridy = 3;
        panel.add(ownerPasswordLabel, gcLabel);

        gc.gridy = 4;
        gc.gridwidth = 1;
        panel.add(ownerPasswordField, gc);
        gc.gridx = 1;
        gc.weightx = 0;
        panel.add(generateOwner, gc);
        gcLabel.gridy += 2;
        panel.add(userPasswordLabel, gcLabel);
        gc.weightx = 1;
        gc.gridwidth = 2;
        gc.gridx = 0;
        gc.gridy += 2;
        panel.add(userPasswordField, gc);

        JPanel permissions = new JPanel(new GridLayout(4, 2));

        permissions.add(compression);
        permissions.add(allowPrinting);
        permissions.add(allowModification);
        permissions.add(allowAnnotations);
        permissions.add(allowExtraction);
        permissions.add(allowFillIn);
        permissions.add(allowAssembly);

        gc.weightx = 1;
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(permissions, gc);

        gc.gridx = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(permissionHelp, gc);

        allowAnnotations.addItemListener(e -> SwingUtilities.invokeLater(this::updatePermissions));
        String ownerPassword = null;

        Preferences prefs = Preferences.getInstance();
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

        panel.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.border.permissions")));

        return panel;
    }

    protected JPanel createDocumentPanel() {
        JPanel documentPanel = new JPanel(new GridBagLayout());
        documentPanel.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.border.document")));

        JPanel browseButtons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));

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

        browseButton.setOpaque(false);
        browseAppendButton.setOpaque(false);

        browseButtons.add(browseButton);
        browseButtons.add(browseAppendButton);

        pageCount.setEditable(false);
        pageCount.setText("0");

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 1;
        gc.gridheight = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;

        JLabel pageCountLabel = new JLabel(I18n.t("ui.pages"));
        pageCountLabel.setLabelFor(pageCount);
        documentPanel.add(pageCountLabel, gc);

        gc.gridx = 1;
        gc.weightx = 1;
        pdfStandard.setFont(hintFont);
        pdfStandard.setToolTipText(I18n.t("ui.pdfStandard.tip"));
        JPanel pageCountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pageCountRow.setOpaque(false);
        pageCountRow.add(pageCount);
        pageCountRow.add(Box.createHorizontalStrut(10));
        standardButton.setEnabled(false);
        standardButton.setToolTipText(I18n.t("ui.standardButton.tip"));
        standardButton.addActionListener(e -> doChangeStandard());
        pageCountRow.add(standardButton);
        pageCountRow.add(Box.createHorizontalStrut(10));
        pageCountRow.add(pdfStandard);
        documentPanel.add(pageCountRow, gc);

        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        statusMessage.setFont(hintFont);
        documentPanel.add(statusMessage, gc);

        gc.gridy = 0;
        gc.gridx = 2;
        gc.anchor = GridBagConstraints.NORTHEAST;
        gc.gridwidth = 1;
        gc.weighty = 0;
        gc.weightx = 0;
        gc.gridheight = 2;
        documentPanel.add(browseButtons, gc);

        gc.gridheight = 1;
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0;
        gc.gridwidth = 2;

        JPanel docButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        docButtons.add(useDocumentInformation);
        docButtons.add(clearInformation);
        documentPanel.add(docButtons, gc);

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

        gc.gridy++;
        gc.gridwidth = 1;

        JLabel titleLabel = new JLabel(I18n.t("info.title"));
        titleLabel.setLabelFor(title);
        documentPanel.add(titleLabel, gc);
        gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        gc.gridx = 1;
        documentPanel.add(title, gc);

        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridy++;
        JLabel authorLabel = new JLabel(I18n.t("info.author"));
        authorLabel.setLabelFor(author);
        documentPanel.add(authorLabel, gc);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        gc.gridx = 1;
        gc.gridwidth = 2;
        documentPanel.add(author, gc);

        gc.gridy++;
        JLabel creatorLabel = new JLabel(I18n.t("info.creator"));
        creatorLabel.setLabelFor(creator);
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 1;
        documentPanel.add(creatorLabel, gc);
        gc.weightx = 1;
        gc.gridx = 1;
        gc.gridwidth = 2;
        documentPanel.add(creator, gc);

        gc.gridy++;
        JLabel producerLabel = new JLabel(I18n.t("info.producer"));
        producerLabel.setLabelFor(producer);
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 1;
        documentPanel.add(producerLabel, gc);
        gc.weightx = 1;
        gc.gridx = 1;
        gc.gridwidth = 2;
        documentPanel.add(producer, gc);


        gc.gridy++;
        JLabel subjectLabel = new JLabel(I18n.t("info.subject"));
        subjectLabel.setLabelFor(subject);
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridwidth = 1;
        documentPanel.add(subjectLabel, gc);
        gc.weightx = 1;
        gc.gridx = 1;
        gc.gridwidth = 2;
        documentPanel.add(subject, gc);

        gc.gridy++;
        JLabel keywordsLabel = new JLabel(I18n.t("info.keywords"));
        keywordsLabel.setLabelFor(keywords);
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridwidth = 1;
        documentPanel.add(keywordsLabel, gc);
        gc.weightx = 1;
        gc.gridx = 1;
        gc.gridwidth = 2;
        documentPanel.add(keywords, gc);


        return documentPanel;
    }

    protected JPanel createSplitterPanel() {
        JPanel splitter = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.weighty = 0;

        JLabel pagesLabel = new JLabel(I18n.t("ui.pageCount"));
        pagesLabel.setLabelFor(pagesPerDocument);
        splitter.add(pagesLabel, gc);
        gc.gridx++;
        splitter.add(pagesPerDocument, gc);

        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1;
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        splitter.add(Box.createGlue(), gc);

        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        gc.gridy++;
        splitter.add(splitDocument, gc);

        splitter.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.border.splitting")));
        return splitter;
    }

    protected JPanel createPageManipulationPanel() {
        deleteButton.setOpaque(false);
        deleteButton.setToolTipText(I18n.t("ui.delete.tip"));
        deleteButton.addActionListener(e -> doDeletePage());

        renderToImageButton.setOpaque(false);
        renderToImageButton.setToolTipText(I18n.t("ui.toImage.tip"));
        renderToImageButton.addActionListener(e -> doRenderToImage());

        renderAllToImageButton.setEnabled(false);
        renderAllToImageButton.setOpaque(false);
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

        moveLeft.setOpaque(false);
        moveLeft.setToolTipText(I18n.t("ui.moveLeft.tip"));
        moveLeft.addActionListener(e -> doMoveLeft());

        moveRight.setOpaque(false);
        moveRight.setToolTipText(I18n.t("ui.moveRight.tip"));
        moveRight.addActionListener(e -> doMoveRight());

        exportImages.setToolTipText(I18n.t("ui.exportImages.tip"));
        exportImages.addActionListener(e -> {
            PageWidget pw = pages.getSelectedPage();
            if (pw != null) {
                showImageExtractor(pw.getPage());
            }
        });

        JPanel pageManipulation = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 5, 5, 5);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        gc.weightx = 0;
        pageManipulation.add(pageNb, gc);
        gc.gridwidth = 1;
        gc.gridy++;
        JLabel rotationLabel = new JLabel(I18n.t("ui.rotation"));
        rotationLabel.setLabelFor(rotation);
        pageManipulation.add(rotationLabel, gc);
        gc.gridx = 1;
        pageManipulation.add(rotation, gc);

        gc.gridy++;
        gc.gridx = 0;
        gc.gridwidth = 2;
        pageManipulation.add(quality, gc);

        gc.gridwidth = 3;
        gc.gridx = 2;
        gc.gridy = 0;
        gc.fill = GridBagConstraints.NONE;

        JPanel toImageButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toImageButtons.setOpaque(false);
        toImageButtons.add(renderToImageButton);
        toImageButtons.add(Box.createHorizontalStrut(5));
        toImageButtons.add(renderAllToImageButton);

        pageManipulation.add(toImageButtons, gc);

        gc.gridwidth = 1;
        gc.gridy++;
        gc.gridx = 2;
        pageManipulation.add(rotateClockwiseButton, gc);
        gc.gridx++;
        pageManipulation.add(rotateAllClockwiseButton, gc);

        gc.gridy++;
        gc.gridx = 5;
        pageManipulation.add(deleteButton, gc);
        gc.gridx = 2;
        pageManipulation.add(moveLeft, gc);
        gc.gridx++;
        pageManipulation.add(moveRight, gc);

        gc.gridx = 5;
        gc.gridy = 0;
        gc.gridheight = 2;
        gc.anchor = GridBagConstraints.NORTH;
        pageManipulation.add(exportImages, gc);

        gc.gridx = 4;
        gc.gridy = 2;
        gc.gridwidth = 1;
        gc.gridheight = 1;
        gc.anchor = GridBagConstraints.NORTHEAST;
        pageManipulation.add(redactButton, gc);

        pageManipulation.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.border.pages")));
        return pageManipulation;
    }

    private final JRadioButtonMenuItem encryptionKeyLength40 = new JRadioButtonMenuItem("40-Bit");
    private final JRadioButtonMenuItem encryptionKeyLength128 = new JRadioButtonMenuItem("128-Bit");
    private final JRadioButtonMenuItem encryptionKeyLength256 = new JRadioButtonMenuItem("256-Bit");


    private final JRadioButtonMenuItem lafLight = new JRadioButtonMenuItem(I18n.t("laf.light"));
    private final JRadioButtonMenuItem lafDark = new JRadioButtonMenuItem(I18n.t("laf.dark"));
    private final JRadioButtonMenuItem lafSystem = new JRadioButtonMenuItem(I18n.t("laf.system"));
    private final JRadioButtonMenuItem lafCross = new JRadioButtonMenuItem(I18n.t("laf.cross"));

    private final JCheckBox storePasswords = new JCheckBox(I18n.t("ui.storePasswords"));
    private final JCheckBox confirmExit = new JCheckBox(I18n.t("ui.confirmExit"));

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
                permissionHelp.setOpaque(false);
                permissionHelp.setIcon(UIManager.getIcon(HELP_ICON_NAME));
                signingHelp.setOpaque(false);
                signingHelp.setIcon(UIManager.getIcon(HELP_ICON_NAME));
                normalFont = UIManager.getFont("Panel.font");
                hintFont = normalFont.deriveFont(Font.ITALIC);
                saveButton.setFont(normalFont.deriveFont(Font.BOLD, normalFont.getSize2D() * 1.5f));
                statusMessage.setFont(hintFont);
                setSelectedPage(selectedPage);
                SwingUtilities.getWindowAncestor(this).pack();
                Log.info("Switched to %s", laf);
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
            menuBar.add(options);
            menuBar.add(createFontsMenu());
            menuBar.add(invoiceActions.createValidationMenu());
            menuBar.add(invoiceActions.createInvoiceMenu());
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
            if (selectedFiles.length > 0 && confirmDiscard(I18n.t("ui.confirm.load"))) {
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
    protected void doMoveRight() {
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
    protected void doMoveLeft() {
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
        // The document, with its unwritten changes, is carried over to the new user interface.
        final Carry carry;
        try {
            carry = capture();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, I18n.t("common.errorPrefix", e.getMessage()),
                    I18n.t("language.restart.title"), JOptionPane.ERROR_MESSAGE);
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
        return confirmDiscard(I18n.t("ui.confirm.exit"));
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
                    statusMessage.setText("");

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

    private void setSelectedPage(PageWidget page) {
        selectedPage = page;
        boolean enabled = page != null;
        deleteButton.setEnabled(enabled);
        renderToImageButton.setEnabled(enabled);
        redactButton.setEnabled(enabled);
        rotateClockwiseButton.setEnabled(enabled);
        moveLeft.setEnabled(enabled);
        moveRight.setEnabled(enabled);
        exportImages.setEnabled(enabled);
        if (enabled) {
            pageNb.setText(I18n.t("ui.pageNumber", page.getPageNumber()));
            pageNb.setFont(normalFont);
            quality.setText(String.format("%d dpi (x %.2f)", page.getPage().dpi, page.getScale()));
            rotation.setText(page.getPage().getRotation() + " °");
        } else {
            rotation.setText("");
            pageNb.setText(I18n.t("ui.selectPage"));
            pageNb.setFont(hintFont);
            quality.setText("");
        }
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

    public void setBusy(boolean busy) {
        if (this.busy != busy) {
            Log.info("setBusy(%s)", busy);
            this.busy = busy;
            UI.mainWindow.setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
            Component glassPane = UI.mainWindow.getGlassPane();
            glassPane.setVisible(busy);
            if (busy) {
                glassPane.addMouseListener(busyMouseAdapter);
                glassPane.addKeyListener(busyKeyAdapter);
                glassPane.requestFocusInWindow();
            } else {
                glassPane.removeMouseListener(busyMouseAdapter);
                glassPane.removeKeyListener(busyKeyAdapter);
            }
        }
    }

    /**
     * Runs the code on the event dispatch thread: directly if already there, otherwise later.
     */
    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread())
            r.run();
        else
            SwingUtilities.invokeLater(r);
    }

    public void runInBusy(final Runnable r) {
        setBusy(true);
        new SwingWorker<>() {
            @Override
            protected Object doInBackground()  {
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

        if (selectedFile != null) {
            documentProxy = new DocumentProxy(renderQueue);
            initialLoad = true;
            hadInvoice = false;
            documentProxy.invoiceStandardCheck = this::checkInvoiceStandard;
            documentProxy.pdfaFixListener = changes -> runOnEdt(() -> notifyPdfaFixes(changes));
            documentProxy.riskListener = (fileName, risks) -> runOnEdt(() -> {
                StringBuilder list = new StringBuilder();
                risks.forEach(r -> list.append("&bull; ").append(r).append("<br>"));
                toaster.toast(ToastType.WARNING, 15000,
                        I18n.t("toast.risks"), fileName, list);
            });
            documentProxy.appendCheck = doc -> {
                if (!pdfaMode || PdfStandard.pdfaPart(doc) > 0)
                    return null;
                toaster.toast(ToastType.ERROR, 10000,
                        I18n.t("toast.notAppended"));
                return I18n.t("ui.notAppended");
            };
            final java.util.function.Function<PDDocument, String> pdfaCheck = documentProxy.appendCheck;
            documentProxy.appendCheck = doc -> {
                String refused = pdfaCheck.apply(doc);
                return refused != null ? refused : EInvoice.hasInvoice(doc) ? checkInvoiceStandard() : null;
            };
            documentProxy.addDocumentConsumer(new DocumentProxy.DocumentConsumer() {

                @Override
                public void documentLoaded(PDDocument document) {
                    // This method is called each time some file wasloaded.
                    // "document" is always the main effective document.

                    renderAllToImageButton.setEnabled(true);
                    rotateAllClockwiseButton.setEnabled(true);
                    saveButton.setEnabled(true);
                    browseAppendButton.setEnabled(true);
                    standardButton.setEnabled(true);
                    // May be called from background tasks (e.g. attaching an invoice).
                    final boolean hasInvoice = EInvoice.hasInvoice(document);
                    runOnEdt(() -> {
                        // Only an added invoice is repaired automatically, not a loaded document.
                        final boolean invoiceAdded = hasInvoice && !hadInvoice && !initialLoad;
                        initialLoad = false;
                        hadInvoice = hasInvoice;
                        updatePdfAState(invoiceAdded);
                    });

                    initDocumentInformation();

                    statusMessage.setText("");
                }

                @Override
                public void failed(String error) {
                    if (documentProxy == null)
                        // Force initialisation for case where loading the main file failed.
                        selectPdf(null);
                    statusMessage.setText(error == null ? "" : error);
                }
            });
            pages.setDocument(documentProxy);
            try {
                final String fileName = selectedFile.getName();
                Toast toast = toaster.toast(I18n.t("toast.loading"), fileName);
                long start = System.currentTimeMillis();
                load(selectedFile.toPath(), new MergeOptions(), document -> {
                    if (toast != null)
                        toast.setMessage(String.format(I18n.t("toast.loaded"),
                                System.currentTimeMillis() - start, fileName));
                });
            } catch (Exception e) {
                statusMessage.setText(e.getMessage());
                selectPdf(null);
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

    protected void initDocumentInformation() {

        final boolean useDoc = useDocumentInformation.isSelected();
        final PDDocument document = documentProxy == null ? null : documentProxy.getDocument();
        if (document != null) {
            pageCount.setText(String.format("%d", documentProxy.getPageCount()));
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
            pageCount.setText("");
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
        compression.setToolTipText(compressionAllowed ? null : I18n.t("toast.compressionOff", declared));
        final boolean pdfa = pdfaMode;
        final String notAllowed = I18n.t("ui.pdfaNotAllowed");

        ownerPasswordField.setEnabled(!pdfa);
        userPasswordField.setEnabled(!pdfa);
        generateOwner.setEnabled(!pdfa);
        for (JRadioButtonMenuItem item : new JRadioButtonMenuItem[]{encryptionKeyLength40, encryptionKeyLength128, encryptionKeyLength256})
            item.setEnabled(!pdfa);
        ownerPasswordField.setToolTipText(pdfa ? notAllowed : null);
        userPasswordField.setToolTipText(pdfa ? notAllowed : null);

        splitDocument.setEnabled(hasDocument && !pdfa);
        splitDocument.setToolTipText(pdfa ? I18n.t("ui.split.pdfaNotAllowed") : null);

        updatePermissions();
    }

    /**
     * Opens the dialog to change the PDF/A level of the document and converts it.
     */
    protected void doChangeStandard() {
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
                proxy.getDocument().getVersion());
        if (choice == null)
            return;
        reportedResult = ValidationResult.conform();
        // The document is announced as changed: that must not start a second check.
        switchingStandard = true;
        runInBusy(() -> {
            try {
                var changes = proxy.convertToStandard(choice.profile(), choice.version());
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
                runOnEdt(() -> {
                    reportedProxy = proxy;
                    reportedResult = result;
                    showSwitchResult(choice.profile(), changes, result);
                });
            } catch (Exception e) {
                Log.error("PDF standard: %s", e.getMessage());
                runOnEdt(() -> toaster.toast(ToastType.ERROR, 20000,
                        I18n.t("toast.notPossible"), e.getMessage()));
            } finally {
                // After the update the announcement caused.
                runOnEdt(() -> switchingStandard = false);
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
            content.add(new JLabel("<html><i>" + escapeHtml(target.notVerifiableReason()) + "</i></html>"),
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
                    ValidationResult verification;
                    try {
                        verification = proxy.validatePdfa();
                    } catch (Exception ex) {
                        verification = ValidationResult.failed(ex.getMessage());
                    }
                    return new Object[]{changes, verification};
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
     * Result of the PDF/A check: the violations and what a repair would change.
     */
    private record PdfaCheck(ValidationResult result, java.util.Map<String, Integer> repairs) {
    }

    /**
     * Checks the document with veraPDF in the background. If it finds violations and a repair would change
     * something, the user is asked to repair the document. Otherwise the violations are shown.
     */
    private void reportPdfaViolations() {
        // The document is announced as loaded many times (e.g. after each rendered page): check one at a time.
        if (!pdfaChecking.compareAndSet(false, true))
            return;
        final DocumentProxy proxy = documentProxy;
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
            proxy.convertToStandardQuietly(PdfStandardProfile.PDFA_3B, pdf2 ? Float.valueOf(1.7f) : null);
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

            UI.mainWindow = new JFrame(I18n.t("ui.windowTitle"));

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
                    if (ui.confirmExit())
                        System.exit(0);
                }
            });

            // System quit (e.g. Cmd+Q on macOS, Dock menu) bypasses windowClosing.
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
                    Desktop.getDesktop().setQuitHandler((e, response) -> {
                        if (ui.confirmExit())
                            response.performQuit();
                        else
                            response.cancelQuit();
                    });
            } catch (Exception ex) {
                Log.error("Cannot install quit handler: %s", ex.getMessage());
            }

            UI.mainWindow.setJMenuBar(ui.getMenu());
            UI.mainWindow.setLocationByPlatform(true);
            ui.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            UI.mainWindow.setContentPane(ui);
            UI.mainWindow.pack();
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

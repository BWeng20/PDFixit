package com.bw.jPdfTool.ui;



import com.bw.jPdfTool.ui.fonts.ImportedFontsDialog;
import com.bw.jPdfTool.ui.fonts.FontsDialog;
import com.bw.jPdfTool.AboutInfo;
import com.bw.jPdfTool.Manual;
import com.bw.jPdfTool.DocumentInformation;
import com.bw.jPdfTool.fonts.FontEmbedException;
import com.bw.jPdfTool.invoice.EInvoice;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.PdfRisk;
import com.bw.jPdfTool.PdfStandard;
import com.bw.jPdfTool.PdfStandardProfile;
import com.bw.jPdfTool.Main;
import com.bw.jPdfTool.Preferences;
import com.bw.jPdfTool.Redactor;
import com.bw.jPdfTool.SafeFile;
import com.bw.jPdfTool.SignatureTool;
import com.bw.jPdfTool.model.DocumentProxy;
import com.bw.jPdfTool.model.MergeOptions;
import com.bw.jPdfTool.model.Page;
import com.bw.jPdfTool.model.RenderQueue;
import com.bw.jPdfTool.toast.Toast;
import com.bw.jPdfTool.toast.ToastType;
import com.bw.jPdfTool.toast.Toaster;
import com.bw.jPdfTool.ValidationResult;
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

    protected static FileNameExtensionFilter pdfFilter = new FileNameExtensionFilter("PDF-Files (*.pdf)", "pdf");
    protected static FileNameExtensionFilter imageFilter = new FileNameExtensionFilter("Picture (*.jpg, *.png)", "jpeg", "jpg", "png");
    protected static FileNameExtensionFilter sigFilter = new FileNameExtensionFilter("PKCS#12-Files (*.p12)", "p12");

    private final JTextField ownerPasswordField = new JTextField();
    private final JTextField userPasswordField = new JTextField();

    private final JButton saveButton = new JButton("Write to File");
    private final JCheckBox compression = new JCheckBox("Compression");

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
    private final JButton standardButton = new JButton("PDF Standard...");
    private final JButton generateOwner = new JButton("<html><body>Generate<br>Owner Password</body></html>");
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
    private final JCheckBox useDocumentInformation = new JCheckBox("Use Information from first loaded Document");
    private final JButton clearInformation = new JButton("Clear Information");
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
    private final JCheckBox sign = new JCheckBox("Sign it");
    private final JLabel signatureKeyFileName = new JLabel();
    private File signatureKeyFile;
    private final JPasswordField signaturePasswordField = new JPasswordField();


    /////////////////////////////////////////////
    // Permissions
    /// //////////////////////////////////////////

    private final JCheckBox allowPrinting = new JCheckBox("Printing");
    private final JCheckBox allowExtraction = new JCheckBox("Extraction");
    private final JCheckBox allowModification = new JCheckBox("Modify");
    private final JCheckBox allowFillIn = new JCheckBox("Fill Form");
    private final JCheckBox allowAssembly = new JCheckBox("Assembly");
    private final JCheckBox allowAnnotations = new JCheckBox("Annotations");

    /////////////////////////////////////////////
    // Page manipulation
    /// //////////////////////////////////////////

    private final JButton deleteButton = new JButton(getIcon("delete"));
    private final JButton renderToImageButton = new JButton("To Image");
    private final JButton renderAllToImageButton = new JButton("All to Images");
    private final JButton redactButton = new JButton("Redact");
    private final JButton rotateClockwiseButton = new JButton(getIcon("rotateClockwise"));
    private final JButton rotateAllClockwiseButton = new JButton(getIcon("rotateAllClockwise"));
    private final JButton moveLeft = new JButton(getIcon("moveLeft"));
    private final JButton moveRight = new JButton(getIcon("moveRight"));
    private final JButton exportImages = new JButton("<html>Export<br>Images</html>");
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
    private final JButton splitDocument = new JButton("Split Document");

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
                            if (!confirmDiscard("Load"))
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
        JButton browseSignature = new JButton("Select P12 File");
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
        JLabel label = new JLabel("Key File");
        panel.add(label, gc);
        gc.gridy++;
        label = new JLabel("Password");
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
                "<html><font size='+2'><b>How to Sign a PDF</b></font><br><br>" +
                        "<font size='+1'>" +
                        "Signing requires a <b>P12 key file</b> which contain a <b>X.509 certificate</b><br>with the following components:<br>" +
                        "<ul>" +
                        "<li><b>Private Key:</b> This is essential to generate the digital signature." +
                        "<br>It proves your identity.</li>" +
                        "<li><b>Certificate Chain:</b> This is a series of certificates that links your<br>" +
                        "certificate back to a trusted Root Certificate Authority (CA).</li>" +
                        "<li><b>Common Name (CN):</b> This attribute is used to identify the signer and<br>" +
                        "is typically displayed as the signer's name in the viewer.</li>" +
                        "</ul><br>" +
                        "For your signature to be accepted by the recipient's system, the certificate<br>" +
                        "chain must ultimately be based on a Root Certificate Authority (CA) that is<br>" +
                        "already installed and trusted by the target system.<br><br>" +
                        "<b>Options for Certificates:</b>" +
                        "<ul><li><b>Purchased Certificates:</b><br>" +
                        "You can buy certificates from commercial CAs (like DigiCert, Comodo, etc.)<br>" +
                        "whose roots are widely trusted.</li><br>" +
                        "<li><b>Self-Signed Certificates</b>:<br>" +
                        "You can create your own.<br>" +
                        "However, for these signatures to be verified, you must distribute the public<br>" +
                        "part of your self-signed certificate to the people you are sharing the PDF with,<br>" +
                        "and they must manually add it to their list of trusted identities.<br>" +
                        "</font></html>", "Help", JOptionPane.INFORMATION_MESSAGE));

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

        panel.setBorder(BorderFactory.createTitledBorder("Signing"));

        Preferences prefs = Preferences.getInstance();

        this.signaturePasswordField.setText(prefs.getString(Preferences.USER_PREF_SIG_PASSWORD, ""));
        this.sign.setSelected(prefs.getBoolean(Preferences.USER_PREF_SIG_ENABLED, false));

        this.sign.addActionListener(e -> Preferences.getInstance().set(Preferences.USER_PREF_SIG_ENABLED, this.sign.isSelected()));

        return panel;
    }

    protected JPanel createPermissionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        permissionHelp.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "<html><font size='+2'><b>How to set PDF Permissions</b></font><br>" +
                        "<font size='+1'>" +
                        "To prevent unauthorized access to a PDF, you only need to set a <b>user password</b>.<br>" +
                        "The user password is required to open the document.<br><br>" +
                        "If you want to define specific permissions - such as restricting printing or<br>" +
                        "editing - you must set an <b>owner password</b>.<br>" +
                        "The <b>owner password</b> always grants full control over the document,<br>" +
                        "but is not required to open the document.<br>" +
                        "If a document with an owner password set is opened without this password,<br>" +
                        "the permissions you selected are enforced. Tools will normally not prompt for it.<br><br>" +
                        "Most tools that set permissions use a hidden, automatically generated owner password to prevent<br>" +
                        "users from gaining full access.<br><br>" +
                        "If you use the same password for both the user and owner passwords, the restrictions will<br>" +
                        "have no effect, as the single entered password will satisfy the <b>owner password</b> requirement,<br>" +
                        "thereby unlocking the document with full owner rights.<br><br>" +
                        "<b>Reminder:</b> The <i>Annotations</i> permission covers forms, comments, and signing.</font></html>",
                "Help", JOptionPane.INFORMATION_MESSAGE));

        compression.setSelected(true);
        allowPrinting.setSelected(true);
        saveButton.setEnabled(false);
        saveButton.setFont(normalFont.deriveFont(Font.BOLD, normalFont.getSize2D() * 1.5f));
        browseAppendButton.setEnabled(false);
        splitDocument.setEnabled(false);
        rotation.setEditable(false);

        JLabel ownerPasswordLabel = new JLabel("Owner Password");
        ownerPasswordLabel.setLabelFor(ownerPasswordField);

        JLabel userPasswordLabel = new JLabel("User Password");
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

        panel.setBorder(BorderFactory.createTitledBorder("Permissions"));

        return panel;
    }

    protected JPanel createDocumentPanel() {
        JPanel documentPanel = new JPanel(new GridBagLayout());
        documentPanel.setBorder(BorderFactory.createTitledBorder("Effective Document"));

        JPanel browseButtons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));

        browseButton.setToolTipText("<html>Loads new document.<br>Resets the content to this file.<br>You can also drag some file(s) here.</html>");
        new DropTarget(browseButton, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleDrop(dtde, false);
            }
        });

        browseAppendButton.setToolTipText("<html>Adds another document.<br>You can also drag some file(s) here.</html>");
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

        JLabel pageCountLabel = new JLabel("Pages");
        pageCountLabel.setLabelFor(pageCount);
        documentPanel.add(pageCountLabel, gc);

        gc.gridx = 1;
        gc.weightx = 1;
        pdfStandard.setFont(hintFont);
        pdfStandard.setToolTipText("<html>PDF version and the standards the document declares in its metadata<br>"
                + "(PDF/A, PDF/X, PDF/UA, e-invoice). This is not a validation.</html>");
        JPanel pageCountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pageCountRow.setOpaque(false);
        pageCountRow.add(pageCount);
        pageCountRow.add(Box.createHorizontalStrut(10));
        standardButton.setEnabled(false);
        standardButton.setToolTipText("<html>Changes the PDF/A level of the document (the current level is shown beside).<br>"
                + "The dialog shows what is changed and what is lost.<br>"
                + "A document that declares PDF/A is kept conform: passwords, permissions and encryption, splitting,<br>"
                + "appending documents that are no PDF/A, and converting pages to images with a non-RGB output intent<br>"
                + "are disabled. A loaded document is only repaired if you agree. Adding an invoice needs PDF/A-3:<br>"
                + "you are asked to switch the document, otherwise the operation is aborted.</html>");
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

        JLabel titleLabel = new JLabel("Title");
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
        JLabel authorLabel = new JLabel("Author");
        authorLabel.setLabelFor(author);
        documentPanel.add(authorLabel, gc);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        gc.gridx = 1;
        gc.gridwidth = 2;
        documentPanel.add(author, gc);

        gc.gridy++;
        JLabel creatorLabel = new JLabel("Creator");
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
        JLabel producerLabel = new JLabel("Producer");
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
        JLabel subjectLabel = new JLabel("Subject");
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
        JLabel keywordsLabel = new JLabel("Keywords");
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

        JLabel pagesLabel = new JLabel("Number of Pages");
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

        splitter.setBorder(BorderFactory.createTitledBorder("Splitting"));
        return splitter;
    }

    protected JPanel createPageManipulationPanel() {
        deleteButton.setOpaque(false);
        deleteButton.setToolTipText("Deletes the current page.");
        deleteButton.addActionListener(e -> doDeletePage());

        renderToImageButton.setOpaque(false);
        renderToImageButton.setToolTipText("Replace the page with the rendered image.");
        renderToImageButton.addActionListener(e -> doRenderToImage());

        renderAllToImageButton.setEnabled(false);
        renderAllToImageButton.setOpaque(false);
        renderAllToImageButton.setToolTipText("Replace all pages with the rendered images.");
        renderAllToImageButton.addActionListener(e -> {
            if (imagesAllowed())
                runInBusy(this::doRenderAllToImage);
        });

        redactButton.setOpaque(false);
        redactButton.setEnabled(false);
        redactButton.setToolTipText("Permanently removes areas or text from the current page.");
        redactButton.addActionListener(e -> doRedact());

        rotateClockwiseButton.setOpaque(false);
        rotateClockwiseButton.setToolTipText("Rotates the current page clockwise.");
        rotateClockwiseButton.addActionListener(e -> doRotateClockwise());

        rotateAllClockwiseButton.setEnabled(false);
        rotateAllClockwiseButton.setOpaque(false);
        rotateAllClockwiseButton.setToolTipText("Rotates all pages clockwise.");
        rotateAllClockwiseButton.addActionListener(e -> runInBusy(this::doRotateAllClockwise));

        moveLeft.setOpaque(false);
        moveLeft.setToolTipText("Moves the current page up.");
        moveLeft.addActionListener(e -> doMoveLeft());

        moveRight.setOpaque(false);
        moveRight.setToolTipText("Moves the current page down.");
        moveRight.addActionListener(e -> doMoveRight());

        exportImages.setToolTipText("Exports embedded images of the current page.");
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
        JLabel rotationLabel = new JLabel("Rotation");
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

        pageManipulation.setBorder(BorderFactory.createTitledBorder("Page Manipulation"));
        return pageManipulation;
    }

    private final JRadioButtonMenuItem encryptionKeyLength40 = new JRadioButtonMenuItem("40-Bit");
    private final JRadioButtonMenuItem encryptionKeyLength128 = new JRadioButtonMenuItem("128-Bit");
    private final JRadioButtonMenuItem encryptionKeyLength256 = new JRadioButtonMenuItem("256-Bit");


    private final JRadioButtonMenuItem lafLight = new JRadioButtonMenuItem("Light");
    private final JRadioButtonMenuItem lafDark = new JRadioButtonMenuItem("Dark");
    private final JRadioButtonMenuItem lafSystem = new JRadioButtonMenuItem("OS Default");
    private final JRadioButtonMenuItem lafCross = new JRadioButtonMenuItem("Cross Platform");

    private final JCheckBox storePasswords = new JCheckBox("Remember Passwords");
    private final JCheckBox confirmExit = new JCheckBox("Confirm exit if modified");

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

            JMenu encryptionKeyLengthMenu = new JMenu("Encryption Key Length");
            encryptionKeyLengthMenu.add(encryptionKeyLength40);
            encryptionKeyLengthMenu.add(encryptionKeyLength128);
            encryptionKeyLengthMenu.add(encryptionKeyLength256);
            ButtonGroup encryptionKeyLengthGroup = new ButtonGroup();
            encryptionKeyLengthGroup.add(encryptionKeyLength40);
            encryptionKeyLengthGroup.add(encryptionKeyLength128);
            encryptionKeyLengthGroup.add(encryptionKeyLength256);

            JMenu laf = new JMenu("Look And Feel");
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
                    "<html>Stores the owner, user and signature passwords in the user preferences.<br>" +
                            "Otherwise the owner password is generated randomly on each start.<br><br>" +
                            "<b>Note:</b> The passwords are stored <b>unencrypted</b> (on Windows in the registry,<br>" +
                            "on Linux/macOS in a file in your home directory). Every program running<br>" +
                            "under your account can read them. Don't use this on shared computers." +
                            "</html>");
            confirmExit.setToolTipText("Asks to write the document when the program is closed and it has unwritten changes.");
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

            JMenu renderQuality = new JMenu("Render Quality");

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

            JMenuItem redactionColor = new JMenuItem("Redaction Color");
            redactionColor.addActionListener(e -> RedactionDialog.chooseColor(this));

            JMenu options = new JMenu("Options");
            options.add(storePasswords);
            options.add(confirmExit);
            options.add(encryptionKeyLengthMenu);
            options.add(laf);
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
            JMenu help = new JMenu("Help");
            JMenuItem manual = new JMenuItem("Manual");
            manual.setToolTipText("Opens the README as PDF.");
            manual.addActionListener(e -> openManual());
            help.add(manual);
            help.addSeparator();
            JMenuItem about = new JMenuItem("About " + AboutInfo.NAME + "…");
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
        chooser.setDialogTitle("Select Signature to use…");
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
        chooser.setDialogTitle("Select PDF(s) to Load…");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = chooser.getSelectedFiles();
            MergeOptions mo = new MergeOptions();
            if (selectedFiles.length > 0 && confirmDiscard("Load")) {
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
        chooser.setDialogTitle("Select PDF(s) to Append…");
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
            toaster.toast(ToastType.SUCCESS, 5000, "<html>Converted to %d dpi image</html>",
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
                        "<html>The document contains an invoice (ZUGFeRD/Factur-X).<br>"
                                + "Redaction doesn't touch its data, so confidential data may remain in the invoice.</html>",
                        "Invoice", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                        new Object[]{"Remove Invoice", "Keep Invoice", "Cancel"}, "Remove Invoice");
                if (choice == 0) {
                    try {
                        documentProxy.removeInvoice();
                    } catch (IOException e) {
                        invoiceActions.invoiceError("Failed to remove invoice", e);
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
                        toaster.toast(ToastType.SUCCESS, 5000, "<html>Redacted %d areas</html>", regions.size());
                    } catch (Exception e) {
                        Log.exception(e);
                        toaster.toast(ToastType.ERROR, 20000,
                                "<html><font size='+2'>Redaction failed</font><br><font size='+1'><i>%s</i></font></html>", e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Creates the menu to show the fonts of the document and to replace them.
     */
    private JMenu createFontsMenu() {
        JMenu menu = new JMenu("Fonts");
        JMenuItem show = new JMenuItem("Show Fonts…");
        show.setToolTipText("Lists the fonts of the document and replaces them by bundled fonts.");
        show.addActionListener(e -> {
            if (documentProxy != null && documentProxy.getDocument() != null)
                FontsDialog.show(this, documentProxy);
        });
        menu.add(show);
        JMenuItem imported = new JMenuItem("Manage Fonts…");
        imported.setToolTipText("Font files of your own, which can replace the fonts of a document.");
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
            toaster.toast(ToastType.SUCCESS, 5000, "<html>Converted all pages to %d dpi images</html>",
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
                chooser.setDialogTitle("Choose a base file name for split…");
                JLabel info = new JLabel("<html>Choose a base file name,<br>the index of the file will be put<br><b>behind</b> the name</html>");
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
                            documentProxy.reportRisks("Split", PdfRisk.tagsLost(PdfRisk.isTagged(source), splittedDocs.get(0)));
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

                        toaster.toast(ToastType.SUCCESS, 5000, "<html>Spitted to <br><font size='+1'>%s<i> [ 001 .. %03d ]%s</i></font></html>",
                                fname, fileCount, fpostfix);
                    } catch (IOException e) {
                        toaster.toast(ToastType.ERROR, 20000,
                                "<html><font size='+2'>PDF Error</font><br><font size='+1'><i>%s</i></font></html>", e.getMessage());
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
     * Asks to save if the option is set and the document has unsaved changes.
     *
     * @return True if the program may exit.
     */
    protected boolean confirmExit() {
        return confirmDiscard("Exit");
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
        Object[] options = {"Write…", "Don't Write", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "The document has changes that are not written to a file.", title,
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
                            JButton open = new JButton("Open External");
                            open.setToolTipText("<html>Tries to open the written file with the<br>system's default application.</html>");
                            open.addActionListener(e -> {
                                try {
                                    Desktop.getDesktop().browse(finalFile);
                                } catch (Exception ex) {
                                    JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                }
                            });
                            options.add(open);
                            final URI finalParent = selectedFilePath.getParent().toUri();
                            JButton openDir = new JButton("Open Directory");
                            openDir.setToolTipText("<html>Tries to open the directory with the system's<br>default application for browsing files.</html>");
                            openDir.addActionListener(e -> {
                                try {
                                    Desktop.getDesktop().browse(finalParent);
                                } catch (Exception ex) {
                                    JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
                            "<html><font size='+1'>The PDF was written to<br><br><b>" + selectedFilePath + "</b><br><br></font></html>", "Stored",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE, null, options.toArray(), null);


                }
            } catch (Exception ex) {
                Log.exception(ex);
                toaster.toast(ToastType.ERROR, 20000, "<html><font size='+2'>Error</font><br><font size='+1'><i>%s</i></font></html>", ex.getMessage());
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
            throw new IOException("Signing failed: " + ex.getMessage(), ex);
        }
        Preferences pref = Preferences.getInstance();
        pref.set(Preferences.USER_PREF_SIG_PASSWORD, new String(pw));
    }

    public static boolean askOverwrite(Component comp, Path file) {
        int overwrite = JOptionPane.showConfirmDialog(
                comp,
                "The File \"" + file + "\" exists.\nShall this file be overwritten?",
                "Overwrite File?",
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
            pageNb.setText("Page " + page.getPageNumber());
            pageNb.setFont(normalFont);
            quality.setText(String.format("%d dpi (x %.2f)", page.getPage().dpi, page.getScale()));
            rotation.setText(page.getPage().getRotation() + " °");
        } else {
            rotation.setText("");
            pageNb.setText("Select a Page");
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
            Toast toast = toaster.toast("<html><b>Appending…</b><br>%s</html>", fileName);
            long start = System.currentTimeMillis();
            load(file.toPath(), mo, document -> {
                if (toast != null)
                    toast.setMessage(String.format("<html><b>Appended (%d ms):</b><br>%s</html>",
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
        if (selectedFile != null) {
            selectedFile = selectedFile.getAbsoluteFile();
            String parent = selectedFile.getParent();
            if (parent != null)
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
                        "<html><font size='+1'><b>%s</b></font><br>%s</html>", fileName, list);
            });
            documentProxy.appendCheck = doc -> {
                if (!pdfaMode || PdfStandard.pdfaPart(doc) > 0)
                    return null;
                toaster.toast(ToastType.ERROR, 10000,
                        "<html><font size='+1'><b>Not appended</b></font><br>The document is no PDF/A document.<br>"
                                + "Change the standard of the main document to <i>No PDF/A</i> to append it anyway.</html>");
                return "Not appended: the document is no PDF/A document.";
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
                Toast toast = toaster.toast("<html><b>Loading…</b><br><font size='+1'>%s</font></html>", fileName);
                long start = System.currentTimeMillis();
                load(selectedFile.toPath(), new MergeOptions(), document -> {
                    if (toast != null)
                        toast.setMessage(String.format("<html><b>Loaded (%d ms):</b><br>%s</html>",
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
                        "<html><font size='+1'><b>PDF/A not possible</b></font><br>%s</html>", e.getMessage());
            } catch (IOException e) {
                Log.error("Failed to fix PDF/A issues: %s", e.getMessage());
            }
        }
        if (hasDocument && PdfStandard.pdfaPart(documentProxy.getDocument()) > 0) {
            if (PdfStandard.pdfaPart(documentProxy.getDocument()) == 1 && compression.isSelected()) {
                compression.setSelected(false);
                toaster.toast(ToastType.INFO, 10000, "<html><font size='+1'><b>Compression switched off</b></font><br>"
                        + "PDF/A-1 forbids compressed object streams.</html>");
            }
            // The result of a switch is shown together with its changes, see doChangeStandard.
            if (!switchingStandard)
                reportPdfaViolations();
        }
        pdfaMode = hasDocument && PdfStandard.pdfaPart(documentProxy.getDocument()) > 0;
        final boolean pdfa = pdfaMode;
        final String notAllowed = "Not allowed for PDF/A (see the button beside the page count).";

        ownerPasswordField.setEnabled(!pdfa);
        userPasswordField.setEnabled(!pdfa);
        generateOwner.setEnabled(!pdfa);
        for (JRadioButtonMenuItem item : new JRadioButtonMenuItem[]{encryptionKeyLength40, encryptionKeyLength128, encryptionKeyLength256})
            item.setEnabled(!pdfa);
        ownerPasswordField.setToolTipText(pdfa ? notAllowed : null);
        userPasswordField.setToolTipText(pdfa ? notAllowed : null);

        splitDocument.setEnabled(hasDocument && !pdfa);
        splitDocument.setToolTipText(pdfa ? "<html>Not allowed for PDF/A: the parts lose the PDF/A metadata.</html>" : null);

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
            toaster.toast(ToastType.ERROR, 10000, "<html>Failed to read the document: %s</html>", e.getMessage());
            return;
        }
        StandardDialog.Choice choice = StandardDialog.showDialog(this, bytes, PdfStandard.profile(proxy.getDocument()));
        if (choice == null)
            return;
        reportedResult = ValidationResult.conform();
        // The document is announced as changed: that must not start a second check.
        switchingStandard = true;
        runInBusy(() -> {
            try {
                var changes = proxy.convertToStandard(choice.profile());
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
                        "<html><font size='+1'><b>Not possible</b></font><br>%s</html>", e.getMessage()));
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
            content.add(createVeraPdfFixerRow(result, actions -> {
                actions.forEach(a -> shown.merge(a, 1, Integer::sum));
                changesLabel.setText(switchChangesHtml(target, shown));
            }), BorderLayout.SOUTH);
        } else if (target != null) {
            content.add(new JLabel("<html><i>" + escapeHtml(target.notVerifiableReason()) + "</i></html>"),
                    BorderLayout.CENTER);
        }
        JOptionPane.showMessageDialog(this, content, "PDF Standard",
                verification.isConform() || target == null || !target.verifiable() ? JOptionPane.INFORMATION_MESSAGE
                        : JOptionPane.WARNING_MESSAGE);
    }

    /**
     * The text above the verification result of the switch: the headline and the changes.
     */
    private static String switchChangesHtml(PdfStandardProfile target, java.util.Map<String, Integer> changes) {
        StringBuilder html = new StringBuilder("<html><b>")
                .append(target == null ? "PDF/A declaration removed" : "Document switched to " + target).append("</b><br><br>");
        if (changes.isEmpty()) {
            html.append("No changes.");
        } else {
            html.append("<b>Changes:</b><br>");
            changes.forEach((what, times) -> html.append("&bull; ").append(escapeHtml(what))
                    .append(times > 1 ? " (" + times + "x)" : "").append("<br>"));
        }
        return html.append("</html>").toString();
    }

    /**
     * Creates the row with the button that runs the fixer of veraPDF and verifies again. The result panel shows the
     * new verification.
     *
     * @param onActions Called with the lines for the list of changes: what the fixer did, also if it could not
     *                  repair the document or failed.
     */
    private JPanel createVeraPdfFixerRow(VerificationResultPanel result,
                                         java.util.function.Consumer<java.util.List<String>> onActions) {
        final DocumentProxy proxy = documentProxy;
        JButton run = new JButton("Run veraPDF Fixer");
        run.setToolTipText("Runs the metadata fixer of veraPDF on the document, then verifies it again.");
        run.addActionListener(e -> {
            run.setEnabled(false);
            run.setText("Running...");
            new SwingWorker<Object[], Void>() {
                @Override
                protected Object[] doInBackground() throws Exception {
                    var fix = proxy.fixWithVeraPdf();
                    ValidationResult verification;
                    try {
                        verification = proxy.validatePdfa();
                    } catch (Exception ex) {
                        verification = ValidationResult.failed(ex.getMessage());
                    }
                    return new Object[]{fix, verification};
                }

                @Override
                protected void done() {
                    java.util.List<String> actions = new java.util.ArrayList<>();
                    try {
                        var fix = (com.bw.jPdfTool.PdfaValidator.MetadataFix) get()[0];
                        ValidationResult verification = (ValidationResult) get()[1];
                        result.setResult(verification);
                        reportedProxy = proxy;
                        reportedResult = verification;
                        if (fix.applied()) {
                            if (fix.fixes().isEmpty())
                                actions.add("veraPDF: metadata fixed");
                            fix.fixes().forEach(f -> actions.add("veraPDF: " + f));
                        } else {
                            // A failed attempt is listed like the others.
                            actions.add("veraPDF: " + fix.status() + ", not applied"
                                    + (fix.status().contains("ID") ? " (it would only remove the PDF/A identification)" : ""));
                            fix.fixes().forEach(f -> actions.add("veraPDF (not applied): " + f));
                        }
                    } catch (Exception ex) {
                        Log.error("veraPDF fixer failed: %s", ex.getMessage());
                        actions.add("veraPDF: failed: " + ex.getMessage());
                    }
                    onActions.accept(actions);
                    run.setText("Run veraPDF Fixer");
                    run.setEnabled(true);
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
                        JOptionPane.showMessageDialog(UI.this, result, "Not PDF/A conform", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    StringBuilder changes = new StringBuilder("<html><b>A repair would change:</b><br>");
                    check.repairs().forEach((what, times) ->
                            changes.append("&bull; ").append(escapeHtml(what)).append(" (").append(times).append("x)<br>"));
                    changes.append("<br>Repair the document?</html>");
                    JPanel message = new JPanel(new BorderLayout(0, 8));
                    message.add(new JLabel("The document is not PDF/A conform."), BorderLayout.NORTH);
                    message.add(result, BorderLayout.CENTER);
                    message.add(new JLabel(changes.toString()), BorderLayout.SOUTH);
                    int answer = JOptionPane.showConfirmDialog(UI.this, message, "PDF/A", JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION)
                        repairPdfa(proxy);
                } catch (Exception e) {
                    Log.error("PDF/A validation failed: %s", e.getMessage());
                }
            }
        }.execute();
    }

    /**
     * Repairs what PDF/A forbids, then checks the document again.
     */
    private void repairPdfa(DocumentProxy proxy) {
        runInBusy(() -> {
            try {
                var changes = proxy.fixPdfa();
                runOnEdt(() -> {
                    if (!changes.isEmpty())
                        notifyPdfaFixes(changes);
                    // Show what remains.
                    reportedResult = ValidationResult.conform();
                    reportPdfaViolations();
                });
            } catch (Exception e) {
                Log.error("PDF/A: %s", e.getMessage());
                runOnEdt(() -> toaster.toast(ToastType.ERROR, 20000,
                        "<html><font size='+1'><b>Not possible</b></font><br>%s</html>", e.getMessage()));
            }
        });
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
        if (document.getVersion() > 1.7f)
            return "Aborted: e-invoices need PDF/A-3, which is based on PDF 1.7. A PDF 2.0 document can't be conform.";
        final String label = PdfStandard.pdfaLabel(document);
        final String message = "<html>E-invoices need PDF/A-3, but the document is "
                + (label == null ? "no PDF/A document" : label) + ".<br>Switch the document to PDF/A-3?</html>";
        final int[] answer = {JOptionPane.NO_OPTION};
        try {
            if (SwingUtilities.isEventDispatchThread())
                answer[0] = JOptionPane.showConfirmDialog(this, message, "Invoice", JOptionPane.YES_NO_OPTION);
            else
                SwingUtilities.invokeAndWait(() -> answer[0] = JOptionPane.showConfirmDialog(this, message, "Invoice",
                        JOptionPane.YES_NO_OPTION));
        } catch (Exception e) {
            return "Aborted: " + e.getMessage();
        }
        if (answer[0] != JOptionPane.YES_OPTION)
            return "Aborted: the document is not PDF/A-3.";
        try {
            // Without telling the listeners: the invoice operation continues with the document.
            proxy.convertToStandardQuietly(PdfStandardProfile.PDFA_3B);
        } catch (IOException e) {
            return "Aborted: " + e.getMessage();
        }
        return null;
    }

    /**
     * Tells the user that the document was changed to be PDF/A-3 conform.
     */
    private void notifyPdfaFixes(java.util.Map<String, Integer> changes) {
        StringBuilder list = new StringBuilder();
        changes.forEach((what, times) -> list.append("&bull; ").append(what).append(" (").append(times).append("x)<br>"));
        final String message = "<html><font size='+1'><b>Document changed for PDF/A</b></font><br>"
                + list
                + "Reload the file to get the original.</html>";
        if (GraphicsEnvironment.isHeadless())
            toaster.toast(ToastType.INFO, 10000, message);
        else
            // Later: this may be called while a document is loading.
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, "PDF/A",
                    JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * Opens the manual (the README as PDF, which lies beside the jar) in the PDF viewer of the system.
     */
    private void openManual() {
        java.nio.file.Path pdf = Manual.find();
        if (pdf == null) {
            toaster.toast(ToastType.ERROR, 10000, "<html><font size='+1'><b>Manual not found</b></font><br>"
                    + "The file <i>manual.pdf</i> is missing beside the program.</html>");
            return;
        }
        try {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(pdf.toFile());
            } else {
                toaster.toast(ToastType.INFO, 10000, "<html>Can't open a viewer. The manual is the file<br><i>%s</i></html>", pdf);
            }
        } catch (Exception e) {
            toaster.toast(ToastType.ERROR, 10000, "<html>Failed to open the manual:<br>%s</html>", e.getMessage());
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
                "<html><font size='+1'><b>Not possible with PDF/A</b></font><br>The images use RGB colors, "
                        + "but the document has a non-RGB output intent (e.g. CMYK).<br>Change the standard to <i>No PDF/A</i> to do it anyway.</html>");
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
        signatureChooser.setDialogTitle("Select Signature file to use…");
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
        savePdfChooser.setDialogTitle("Select PDF to Write…");
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
            buttons.add(new JButton("close"));

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

            UI.mainWindow = new JFrame("PDF Passwords & Rights");

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

            if (!files.isEmpty()) {
                MergeOptions mo = new MergeOptions();
                ui.selectPdf(files.get(0).toFile());
                for (int i = 1; i < files.size(); ++i)
                    ui.appendPdf(files.get(i).toFile(), mo);
            }
        });
    }

}

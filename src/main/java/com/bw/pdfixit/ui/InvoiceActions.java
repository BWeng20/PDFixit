package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.ValidationResult;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.toast.ToastType;
import com.bw.pdfixit.toast.Toaster;
import com.bw.pdfixit.ui.invoice.ValidationDialog;

import org.apache.pdfbox.pdmodel.PDDocument;

import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.concurrent.ExecutionException;

/**
 * The validation and e-invoice (ZUGFeRD/Factur-X) menus of the {@link UI} and their actions.<br>
 * The invoice of the document is checked automatically, in the background, when the document is loaded or its content
 * changes. A check never changes the document. The result is the status line of the box "Invoice in the document", it
 * says nothing about the draft in the editor (that is checked when it is applied).
 */
final class InvoiceActions {

    private final UI ui;
    private DocumentProxy proxy;
    private final Toaster toaster;

    InvoiceActions(UI ui, Toaster toaster) {
        this.ui = ui;
        this.toaster = toaster;
    }



    /**
     * Creates the menu with the validations of the current document. None of them changes the document.
     */
    JMenu createValidationMenu() {
        JMenu menu = new JMenu(I18n.t("menu.validation"));
        JMenuItem pdfa = new JMenuItem(I18n.t("menu.validation.pdfa"));
        pdfa.setToolTipText(I18n.t("menu.validation.pdfa.tip"));
        pdfa.addActionListener(e -> doValidatePdfa());
        menu.add(pdfa);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                boolean hasDocument = proxy != null && proxy.getDocument() != null;
                pdfa.setEnabled(hasDocument);
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

    private boolean hasDocument;
    private boolean hasInvoice;
    /**
     * The document has no PDF/A-3 declaration: it can't have a valid invoice, the editor is locked until it is converted.
     */
    private boolean needsConversion;

    /**
     * The check of the invoice of the document and the content generation of the document it was made for, see
     * {@link DocumentProxy#getContentGeneration()}. Rotating, moving or deleting pages doesn't change the generation,
     * so the result stays. The generation is -1 if there is no result (yet). The result is null if the check failed.
     */
    private EInvoice.Validation documentCheck;
    private int documentCheckGeneration = -1;
    /**
     * The result that is shown now, for the details.
     */
    private EInvoice.Validation shownCheck;
    /**
     * The check is running.
     */
    private boolean checking;

    /**
     * The document was loaded, changed or closed. Call it on the event dispatch thread.
     *
     * @param loaded      If the document was just loaded (or closed): the invoice editor shows it, changes of the editor
     *                    for the document before are discarded. Otherwise the document only changed.
     */
    void documentChanged(DocumentProxy proxy, boolean loaded) {
        if (proxy != this.proxy || loaded) {
            // Another document: the result of the document before doesn't apply.
            documentCheck = null;
            documentCheckGeneration = -1;
        }
        this.proxy = proxy;
        PDDocument document = proxy != null ? proxy.getDocument(): null;
        this.hasDocument = document != null;
        this.hasInvoice = hasDocument && EInvoice.hasInvoice(document);
        this.needsConversion = EInvoice.needsConversion(document);
        // The invoice needs PDF/A-3. If the document is something else, the editor is locked until it is converted.
        ui.invoiceEditor.documentChanged(proxy, loaded);
        updateInvoiceControls();
        checkIfMissing();
    }

    /**
     * Starts the check of the invoice of the document if there is none for the current content, in the background.
     * There is only one check at a time.
     */
    private void checkIfMissing() {
        if (checking || !hasInvoice || needsConversion || proxy == null)
            return;
        final int generation = proxy.getContentGeneration();
        if (generation == documentCheckGeneration)
            return;
        final DocumentProxy checked = proxy;
        checking = true;
        updateInvoiceControls();
        new SwingWorker<EInvoice.Validation, Void>() {
            @Override
            protected EInvoice.Validation doInBackground() throws Exception {
                return EInvoice.validate(checked.getBytes(), "current document");
            }

            @Override
            protected void done() {
                checking = false;
                EInvoice.Validation result = null;
                try {
                    result = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Exception cause = e.getCause() instanceof Exception ex ? ex : e;
                    invoiceError(I18n.t("invoice.validationFailed"), cause);
                }
                // Not for another document than the one that was checked. A failed check is not repeated for the same
                // content, there is no result then.
                if (checked == proxy) {
                    documentCheck = result;
                    documentCheckGeneration = generation;
                }
                updateInvoiceControls();
                // The content may have changed meanwhile.
                checkIfMissing();
            }
        }.execute();
    }

    /**
     * Sets the status line of the box "Invoice in the document" ({@link InvoicePanel}): there is no document, it needs
     * PDF/A-3, it has no invoice, or the result of the automatic check of its invoice.
     */
    private void updateInvoiceControls() {
        ui.validatePdfaButton.setEnabled(hasDocument);

        shownCheck = null;
        String text;
        String toolTip = null;
        if (!hasDocument) {
            text = I18n.t("invoice.state.noDocument");
            toolTip = I18n.t("invoice.state.noDocument.tip");
        } else if (needsConversion) {
            text = I18n.t("invoice.state.convert");
            toolTip = I18n.t("invoice.state.convert.tip");
        } else if (!hasInvoice) {
            text = I18n.t("ui.invoice.status.none");
        } else {
            if (documentCheckGeneration == proxy.getContentGeneration())
                shownCheck = documentCheck;
            if (shownCheck == null) {
                text = I18n.t(checking ? "ui.invoice.status.checking" : "ui.invoice.status.present");
            } else if (!shownCheck.valid()) {
                text = I18n.t("ui.invoice.status.invalid", ValidationDialog.summary(shownCheck));
            } else {
                int warnings = ValidationDialog.count(shownCheck)[1];
                text = warnings > 0 ? I18n.t("ui.invoice.status.validWarnings", I18n.t("ui.invoice.warnings", warnings))
                        : I18n.t("ui.invoice.status.valid");
            }
        }
        // The line can be cut off when it is long: the tooltip has all of it.
        ui.invoiceStatus.setToolTipText(toolTip != null ? toolTip : text);
        ui.invoiceStatus.setIcon(shownCheck == null ? null : UI.getIcon(shownCheck.valid() ? "svg/status-ok" : "svg/status-error"));
        ui.invoiceStatus.setText(text);
        ui.invoiceDetailsButton.setVisible(shownCheck != null);
    }

    /**
     * Shows the messages and the report of the result that is shown.
     */
    void doShowValidationDetails() {
        if (shownCheck != null)
            ValidationDialog.showDialog(ui, shownCheck);
    }

    private void showText(String title, String text) {
        JTextArea area = new JTextArea(text, 24, 90);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(ui, new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Reports an error. Long or multi-line messages (e.g. validation results) don't fit into a toast,
     * they are shown in a dialog.
     */
    void invoiceError(String what, Exception e) {
        Log.exception(e);
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (message.length() > 80 || message.indexOf('\n') >= 0) {
            toaster.toast(ToastType.ERROR, 10000,
                    I18n.t("invoice.seeDetails"), what);
            SwingUtilities.invokeLater(() -> showText(what, message));
        } else {
            toaster.toast(ToastType.ERROR, 20000,
                    "<html><font size='+2'>%s</font><br><font size='+1'><i>%s</i></font></html>", what, message);
        }
    }

    /**
     * Checks the document with veraPDF, independent of the PDF/A level, and shows the result.
     */
    void doValidatePdfa() {
        if (proxy == null || proxy.getDocument() == null)
            return;
        ui.runInBusy(() -> {
            try {
                ValidationResult result = proxy.validatePdfa();
                SwingUtilities.invokeLater(() -> {
                    VerificationResultPanel panel = new VerificationResultPanel();
                    panel.setResult(result);
                    JPanel content = new JPanel(new BorderLayout(0, 8));
                    content.add(panel, BorderLayout.CENTER);
                    if (result.getState() == ValidationResult.State.NOT_CONFORM) {
                        // Repairing is always possible here, also if it was declined when the document was loaded.
                        JLabel done = new JLabel();
                        JPanel south = new JPanel(new BorderLayout(0, 6));
                        south.add(ui.createVeraPdfFixerRow(panel, actions -> {
                            done.setText("<html>" + String.join("<br>", actions.stream()
                                    .map(a -> "&bull; " + a.replace("&", "&amp;").replace("<", "&lt;")).toList()) + "</html>");
                            java.awt.Window w = SwingUtilities.getWindowAncestor(done);
                            if (w != null)
                                w.pack();
                        }), BorderLayout.NORTH);
                        south.add(done, BorderLayout.CENTER);
                        content.add(south, BorderLayout.SOUTH);
                    }
                    JOptionPane.showMessageDialog(ui, content, I18n.t("common.pdfa"), switch (result.getState()) {
                        case CONFORM -> JOptionPane.INFORMATION_MESSAGE;
                        case NOT_DECLARED -> JOptionPane.WARNING_MESSAGE;
                        default -> JOptionPane.ERROR_MESSAGE;
                    });
                });
            } catch (Exception e) {
                invoiceError(I18n.t("invoice.validationFailed"), e);
            }
        });
    }
}

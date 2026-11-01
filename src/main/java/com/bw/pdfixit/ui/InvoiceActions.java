package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.ValidationResult;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.toast.ToastType;
import com.bw.pdfixit.toast.Toaster;
import com.bw.pdfixit.ui.invoice.InvoiceDialog;
import com.bw.pdfixit.ui.invoice.InvoiceEditorDialog;
import com.bw.pdfixit.ui.invoice.PartySettingsDialog;
import com.bw.pdfixit.ui.invoice.ValidationDialog;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Supplier;

/**
 * The validation and e-invoice (ZUGFeRD/Factur-X) menus of the {@link UI} and their actions.
 */
final class InvoiceActions {

    private final String ATTACH_INVOICE = I18n.t("invoice.attach");
    private final String ATTACH_INVOICE_TOOLTIP =
            I18n.t("invoice.attach.tip");

    private final UI ui;
    private final Supplier<DocumentProxy> proxySupplier;
    private final Supplier<Toaster> toasterSupplier;

    InvoiceActions(UI ui, Supplier<DocumentProxy> proxySupplier, Supplier<Toaster> toasterSupplier) {
        this.ui = ui;
        this.proxySupplier = proxySupplier;
        this.toasterSupplier = toasterSupplier;
    }

    private DocumentProxy proxy() {
        return proxySupplier.get();
    }

    private Toaster toaster() {
        return toasterSupplier.get();
    }

    /**
     * Creates the menu with the validations of the current document. None of them changes the document.
     */
    JMenu createValidationMenu() {
        JMenu menu = new JMenu(I18n.t("menu.validation"));
        JMenuItem pdfa = new JMenuItem(I18n.t("menu.validation.pdfa"));
        pdfa.setToolTipText(I18n.t("menu.validation.pdfa.tip"));
        pdfa.addActionListener(e -> doValidatePdfa());
        JMenuItem invoice = new JMenuItem(I18n.t("menu.validation.invoice"));
        invoice.setToolTipText(I18n.t("menu.validation.invoice.tip"));
        invoice.addActionListener(e -> doValidateInvoice());
        menu.add(pdfa);
        menu.add(invoice);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                boolean hasDocument = proxy() != null && proxy().getDocument() != null;
                pdfa.setEnabled(hasDocument);
                invoice.setEnabled(hasDocument);
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
     * Creates the menu with the e-invoice (ZUGFeRD/Factur-X) actions.
     */
    JMenu createInvoiceMenu() {
        JMenu menu = new JMenu(I18n.t("menu.invoice"));

        JMenuItem show = new JMenuItem(I18n.t("menu.invoice.show"));
        show.addActionListener(e -> doShowInvoice());
        JMenuItem edit = new JMenuItem(I18n.t("menu.invoice.create"));
        edit.addActionListener(e -> doEditInvoice());
        JMenuItem save = new JMenuItem(I18n.t("menu.invoice.save"));
        save.addActionListener(e -> doSaveInvoiceXml());
        JMenuItem attach = new JMenuItem(ATTACH_INVOICE);
        attach.setToolTipText(ATTACH_INVOICE_TOOLTIP);
        attach.addActionListener(e -> doAttachInvoiceXml());
        JMenuItem remove = new JMenuItem(I18n.t("ui.invoice.remove"));
        remove.addActionListener(e -> doRemoveInvoice());

        menu.add(show);
        menu.add(edit);
        menu.addSeparator();
        menu.add(save);
        menu.add(attach);
        menu.add(remove);
        menu.addSeparator();
        JMenuItem parties = new JMenuItem(I18n.t("menu.invoice.parties"));
        parties.setToolTipText(I18n.t("menu.invoice.parties.tip"));
        parties.addActionListener(e -> PartySettingsDialog.showDialog(ui));
        menu.add(parties);

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                boolean hasDocument = proxy() != null && proxy().getDocument() != null;
                boolean hasInvoice = hasDocument && EInvoice.hasInvoice(proxy().getDocument());
                show.setEnabled(hasInvoice);
                save.setEnabled(hasInvoice);
                remove.setEnabled(hasInvoice);

                attach.setEnabled(hasDocument);
                edit.setEnabled(hasDocument);
                edit.setText(hasInvoice ? I18n.t("menu.invoice.edit") : I18n.t("menu.invoice.create"));
                edit.setToolTipText(I18n.t("menu.invoice.create.tip"));
                attach.setText(ATTACH_INVOICE);
                attach.setToolTipText(ATTACH_INVOICE_TOOLTIP);
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
            toaster().toast(ToastType.ERROR, 10000,
                    I18n.t("invoice.seeDetails"), what);
            SwingUtilities.invokeLater(() -> showText(what, message));
        } else {
            toaster().toast(ToastType.ERROR, 20000,
                    "<html><font size='+2'>%s</font><br><font size='+1'><i>%s</i></font></html>", what, message);
        }
    }

    private void doShowInvoice() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        try {
            var xml = EInvoice.extractXml(proxy().getDocument());
            if (xml.isEmpty()) {
                toaster().toast(ToastType.INFO, 5000, I18n.t("toast.noInvoice"));
                return;
            }
            InvoiceDialog.showDialog(ui, xml.get());
        } catch (IOException e) {
            invoiceError(I18n.t("invoice.readFailed"), e);
        }
    }

    /**
     * Opens the invoice editor. Edits the existing invoice of the document or creates a new one.
     */
    private void doEditInvoice() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        byte[] xml;
        try {
            xml = EInvoice.extractXml(proxy().getDocument()).orElse(null);
        } catch (IOException e) {
            invoiceError(I18n.t("invoice.readFailed"), e);
            return;
        }
        if (InvoiceEditorDialog.showDialog(ui, xml, proxy()))
            toaster().toast(ToastType.SUCCESS, 5000, xml == null ? I18n.t("toast.invoice.attached") : I18n.t("toast.invoice.updated"));
    }

    void doValidateInvoice() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        ui.runInBusy(() -> {
            try {
                EInvoice.Validation v = EInvoice.validate(proxy().getBytes(), "current document");
                SwingUtilities.invokeLater(() -> ValidationDialog.showDialog(ui, v));
            } catch (Exception e) {
                invoiceError(I18n.t("invoice.validationFailed"), e);
            }
        });
    }

    /**
     * Checks the document with veraPDF, independent of the PDF/A level, and shows the result.
     */
    void doValidatePdfa() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        ui.runInBusy(() -> {
            try {
                ValidationResult result = proxy().validatePdfa();
                SwingUtilities.invokeLater(() -> {
                    VerificationResultPanel panel = new VerificationResultPanel();
                    panel.setResult(result);
                    JOptionPane.showMessageDialog(ui, panel, I18n.t("common.pdfa"), switch (result.getState()) {
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

    private File chooseXml(boolean save) {
        JFileChooser chooser = new JFileChooser();
        String lastDir = UI.getPref(Preferences.USER_PREF_LAST_PDF_DIR, null);
        if (lastDir != null)
            chooser.setCurrentDirectory(new File(lastDir));
        chooser.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
        chooser.setMultiSelectionEnabled(false);
        if (save) {
            chooser.setDialogTitle(I18n.t("menu.invoice.save"));
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), "factur-x.xml"));
        } else {
            chooser.setDialogTitle(I18n.t("chooser.invoiceXml"));
        }
        int result = save ? chooser.showSaveDialog(ui) : chooser.showOpenDialog(ui);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private void doSaveInvoiceXml() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        try {
            var xml = EInvoice.extractXml(proxy().getDocument());
            if (xml.isEmpty())
                return;
            File file = chooseXml(true);
            if (file != null && (!file.exists() || UI.askOverwrite(ui, file.toPath()))) {
                Files.write(file.toPath(), xml.get());
                toaster().toast(ToastType.SUCCESS, 5000, I18n.t("toast.invoice.stored"), file.getName());
            }
        } catch (IOException e) {
            invoiceError(I18n.t("invoice.storeFailed"), e);
        }
    }

    private void doAttachInvoiceXml() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        File file = chooseXml(false);
        if (file != null) {
            ui.runInBusy(() -> {
                try {
                    proxy().attachInvoice(Files.readAllBytes(file.toPath()));
                    toaster().toast(ToastType.SUCCESS, 5000, I18n.t("toast.invoice.attachedFile"), file.getName());
                } catch (Exception e) {
                    invoiceError(I18n.t("invoice.attachFailed"), e);
                }
            });
        }
    }

    private void doRemoveInvoice() {
        if (proxy() == null || proxy().getDocument() == null)
            return;
        try {
            if (proxy().removeInvoice())
                toaster().toast(ToastType.SUCCESS, 5000, I18n.t("toast.invoice.removed"));
        } catch (IOException e) {
            invoiceError(I18n.t("ui.invoice.removeFailed"), e);
        }
    }
}

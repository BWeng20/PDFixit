package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;

/**
 * The data of the invoice itself: type, profile, number, currency, dates, buyer reference, note and VAT exemption
 * reason. The tab "Invoice" of the {@link InvoiceEditor}.
 */
class InvoiceDetailsPanel extends FormPanel {

    InvoiceDetailsPanel(InvoiceFields fields) {
        // One column: the editor is narrow, it is beside the pages.
        super(true);
        // Short values: type and profile, number and currency fit side by side.
        pairRow(I18n.t("editor.type"), fields.documentType(), I18n.t("editor.profile"), fields.profile(), false);
        pairRow(I18n.t("editor.number"), fields.number(), I18n.t("editor.currency"), fields.currency(), true);
        // Date fields keep their preferred width, they don't grow with the form.
        compactRow(I18n.t("invoice.issueDate"), fields.issueDate());
        compactRow(I18n.t("editor.deliveryDate"), fields.deliveryDate());
        compactRow(I18n.t("editor.serviceFrom"), fields.periodStart());
        compactRow(I18n.t("editor.serviceTo"), fields.periodEnd());
        row(I18n.t("editor.buyerReference"), fields.buyerReference());
        row(I18n.t("editor.note"), fields.note());
        row(I18n.t("editor.exemption"), fields.vatExemptionReason());
    }
}

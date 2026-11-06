package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;

import javax.swing.JLabel;
import javax.swing.UIManager;

/**
 * Due date and payment terms; the bank account is part of the seller. The tab "Payment" of the {@link InvoiceEditor}.
 */
class PaymentPanel extends FormPanel {

    PaymentPanel(InvoiceFields fields) {
        super(true);
        compactRow(I18n.t("invoice.dueDate"), fields.dueDate());
        row(I18n.t("invoice.paymentTerms"), fields.paymentTerms());
        JLabel hint = new JLabel(I18n.t("editor.bankHint"));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        row("", hint);
    }
}

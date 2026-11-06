package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.ui.UI;
import com.bw.pdfixit.ui.wizard.Wizard;

import javax.swing.SwingUtilities;

/**
 * The E-Invoice wizard: a new invoice on a letterhead template, an invoice attached to an existing PDF, or an invoice
 * from an XML file; seller, buyer, items, payment, template and review in seven steps. The steps are made by
 * {@link InvoiceWizardSession}.
 */
public final class EInvoiceWizard implements Wizard {

    @Override
    public String title() {
        return I18n.t("wizard.invoice.title");
    }

    @Override
    public String description() {
        return I18n.t("wizard.invoice.description");
    }

    @Override
    public String icon() {
        return "svg/card-invoice";
    }

    @Override
    public void start(UI ui) {
        new InvoiceWizardSession(ui).run(SwingUtilities.getWindowAncestor(ui));
    }
}

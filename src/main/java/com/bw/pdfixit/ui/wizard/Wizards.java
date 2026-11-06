package com.bw.pdfixit.ui.wizard;

import com.bw.pdfixit.ui.invoice.EInvoiceWizard;

import java.util.List;

/**
 * The wizards the user can choose, in the order of the menu and of the start page. A new wizard is added here.
 */
public final class Wizards {

    public static final List<Wizard> ALL = List.of(new EInvoiceWizard());

    private Wizards() {
    }
}

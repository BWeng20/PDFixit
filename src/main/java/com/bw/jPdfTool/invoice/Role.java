package com.bw.jPdfTool.invoice;

import com.bw.jPdfTool.I18n;

/**
 * Role of a party in an invoice. The presets in {@link InvoiceData#partiesFile()} are one list,
 * each entry says in which roles it is offered (see {@link Party#hasRole(Role)}).
 */
public enum Role {
    SELLER,
    BUYER;

    /**
     * The name of the role in the language of the user.
     */
    public String title() {
        return I18n.t("role." + name().toLowerCase(java.util.Locale.ROOT));
    }
}

package com.bw.pdfixit.template;

import com.bw.pdfixit.invoice.InvoiceData;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A made-up invoice for the preview of the layout editor: every field has a value, so the user sees every region
 * filled. No real names or numbers.
 */
public final class SampleInvoice {

    private SampleInvoice() {
    }

    /**
     * @param items The number of items: a few show the first page with everything, many give following pages.
     */
    public static InvoiceData create(int items) {
        InvoiceData d = new InvoiceData();
        d.number = "RE-2026-0042";
        d.issueDate = LocalDate.of(2026, 3, 2);
        d.deliveryDate = LocalDate.of(2026, 2, 27);
        d.periodStart = LocalDate.of(2026, 2, 1);
        d.periodEnd = LocalDate.of(2026, 2, 28);
        d.dueDate = LocalDate.of(2026, 3, 16);
        d.buyerReference = "991-12345-67";
        d.note = "Leistungen gemäß Angebot vom 15.01.2026.";
        d.paymentTerms = "Zahlbar innerhalb von 14 Tagen ohne Abzug.";
        d.vatExemptionReason = "Beispiel für einen Befreiungsgrund";
        d.seller.name = "Beispiel Handels GmbH";
        d.seller.street = "Musterweg 1";
        d.seller.zip = "12345";
        d.seller.city = "Beispielstadt";
        d.seller.country = "DE";
        d.seller.vatId = "DE000000000";
        d.seller.taxId = "000/000/00000";
        d.seller.email = "rechnung@example.com";
        d.seller.contactName = "Erika Beispiel";
        d.seller.phone = "+49 000 000000";
        d.seller.iban = "DE00000000000000000000";
        d.seller.bic = "XXXXDEXXXXX";
        d.seller.accountHolder = "Beispiel Handels GmbH";
        d.buyer.name = "Kunde Muster AG";
        d.buyer.street = "Kundenstraße 7";
        d.buyer.zip = "54321";
        d.buyer.city = "Musterhausen";
        d.buyer.country = "DE";
        d.buyer.vatId = "DE111111111";
        d.buyer.taxId = "111/111/11111";
        d.buyer.email = "einkauf@example.org";
        for (int i = 1; i <= items; ++i)
            d.lines.add(new InvoiceData.Line(i == 1 ? "Beratung und Konzeption" : "Beispielposition " + i,
                    new BigDecimal(i % 3 == 0 ? "2.5" : "1"), i % 2 == 0 ? "HUR" : "C62", new BigDecimal("95.00"),
                    new BigDecimal(i % 4 == 0 ? "7" : "19")));
        return d;
    }
}

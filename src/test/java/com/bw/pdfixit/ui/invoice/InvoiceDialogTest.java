package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.InvoiceData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

class InvoiceDialogTest {

    @Test
    void formatDate() {
        Assertions.assertEquals("01.10.2024", InvoiceDialog.formatDate("20241001", Locale.GERMANY));
        Assertions.assertEquals("Oct 1, 2024", InvoiceDialog.formatDate("20241001", Locale.US));
        Assertions.assertEquals("2024-10-01T12:00", InvoiceDialog.formatDate("2024-10-01T12:00", Locale.US), "unknown format is kept");
        Assertions.assertNull(InvoiceDialog.formatDate(null, Locale.US));
    }

    @Test
    void formatAmount() {
        // Java uses a non-breaking space between amount and symbol in Germany.
        Assertions.assertEquals("119,00 €", InvoiceDialog.formatAmount("119.00", "EUR", Locale.GERMANY).replace(' ', ' '));
        Assertions.assertEquals("$1,234.50", InvoiceDialog.formatAmount("1234.5", "USD", Locale.US));
        Assertions.assertEquals("1.234,567 €", InvoiceDialog.formatAmount("1234.567", "EUR", Locale.GERMANY).replace(' ', ' '),
                "more digits than usual are kept");
        Assertions.assertEquals("abc EUR", InvoiceDialog.formatAmount("abc", "EUR", Locale.US));
        Assertions.assertEquals("12 XYZ1", InvoiceDialog.formatAmount("12", "XYZ1", Locale.US), "unknown currency");
        Assertions.assertNull(InvoiceDialog.formatAmount(null, "EUR", Locale.US));
    }

    @Test
    void formatIban() {
        Assertions.assertEquals("DE71 3933 6053 0432 7394 89", InvoiceDialog.formatIban("DE71393360530432739489"));
        Assertions.assertEquals("DE71 3933 6053 0432 7394 89", InvoiceDialog.formatIban("DE71 3933 60530432739489"));
        Assertions.assertNull(InvoiceDialog.formatIban(null));
    }

    @Test
    void names() {
        Assertions.assertEquals("Invoice", InvoiceDialog.documentType("380"));
        Assertions.assertEquals("Credit note", InvoiceDialog.documentType("381"));
        Assertions.assertEquals("Invoice", InvoiceDialog.documentType(null));
        Assertions.assertEquals("Document (type 999)", InvoiceDialog.documentType("999"));
        Assertions.assertEquals("EN 16931 (Comfort)", InvoiceDialog.profileName("COMFORT"));
        Assertions.assertEquals("Minimum", InvoiceDialog.profileName("MINIMUM"));
        Assertions.assertEquals("SOMETHING", InvoiceDialog.profileName("SOMETHING"));
    }

    @Test
    void hasData() throws Exception {
        Assertions.assertTrue(InvoiceDialog.hasData(EInvoice.summarize(sample())));
        Assertions.assertFalse(InvoiceDialog.hasData(EInvoice.summarize("<root/>".getBytes(StandardCharsets.UTF_8))));
        Assertions.assertFalse(InvoiceDialog.hasData(null));
    }

    @Test
    void prettyXml() {
        String pretty = InvoiceData.prettyXml("<a><b>x</b><c/></a>".getBytes(StandardCharsets.UTF_8));
        Assertions.assertTrue(pretty.contains("\n  <b>x</b>"), pretty);
        // Not parseable: shown as it is.
        Assertions.assertEquals("<a><b>", InvoiceData.prettyXml("<a><b>".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sample() throws Exception {
        try (var is = InvoiceDialogTest.class.getResourceAsStream("/factur-x-minimum.xml")) {
            return java.util.Objects.requireNonNull(is).readAllBytes();
        }
    }
}

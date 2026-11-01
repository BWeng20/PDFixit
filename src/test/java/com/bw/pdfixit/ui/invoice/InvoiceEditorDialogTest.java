package com.bw.pdfixit.ui.invoice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

class InvoiceEditorDialogTest {

    @Test
    void parseDecimal() {
        Assertions.assertEquals(new BigDecimal("12.5"), InvoiceEditorDialog.parseDecimal("12,5", Locale.GERMANY));
        Assertions.assertEquals(new BigDecimal("12.5"), InvoiceEditorDialog.parseDecimal("12.5", Locale.GERMANY), "dot works too");
        Assertions.assertEquals(new BigDecimal("12.5"), InvoiceEditorDialog.parseDecimal("12.5", Locale.US));
        Assertions.assertEquals(new BigDecimal("19"), InvoiceEditorDialog.parseDecimal("19 %", Locale.GERMANY));
        Assertions.assertNull(InvoiceEditorDialog.parseDecimal("  ", Locale.GERMANY));
        Assertions.assertThrows(NumberFormatException.class, () -> InvoiceEditorDialog.parseDecimal("1.234,5", Locale.GERMANY));
        Assertions.assertThrows(NumberFormatException.class, () -> InvoiceEditorDialog.parseDecimal("abc", Locale.US));
    }

    @Test
    void formatDecimal() {
        Assertions.assertEquals("1234,50", InvoiceEditorDialog.formatDecimal(new BigDecimal("1234.5"), 2, Locale.GERMANY));
        Assertions.assertEquals("8", InvoiceEditorDialog.formatDecimal(new BigDecimal("8.000"), 0, Locale.US));
        Assertions.assertEquals("0.125", InvoiceEditorDialog.formatDecimal(new BigDecimal("0.125"), 2, Locale.US));
    }
}

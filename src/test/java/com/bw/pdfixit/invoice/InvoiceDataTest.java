package com.bw.pdfixit.invoice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

class InvoiceDataTest {

    /**
     * Complete invoice with made-up data.
     */
    static InvoiceData sample() {
        InvoiceData d = new InvoiceData();
        d.number = "T-2024-17";
        d.issueDate = LocalDate.of(2024, 10, 1);
        d.deliveryDate = LocalDate.of(2024, 9, 30);
        d.dueDate = LocalDate.of(2024, 10, 15);
        d.paymentTerms = "Payable within 14 days";
        d.buyerReference = "04011000-12345-34";
        d.seller.name = "Kelomi Testfirma GmbH";
        d.seller.street = "Rasoweg 12";
        d.seller.zip = "12345";
        d.seller.city = "Vizostadt";
        d.seller.vatId = "DE123456789";
        d.seller.email = "billing@example.com";
        d.seller.contactName = "Quala Frelun";
        d.seller.phone = "+49 123 456789";
        d.buyer.name = "Banuke Testfirma AG";
        d.buyer.street = "Lunweg 3";
        d.buyer.zip = "54321";
        d.buyer.city = "Mitestadt";
        d.buyer.email = "invoices@example.org";
        d.seller.iban = "DE71 3933 6053 0432 7394 89";
        d.seller.accountHolder = "Kelomi Testfirma GmbH";
        d.lines.add(new InvoiceData.Line("Consulting", new BigDecimal("8"), "HUR", new BigDecimal("95.00"), new BigDecimal("19")));
        d.lines.add(new InvoiceData.Line("Book", new BigDecimal("2"), "C62", new BigDecimal("12.50"), new BigDecimal("7")));
        return d;
    }

    @Test
    void totals() {
        InvoiceData.Totals t = sample().totals();
        // 8 * 95 = 760 (19% = 144.40), 2 * 12.50 = 25 (7% = 1.75)
        Assertions.assertEquals(new BigDecimal("785.00"), t.net());
        Assertions.assertEquals(new BigDecimal("146.15"), t.vat());
        Assertions.assertEquals(new BigDecimal("931.15"), t.gross());
    }

    @Test
    void check() {
        Assertions.assertEquals(java.util.List.of(), sample().check());

        InvoiceData empty = new InvoiceData();
        var problems = empty.check();
        Assertions.assertTrue(problems.contains("Invoice number is missing."), problems.toString());
        Assertions.assertTrue(problems.contains("The invoice has no items."), problems.toString());

        InvoiceData x = sample();
        x.profile = InvoiceData.PROFILE_XRECHNUNG;
        x.buyerReference = " ";
        Assertions.assertEquals(java.util.List.of("XRechnung needs the buyer reference (Leitweg-ID)."), x.check());
    }

    @Test
    void roundTrip() throws Exception {
        InvoiceData d = sample();
        InvoiceData r = InvoiceData.fromXml(d.toXml());
        Assertions.assertEquals(d.number, r.number);
        Assertions.assertEquals(d.issueDate, r.issueDate);
        Assertions.assertEquals(d.dueDate, r.dueDate);
        Assertions.assertEquals(d.deliveryDate, r.deliveryDate);
        Assertions.assertEquals(d.buyerReference, r.buyerReference);
        Assertions.assertEquals(d.paymentTerms, r.paymentTerms);
        Assertions.assertEquals(d.seller.name, r.seller.name);
        Assertions.assertEquals(d.seller.city, r.seller.city);
        Assertions.assertEquals(d.seller.vatId, r.seller.vatId);
        Assertions.assertEquals(d.buyer.name, r.buyer.name);
        Assertions.assertEquals("DE71393360530432739489", r.seller.iban);
        Assertions.assertEquals(2, r.lines.size());
        Assertions.assertEquals("Consulting", r.lines.get(0).name);
        Assertions.assertEquals("HUR", r.lines.get(0).unit);
        Assertions.assertEquals(0, new BigDecimal("95").compareTo(r.lines.get(0).price));
        Assertions.assertEquals(0, new BigDecimal("8").compareTo(r.lines.get(0).quantity));
        Assertions.assertEquals(0, new BigDecimal("7").compareTo(r.lines.get(1).vatPercent));
        Assertions.assertEquals(d.totals(), r.totals());

        EInvoice.Summary s = EInvoice.summarize(d.toXml());
        Assertions.assertEquals("T-2024-17", s.id());
        Assertions.assertEquals("931.15", new BigDecimal(s.amount()).setScale(2).toPlainString());
    }

    @Test
    void creditNote() throws Exception {
        InvoiceData d = sample();
        d.documentCode = InvoiceData.TYPE_CREDIT_NOTE;
        Assertions.assertEquals("381", EInvoice.summarize(d.toXml()).documentCode());
        Assertions.assertEquals("381", InvoiceData.fromXml(d.toXml()).documentCode);
    }

    @Test
    void attachedInvoiceIsValid() throws Exception {
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), sample().toXml()));
    }

    @Test
    void xrechnungIsValid() throws Exception {
        InvoiceData d = sample();
        d.profile = InvoiceData.PROFILE_XRECHNUNG;
        byte[] xml = d.toXml();
        Assertions.assertEquals(InvoiceData.PROFILE_XRECHNUNG, InvoiceData.fromXml(xml).profile);
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), xml));
    }

    @Test
    void exemptLinesAreValid() throws Exception {
        InvoiceData d = sample();
        d.lines.clear();
        d.lines.add(new InvoiceData.Line("Service", BigDecimal.ONE, "C62", new BigDecimal("100"), BigDecimal.ZERO));
        d.vatExemptionReason = "Kleinunternehmer gemäß § 19 UStG";
        Assertions.assertEquals(new BigDecimal("100.00"), d.totals().gross());
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), d.toXml()));
        Assertions.assertEquals(d.vatExemptionReason, InvoiceData.fromXml(d.toXml()).vatExemptionReason);
    }

    @Test
    void importExisting() throws Exception {
        InvoiceData d = InvoiceData.fromXml(EInvoiceTest.sampleXml());
        Assertions.assertNotNull(d.number);
        Assertions.assertNotNull(d.seller.name);
    }
}

package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.InvoiceData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class InvoiceRendererTest {

    private static final Locale DE = Locale.GERMAN;

    /**
     * A complete invoice with made-up data.
     */
    public static InvoiceData sample() {
        InvoiceData d = new InvoiceData();
        d.number = "T-2024-17";
        d.issueDate = LocalDate.of(2024, 10, 1);
        d.deliveryDate = LocalDate.of(2024, 9, 30);
        d.dueDate = LocalDate.of(2024, 10, 15);
        d.paymentTerms = "Zahlbar innerhalb von 14 Tagen ohne Abzug.";
        d.note = "Leistungen gemäß Vertrag vom 01.09.2024.";
        d.buyerReference = "04011000-12345-34";
        d.seller.name = "Kelomi Testfirma GmbH";
        d.seller.street = "Rasoweg 12";
        d.seller.zip = "12345";
        d.seller.city = "Vizostadt";
        d.seller.country = "DE";
        d.seller.vatId = "DE123456789";
        d.seller.email = "billing@example.com";
        d.seller.contactName = "Quala Frelun";
        d.seller.phone = "+49 123 456789";
        d.seller.iban = "DE71393360530432739489";
        d.seller.bic = "COBADEFFXXX";
        d.seller.accountHolder = "Kelomi Testfirma GmbH";
        d.buyer.name = "Banuke Testfirma AG";
        d.buyer.street = "Lunweg 3";
        d.buyer.zip = "54321";
        d.buyer.city = "Mitestadt";
        d.buyer.country = "DE";
        d.buyer.email = "invoices@example.org";
        d.lines.add(new InvoiceData.Line("Beratung", new BigDecimal("8"), "HUR", new BigDecimal("95.00"), new BigDecimal("19")));
        d.lines.add(new InvoiceData.Line("Fachbuch", new BigDecimal("2"), "C62", new BigDecimal("12.50"), new BigDecimal("7")));
        return d;
    }

    @Test
    void test_valuesInGerman() {
        InvoiceData d = sample();
        InvoiceContent c = InvoiceRenderer.content(d, DE);
        Map<String, String> v = c.values;
        Assertions.assertEquals("T-2024-17", v.get("number"));
        Assertions.assertEquals("01.10.2024", v.get("issueDate"));
        Assertions.assertEquals("30.09.2024", v.get("deliveryDate"));
        Assertions.assertEquals("15.10.2024", v.get("dueDate"));
        Assertions.assertEquals("04011000-12345-34", v.get("buyerReference"));
        Assertions.assertEquals("DE71 3933 6053 0432 7394 89", v.get("seller.iban"));
        Assertions.assertEquals("COBADEFFXXX", v.get("seller.bic"));
        Assertions.assertEquals("Deutschland", v.get("seller.country"));
        Assertions.assertEquals("Kelomi Testfirma GmbH\nRasoweg 12\n12345 Vizostadt", v.get("seller.address"));
        Assertions.assertEquals("Banuke Testfirma AG\nLunweg 3\n54321 Mitestadt", v.get("buyer.address"));
        Assertions.assertNull(v.get("periodStart"), "empty values are left out");

        Assertions.assertEquals(2, c.lines.size());
        Map<Layout.Column, String> row = c.lines.get(0);
        Assertions.assertEquals("1", row.get(Layout.Column.POS));
        Assertions.assertEquals("Beratung", row.get(Layout.Column.NAME));
        Assertions.assertEquals("8", row.get(Layout.Column.QUANTITY));
        Assertions.assertEquals("Std.", row.get(Layout.Column.UNIT));
        Assertions.assertEquals("95,00 €", row.get(Layout.Column.PRICE));
        Assertions.assertEquals("19 %", row.get(Layout.Column.VAT_PERCENT));
        Assertions.assertEquals("760,00 €", row.get(Layout.Column.TOTAL));
        Assertions.assertEquals("12,50 €", c.lines.get(1).get(Layout.Column.PRICE));

        List<InvoiceContent.TotalsRow> t = c.totals;
        Assertions.assertEquals(List.of(
                new InvoiceContent.TotalsRow("Nettobetrag", "785,00 €", false),
                new InvoiceContent.TotalsRow("USt. 19 %", "144,40 €", false),
                new InvoiceContent.TotalsRow("USt. 7 %", "1,75 €", false),
                new InvoiceContent.TotalsRow("Gesamtbetrag", "931,15 €", true)), t);
    }

    @Test
    void test_valuesInEnglish() {
        InvoiceContent c = InvoiceRenderer.content(sample(), Locale.ENGLISH);
        Assertions.assertEquals("Oct 1, 2024", c.values.get("issueDate"));
        Assertions.assertEquals("€760.00", c.lines.get(0).get(Layout.Column.TOTAL));
        Assertions.assertEquals("19%", c.lines.get(0).get(Layout.Column.VAT_PERCENT));
        Assertions.assertEquals("h", c.lines.get(0).get(Layout.Column.UNIT));
        Assertions.assertEquals("Germany", c.values.get("seller.country"));
        Assertions.assertEquals("Total amount", c.totals.get(c.totals.size() - 1).caption());
    }

    @Test
    void test_priceWithMoreDecimalsAndOtherCurrency() {
        InvoiceData d = sample();
        d.lines.get(1).price = new BigDecimal("0.1250");
        d.currency = "CHF";
        InvoiceContent c = InvoiceRenderer.content(d, DE);
        String price = c.lines.get(1).get(Layout.Column.PRICE);
        Assertions.assertTrue(price.startsWith("0,125") && price.contains("CHF"), price);
    }

    @Test
    void test_countryOnlyAbroad() {
        InvoiceData d = sample();
        d.buyer.country = "AT";
        InvoiceContent c = InvoiceRenderer.content(d, DE);
        Assertions.assertTrue(c.values.get("buyer.address").endsWith("\nÖsterreich"), c.values.get("buyer.address"));
        Assertions.assertTrue(c.values.get("seller.address").endsWith("\nDeutschland"));
    }

    @Test
    void test_profiles() {
        InvoiceData d = sample();
        d.profile = InvoiceData.PROFILE_BASIC;
        Map<String, String> v = InvoiceRenderer.content(d, DE).values;
        for (String id : List.of("seller.contactName", "seller.phone", "seller.accountHolder", "seller.bic"))
            Assertions.assertNull(v.get(id), id);
        Assertions.assertNotNull(v.get("seller.iban"));

        d.profile = InvoiceData.PROFILE_MINIMUM;
        InvoiceContent minimum = InvoiceRenderer.content(d, DE);
        for (String id : List.of("seller.street", "buyer.street", "note", "paymentTerms", "dueDate", "deliveryDate",
                "seller.iban", "seller.email"))
            Assertions.assertNull(minimum.values.get(id), id);
        Assertions.assertEquals("Kelomi Testfirma GmbH", minimum.values.get("seller.address"));
        Assertions.assertEquals("T-2024-17", minimum.values.get("number"));
        Assertions.assertTrue(minimum.lines.isEmpty());

        d = sample();
        d.profile = InvoiceData.PROFILE_BASIC_WL;
        // Switching to a profile without items: the net amounts per rate follow from the items.
        InvoiceContent wl = InvoiceRenderer.content(d, DE);
        Assertions.assertEquals(2, wl.lines.size());
        Assertions.assertEquals("Leistungen zu 19 % USt.", wl.lines.get(0).get(Layout.Column.NAME));
        Assertions.assertEquals("760,00 €", wl.lines.get(0).get(Layout.Column.TOTAL));
        Assertions.assertEquals("785,00 €", wl.totals.get(0).amount());
        Assertions.assertEquals("931,15 €", wl.totals.get(wl.totals.size() - 1).amount());
    }

    /**
     * The printed totals are the totals of the XML.
     */
    @Test
    void test_totalsEqualTheXml() throws Exception {
        InvoiceData d = sample();
        InvoiceData read = InvoiceData.fromXml(d.toXml());
        InvoiceData.Totals xml = read.totals();
        InvoiceRenderer.Formats f = new InvoiceRenderer.Formats(DE, "EUR");
        List<InvoiceContent.TotalsRow> rows = InvoiceRenderer.content(read, DE).totals;
        Assertions.assertEquals(f.money(xml.net()), rows.get(0).amount());
        Assertions.assertEquals(f.money(xml.gross()), rows.get(rows.size() - 1).amount());
        BigDecimal vat = BigDecimal.ZERO;
        for (InvoiceData.VatBreakdown b : read.vatBreakdown())
            vat = vat.add(b.tax());
        Assertions.assertEquals(0, vat.compareTo(xml.vat()));
    }

    // ---------------------------------------------------------------------------------------------- generate

    /**
     * The e-invoice from a synthetic letterhead, in every level: valid (PDF/A-3 and EN 16931, no messages), the
     * chosen level, the document information of the program, the totals of the XML visible on the page.
     */
    @Test
    void test_generateInEveryLevel() throws Exception {
        InvoiceData d = sample();
        for (int items : new int[]{2, 60})
            for (PdfStandardProfile level : TemplatePreparer.LEVELS) {
                String what = items + " items, " + level;
                InvoiceData data = sample();
                for (int i = 2; i < items; ++i)
                    data.lines.add(new InvoiceData.Line("Position " + (i + 1), BigDecimal.ONE, "C62",
                            new BigDecimal("10.00"), new BigDecimal("19")));
                InvoiceRenderer.Generated g = InvoiceRenderer.generate(TestTemplates.letterhead(true),
                        TemplateEngineTest.layout(), data, DE, level, List.of());
                Assertions.assertTrue(g.render().problems().isEmpty(), what + ": " + g.render().problems());
                Assertions.assertTrue(g.isValid(), what + ": " + g.validation().messages());
                Assertions.assertTrue(g.validation().messages().isEmpty(), what + ": " + g.validation().messages());
                Assertions.assertEquals(items > 2, g.render().pageCount() > 1, what);
                try (PDDocument doc = Loader.loadPDF(g.pdf())) {
                    Assertions.assertEquals(level, PdfStandard.profile(doc), what);
                    Assertions.assertEquals(level == PdfStandardProfile.PDFA_3A, PdfTagger.isTagged(doc), what);
                    var info = doc.getDocumentInformation();
                    Assertions.assertTrue(info.getProducer().startsWith("PDFixit "), info.getProducer());
                    Assertions.assertEquals(info.getProducer(), info.getCreator());
                    Assertions.assertEquals("Rechnung T-2024-17", info.getTitle());
                    Assertions.assertEquals("Kelomi Testfirma GmbH", info.getAuthor());
                    // The attached XML is the invoice, its total is printed.
                    byte[] xml = EInvoice.extractXml(doc).orElseThrow();
                    Assertions.assertTrue(new String(xml, StandardCharsets.UTF_8).contains("T-2024-17"));
                    String gross = new InvoiceRenderer.Formats(DE, "EUR").money(InvoiceData.fromXml(xml).totals().gross());
                    String last = TemplateEngineTest.pageText(g.pdf(), doc.getNumberOfPages());
                    Assertions.assertTrue(last.contains(gross), what + ": " + gross + " in\n" + last);
                }
            }
        Assertions.assertNotNull(d);
    }

    @Test
    void test_generateStopsOnErrors() throws Exception {
        InvoiceData d = sample();
        d.number = "A-VERY-LONG-INVOICE-NUMBER-THAT-DOES-NOT-FIT-2024-0000017";
        InvoiceRenderer.Generated g = InvoiceRenderer.generate(TestTemplates.letterhead(false),
                TemplateEngineTest.layout(), d, DE, PdfStandardProfile.PDFA_3U, List.of());
        Assertions.assertNull(g.pdf());
        Assertions.assertNull(g.validation());
        Assertions.assertFalse(g.isValid());
        Assertions.assertTrue(g.render().problems().stream().anyMatch(p -> "number".equals(p.element())));

        // A long invoice on a one-page template without a choice.
        d = sample();
        for (int i = 0; i < 60; ++i)
            d.lines.add(new InvoiceData.Line("Position", BigDecimal.ONE, "C62", BigDecimal.TEN, new BigDecimal("19")));
        g = InvoiceRenderer.generate(TestTemplates.onePageLetterhead(), TemplateEngineTest.layout(), d, DE,
                PdfStandardProfile.PDFA_3U, List.of());
        Assertions.assertTrue(g.render().choiceNeeded());
        Assertions.assertNull(g.pdf());
    }

    @Test
    void test_ibanBlocks() {
        Assertions.assertEquals("DE71 3933 6053 0432 7394 89", InvoiceRenderer.iban("de71 3933 60530432739489"));
        Assertions.assertEquals("AT61 1904 3002 3457 3201", InvoiceRenderer.iban("AT611904300234573201"));
    }

    @Test
    void test_title() {
        InvoiceData d = sample();
        Assertions.assertEquals("Rechnung T-2024-17", InvoiceRenderer.title(d, DE));
        d.documentCode = InvoiceData.TYPE_CREDIT_NOTE;
        Assertions.assertEquals("Credit note T-2024-17", InvoiceRenderer.title(d, Locale.ENGLISH));
        d.documentCode = "326";
        Assertions.assertEquals("Rechnung T-2024-17", InvoiceRenderer.title(d, DE));
    }
}

package com.bw.pdfixit.invoice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The XML based editing of {@link InvoiceData}: data without a field is kept, sums are recalculated.
 */
class InvoiceDataXmlTest {

    private static final String ALLOWANCE = "<ram:SpecifiedTradeAllowanceCharge><ram:ChargeIndicator><udt:Indicator>false</udt:Indicator>"
            + "</ram:ChargeIndicator><ram:ActualAmount>10.00</ram:ActualAmount><ram:Reason>Loyalty discount</ram:Reason>"
            + "<ram:CategoryTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CategoryCode>S</ram:CategoryCode>"
            + "<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent></ram:CategoryTradeTax></ram:SpecifiedTradeAllowanceCharge>\n";

    private static final String SHIP_TO = "<ram:ShipToTradeParty><ram:Name>Lager Nordstadt</ram:Name><ram:PostalTradeAddress>"
            + "<ram:PostcodeCode>11111</ram:PostcodeCode><ram:CityName>Nordstadt</ram:CityName><ram:CountryID>DE</ram:CountryID>"
            + "</ram:PostalTradeAddress></ram:ShipToTradeParty>";

    /**
     * A valid invoice (random, made-up data) with a delivery address, an item note and a document allowance.
     * The sums in the XML don't include the allowance yet.
     */
    private static byte[] invoiceWithHiddenData(long seed) {
        String xml = new InvoiceXmlGenerator(seed).create()
                .replace("<ram:SpecifiedTradePaymentTerms>", ALLOWANCE + "<ram:SpecifiedTradePaymentTerms>")
                .replace("<ram:ApplicableHeaderTradeDelivery>", "<ram:ApplicableHeaderTradeDelivery>" + SHIP_TO)
                .replaceFirst("</ram:LineID></ram:AssociatedDocumentLineDocument>",
                        "</ram:LineID><ram:IncludedNote><ram:Content>Delivered in two parts</ram:Content></ram:IncludedNote>"
                                + "</ram:AssociatedDocumentLineDocument>");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String fieldsAsText(InvoiceData d) {
        return d.xmlFields().stream().map(f -> f.path() + "=" + f.value() + " " + f.attributes())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void hiddenDataIsKept() throws Exception {
        for (long seed = 1; seed <= 5; ++seed) {
            InvoiceData d = InvoiceData.fromXml(invoiceWithHiddenData(seed));
            d.number = "EDITED-" + seed;
            d.lines.get(0).quantity = d.lines.get(0).quantity.add(BigDecimal.ONE);
            d.seller.city = "Neustadt";
            byte[] xml = d.toXml();
            String s = new String(xml, StandardCharsets.UTF_8);
            Assertions.assertTrue(s.contains("Lager Nordstadt"), "delivery address is kept");
            Assertions.assertTrue(s.contains("Delivered in two parts"), "item note is kept");
            Assertions.assertTrue(s.contains("Loyalty discount"), "allowance is kept");
            Assertions.assertTrue(s.contains("Neustadt") && s.contains("EDITED-" + seed));

            // The sums include the allowance.
            BigDecimal lineSum = d.lines.stream().map(InvoiceData.Line::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            Assertions.assertEquals(lineSum.subtract(new BigDecimal("10.00")), d.totals().net(), "seed " + seed);

            // Valid PDF/A-3 + EN 16931 (checks the sums and the order of the elements).
            final byte[] result = xml;
            Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), result), "seed " + seed);
        }
    }

    @Test
    void unchangedInvoiceKeepsAllValues() throws Exception {
        byte[] original = new InvoiceXmlGenerator(42).create().getBytes(StandardCharsets.UTF_8);
        InvoiceData a = InvoiceData.fromXml(original);
        String before = fieldsAsText(a);
        InvoiceData b = InvoiceData.fromXml(a.toXml());
        Assertions.assertEquals(before, fieldsAsText(b));
    }

    @Test
    void sampleWithoutItemsKeepsItsSums() throws Exception {
        // Profile MINIMUM has no items, the sums are not recalculated.
        InvoiceData d = InvoiceData.fromXml(EInvoiceTest.sampleXml());
        Assertions.assertTrue(d.lines.isEmpty());
        Assertions.assertFalse(d.check().contains("The invoice has no items."));
        Assertions.assertEquals(new BigDecimal("119.00"), d.totals().gross());
        Assertions.assertEquals("urn:factur-x.eu:1p0:minimum", InvoiceData.fromXml(d.toXml()).profile);
    }

    @Test
    void minimumProfileAllowsNoItems() throws Exception {
        InvoiceData d = InvoiceData.fromXml(EInvoiceTest.sampleXml());
        Assertions.assertEquals(List.of(), d.check(), "MINIMUM needs no addresses and no due date");
        byte[] unchanged = d.toXml();
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), unchanged));

        // The schema of MINIMUM has no items: they are not written.
        d.lines.add(new InvoiceData.Line("Extra", BigDecimal.ONE, "C62", BigDecimal.TEN, new BigDecimal("19")));
        String s = new String(d.toXml(), StandardCharsets.UTF_8);
        Assertions.assertFalse(s.contains("IncludedSupplyChainTradeLineItem"), s);
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void minimumAmounts() throws Exception {
        InvoiceData d = InvoiceData.fromXml(EInvoiceTest.sampleXml());
        // Read from the totals: 100.00 net, 19.00 VAT.
        Assertions.assertEquals(1, d.vatAmounts.size());
        Assertions.assertEquals(0, new BigDecimal("100").compareTo(d.vatAmounts.get(0).net));
        Assertions.assertEquals(0, new BigDecimal("19").compareTo(d.vatAmounts.get(0).vatPercent));

        d.vatAmounts.get(0).net = new BigDecimal("250");
        InvoiceData.Totals t = d.totals();
        Assertions.assertEquals(new BigDecimal("250.00"), t.net());
        Assertions.assertEquals(new BigDecimal("47.50"), t.vat());
        Assertions.assertEquals(new BigDecimal("297.50"), t.gross());
        String s = new String(d.toXml(), StandardCharsets.UTF_8);
        Assertions.assertTrue(s.contains("<ram:DuePayableAmount>297.50</ram:DuePayableAmount>"), s);
        Assertions.assertFalse(s.contains("ApplicableTradeTax"), "MINIMUM has no VAT breakdown");
        Assertions.assertFalse(s.contains("<ram:LineTotalAmount>"), "MINIMUM has no item total");
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), s.getBytes(StandardCharsets.UTF_8)));

        d.vatAmounts.get(0).vatPercent = null;
        Assertions.assertTrue(d.check().contains("Amount 1: VAT rate is missing."), d.check().toString());
        d.vatAmounts.clear();
        Assertions.assertTrue(d.check().contains("The invoice has no amounts."), d.check().toString());
    }

    @Test
    void switchToMinimumRemovesWhatTheProfileHasNot()  {
        InvoiceData d = InvoiceDataTest.sample();
        d.note = "A note";
        d.seller.bic = "COBADEFFXXX";
        d.profile = "urn:factur-x.eu:1p0:minimum";
        Assertions.assertFalse(d.supports(InvoiceData.Field.SELLER_ADDRESS));
        Assertions.assertEquals(List.of(), d.check());
        byte[] xml = d.toXml();
        String s = new String(xml, StandardCharsets.UTF_8);
        for (String gone : List.of("LineOne", "CityName", "DefinedTradeContact", "IBANID", "BICID", "A note",
                "DueDateDateTime", "OccurrenceDateTime", "URIUniversalCommunication", "ApplicableTradeTax"))
            Assertions.assertFalse(s.contains(gone), gone + " is removed:\n" + s);
        Assertions.assertTrue(s.contains("<ram:CountryID>DE</ram:CountryID>"), "the seller keeps the country");
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), xml));

        // The values are only hidden: back to EN 16931, they are written again.
        d.profile = InvoiceData.PROFILE_EN16931;
        String back = new String(d.toXml(), StandardCharsets.UTF_8);
        Assertions.assertTrue(back.contains("CityName") && back.contains("A note") && back.contains("BICID"), back);
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), back.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void basicWlUsesTheItemsAsAmounts() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        // 760.00 at 19%, 25.00 at 7%
        d.profile = "urn:factur-x.eu:1p0:basicwl";
        Assertions.assertFalse(d.hasItems());
        InvoiceData.Totals t = d.totals();
        Assertions.assertEquals(2, d.vatAmounts.size(), "taken from the items");
        Assertions.assertEquals(new BigDecimal("931.15"), t.gross());

        // A third rate.
        d.vatAmounts.add(new InvoiceData.VatAmount(new BigDecimal("100"), BigDecimal.ZERO));
        d.vatExemptionReason = "Kleinunternehmer";
        byte[] xml = d.toXml();
        String s = new String(xml, StandardCharsets.UTF_8);
        Assertions.assertFalse(s.contains("IncludedSupplyChainTradeLineItem"), s);
        Assertions.assertEquals(List.of("S", "S", "E"), InvoiceTotals.categories(CiiXml.parse(xml).getDocumentElement()));
        Assertions.assertTrue(s.contains("<ram:LineTotalAmount>885.00</ram:LineTotalAmount>"), s);
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), xml));

        // Read again: the amounts come from the VAT breakdown.
        InvoiceData r = InvoiceData.fromXml(xml);
        Assertions.assertEquals(3, r.vatAmounts.size());
        Assertions.assertEquals(new BigDecimal("1031.15"), r.totals().gross());

        // Back to EN 16931: the items are there again.
        d.profile = InvoiceData.PROFILE_EN16931;
        Assertions.assertTrue(new String(d.toXml(), StandardCharsets.UTF_8).contains("IncludedSupplyChainTradeLineItem"));
    }

    @Test
    void itemsAreAddedAndRemoved() throws Exception {
        InvoiceData d = InvoiceData.fromXml(invoiceWithHiddenData(7));
        int count = d.lines.size();
        d.lines.add(new InvoiceData.Line("Extra", new BigDecimal("3"), "HUR", new BigDecimal("50"), new BigDecimal("19")));
        InvoiceData r = InvoiceData.fromXml(d.toXml());
        Assertions.assertEquals(count + 1, r.lines.size());
        Assertions.assertEquals("Extra", r.lines.get(count).name);
        Assertions.assertTrue(fieldsAsText(r).contains("AssociatedDocumentLineDocument/LineID=" + (count + 1)),
                "new item gets the next id:\n" + fieldsAsText(r));

        // Removing the first item removes its note.
        r.lines.remove(0);
        String s = new String(r.toXml(), StandardCharsets.UTF_8);
        Assertions.assertFalse(s.contains("Delivered in two parts"));
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void newElementsAreInsertedInSchemaOrder() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        d.dueDate = null;
        InvoiceData r = InvoiceData.fromXml(d.toXml());
        r.dueDate = LocalDate.of(2025, 1, 31);
        r.note = "Thank you";
        String s = new String(r.toXml(), StandardCharsets.UTF_8);
        Assertions.assertTrue(s.indexOf("DueDateDateTime") < s.indexOf("SpecifiedTradeSettlementHeaderMonetarySummation"), s);
        Assertions.assertTrue(s.indexOf("<ram:TypeCode>380") < s.indexOf("Thank you"), s);
        Assertions.assertTrue(s.indexOf("Thank you") < s.indexOf("SupplyChainTradeTransaction"), s);
        Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void genericFields() throws Exception {
        InvoiceData d = InvoiceData.fromXml(invoiceWithHiddenData(3));
        List<InvoiceData.XmlField> fields = d.xmlFields();

        InvoiceData.XmlField city = fields.stream()
                .filter(f -> f.path().endsWith("SellerTradeParty/PostalTradeAddress/CityName")).findFirst().orElseThrow();
        city.setValue("Generic City");
        d.readFromDocument();
        Assertions.assertEquals("Generic City", d.seller.city);

        InvoiceData.XmlField vat = fields.stream()
                .filter(f -> f.path().endsWith("SellerTradeParty/SpecifiedTaxRegistration/ID")).findFirst().orElseThrow();
        Assertions.assertEquals("schemeID=VA", vat.attributes());
        vat.setAttributes("schemeID=FC");
        d.readFromDocument();
        Assertions.assertNull(d.seller.vatId);
        Assertions.assertNotNull(d.seller.taxId);
        Assertions.assertThrows(IllegalArgumentException.class, () -> vat.setAttributes("no value"));

        // Removing the only value of the delivery address removes the empty parents, but not the delivery itself.
        for (InvoiceData.XmlField f : d.xmlFields())
            if (f.path().contains("ShipToTradeParty"))
                d.remove(f);
        String s = new String(d.toXml(), StandardCharsets.UTF_8);
        Assertions.assertFalse(s.contains("ShipToTradeParty"), s);
        Assertions.assertTrue(s.contains("ApplicableHeaderTradeDelivery"), s);

        // The structure every invoice has can't be removed.
        InvoiceData empty = new InvoiceData();
        InvoiceData.XmlField delivery = empty.xmlFields().stream()
                .filter(f -> f.path().endsWith("ApplicableHeaderTradeDelivery")).findFirst().orElseThrow();
        Assertions.assertFalse(delivery.isRemovable());
    }

    @Test
    void otherCategoriesAreKept() throws Exception {
        // Reverse charge (AE) with 0% stays AE, even if the item is changed.
        String xml = new InvoiceXmlGenerator(9).create()
                .replace("<ram:CategoryCode>S</ram:CategoryCode>", "<ram:CategoryCode>AE</ram:CategoryCode>")
                .replace("<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>",
                        "<ram:RateApplicablePercent>0</ram:RateApplicablePercent>");
        InvoiceData d = InvoiceData.fromXml(xml.getBytes(StandardCharsets.UTF_8));
        d.lines.get(0).name = "Changed";
        d.vatExemptionReason = "Reverse charge";
        Assertions.assertEquals(List.of("AE"), InvoiceTotals.categories(rootOf(d)));
        Assertions.assertTrue(new String(d.toXml(), StandardCharsets.UTF_8).contains("Reverse charge"),
                "the reason is added to the VAT breakdown");
        Assertions.assertEquals(BigDecimal.ZERO.setScale(2), d.totals().vat());
    }

    @Test
    void exemptionReasonCanBeEdited() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        d.lines.clear();
        d.lines.add(new InvoiceData.Line("Service", BigDecimal.ONE, "C62", new BigDecimal("100"), BigDecimal.ZERO));
        // The editor calculates after every key: the first letter must not stick.
        d.vatExemptionReason = "K";
        d.totals();
        d.vatExemptionReason = "Kleinunternehmer gemäß § 19 UStG";
        d.totals();
        Assertions.assertEquals("Kleinunternehmer gemäß § 19 UStG", InvoiceData.fromXml(d.toXml()).vatExemptionReason);

        // An opened invoice: the reason can be changed.
        InvoiceData r = InvoiceData.fromXml(d.toXml());
        r.vatExemptionReason = "Another reason";
        String s = new String(r.toXml(), StandardCharsets.UTF_8);
        Assertions.assertTrue(s.contains("Another reason"), s);
        Assertions.assertFalse(s.contains("Kleinunternehmer"), s);
    }

    @Test
    void endpointIdIsNotTheEmail() throws Exception {
        String xml = new String(InvoiceDataTest.sample().toXml(), StandardCharsets.UTF_8);
        String endpoint = xml.replaceFirst("<ram:URIID schemeID=\"EM\">billing@example.com</ram:URIID>",
                "<ram:URIID schemeID=\"0088\">0088:123</ram:URIID>");
        Assertions.assertNotEquals(xml, endpoint, "test data has the e-mail as URIID");

        InvoiceData d = InvoiceData.fromXml(endpoint.getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals("billing@example.com", d.seller.email, "from the contact");
        String back = new String(d.toXml(), StandardCharsets.UTF_8);
        Assertions.assertTrue(back.contains("<ram:URIID schemeID=\"0088\">0088:123</ram:URIID>"), "the endpoint is kept:\n" + back);
        Assertions.assertTrue(back.contains("billing@example.com"), back);
    }

    @Test
    void servicePeriod_isWrittenReadAndValid() throws Exception {
        for (String profile : List.of(InvoiceData.PROFILE_EN16931, "urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic",
                "urn:factur-x.eu:1p0:basicwl")) {
            InvoiceData d = InvoiceDataTest.sample();
            d.profile = profile;
            d.periodStart = java.time.LocalDate.of(2026, 1, 1);
            d.periodEnd = java.time.LocalDate.of(2026, 1, 31);
            byte[] xml = d.toXml();
            String s = new String(xml, StandardCharsets.UTF_8);
            Assertions.assertTrue(s.indexOf("20260101") < s.indexOf("20260131") && s.contains("BillingSpecifiedPeriod"), profile);
            Assertions.assertDoesNotThrow(() -> EInvoice.attachXmlStrict(EInvoiceTest.plainPdf(1), xml), profile);

            InvoiceData r = InvoiceData.fromXml(xml);
            Assertions.assertEquals(d.periodStart, r.periodStart);
            Assertions.assertEquals(d.periodEnd, r.periodEnd);

            // Cleared: the element goes away.
            r.periodStart = null;
            r.periodEnd = null;
            Assertions.assertFalse(new String(r.toXml(), StandardCharsets.UTF_8).contains("BillingSpecifiedPeriod"));
        }
    }

    @Test
    void servicePeriod_notWrittenWhereTheProfileHasNone() throws Exception {
        InvoiceData d = InvoiceData.fromXml(EInvoiceTest.sampleXml());
        Assertions.assertFalse(d.supports(InvoiceData.Field.PERIOD), "MINIMUM");
        d.periodStart = java.time.LocalDate.of(2026, 1, 1);
        Assertions.assertFalse(new String(d.toXml(), StandardCharsets.UTF_8).contains("BillingSpecifiedPeriod"));
    }

    @Test
    void hints_serviceDate() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        Assertions.assertEquals(List.of(), d.hints(), "sample has a delivery date");

        d.deliveryDate = null;
        Assertions.assertEquals(1, d.hints().size());
        Assertions.assertTrue(d.hints().get(0).startsWith("Neither a delivery date nor a service period"));
        Assertions.assertEquals(List.of(), d.check(), "a hint is no problem");

        d.periodStart = java.time.LocalDate.of(2026, 1, 1);
        Assertions.assertEquals(List.of(), d.hints(), "a period is enough");

        d.periodEnd = java.time.LocalDate.of(2026, 1, 31);
        d.deliveryDate = java.time.LocalDate.of(2026, 2, 1);
        Assertions.assertEquals(List.of("The delivery date is outside the service period."), d.hints());
        d.deliveryDate = java.time.LocalDate.of(2026, 1, 15);
        Assertions.assertEquals(List.of(), d.hints());

        // MINIMUM has neither field.
        InvoiceData minimum = InvoiceData.fromXml(EInvoiceTest.sampleXml());
        Assertions.assertEquals(List.of(), minimum.hints());
    }

    @Test
    void buyerPaymentDefaults_onlyFillEmptyValues()  {
        InvoiceData d = InvoiceDataTest.sample();
        d.paymentTerms = null;
        d.dueDate = null;
        d.issueDate = java.time.LocalDate.of(2026, 10, 29);
        d.buyer.paymentTerms = "Net 30 days";
        d.buyer.paymentDays = 30;
        Assertions.assertTrue(d.applyBuyerPaymentDefaults());
        Assertions.assertEquals("Net 30 days", d.paymentTerms);
        Assertions.assertEquals(java.time.LocalDate.of(2026, 11, 28), d.dueDate);

        // Values of the invoice are kept.
        d.buyer.paymentTerms = "Other";
        d.buyer.paymentDays = 5;
        Assertions.assertFalse(d.applyBuyerPaymentDefaults());
        Assertions.assertEquals("Net 30 days", d.paymentTerms);
        Assertions.assertEquals(java.time.LocalDate.of(2026, 11, 28), d.dueDate);
    }

    @Test
    void takePaymentConditions_fromTheInvoice() {
        InvoiceData d = InvoiceDataTest.sample();
        d.paymentTerms = " Net 14 days ";
        d.issueDate = java.time.LocalDate.of(2026, 10, 29);
        d.dueDate = java.time.LocalDate.of(2026, 11, 12);
        Party proposal = d.buyer.copy();
        d.takePaymentConditions(proposal);
        Assertions.assertEquals("Net 14 days", proposal.paymentTerms);
        Assertions.assertEquals(14, proposal.paymentDays);

        // What the invoice doesn't have stays as it is; a due date before the issue date gives no days.
        proposal.paymentTerms = "Keep";
        proposal.paymentDays = 7;
        d.paymentTerms = null;
        d.dueDate = java.time.LocalDate.of(2026, 10, 1);
        d.takePaymentConditions(proposal);
        Assertions.assertEquals("Keep", proposal.paymentTerms);
        Assertions.assertEquals(7, proposal.paymentDays);
        d.dueDate = null;
        d.takePaymentConditions(proposal);
        Assertions.assertEquals(7, proposal.paymentDays);
    }

    @Test
    void buyerPaymentDefaults_areStoredInThePresets() throws Exception {
        Party p = InvoiceDataTest.sample().buyer;
        p.paymentTerms = "Net 14 days";
        p.paymentDays = 14;
        java.nio.file.Path file = java.nio.file.Files.createTempFile("parties", ".json");
        try {
            InvoiceData.saveParties(file, List.of(p));
            Party back = InvoiceData.loadParties(file).get(0);
            Assertions.assertEquals("Net 14 days", back.paymentTerms);
            Assertions.assertEquals(14, back.paymentDays);
            Assertions.assertTrue(p.sameData(back));
            Party copy = new Party();
            copy.copyFrom(back);
            Assertions.assertEquals(14, copy.paymentDays);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void servicePeriod_endBeforeStartIsAProblem() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        d.periodStart = java.time.LocalDate.of(2026, 2, 1);
        d.periodEnd = java.time.LocalDate.of(2026, 1, 1);
        Assertions.assertTrue(d.check().contains("The end of the service period is before its start."));
    }

    @Test
    void amountsOfAProfileWithoutItems() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        d.profile = "urn:factur-x.eu:1p0:basicwl";
        d.totals();
        Assertions.assertEquals(2, d.vatAmounts.size());

        // The last row can be deleted, it isn't added again.
        d.vatAmounts.clear();
        d.totals();
        d.toXml();
        Assertions.assertEquals(0, d.vatAmounts.size());

        // Back to items, change a price: the amounts follow from the new prices.
        d.profile = InvoiceData.PROFILE_EN16931;
        d.totals();
        d.lines.get(0).price = new BigDecimal("100.00");
        d.profile = "urn:factur-x.eu:1p0:basicwl";
        d.totals();
        Assertions.assertEquals(2, d.vatAmounts.size());
        Assertions.assertEquals(0, new BigDecimal("800").compareTo(d.vatAmounts.get(0).net), d.vatAmounts.get(0).net.toString());
    }

    @Test
    void outsideOfScopeKeepsItsReason() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        d.lines.clear();
        d.lines.add(new InvoiceData.Line("Service", BigDecimal.ONE, "C62", new BigDecimal("100"), BigDecimal.ZERO));
        d.vatExemptionReason = "Outside the scope of VAT";
        d.profile = "urn:factur-x.eu:1p0:basicwl";
        String xml = new String(d.toXml(), StandardCharsets.UTF_8)
                .replace("<ram:CategoryCode>E</ram:CategoryCode>", "<ram:CategoryCode>O</ram:CategoryCode>")
                .replaceAll("<ram:RateApplicablePercent>0(\\.00)?</ram:RateApplicablePercent>", "");
        Assertions.assertTrue(xml.contains("<ram:CategoryCode>O</ram:CategoryCode>"), xml);

        InvoiceData r = InvoiceData.fromXml(xml.getBytes(StandardCharsets.UTF_8));
        byte[] back = r.toXml();
        Assertions.assertTrue(new String(back, StandardCharsets.UTF_8).contains("Outside the scope of VAT"),
                "the reason is kept");
        Assertions.assertEquals(List.of("O"), InvoiceTotals.categories(CiiXml.parse(back).getDocumentElement()));
    }

    @Test
    void allowanceWithoutCategoryAddsNoVatEntry() throws Exception {
        String plain = new InvoiceXmlGenerator(5).create();
        String allowance = "<ram:SpecifiedTradeAllowanceCharge><ram:ChargeIndicator><udt:Indicator>false</udt:Indicator>"
                + "</ram:ChargeIndicator><ram:ActualAmount>10.00</ram:ActualAmount><ram:Reason>Discount</ram:Reason>"
                + "</ram:SpecifiedTradeAllowanceCharge>\n";
        String with = plain.replace("<ram:SpecifiedTradePaymentTerms>", allowance + "<ram:SpecifiedTradePaymentTerms>");
        Assertions.assertNotEquals(plain, with);
        List<String> expected = InvoiceTotals.categories(rootOf(InvoiceData.fromXml(plain.getBytes(StandardCharsets.UTF_8))));
        List<String> actual = InvoiceTotals.categories(rootOf(InvoiceData.fromXml(with.getBytes(StandardCharsets.UTF_8))));
        Assertions.assertEquals(expected, actual);
    }

    private static org.w3c.dom.Element rootOf(InvoiceData d) throws Exception {
        return CiiXml.parse(d.toXml()).getDocumentElement();
    }

    @Test
    void replaceXml() throws Exception {
        InvoiceData d = InvoiceDataTest.sample();
        InvoiceData other = InvoiceDataTest.sample();
        other.number = "OTHER-1";
        other.buyer.name = "Other Buyer";
        other.lines.remove(1);
        d.replaceXml(other.toXml());
        Assertions.assertEquals("OTHER-1", d.number);
        Assertions.assertEquals("Other Buyer", d.buyer.name);
        Assertions.assertEquals(1, d.lines.size());
        Assertions.assertEquals("OTHER-1", InvoiceData.fromXml(d.toXml()).number);

        // An invalid file changes nothing.
        Assertions.assertThrows(java.io.IOException.class, () -> d.replaceXml("no xml".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(java.io.IOException.class,
                () -> d.replaceXml("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals("OTHER-1", d.number);
        Assertions.assertEquals(1, d.lines.size());
    }

    @Test
    void datesOfOtherFormatsAreRead() {
        LocalDate day = LocalDate.of(2024, 10, 1);
        Assertions.assertEquals(day, InvoiceData.parseDate("20241001", "102"));
        Assertions.assertEquals(day, InvoiceData.parseDate("20241001", null));
        Assertions.assertEquals(day, InvoiceData.parseDate("202410011530", "203"));
        Assertions.assertEquals(day, InvoiceData.parseDate("2024-10-01", null));
        Assertions.assertEquals(day, InvoiceData.parseDate("2024-10-01T15:30:00", null));
        // Month and week formats have no day, and a wrong code must not be guessed.
        Assertions.assertNull(InvoiceData.parseDate("202410", "610"));
        Assertions.assertNull(InvoiceData.parseDate("20241001", "610"));
        Assertions.assertNull(InvoiceData.parseDate("20241301", "102"));
        Assertions.assertNull(InvoiceData.parseDate(null, null));
    }

    @Test
    void noCiiIsRejected() {
        Assertions.assertThrows(java.io.IOException.class,
                () -> InvoiceData.fromXml("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>"
                        .getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(java.io.IOException.class, () -> InvoiceData.fromXml("no xml".getBytes(StandardCharsets.UTF_8)));
    }
}

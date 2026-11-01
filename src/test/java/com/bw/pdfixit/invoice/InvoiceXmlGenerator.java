package com.bw.pdfixit.invoice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Random;

/**
 * Creates random but consistent ZUGFeRD/Factur-X invoices (CII, profile EN 16931) for tests.<br>
 * All data (names, addresses, ids) is made up from random syllables, nothing is taken from real invoices.
 */
final class InvoiceXmlGenerator {

    private static final String[] SYLLABLES = {"ba", "ke", "lo", "mi", "nu", "ra", "so", "te", "vi", "zo", "qua", "fre", "lun"};

    private final Random random;
    private final long seed;

    InvoiceXmlGenerator(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    long seed() {
        return seed;
    }

    private String word(int minSyllables, int maxSyllables) {
        int n = minSyllables + random.nextInt(maxSyllables - minSyllables + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; ++i)
            sb.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
        return Character.toUpperCase(sb.charAt(0)) + sb.substring(1);
    }

    private String digits(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; ++i)
            sb.append(random.nextInt(10));
        return sb.toString();
    }

    private String company() {
        return word(2, 3) + " " + word(1, 2) + " Testfirma GmbH";
    }

    /**
     * Random German IBAN with correct check digits.
     */
    private String iban() {
        String bban = digits(18);
        // check digits: mod 97 of bban + "DE00" with letters as numbers (D=13, E=14)
        BigInteger n = new BigInteger(bban + "131400");
        int check = 98 - n.mod(BigInteger.valueOf(97)).intValue();
        return String.format("DE%02d%s", check, bban);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String date(LocalDate d) {
        return String.format("%04d%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    private String party(String tag, String name, String legalOrganization, boolean withTaxRegistration) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ram:").append(tag).append(">\n");
        sb.append("<ram:Name>").append(xml(name)).append("</ram:Name>\n");
        if (legalOrganization != null)
            sb.append(legalOrganization);
        sb.append("<ram:PostalTradeAddress>\n");
        sb.append("<ram:PostcodeCode>").append(digits(5)).append("</ram:PostcodeCode>\n");
        sb.append("<ram:LineOne>").append(word(2, 3)).append("weg ").append(1 + random.nextInt(120)).append("</ram:LineOne>\n");
        sb.append("<ram:CityName>").append(word(2, 3)).append("stadt</ram:CityName>\n");
        sb.append("<ram:CountryID>DE</ram:CountryID>\n");
        sb.append("</ram:PostalTradeAddress>\n");
        if (withTaxRegistration)
            sb.append("<ram:SpecifiedTaxRegistration><ram:ID schemeID=\"VA\">DE").append(digits(9)).append("</ram:ID></ram:SpecifiedTaxRegistration>\n");
        sb.append("</ram:").append(tag).append(">\n");
        return sb.toString();
    }

    /**
     * Creates a valid invoice.
     */
    String create() {
        return create(null);
    }

    /**
     * Creates an invoice.
     *
     * @param sellerLegalOrganizationScheme If not null, the seller gets a legal organization id with this schemeID.
     *                                      Use this to create invoices the validator has to reject.
     */
    String create(String sellerLegalOrganizationScheme) {
        LocalDate issue = LocalDate.of(2020 + random.nextInt(6), 1 + random.nextInt(12), 1 + random.nextInt(28));
        String number = "T-" + digits(8);

        String legalOrg = sellerLegalOrganizationScheme == null ? null
                : "<ram:SpecifiedLegalOrganization><ram:ID schemeID=\"" + sellerLegalOrganizationScheme + "\">" + digits(9)
                + "</ram:ID></ram:SpecifiedLegalOrganization>\n";

        BigDecimal net = BigDecimal.ZERO;
        StringBuilder lines = new StringBuilder();
        int lineCount = 1 + random.nextInt(4);
        for (int i = 1; i <= lineCount; ++i) {
            BigDecimal price = BigDecimal.valueOf(100 + random.nextInt(50000), 2);
            BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(10));
            BigDecimal total = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            net = net.add(total);
            lines.append("<ram:IncludedSupplyChainTradeLineItem>\n")
                    .append("<ram:AssociatedDocumentLineDocument><ram:LineID>").append(i).append("</ram:LineID></ram:AssociatedDocumentLineDocument>\n")
                    .append("<ram:SpecifiedTradeProduct><ram:Name>").append(word(2, 4)).append(" Testartikel</ram:Name></ram:SpecifiedTradeProduct>\n")
                    .append("<ram:SpecifiedLineTradeAgreement><ram:NetPriceProductTradePrice><ram:ChargeAmount>").append(price)
                    .append("</ram:ChargeAmount></ram:NetPriceProductTradePrice></ram:SpecifiedLineTradeAgreement>\n")
                    .append("<ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode=\"C62\">").append(quantity)
                    .append("</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>\n")
                    .append("<ram:SpecifiedLineTradeSettlement>\n")
                    .append("<ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CategoryCode>S</ram:CategoryCode>")
                    .append("<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent></ram:ApplicableTradeTax>\n")
                    .append("<ram:SpecifiedTradeSettlementLineMonetarySummation><ram:LineTotalAmount>").append(total)
                    .append("</ram:LineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>\n")
                    .append("</ram:SpecifiedLineTradeSettlement>\n")
                    .append("</ram:IncludedSupplyChainTradeLineItem>\n");
        }
        BigDecimal tax = net.multiply(new BigDecimal("0.19")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = net.add(tax);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\""
                + " xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\""
                + " xmlns:udt=\"urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100\">\n"
                + "<rsm:ExchangedDocumentContext><ram:GuidelineSpecifiedDocumentContextParameter>"
                + "<ram:ID>urn:cen.eu:en16931:2017</ram:ID></ram:GuidelineSpecifiedDocumentContextParameter></rsm:ExchangedDocumentContext>\n"
                + "<rsm:ExchangedDocument><ram:ID>" + number + "</ram:ID><ram:TypeCode>380</ram:TypeCode>"
                + "<ram:IssueDateTime><udt:DateTimeString format=\"102\">" + date(issue) + "</udt:DateTimeString></ram:IssueDateTime></rsm:ExchangedDocument>\n"
                + "<rsm:SupplyChainTradeTransaction>\n"
                + lines
                + "<ram:ApplicableHeaderTradeAgreement>\n"
                + party("SellerTradeParty", company(), legalOrg, true)
                + party("BuyerTradeParty", company(), null, false)
                + "</ram:ApplicableHeaderTradeAgreement>\n"
                + "<ram:ApplicableHeaderTradeDelivery><ram:ActualDeliverySupplyChainEvent><ram:OccurrenceDateTime>"
                + "<udt:DateTimeString format=\"102\">" + date(issue) + "</udt:DateTimeString>"
                + "</ram:OccurrenceDateTime></ram:ActualDeliverySupplyChainEvent></ram:ApplicableHeaderTradeDelivery>\n"
                + "<ram:ApplicableHeaderTradeSettlement>\n"
                + "<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>\n"
                + "<ram:SpecifiedTradeSettlementPaymentMeans><ram:TypeCode>58</ram:TypeCode>"
                + "<ram:PayeePartyCreditorFinancialAccount><ram:IBANID>" + iban() + "</ram:IBANID></ram:PayeePartyCreditorFinancialAccount>"
                + "</ram:SpecifiedTradeSettlementPaymentMeans>\n"
                + "<ram:ApplicableTradeTax><ram:CalculatedAmount>" + tax + "</ram:CalculatedAmount><ram:TypeCode>VAT</ram:TypeCode>"
                + "<ram:BasisAmount>" + net + "</ram:BasisAmount><ram:CategoryCode>S</ram:CategoryCode>"
                + "<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent></ram:ApplicableTradeTax>\n"
                + "<ram:SpecifiedTradePaymentTerms><ram:DueDateDateTime><udt:DateTimeString format=\"102\">"
                + date(issue.plusDays(14)) + "</udt:DateTimeString></ram:DueDateDateTime></ram:SpecifiedTradePaymentTerms>\n"
                + "<ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n"
                + "<ram:LineTotalAmount>" + net + "</ram:LineTotalAmount>\n"
                + "<ram:TaxBasisTotalAmount>" + net + "</ram:TaxBasisTotalAmount>\n"
                + "<ram:TaxTotalAmount currencyID=\"EUR\">" + tax + "</ram:TaxTotalAmount>\n"
                + "<ram:GrandTotalAmount>" + gross + "</ram:GrandTotalAmount>\n"
                + "<ram:DuePayableAmount>" + gross + "</ram:DuePayableAmount>\n"
                + "</ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n"
                + "</ram:ApplicableHeaderTradeSettlement>\n"
                + "</rsm:SupplyChainTradeTransaction>\n"
                + "</rsm:CrossIndustryInvoice>\n";
    }
}

package com.bw.pdfixit.template;

import com.bw.pdfixit.AboutInfo;
import com.bw.pdfixit.BuildVersion;
import com.bw.pdfixit.DocumentInformation;
import com.bw.pdfixit.I18n;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.ImportedFonts;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.invoice.Party;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Turns an invoice into what the engine prints ({@link InvoiceContent}), formatted in the printed language, and
 * generates the e-invoice: the visible pages from the template, the XML attached, validated.<br>
 * The printed values are the values of the XML: the totals, the VAT breakdown and the line totals are read from the
 * XML after it was calculated. Fields the profile doesn't carry ({@link InvoiceData#supports}) are not printed.
 */
public final class InvoiceRenderer {

    private InvoiceRenderer() {
    }

    /**
     * Formats in the printed language.
     */
    static final class Formats {
        final Locale language;
        final String currency;
        private final NumberFormat money;
        private final NumberFormat price;
        private final NumberFormat number;
        private final DateTimeFormatter date;

        Formats(Locale language, String currency) {
            this.language = language;
            this.currency = currency == null || currency.isBlank() ? "EUR" : currency.trim();
            NumberFormat m;
            try {
                m = NumberFormat.getCurrencyInstance(language);
                m.setCurrency(Currency.getInstance(this.currency));
            } catch (IllegalArgumentException e) {
                // Not an ISO currency Java knows: the number and the code.
                m = null;
            }
            money = m;
            if (m != null) {
                price = (NumberFormat) m.clone();
                price.setMaximumFractionDigits(4);
            } else {
                price = null;
            }
            number = NumberFormat.getNumberInstance(language);
            number.setMaximumFractionDigits(4);
            date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(language);
        }

        String money(BigDecimal v) {
            if (v == null)
                return "";
            if (money == null)
                return plain(v, 2) + " " + currency;
            return money.format(v);
        }

        /**
         * A price: two decimals, more if the price has them (up to four).
         */
        String price(BigDecimal v) {
            if (v == null)
                return "";
            if (price == null)
                return plain(v, 4) + " " + currency;
            return price.format(v);
        }

        private String plain(BigDecimal v, int maxDecimals) {
            NumberFormat f = NumberFormat.getNumberInstance(language);
            f.setMinimumFractionDigits(2);
            f.setMaximumFractionDigits(maxDecimals);
            return f.format(v);
        }

        String number(BigDecimal v) {
            return v == null ? "" : number.format(v);
        }

        String percent(BigDecimal v) {
            return v == null ? "" : I18n.t(language, "print.percent", number.format(v));
        }

        String date(LocalDate d) {
            return d == null ? "" : date.format(d);
        }

        String country(String code) {
            if (code == null || code.isBlank())
                return "";
            String name = new Locale("", code.trim().toUpperCase(Locale.ROOT)).getDisplayCountry(language);
            return name.isBlank() ? code.trim() : name;
        }

        String unit(String code) {
            if (code == null || code.isBlank())
                return "";
            // The short form for the table, else the name, else the code.
            String name = I18n.t(language, "print.unit." + code.trim());
            if (name.startsWith("["))
                name = I18n.t(language, "unit." + code.trim());
            return name.startsWith("[") ? code.trim() : name;
        }
    }

    /**
     * The IBAN in blocks of four characters.
     */
    static String iban(String iban) {
        if (iban == null)
            return null;
        String compact = iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < compact.length(); i += 4) {
            if (i > 0)
                sb.append(' ');
            sb.append(compact, i, Math.min(i + 4, compact.length()));
        }
        return sb.toString();
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * What the engine prints for an invoice.
     *
     * @param language The printed language.
     */
    public static InvoiceContent content(InvoiceData d, Locale language) {
        Formats f = new Formats(language, d.currency);
        InvoiceContent c = new InvoiceContent();
        // Brings the XML and the amounts of profiles without items (they follow a change of the profile) up to date.
        d.totals();
        // MINIMUM is the only profile without a note.
        boolean minimum = !d.supports(InvoiceData.Field.NOTE);
        put(c, "number", d.number);
        put(c, "issueDate", f.date(d.issueDate));
        if (d.supports(InvoiceData.Field.DELIVERY_DATE))
            put(c, "deliveryDate", f.date(d.deliveryDate));
        if (d.supports(InvoiceData.Field.PERIOD)) {
            put(c, "periodStart", f.date(d.periodStart));
            put(c, "periodEnd", f.date(d.periodEnd));
        }
        if (d.supports(InvoiceData.Field.PAYMENT)) {
            put(c, "dueDate", f.date(d.dueDate));
            put(c, "paymentTerms", d.paymentTerms);
        }
        put(c, "buyerReference", d.buyerReference);
        if (d.supports(InvoiceData.Field.NOTE))
            put(c, "note", d.note);
        if (!minimum)
            put(c, "vatExemptionReason", d.vatExemptionReason);

        Party s = d.seller;
        boolean sellerAddress = d.supports(InvoiceData.Field.SELLER_ADDRESS);
        put(c, "seller.name", s.name);
        if (sellerAddress) {
            put(c, "seller.street", s.street);
            put(c, "seller.zip", s.zip);
            put(c, "seller.city", s.city);
        }
        put(c, "seller.country", f.country(s.country));
        put(c, "seller.vatId", s.vatId);
        put(c, "seller.taxId", s.taxId);
        if (d.supports(InvoiceData.Field.SELLER_EMAIL))
            put(c, "seller.email", s.email);
        if (d.supports(InvoiceData.Field.SELLER_CONTACT)) {
            put(c, "seller.contactName", s.contactName);
            put(c, "seller.phone", s.phone);
        }
        if (d.supports(InvoiceData.Field.BANK_ACCOUNT))
            put(c, "seller.iban", iban(s.iban));
        if (d.supports(InvoiceData.Field.ACCOUNT_DETAILS)) {
            put(c, "seller.accountHolder", s.accountHolder);
            put(c, "seller.bic", s.bic);
        }
        Party b = d.buyer;
        boolean buyerDetails = d.supports(InvoiceData.Field.BUYER_DETAILS);
        put(c, "buyer.name", b.name);
        if (buyerDetails) {
            put(c, "buyer.street", b.street);
            put(c, "buyer.zip", b.zip);
            put(c, "buyer.city", b.city);
            put(c, "buyer.country", f.country(b.country));
            put(c, "buyer.vatId", b.vatId);
            put(c, "buyer.taxId", b.taxId);
            put(c, "buyer.email", b.email);
        }
        // The country is part of an address only if the parties are in different countries.
        boolean abroad = isSet(s.country) && isSet(b.country) && !s.country.trim().equalsIgnoreCase(b.country.trim());
        put(c, "seller.address", address(s, sellerAddress, abroad, f));
        put(c, "buyer.address", address(b, buyerDetails, abroad, f));

        if (d.hasItems()) {
            List<BigDecimal> lineTotals = d.lineTotals();
            for (int i = 0; i < d.lines.size(); ++i) {
                InvoiceData.Line line = d.lines.get(i);
                BigDecimal total = i < lineTotals.size() && lineTotals.get(i) != null ? lineTotals.get(i) : line.total();
                c.addLine(String.valueOf(i + 1), line.name, f.number(line.quantity), f.unit(line.unit),
                        f.price(line.price), f.percent(line.vatPercent), f.money(total));
            }
        } else if (!minimum) {
            // BASIC WL: no items, the net amounts per rate. MINIMUM has only the totals.
            for (InvoiceData.VatAmount v : d.vatAmounts)
                c.addLine(null, I18n.t(language, "print.vatAmount", f.percent(v.vatPercent)), null, null, null,
                        f.percent(v.vatPercent), f.money(v.net));
        }
        totals(c, d, f);
        return c;
    }

    private static void put(InvoiceContent c, String id, String value) {
        if (isSet(value))
            c.values.put(id, value.trim());
    }

    /**
     * Name, street, zip and city, country: one item per line.
     */
    private static String address(Party p, boolean details, boolean withCountry, Formats f) {
        List<String> lines = new ArrayList<>();
        if (isSet(p.name))
            lines.add(p.name.trim());
        if (details) {
            if (isSet(p.street))
                lines.add(p.street.trim());
            String place = ((isSet(p.zip) ? p.zip.trim() + " " : "") + (isSet(p.city) ? p.city.trim() : "")).trim();
            if (!place.isEmpty())
                lines.add(place);
        }
        if (withCountry && isSet(p.country))
            lines.add(f.country(p.country));
        return String.join("\n", lines);
    }

    /**
     * Net, VAT per rate, gross, amount due, all from the XML. If the items don't add up to the net amount (allowances
     * or charges of the document), their sum and the difference come first.
     */
    private static void totals(InvoiceContent c, InvoiceData d, Formats f) {
        InvoiceData.Totals t = d.totals();
        Locale l = f.language;
        if (d.hasItems()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : d.lineTotals())
                if (v != null)
                    sum = sum.add(v);
            if (t.net() != null && sum.compareTo(t.net()) != 0) {
                c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.lines"), f.money(sum), false));
                c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.allowances"),
                        f.money(t.net().subtract(sum)), false));
            }
        }
        c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.net"), f.money(t.net()), false));
        List<InvoiceData.VatBreakdown> breakdown = d.vatBreakdown();
        if (breakdown.isEmpty()) {
            c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.vat", ""), f.money(t.vat()), false));
        } else {
            for (InvoiceData.VatBreakdown v : breakdown) {
                String rate = v.rate() != null ? f.percent(v.rate()) : String.valueOf(v.category());
                c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.vat", rate), f.money(v.tax()), false));
            }
        }
        boolean dueDiffers = t.due() != null && t.gross() != null && t.due().compareTo(t.gross()) != 0;
        c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.gross"), f.money(t.gross()), !dueDiffers));
        if (dueDiffers)
            c.totals.add(new InvoiceContent.TotalsRow(I18n.t(l, "print.totals.due"), f.money(t.due()), true));
    }

    // ---------------------------------------------------------------------------------------------- generate

    /**
     * The generated e-invoice.
     *
     * @param pdf        The e-invoice, null if the engine reported errors (see {@link #render}).
     * @param render     The result of the engine: problems, page count, choice needed.
     * @param validation The validation of {@link #pdf} (PDF/A-3 and EN 16931), null if there is no PDF.
     */
    public record Generated(byte[] pdf, TemplateEngine.Result render, EInvoice.Validation validation) {
        public boolean isValid() {
            return pdf != null && validation != null && validation.valid();
        }
    }

    /**
     * Generates an e-invoice with the imported fonts of the user, see
     * {@link #generate(byte[], Layout, InvoiceData, Locale, PdfStandardProfile, List)}.
     */
    public static Generated generate(byte[] template, Layout layout, InvoiceData data, Locale language,
                                     PdfStandardProfile level) throws IOException {
        return generate(template, layout, data, language, level, ImportedFonts.fonts());
    }

    /**
     * Generates an e-invoice: the visible pages from the template ({@link TemplateEngine}), the invoice XML attached
     * with the PDF/A-3 level, the document information written (title, author, creator, producer; Mustang's are
     * replaced), and the result validated. Use {@link InvoiceData#check()} and {@link TemplateCheck} before.
     *
     * @param level {@link PdfStandardProfile#PDFA_3A}, {@link PdfStandardProfile#PDFA_3B} or
     *              {@link PdfStandardProfile#PDFA_3U}.
     */
    public static Generated generate(byte[] template, Layout layout, InvoiceData data, Locale language,
                                     PdfStandardProfile level, List<ImportedFont> fonts) throws IOException {
        InvoiceContent content = content(data, language);
        TemplateEngine.Result render = TemplateEngine.render(template, layout, content, language, level, fonts);
        if (render.hasErrors())
            return new Generated(null, render, null);
        byte[] pdf = EInvoice.attachXml(render.pdf(), data.toXml(), level);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String program = AboutInfo.NAME + " " + BuildVersion.VERSION;
            DocumentInformation.apply(doc, new DocumentInformation.Values(title(data, language), data.seller.name,
                    null, null, program, program));
            ByteArrayOutputStream os = new ByteArrayOutputStream(pdf.length + 4096);
            doc.save(os);
            pdf = os.toByteArray();
        }
        EInvoice.Validation validation = EInvoice.validate(pdf, isSet(data.number) ? data.number : "invoice");
        return new Generated(pdf, render, validation);
    }

    /**
     * The title of the document, e.g. "Rechnung T-2024-17": the document type in the printed language and the
     * number.
     */
    static String title(InvoiceData data, Locale language) {
        String type = I18n.t(language, "print.title." + data.documentCode);
        if (type.startsWith("["))
            type = I18n.t(language, "print.title");
        return isSet(data.number) ? type + " " + data.number.trim() : type;
    }
}

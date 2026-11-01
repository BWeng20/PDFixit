package com.bw.jPdfTool.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.SafeFile;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.bw.jPdfTool.invoice.CiiXml.children;
import static com.bw.jPdfTool.invoice.CiiXml.ensure;
import static com.bw.jPdfTool.invoice.CiiXml.find;
import static com.bw.jPdfTool.invoice.CiiXml.insert;
import static com.bw.jPdfTool.invoice.CiiXml.set;
import static com.bw.jPdfTool.invoice.CiiXml.text;
import static com.bw.jPdfTool.invoice.InvoiceTotals.decimal;

/**
 * Editable data of an e-invoice (ZUGFeRD/Factur-X/XRechnung, CII), as used by the invoice editor.<br>
 * The invoice XML holds all data. The fields of this class are a view on the most important values:
 * {@link #readFromDocument()} fills them from the XML, {@link #applyToDocument()} writes changed values back.
 * Everything else in the XML is kept and can be edited with {@link #xmlFields()}.
 * <p>
 * Text fields are null or blank if not set.
 */
public final class InvoiceData {

    /**
     * Guideline ids (BT-24) of the profiles the editor offers.
     */
    public static final String PROFILE_EN16931 = "urn:cen.eu:en16931:2017";
    public static final String PROFILE_XRECHNUNG = "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0";
    /**
     * Business process (BT-23) that XRechnung expects.
     */
    static final String XRECHNUNG_PROCESS = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    public static final String TYPE_INVOICE = "380";
    public static final String TYPE_CREDIT_NOTE = "381";

    /**
     * The settings directory of the application in the user home, e.g. for the seller and buyer presets.
     */
    public static Path settingsDirectory() {
        return Path.of(System.getProperty("user.home"), ".jPdfTool");
    }

    /**
     * "parties.json" in the {@link #settingsDirectory() settings directory}: the presets for sellers and buyers
     * as one list, e.g. <code>{"parties": [{"name": ..., "asSeller": true, "asBuyer": false}, ...]}</code>.
     */
    public static Path partiesFile() {
        return settingsDirectory().resolve("parties.json");
    }

    private static final String PARTIES_KEY = "parties";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_EMPTY));

    /**
     * Reads all presets.
     *
     * @return The parties, empty if the file doesn't exist.
     * @throws IOException If the file exists, but can't be read.
     */
    public static List<Party> loadParties(Path file) throws IOException {
        List<Party> parties = new ArrayList<>();
        if (!Files.exists(file))
            return parties;
        JsonNode root = MAPPER.readTree(file.toFile());
        if (root == null)
            return parties;
        parties.addAll(readList(root.get(PARTIES_KEY)));
        return parties;
    }

    private static List<Party> readList(JsonNode list) {
        List<Party> parties = new ArrayList<>();
        if (list != null && list.isArray())
            for (Party p : MAPPER.convertValue(list, new TypeReference<List<Party>>() {
            }))
                if (p != null)
                    parties.add(p);
        return parties;
    }

    /**
     * Adds the entries of an old seller or buyer list. An entry with the same name as an existing one
     * only adds the role to it.
     */
    private static void mergeOldList(List<Party> parties, List<Party> old, Role role) {
        for (Party p : old) {
            Party existing = null;
            for (Party e : parties)
                if (p.name != null && e.name != null && p.name.trim().equalsIgnoreCase(e.name.trim()))
                    existing = e;
            if (existing != null) {
                existing.setRole(role, true);
            } else {
                p.asSeller = role == Role.SELLER;
                p.asBuyer = role == Role.BUYER;
                parties.add(p);
            }
        }
    }

    /**
     * Reads all presets from {@link #partiesFile()}. Errors are logged.
     *
     * @return The parties, empty if there are none or the file can't be read.
     */
    public static List<Party> loadParties() {
        try {
            return loadParties(partiesFile());
        } catch (IOException e) {
            Log.error("Failed to read %s: %s", partiesFile(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Reads the presets offered for a role from {@link #partiesFile()}. Errors are logged.
     */
    public static List<Party> loadParties(Role role) {
        List<Party> parties = loadParties();
        parties.removeIf(p -> !p.hasRole(role));
        return parties;
    }

    /**
     * Writes all presets. The directory is created if needed.
     * Other content of an existing file is kept, lists of the old format are replaced.
     */
    public static void saveParties(Path file, List<Party> parties) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null)
            Files.createDirectories(dir);
        ObjectNode root = null;
        if (Files.exists(file)) {
            try {
                if (MAPPER.readTree(file.toFile()) instanceof ObjectNode o)
                    root = o;
            } catch (IOException e) {
                // Broken file, replaced.
            }
        }
        if (root == null)
            root = MAPPER.createObjectNode();
        root.set(PARTIES_KEY, MAPPER.valueToTree(parties));
        byte[] json = MAPPER.writeValueAsBytes(root);
        SafeFile.write(file, os -> os.write(json));
    }


    public static final class Line {
        public String name;
        public BigDecimal quantity = BigDecimal.ONE;
        /**
         * Unit code (UN/ECE Recommendation 20), e.g. "C62" (piece) or "HUR" (hour).
         */
        public String unit = "C62";
        /**
         * Net price per unit.
         */
        public BigDecimal price;
        public BigDecimal vatPercent;

        /**
         * The item in the XML, null for a new item.
         */
        Element element;
        /**
         * The price as read from the XML. The price is only written if it was changed, as a price per
         * basis quantity (e.g. per 100 pieces) can't always be converted back exactly.
         */
        BigDecimal readPrice;

        public Line() {
        }

        public Line(String name, BigDecimal quantity, String unit, BigDecimal price, BigDecimal vatPercent) {
            this.name = name;
            this.quantity = quantity;
            this.unit = unit;
            this.price = price;
            this.vatPercent = vatPercent;
        }

        /**
         * Quantity × price, rounded to cents. Null if quantity or price is missing.
         * Allowances and charges of the item are not included.
         */
        public BigDecimal total() {
            if (quantity == null || price == null)
                return null;
            return quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Sums of the invoice, as they are written to the XML.
     *
     * @param net   Sum without VAT (tax basis).
     * @param vat   VAT.
     * @param gross Total with VAT.
     * @param due   Amount due: total minus prepaid amount plus rounding.
     */
    public record Totals(BigDecimal net, BigDecimal vat, BigDecimal gross, BigDecimal due) {
    }

    /**
     * Net amount for one VAT rate. Used instead of items by the profiles MINIMUM and BASIC WL,
     * which only carry the sums of an invoice.
     */
    public static final class VatAmount {
        public BigDecimal net;
        public BigDecimal vatPercent;

        /**
         * VAT category from the XML, null for a new amount.
         */
        String category;
        /**
         * Values as read from the XML. The VAT is only calculated again if net amount or rate were changed,
         * so an unchanged invoice keeps its sums exactly.
         */
        BigDecimal readNet;
        BigDecimal readRate;
        BigDecimal readVat;

        public VatAmount() {
        }

        public VatAmount(BigDecimal net, BigDecimal vatPercent) {
            this.net = net;
            this.vatPercent = vatPercent;
        }

        private static boolean same(BigDecimal a, BigDecimal b) {
            return a != null && b != null && a.compareTo(b) == 0;
        }

        /**
         * The VAT of this amount, rounded to cents. Null if net amount or rate is missing.
         */
        public BigDecimal vat() {
            if (readVat != null && same(net, readNet) && (same(vatPercent, readRate) || Objects.equals(vatPercent, readRate)))
                return readVat;
            if (net == null || vatPercent == null)
                return null;
            return net.multiply(vatPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Guideline id (BT-24), see {@link #PROFILE_EN16931} and {@link #PROFILE_XRECHNUNG}.
     * An invoice may use another one.
     */
    public String profile = PROFILE_EN16931;
    public String documentCode = TYPE_INVOICE;
    public String number;
    public LocalDate issueDate = LocalDate.now();
    public LocalDate deliveryDate;
    /**
     * Invoicing period (BG-14), e.g. the month of a service. Either end may be missing.
     */
    public LocalDate periodStart;
    public LocalDate periodEnd;
    public LocalDate dueDate;
    /**
     * Dates of the XML that couldn't be read (caption to text), e.g. a month format without a day.
     */
    private final Map<String, String> unreadableDates = new HashMap<>();
    public String currency = "EUR";
    /**
     * Reference of the buyer (BT-10). For XRechnung this is the "Leitweg-ID".
     */
    public String buyerReference;
    public String paymentTerms;
    public String note;
    /**
     * Reason for items without VAT, e.g. "Kleinunternehmer gemäß § 19 UStG".
     * If set, new or changed items with 0% are "exempt" (category E), otherwise "zero rated" (category Z).
     */
    public String vatExemptionReason;

    public final Party seller = new Party();
    public final Party buyer = new Party();

    /**
     * The items. Not used by the profiles MINIMUM and BASIC WL, see {@link #hasItems()}.
     */
    public final List<Line> lines = new ArrayList<>();

    /**
     * Net amounts per VAT rate, used instead of the items by the profiles MINIMUM and BASIC WL.
     */
    public final List<VatAmount> vatAmounts = new ArrayList<>();

    /**
     * The invoice XML with all data.
     */
    private Document document;

    /**
     * Creates an empty invoice.
     */
    public InvoiceData() {
        this(CiiXml.newDocument());
    }

    private InvoiceData(Document document) {
        this.document = document;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return isSet(s) ? s.trim() : null;
    }

    private static String upper(String s) {
        return isSet(s) ? s.trim().toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Checks if the profile is XRechnung.
     */
    public boolean isXRechnung() {
        return profile != null && profile.toLowerCase(Locale.ROOT).contains("xrechnung");
    }

    private boolean isProfile(String part) {
        return profile != null && profile.toLowerCase(Locale.ROOT).contains(part);
    }

    /**
     * If the profile had items at the last update. Used to detect a change to a profile without items.
     */
    private boolean itemsShown = true;

    /**
     * Checks if the profile has items. MINIMUM and BASIC WL have none, they use {@link #vatAmounts}.
     */
    public boolean hasItems() {
        return !isProfile("minimum") && !isProfile("basicwl");
    }

    /**
     * Groups of fields that not every profile has.
     */
    public enum Field {
        NOTE, DELIVERY_DATE,
        /**
         * Invoicing period, the start and the end of the service.
         */
        PERIOD,
        /**
         * Due date and payment terms.
         */
        PAYMENT,
        /**
         * IBAN of the seller.
         */
        BANK_ACCOUNT,
        /**
         * Account holder and BIC.
         */
        ACCOUNT_DETAILS,
        /**
         * Street, postcode and city of the seller.
         */
        SELLER_ADDRESS, SELLER_EMAIL,
        /**
         * Contact person and phone of the seller.
         */
        SELLER_CONTACT,
        /**
         * Address, country, VAT ID and e-mail of the buyer.
         */
        BUYER_DETAILS
    }

    /**
     * Checks if the profile has these fields. Fields the profile doesn't have are not written,
     * their elements are removed from the XML (the schema of the profile doesn't allow them).
     * MINIMUM has only names, tax registrations of the seller and the sums. BASIC and BASIC WL have no contact person,
     * account holder and BIC.
     */
    public boolean supports(Field field) {
        if (isProfile("minimum"))
            return false;
        return (field != Field.SELLER_CONTACT && field != Field.ACCOUNT_DETAILS) || !isProfile("basic");
    }

    /**
     * BASIC WL has a VAT breakdown without items. MINIMUM only has the totals.
     */
    private boolean hasVatBreakdownWithoutItems() {
        return isProfile("basicwl");
    }

    /**
     * Lists what is missing or wrong for a valid invoice of the selected profile.
     *
     * @return Readable problems, empty if the data is complete.
     */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        if (!isSet(number))
            problems.add(I18n.t("check.number"));
        if (issueDate == null)
            problems.add(unreadableDates.containsKey("Issue date")
                    ? I18n.t("check.issueDate.unreadable", unreadableDates.get("Issue date"))
                    : I18n.t("check.issueDate.missing"));
        if (!isSet(currency) || !currency.trim().matches("[A-Za-z]{3}"))
            problems.add(I18n.t("check.currency"));
        if (supports(Field.PERIOD) && periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart))
            problems.add(I18n.t("check.period"));
        // MINIMUM and BASIC WL only carry the sums: no items, and less data of the parties.
        boolean itemless = !hasItems();
        checkParty(problems, "seller", seller, supports(Field.SELLER_ADDRESS), true);
        checkParty(problems, "buyer", buyer, supports(Field.BUYER_DETAILS), supports(Field.BUYER_DETAILS));
        if (!isSet(seller.vatId) && !isSet(seller.taxId))
            problems.add(I18n.t("check.sellerTax"));
        if (itemless) {
            // Without amounts, they are taken from the items when writing.
            if (vatAmounts.isEmpty() && lines.stream().noneMatch(l -> l.total() != null && l.vatPercent != null))
                problems.add(I18n.t("check.noAmounts"));
            for (int i = 0; i < vatAmounts.size(); ++i) {
                VatAmount a = vatAmounts.get(i);
                final int n = i + 1;
                if (a.net == null)
                    problems.add(I18n.t("check.amount.net", n));
                if (a.vatPercent == null)
                    problems.add(I18n.t("check.amount.vat", n));
                else if (a.vatPercent.signum() < 0)
                    problems.add(I18n.t("check.amount.vatNegative", n));
            }
        } else if (lines.isEmpty()) {
            problems.add(I18n.t("check.noItems"));
        }
        for (int i = 0; i < (itemless ? 0 : lines.size()); ++i) {
            Line l = lines.get(i);
            final int n = i + 1;
            if (!isSet(l.name))
                problems.add(I18n.t("check.item.name", n));
            if (l.quantity == null)
                problems.add(I18n.t("check.item.quantity", n));
            if (l.price == null)
                problems.add(I18n.t("check.item.price", n));
            if (l.vatPercent == null)
                problems.add(I18n.t("check.item.vat", n));
            else if (l.vatPercent.signum() < 0)
                problems.add(I18n.t("check.item.vatNegative", n));
            if (!isSet(l.unit))
                problems.add(I18n.t("check.item.unit", n));
        }
        if (supports(Field.PAYMENT) && dueDate == null && !isSet(paymentTerms))
            problems.add(I18n.t("check.dueOrTerms"));
        if (isXRechnung()) {
            if (!isSet(buyerReference))
                problems.add(I18n.t("check.xr.ref"));
            if (!isSet(seller.contactName) || !isSet(seller.phone) || !isSet(seller.email))
                problems.add(I18n.t("check.xr.contact"));
            if (!isSet(buyer.email))
                problems.add(I18n.t("check.xr.buyerEmail"));
        }
        return problems;
    }

    /**
     * Notes that don't prevent the invoice, but are worth a look.
     *
     * @return Readable hints, empty if there is nothing to note.
     */
    public List<String> hints() {
        List<String> hints = new ArrayList<>();
        if (supports(Field.PERIOD)) {
            boolean period = periodStart != null || periodEnd != null;
            if (deliveryDate == null && !period)
                hints.add(I18n.t("hint.noDate"));
            if (deliveryDate != null && ((periodStart != null && deliveryDate.isBefore(periodStart))
                    || (periodEnd != null && deliveryDate.isAfter(periodEnd))))
                hints.add(I18n.t("hint.dateOutside"));
        }
        return hints;
    }

    /**
     * Takes the default payment terms of the buyer (from a preset) over into the invoice, where the invoice has none:
     * the terms if they are empty, the due date (days after the issue date) if it is empty.
     *
     * @return True if something was changed.
     */
    public boolean applyBuyerPaymentDefaults() {
        if (!supports(Field.PAYMENT))
            return false;
        boolean changed = false;
        if (!isSet(paymentTerms) && isSet(buyer.paymentTerms)) {
            paymentTerms = buyer.paymentTerms.trim();
            changed = true;
        }
        if (dueDate == null && buyer.paymentDays != null && issueDate != null) {
            dueDate = issueDate.plusDays(buyer.paymentDays);
            changed = true;
        }
        return changed;
    }

    /**
     * The opposite of {@link #applyBuyerPaymentDefaults}: puts the payment conditions of this invoice into a buyer
     * (a proposal for the presets): the payment terms and the days from the issue date to the due date.
     * Conditions the invoice doesn't have leave the value of the buyer as it is.
     *
     * @param proposal The buyer to change, usually a copy of {@link #buyer}.
     */
    public void takePaymentConditions(Party proposal) {
        if (!supports(Field.PAYMENT))
            return;
        if (isSet(paymentTerms))
            proposal.paymentTerms = paymentTerms.trim();
        if (issueDate != null && dueDate != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(issueDate, dueDate);
            if (days >= 0 && days <= 9999)
                proposal.paymentDays = (int) days;
        }
    }

    /**
     * @param address        True if the address is required.
     * @param countryNeeded  True if the country is required even without the address.
     */
    private static void checkParty(List<String> problems, String role, Party p, boolean address, boolean countryNeeded) {
        if (!isSet(p.name))
            problems.add(I18n.t("check." + role + ".name"));
        if (address && !isSet(p.city))
            problems.add(I18n.t("check." + role + ".city"));
        if (address && !isSet(p.zip))
            problems.add(I18n.t("check." + role + ".zip"));
        if ((address || countryNeeded || isSet(p.country)) && (!isSet(p.country) || !p.country.trim().matches("[A-Za-z]{2}")))
            problems.add(I18n.t("check." + role + ".country"));
    }

    /**
     * Writes the fields into the XML and calculates the sums, see {@link #applyToDocument()}.
     */
    public Totals totals() {
        return applyToDocument();
    }

    /**
     * Creates the invoice XML (CII). Use {@link #check()} before.
     */
    public byte[] toXml() {
        applyToDocument();
        return CiiXml.serialize(document);
    }

    /**
     * Reads an invoice XML (CII). All data is kept, also the data without a field in this class.
     *
     * @throws IOException If the XML can't be read or is no CII invoice.
     */
    public static InvoiceData fromXml(byte[] xml) throws IOException {
        InvoiceData d = new InvoiceData(CiiXml.parse(xml));
        d.readFromDocument();
        return d;
    }

    /**
     * Replaces the whole content by another invoice XML (CII). This object is not changed if the XML is not valid.
     *
     * @throws IOException If the XML can't be read or is no CII invoice.
     */
    public void replaceXml(byte[] xml) throws IOException {
        Document parsed = CiiXml.parse(xml);
        InvoiceData probe = new InvoiceData(parsed);
        // Reading is the real test: it must work before anything of this object is replaced.
        probe.readFromDocument();
        document = parsed;
        readFromDocument();
    }

    // ---------------------------------------------------------------- write

    /**
     * Writes the fields into the XML. Only values that differ from the XML are written, everything else
     * keeps its form. Empty fields remove their element. Afterwards the sums are calculated.
     *
     * @return The sums.
     */
    public Totals applyToDocument() {
        Element root = document.getDocumentElement();

        Element context = ensure(root, "ExchangedDocumentContext");
        set(context, profile, "GuidelineSpecifiedDocumentContextParameter", "ID");
        if (isXRechnung() && find(context, "BusinessProcessSpecifiedDocumentContextParameter") == null)
            set(context, XRECHNUNG_PROCESS, "BusinessProcessSpecifiedDocumentContextParameter", "ID");

        Element exchanged = ensure(root, "ExchangedDocument");
        set(exchanged, number, "ID");
        set(exchanged, isSet(documentCode) ? documentCode : TYPE_INVOICE, "TypeCode");
        setDate(exchanged, issueDate, "IssueDateTime");
        writeNote(exchanged);

        Element tx = ensure(root, "SupplyChainTradeTransaction");
        boolean items = hasItems();
        // Changing from a profile with items to one without: the amounts follow from the items.
        if (!items && itemsShown) {
            vatAmounts.clear();
            amountsFromItems();
        }
        itemsShown = items;
        // MINIMUM and BASIC WL allow no items. The items stay in the list, in case the profile is changed back.
        writeLines(tx, items ? lines : List.of());

        Element agreement = ensure(tx, "ApplicableHeaderTradeAgreement");
        set(agreement, buyerReference, "BuyerReference");
        writeParty(ensure(agreement, "SellerTradeParty"), seller, true);
        writeParty(ensure(agreement, "BuyerTradeParty"), buyer, false);

        Element delivery = ensure(tx, "ApplicableHeaderTradeDelivery");
        setDate(delivery, supports(Field.DELIVERY_DATE) ? deliveryDate : null, "ActualDeliverySupplyChainEvent", "OccurrenceDateTime");

        Element settlement = ensure(tx, "ApplicableHeaderTradeSettlement");
        setDate(settlement, supports(Field.PERIOD) ? periodStart : null, "BillingSpecifiedPeriod", "StartDateTime");
        setDate(settlement, supports(Field.PERIOD) ? periodEnd : null, "BillingSpecifiedPeriod", "EndDateTime");
        set(settlement, upper(currency), "InvoiceCurrencyCode");
        writeBankAccount(settlement);
        set(settlement, supports(Field.PAYMENT) ? paymentTerms : null, "SpecifiedTradePaymentTerms", "Description");
        setDate(settlement, supports(Field.PAYMENT) ? dueDate : null, "SpecifiedTradePaymentTerms", "DueDateDateTime");

        if (items)
            return InvoiceTotals.update(root, vatExemptionReason);
        return InvoiceTotals.updateWithoutItems(root, vatAmounts, hasVatBreakdownWithoutItems(), vatExemptionReason);
    }

    /**
     * Fills the amounts from the items: the net totals per VAT rate. Used when the profile is changed to one
     * without items.
     */
    private void amountsFromItems() {
        java.util.Map<BigDecimal, BigDecimal> perRate = new java.util.LinkedHashMap<>();
        for (Line l : lines)
            if (l != null && l.vatPercent != null && l.total() != null)
                perRate.merge(l.vatPercent.stripTrailingZeros(), l.total(), BigDecimal::add);
        perRate.forEach((rate, net) -> vatAmounts.add(new VatAmount(net, rate)));
    }

    /**
     * The general note: the first note without subject code (notes with a subject code are e.g. legal notes).
     */
    private static Element generalNote(Element exchanged) {
        for (Element n : children(exchanged, "IncludedNote"))
            if (text(n, "SubjectCode") == null)
                return n;
        return null;
    }

    private void writeNote(Element exchanged) {
        String noteText = supports(Field.NOTE) ? note : null;
        Element n = generalNote(exchanged);
        if (n == null) {
            if (!isSet(noteText))
                return;
            n = insert(exchanged, CiiXml.create(document, "IncludedNote"));
        }
        set(n, noteText, "Content");
        if (children(n).isEmpty())
            exchanged.removeChild(n);
    }

    /**
     * Writes a party. Values the profile doesn't support are written as empty, which removes them.
     */
    private void writeParty(Element tp, Party p, boolean isSeller) {
        boolean address = supports(isSeller ? Field.SELLER_ADDRESS : Field.BUYER_DETAILS);
        boolean details = isSeller || supports(Field.BUYER_DETAILS);
        boolean contact = isSeller && supports(Field.SELLER_CONTACT);

        set(tp, p.name, "Name");
        set(tp, address ? p.zip : null, "PostalTradeAddress", "PostcodeCode");
        set(tp, address ? p.street : null, "PostalTradeAddress", "LineOne");
        set(tp, address ? p.city : null, "PostalTradeAddress", "CityName");
        set(tp, details ? upper(p.country) : null, "PostalTradeAddress", "CountryID");
        writeTaxRegistration(tp, "VA", details ? p.vatId : null);
        writeTaxRegistration(tp, "FC", details ? p.taxId : null);

        String email = supports(isSeller ? Field.SELLER_EMAIL : Field.BUYER_DETAILS) ? trim(p.email) : null;
        if (isEmailUri(find(tp, "URIUniversalCommunication", "URIID"))
                && !Objects.equals(text(tp, "URIUniversalCommunication", "URIID"), email)) {
            Element id = set(tp, email, "URIUniversalCommunication", "URIID");
            if (id != null)
                id.setAttribute("schemeID", "EM");
        }
        if (isSeller) {
            set(tp, contact ? p.contactName : null, "DefinedTradeContact", "PersonName");
            set(tp, contact ? p.phone : null, "DefinedTradeContact", "TelephoneUniversalCommunication", "CompleteNumber");
            if (find(tp, "DefinedTradeContact") != null)
                set(tp, contact ? email : null, "DefinedTradeContact", "EmailURIUniversalCommunication", "URIID");
        }
    }

    /**
     * Writes the tax registration with this scheme ("VA" VAT ID, "FC" tax number).
     */
    private void writeTaxRegistration(Element tp, String scheme, String value) {
        Element reg = taxRegistration(tp, scheme);
        String v = trim(value);
        if (reg == null) {
            if (v == null)
                return;
            reg = insert(tp, CiiXml.create(document, "SpecifiedTaxRegistration"));
        }
        Element id = set(reg, v, "ID");
        if (id == null)
            tp.removeChild(reg);
        else
            id.setAttribute("schemeID", scheme);
    }

    private static Element taxRegistration(Element tp, String scheme) {
        for (Element r : children(tp, "SpecifiedTaxRegistration")) {
            Element id = find(r, "ID");
            if (id != null && scheme.equals(id.getAttribute("schemeID")))
                return r;
        }
        return null;
    }

    private static String compactIban(String iban) {
        if (iban == null)
            return null;
        String c = iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return c.isEmpty() ? null : c;
    }

    /**
     * The payment means with the seller's bank account, or the first one.
     */
    private static Element paymentMeans(Element settlement) {
        List<Element> means = children(settlement, "SpecifiedTradeSettlementPaymentMeans");
        for (Element m : means)
            if (find(m, "PayeePartyCreditorFinancialAccount") != null)
                return m;
        return means.isEmpty() ? null : means.get(0);
    }

    private void writeBankAccount(Element settlement) {
        // Profiles without bank account: an empty account removes it.
        Party bank = supports(Field.BANK_ACCOUNT) ? seller : new Party();
        String iban = compactIban(bank.iban);
        Element m = paymentMeans(settlement);
        if (m == null) {
            if (iban == null)
                return;
            m = insert(settlement, CiiXml.create(document, "SpecifiedTradeSettlementPaymentMeans"));
            // SEPA credit transfer
            set(m, "58", "TypeCode");
        }
        if (!Objects.equals(compactIban(text(m, "PayeePartyCreditorFinancialAccount", "IBANID")), iban))
            set(m, iban, "PayeePartyCreditorFinancialAccount", "IBANID");
        boolean details = supports(Field.ACCOUNT_DETAILS);
        set(m, details ? bank.accountHolder : null, "PayeePartyCreditorFinancialAccount", "AccountName");
        set(m, details ? bank.bic : null, "PayeeSpecifiedCreditorFinancialInstitution", "BICID");
        // A credit transfer without account says nothing.
        if (iban == null && "58".equals(text(m, "TypeCode")) && children(m).size() == 1)
            settlement.removeChild(m);
    }

    private void writeLines(Element tx, List<Line> lines) {
        int nextId = 1;
        for (Line l : lines) {
            if (l.element != null) {
                try {
                    nextId = Math.max(nextId, Integer.parseInt(text(l.element, "AssociatedDocumentLineDocument", "LineID")) + 1);
                } catch (Exception ignored) {
                    // Not numeric
                }
            }
        }
        // Removed items disappear, the others are put in the order of the list.
        for (Element e : children(tx, "IncludedSupplyChainTradeLineItem"))
            tx.removeChild(e);
        for (Line l : lines) {
            if (l.element == null || l.element.getOwnerDocument() != document) {
                l.element = CiiXml.create(document, "IncludedSupplyChainTradeLineItem");
                insert(tx, l.element);
                set(l.element, String.valueOf(nextId++), "AssociatedDocumentLineDocument", "LineID");
            } else {
                insert(tx, l.element);
            }
            writeLine(l);
        }
    }

    private void writeLine(Line l) {
        Element e = l.element;
        set(e, l.name, "SpecifiedTradeProduct", "Name");

        String unit = upper(l.unit);
        Element quantity = setDecimal(e, l.quantity, 0, "SpecifiedLineTradeDelivery", "BilledQuantity");
        if (quantity != null && unit != null && !unit.equals(quantity.getAttribute("unitCode")))
            quantity.setAttribute("unitCode", unit);

        Element price = find(e, "SpecifiedLineTradeAgreement", "NetPriceProductTradePrice");
        Element basisQuantity = find(price, "BasisQuantity");
        if (l.price == null || l.readPrice == null || l.price.compareTo(l.readPrice) != 0 || price == null) {
            BigDecimal basis = basisQuantity == null ? null : decimal(text(basisQuantity));
            BigDecimal amount = l.price == null ? null
                    : basis == null || basis.signum() == 0 ? l.price : l.price.multiply(basis);
            setDecimal(e, amount, 2, "SpecifiedLineTradeAgreement", "NetPriceProductTradePrice", "ChargeAmount");
            l.readPrice = l.price;
        }
        if (basisQuantity != null && unit != null && basisQuantity.getParentNode() != null
                && !unit.equals(basisQuantity.getAttribute("unitCode")))
            basisQuantity.setAttribute("unitCode", unit);

        Element tax = ensure(e, "SpecifiedLineTradeSettlement", "ApplicableTradeTax");
        set(tax, "VAT", "TypeCode");
        String category = taxCategory(text(tax, "CategoryCode"), l.vatPercent);
        set(tax, category, "CategoryCode");
        if ("O".equals(category))
            set(tax, null, "RateApplicablePercent");
        else
            setDecimal(tax, l.vatPercent, 2, "RateApplicablePercent");
    }

    /**
     * VAT category of an item. A special category for 0% (e.g. reverse charge "AE") is kept.
     */
    private String taxCategory(String existing, BigDecimal rate) {
        return InvoiceTotals.category(existing, rate, vatExemptionReason);
    }

    /**
     * Writes a number, if it differs from the number in the XML.
     *
     * @param minScale Minimal number of decimal places for new values (e.g. 2 for amounts).
     * @return The element, null if removed.
     */
    private static Element setDecimal(Element parent, BigDecimal value, int minScale, String... path) {
        BigDecimal old = decimal(text(parent, path));
        if (value != null && old != null && value.compareTo(old) == 0)
            return find(parent, path);
        String v = null;
        if (value != null) {
            BigDecimal d = value.stripTrailingZeros();
            if (d.scale() < minScale)
                d = d.setScale(minScale, RoundingMode.UNNECESSARY);
            v = d.toPlainString();
        }
        return set(parent, v, path);
    }

    private static final DateTimeFormatter DATE_102 = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Writes a date in format 102 ("yyyyMMdd") into "DateTimeString" below the path.
     * A date the XML has in another format is only replaced if a new date is set.
     */
    private static void setDate(Element parent, LocalDate date, String... path) {
        String[] full = java.util.Arrays.copyOf(path, path.length + 1);
        full[path.length] = "DateTimeString";
        Element container = find(parent, path);
        String old = dateText(container);
        LocalDate oldDate = parseDate(old, dateFormat(container));
        if (Objects.equals(oldDate, date) || (date == null && old != null && oldDate == null))
            return;
        // DateTimeString and DateTime are alternatives: don't leave the old one next to the new one.
        if (container != null)
            for (Element dt : children(container, "DateTime"))
                container.removeChild(dt);
        Element e = set(parent, date == null ? null : date.format(DATE_102), full);
        if (e != null)
            e.setAttribute("format", "102");
    }

    /**
     * Text of the date element: "DateTimeString", or "DateTime" (xs:dateTime).
     */
    private static String dateText(Element container) {
        String s = text(container, "DateTimeString");
        return s != null ? s : text(container, "DateTime");
    }

    /**
     * Format code (UN/CEFACT 2379) of the date element, null if not given.
     */
    private static String dateFormat(Element container) {
        Element e = find(container, "DateTimeString");
        return e == null || !isSet(e.getAttribute("format")) ? null : e.getAttribute("format").trim();
    }

    /**
     * Reads the date at the path. EN 16931 only allows format 102 ("yyyyMMdd"), but other files
     * use further formats, e.g. 203 ("yyyyMMddHHmm") or ISO dates.
     * A date that can't be read is remembered in {@link #unreadableDates}.
     */
    private LocalDate readDate(String caption, Element parent, String... path) {
        Element container = find(parent, path);
        String s = dateText(container);
        LocalDate d = parseDate(s, dateFormat(container));
        if (d == null && s != null)
            unreadableDates.put(caption, s);
        return d;
    }

    /**
     * Parses a date of the XML.
     *
     * @param format The format code, null if unknown. Month or week formats (e.g. 610) have no day.
     * @return The date, null if it is not given or has no day or an unknown form.
     */
    static LocalDate parseDate(String s, String format) {
        if (s == null)
            return null;
        String t = s.trim();
        try {
            // 102 (yyyyMMdd), 203 (yyyyMMddHHmm), 204 (yyyyMMddHHmmss): the date comes first.
            if (t.matches("\\d{8}(\\d{4}(\\d{2})?)?") && (format == null || format.matches("102|203|204")))
                return LocalDate.parse(t.substring(0, 8), DATE_102);
            // ISO date, with or without time.
            if (t.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?"))
                return LocalDate.parse(t.substring(0, 10));
        } catch (Exception e) {
            // not a valid date
        }
        return null;
    }

    // ---------------------------------------------------------------- read

    /**
     * Fills the fields from the XML, e.g. after it was changed with {@link #xmlFields()}.
     */
    public void readFromDocument() {
        Element root = document.getDocumentElement();
        Element context = find(root, "ExchangedDocumentContext");
        profile = Objects.requireNonNullElse(text(context, "GuidelineSpecifiedDocumentContextParameter", "ID"), PROFILE_EN16931);

        Element exchanged = find(root, "ExchangedDocument");
        number = text(exchanged, "ID");
        documentCode = Objects.requireNonNullElse(text(exchanged, "TypeCode"), TYPE_INVOICE);
        unreadableDates.clear();
        issueDate = readDate("Issue date", exchanged, "IssueDateTime");
        note = text(generalNote(exchanged), "Content");

        Element tx = find(root, "SupplyChainTradeTransaction");
        Element agreement = find(tx, "ApplicableHeaderTradeAgreement");
        buyerReference = text(agreement, "BuyerReference");
        readParty(find(agreement, "SellerTradeParty"), seller);
        readParty(find(agreement, "BuyerTradeParty"), buyer);

        deliveryDate = readDate("Delivery date", tx, "ApplicableHeaderTradeDelivery", "ActualDeliverySupplyChainEvent",
                "OccurrenceDateTime");

        Element settlement = find(tx, "ApplicableHeaderTradeSettlement");
        periodStart = readDate("Period start", settlement, "BillingSpecifiedPeriod", "StartDateTime");
        periodEnd = readDate("Period end", settlement, "BillingSpecifiedPeriod", "EndDateTime");
        currency = text(settlement, "InvoiceCurrencyCode");
        Element means = paymentMeans(settlement);
        seller.iban = text(means, "PayeePartyCreditorFinancialAccount", "IBANID");
        seller.accountHolder = text(means, "PayeePartyCreditorFinancialAccount", "AccountName");
        seller.bic = text(means, "PayeeSpecifiedCreditorFinancialInstitution", "BICID");
        paymentTerms = text(settlement, "SpecifiedTradePaymentTerms", "Description");
        dueDate = readDate("Due date", settlement, "SpecifiedTradePaymentTerms", "DueDateDateTime");
        vatExemptionReason = null;
        for (Element t : children(settlement, "ApplicableTradeTax"))
            if (vatExemptionReason == null)
                vatExemptionReason = text(t, "ExemptionReason");

        lines.clear();
        for (Element e : children(tx, "IncludedSupplyChainTradeLineItem")) {
            Line l = new Line();
            l.element = e;
            l.name = text(e, "SpecifiedTradeProduct", "Name");
            Element quantity = find(e, "SpecifiedLineTradeDelivery", "BilledQuantity");
            l.quantity = decimal(text(quantity));
            l.unit = quantity != null && isSet(quantity.getAttribute("unitCode")) ? quantity.getAttribute("unitCode") : "C62";
            Element price = find(e, "SpecifiedLineTradeAgreement", "NetPriceProductTradePrice");
            BigDecimal amount = decimal(text(price, "ChargeAmount"));
            BigDecimal basis = decimal(text(price, "BasisQuantity"));
            // The price may be given for a basis quantity (e.g. per 100 pieces).
            if (amount != null && basis != null && basis.signum() > 0 && basis.compareTo(BigDecimal.ONE) != 0)
                amount = amount.divide(basis, 6, RoundingMode.HALF_UP).stripTrailingZeros();
            l.price = amount;
            l.readPrice = amount;
            Element tax = find(e, "SpecifiedLineTradeSettlement", "ApplicableTradeTax");
            l.vatPercent = decimal(text(tax, "RateApplicablePercent"));
            if (l.vatPercent == null && "O".equals(text(tax, "CategoryCode")))
                l.vatPercent = BigDecimal.ZERO;
            lines.add(l);
        }

        vatAmounts.clear();
        if (!hasItems())
            readVatAmounts(settlement);
        itemsShown = hasItems();
    }

    /**
     * Reads the amounts of an invoice without items: from the VAT breakdown (BASIC WL), or from the totals (MINIMUM).
     */
    private void readVatAmounts(Element settlement) {
        for (Element t : children(settlement, "ApplicableTradeTax")) {
            VatAmount a = new VatAmount(decimal(text(t, "BasisAmount")), decimal(text(t, "RateApplicablePercent")));
            a.category = text(t, "CategoryCode");
            if (a.vatPercent == null && "O".equals(a.category))
                a.vatPercent = BigDecimal.ZERO;
            a.readNet = a.net;
            a.readRate = a.vatPercent;
            a.readVat = decimal(text(t, "CalculatedAmount"));
            vatAmounts.add(a);
        }
        if (!vatAmounts.isEmpty())
            return;

        // MINIMUM: one amount with the total. The rate follows from net amount and VAT.
        Element sum = find(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");
        BigDecimal net = decimal(text(sum, "TaxBasisTotalAmount"));
        if (net == null)
            return;
        BigDecimal vat = null;
        String currency = text(settlement, "InvoiceCurrencyCode");
        for (Element t : children(sum, "TaxTotalAmount"))
            if (vat == null && (currency == null || !t.hasAttribute("currencyID") || currency.equals(t.getAttribute("currencyID"))))
                vat = decimal(text(t));
        VatAmount a = new VatAmount(net, null);
        if (vat != null)
            a.vatPercent = net.signum() == 0 ? BigDecimal.ZERO
                    : vat.multiply(BigDecimal.valueOf(100)).divide(net, 2, RoundingMode.HALF_UP).stripTrailingZeros();
        a.readNet = net;
        a.readRate = a.vatPercent;
        a.readVat = vat;
        vatAmounts.add(a);
    }

    /**
     * Indents the XML for display. The stored attachment is not changed.
     *
     * @return The indented XML, or the XML as it is if it can't be parsed.
     */
    public static String prettyXml(byte[] xml) {
        return CiiXml.pretty(xml);
    }

    /**
     * True if the URI element is absent or holds an e-mail address. Other values are endpoint IDs (BT-34, e.g. Peppol)
     * and must neither be read as nor overwritten by the e-mail.
     */
    private static boolean isEmailUri(Element uri) {
        if (uri == null)
            return true;
        String scheme = uri.getAttribute("schemeID");
        final String turi =  text(uri);
        return scheme.isEmpty() ? turi == null || turi.contains("@") : "EM".equals(scheme);
    }

    private static void readParty(Element tp, Party p) {
        p.name = text(tp, "Name");
        p.street = text(tp, "PostalTradeAddress", "LineOne");
        p.zip = text(tp, "PostalTradeAddress", "PostcodeCode");
        p.city = text(tp, "PostalTradeAddress", "CityName");
        p.country = text(tp, "PostalTradeAddress", "CountryID");
        p.vatId = text(taxRegistration(tp, "VA"), "ID");
        p.taxId = text(taxRegistration(tp, "FC"), "ID");
        Element uri = find(tp, "URIUniversalCommunication", "URIID");
        p.email = isEmailUri(uri) ? text(uri) : null;
        p.contactName = text(tp, "DefinedTradeContact", "PersonName");
        p.phone = text(tp, "DefinedTradeContact", "TelephoneUniversalCommunication", "CompleteNumber");
        if (p.email == null)
            p.email = text(tp, "DefinedTradeContact", "EmailURIUniversalCommunication", "URIID");
    }

    // ---------------------------------------------------------------- generic access

    /**
     * Elements that are never removed by {@link #remove(XmlField)}: every invoice has them.
     */
    private static final Set<String> STRUCTURE = Set.of(CiiXml.ROOT, "ExchangedDocumentContext", "ExchangedDocument",
            "SupplyChainTradeTransaction", "ApplicableHeaderTradeAgreement", "ApplicableHeaderTradeDelivery",
            "ApplicableHeaderTradeSettlement");

    /**
     * A value of the XML: an element without child elements.<br>
     * After changes, call {@link #readFromDocument()} to update the fields.
     */
    public static final class XmlField {
        private final Element element;
        private final String path;

        private XmlField(Element element, String path) {
            this.element = element;
            this.path = path;
        }

        /**
         * Path below the root, e.g. "SupplyChainTradeTransaction/ApplicableHeaderTradeAgreement/SellerTradeParty/Name".
         * Elements that occur more than once get their index, e.g. "IncludedSupplyChainTradeLineItem[2]".
         */
        public String path() {
            return path;
        }

        public String value() {
            return element.getTextContent();
        }

        public void setValue(String value) {
            element.setTextContent(value == null ? "" : value.trim());
        }

        /**
         * The attributes as "name=value", separated by "; ". Namespace declarations are not shown.
         */
        public String attributes() {
            StringBuilder sb = new StringBuilder();
            NamedNodeMap attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); ++i) {
                Attr a = (Attr) attrs.item(i);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI()))
                    continue;
                if (!sb.isEmpty())
                    sb.append("; ");
                sb.append(a.getName()).append('=').append(a.getValue());
            }
            return sb.toString();
        }

        /**
         * Replaces the attributes.
         *
         * @param attributes "name=value", separated by ";".
         * @throws IllegalArgumentException If the text has the wrong format.
         */
        public void setAttributes(String attributes) {
            List<String[]> parsed = new ArrayList<>();
            if (attributes != null) {
                for (String part : attributes.split(";")) {
                    if (part.isBlank())
                        continue;
                    int idx = part.indexOf('=');
                    String name = idx < 0 ? "" : part.substring(0, idx).trim();
                    if (!name.matches("[A-Za-z_][\\w.-]*"))
                        throw new IllegalArgumentException("Use name=value, separated by \";\".");
                    parsed.add(new String[]{name, part.substring(idx + 1).trim()});
                }
            }
            NamedNodeMap attrs = element.getAttributes();
            for (int i = attrs.getLength() - 1; i >= 0; --i) {
                Attr a = (Attr) attrs.item(i);
                if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI()))
                    element.removeAttributeNode(a);
            }
            for (String[] a : parsed)
                element.setAttribute(a[0], a[1]);
        }

        /**
         * Checks if the element is part of the structure every invoice has and can't be removed.
         */
        public boolean isRemovable() {
            return !STRUCTURE.contains(element.getLocalName());
        }
    }

    /**
     * All values of the XML in document order.
     */
    public List<XmlField> xmlFields() {
        List<XmlField> fields = new ArrayList<>();
        collect(document.getDocumentElement(), "", fields);
        return fields;
    }

    private static void collect(Element e, String path, List<XmlField> fields) {
        List<Element> children = children(e);
        if (children.isEmpty()) {
            if (!path.isEmpty())
                fields.add(new XmlField(e, path));
            return;
        }
        for (Element c : children) {
            String name = c.getLocalName();
            List<Element> same = children(e, name);
            String step = same.size() > 1 ? name + "[" + (same.indexOf(c) + 1) + "]" : name;
            collect(c, path.isEmpty() ? step : path + "/" + step, fields);
        }
    }

    /**
     * Removes a value from the XML, with parents that get empty.
     * After that, call {@link #readFromDocument()} to update the fields.
     */
    public void remove(XmlField field) {
        if (!field.isRemovable() || field.element.getParentNode() == null)
            return;
        Node stop = field.element.getParentNode();
        while (stop instanceof Element se && !STRUCTURE.contains(se.getLocalName()))
            stop = stop.getParentNode();
        if ( stop instanceof Element stopElement)
            CiiXml.removeAndPrune(field.element, stopElement);
    }
}

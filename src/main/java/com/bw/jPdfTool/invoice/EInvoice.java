package com.bw.jPdfTool.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.fonts.FontEmbedException;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.PdfStandard;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpSerializer;
import org.mustangproject.ZUGFeRD.IZUGFeRDExporter;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromPDFA;
import org.mustangproject.ZUGFeRD.ZUGFeRDImporter;
import org.mustangproject.validator.ESeverity;
import org.mustangproject.validator.ValidationResultItem;
import org.mustangproject.validator.ZUGFeRDValidator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeMap;

/**
 * Support for hybrid e-invoices (ZUGFeRD / Factur-X): a PDF that carries the invoice as CII XML attachment.<br>
 * Pure XML invoices (XRechnung) are not handled.
 */
public final class EInvoice {

    /**
     * Names the invoice XML is stored under (lower case): Factur-X / ZUGFeRD 2.1+, ZUGFeRD 1.0/2.0
     * and the XRECHNUNG profile of ZUGFeRD 2.1+.
     */
    private static final Set<String> XML_NAMES = Set.of("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml");

    static {
        // Mustang's PDF/A validator logs warnings via java.util.logging, Log silences them (unless debug).
        Log.init();
    }

    private EInvoice() {
    }

    /**
     * The most important invoice data. Fields are null if not available.
     */
    public record Summary(String id, String documentCode, String issueDate, String dueDate,
                          String seller, String buyer, String reference,
                          String netAmount, String taxAmount, String amount, String currency,
                          String iban, String paymentTerms, String profile) {

        @Override
        public String toString() {
            return String.format("Invoice %s from %s, %s: %s %s to %s [%s]", id, issueDate, seller, amount, currency, buyer, profile);
        }
    }

    /**
     * Calls a getter of the importer. Not all data exists in all profiles.
     *
     * @return The value or null if it is missing, blank or can't be read.
     */
    private static String optional(java.util.concurrent.Callable<String> getter) {
        try {
            String v = getter.call();
            return v == null || v.isBlank() ? null : v.trim();
        } catch (Exception e) {
            Log.debug("Failed to read invoice value: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Result of a validation.
     *
     * @param valid    True if the document is completely valid.
     * @param messages Errors and warnings, one per line.
     * @param report   The complete XML report of the validator.
     */
    public record Validation(boolean valid, List<String> messages, String report) {
    }

    /**
     * Gets the invoice XML from the attachments.
     *
     * @return The XML or empty if the document is no hybrid invoice.
     */
    public static Optional<byte[]> extractXml(PDDocument document) throws IOException {
        PDComplexFileSpecification spec = findSpec(document);
        if (spec == null)
            return Optional.empty();
        PDEmbeddedFile file = spec.getEmbeddedFile();
        if (file == null)
            return Optional.empty();
        return Optional.of(file.toByteArray());
    }

    /**
     * Checks if an invoice can be attached to the document.<br>
     * ZUGFeRD/Factur-X requires PDF/A-3, which allows file headers up to PDF 1.7. A PDF 2.0 document can't be
     * conform.
     */
    public static boolean supportsInvoice(PDDocument document) {
        return document.getDocument().getVersion() <= 1.7f;
    }

    /**
     * Checks if the document carries an invoice XML.
     */
    public static boolean hasInvoice(PDDocument document) {
        try {
            return findSpec(document) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static PDComplexFileSpecification findSpec(PDDocument document) throws IOException {
        PDDocumentNameDictionary names = document.getDocumentCatalog().getNames();
        if (names == null)
            return null;
        return findSpec(names.getEmbeddedFiles());
    }

    private static PDComplexFileSpecification findSpec(PDNameTreeNode<PDComplexFileSpecification> node) throws IOException {
        if (node == null)
            return null;
        Map<String, PDComplexFileSpecification> entries = node.getNames();
        if (entries != null) {
            for (var e : entries.entrySet()) {
                if (isInvoiceName(e.getKey()))
                    return e.getValue();
            }
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) {
                PDComplexFileSpecification spec = findSpec(kid);
                if (spec != null)
                    return spec;
            }
        }
        return null;
    }

    private static boolean isInvoiceName(String name) {
        return name != null && XML_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the main data of the invoice.
     *
     * @param xml The invoice XML (CII).
     */
    public static Summary summarize(byte[] xml) throws Exception {
        ZUGFeRDImporter importer = new ZUGFeRDImporter();
        importer.setRawXML(xml);
        String seller = null;
        try {
            var sender = importer.extractInvoice().getSender();
            if (sender != null)
                seller = sender.getName();
        } catch (Exception e) {
            Log.debug("Failed to read seller: %s", e.getMessage());
        }
        return new Summary(optional(importer::getInvoiceID), optional(importer::getDocumentCode),
                optional(importer::getIssueDate), optional(importer::getDueDate),
                seller, optional(importer::getBuyerTradePartyName), optional(importer::getReference),
                optional(importer::getTaxBasisTotalAmount), optional(importer::getTaxTotalAmount),
                optional(importer::getAmount), optional(importer::getInvoiceCurrencyCode),
                optional(importer::getIBAN), optional(importer::getPaymentTerms),
                optional(importer::getZUGFeRDProfil));
    }

    /**
     * Removes the invoice XML including the Factur-X/ZUGFeRD part of the metadata.
     *
     * @return True if something was removed.
     */
    public static boolean removeXml(PDDocument document) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentNameDictionary names = catalog.getNames();
        if (names == null || names.getEmbeddedFiles() == null)
            return false;

        PDComplexFileSpecification spec = findSpec(names.getEmbeddedFiles());
        if (spec == null)
            return false;

        removeFromTree(names.getEmbeddedFiles());

        COSArray af = catalog.getCOSObject().getCOSArray(COSName.getPDFName("AF"));
        if (af != null) {
            for (int i = af.size() - 1; i >= 0; --i) {
                COSBase b = af.getObject(i);
                if (b == spec.getCOSObject() || (b instanceof COSDictionary d && isInvoiceName(
                        new PDComplexFileSpecification(d).getFilename())))
                    af.remove(i);
            }
            if (af.size() == 0)
                catalog.getCOSObject().removeItem(COSName.getPDFName("AF"));
        }
        removeInvoiceMetadata(catalog);
        return true;
    }

    private static void removeFromTree(PDNameTreeNode<PDComplexFileSpecification> node) throws IOException {
        Map<String, PDComplexFileSpecification> entries = node.getNames();
        if (entries != null) {
            Map<String, PDComplexFileSpecification> rest = new TreeMap<>(entries);
            if (rest.keySet().removeIf(EInvoice::isInvoiceName))
                node.setNames(rest);
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids != null)
            for (PDNameTreeNode<PDComplexFileSpecification> kid : kids)
                removeFromTree(kid);
    }

    /**
     * Removes the schema that declares the invoice file from the XMP metadata. Best effort.
     */
    private static void removeInvoiceMetadata(PDDocumentCatalog catalog) {
        PDMetadata metadata = catalog.getMetadata();
        if (metadata == null)
            return;
        try {
            XMPMetadata xmp = new DomXmpParser().parse(metadata.toByteArray());
            List<XMPSchema> toRemove = new ArrayList<>();
            for (XMPSchema s : xmp.getAllSchemas()) {
                String ns = s.getNamespace();
                if (mentionsInvoiceSchema(ns))
                    toRemove.add(s);
            }
            if (!toRemove.isEmpty()) {
                toRemove.forEach(xmp::removeSchema);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                new XmpSerializer().serialize(xmp, os, true);
                metadata.importXMPMetadata(os.toByteArray());
            }
        } catch (Exception e) {
            Log.error("Failed to clean metadata: %s", e.getMessage());
        }
    }

    /**
     * Thrown if a document is no valid e-invoice.
     */
    public static class NotConformException extends IOException {
        public NotConformException(String message) {
            super(message);
        }
    }

    /**
     * Attaches an invoice XML (CII) to a PDF. An existing invoice is replaced.<br>
     * The result is only a valid ZUGFeRD/Factur-X document if the PDF is PDF/A-3 compliant. Other PDFs are
     * accepted, but {@link #validate} will complain.
     *
     * @param pdf The PDF.
     * @param xml The invoice.
     * @return The new PDF.
     */
    public static byte[] attachXml(byte[] pdf, byte[] xml) throws IOException {
        pdf = addMissingResources(pdf);
        pdf = fixPdfaIssues(pdf);
        try (IZUGFeRDExporter exporter = loadExporter(pdf)) {
            ByteArrayOutputStream os = new ByteArrayOutputStream(pdf.length + xml.length + 64 * 1024);
            exporter.setXML(xml);
            exporter.export(os);
            return os.toByteArray();
        }
    }

    /**
     * Gives pages without any resources (not even inherited ones) an empty resource dictionary.<br>
     * Mustang (2.26.0, ZUGFeRDExporterFromA3.removeCidSet) fails with a NullPointerException on such pages.
     * An empty dictionary is valid and doesn't change the appearance.
     *
     * @param pdf The PDF.
     * @return The same array if no page needs a change, otherwise the fixed PDF.
     */
    static byte[] addMissingResources(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            boolean changed = false;
            for (PDPage page : document.getPages()) {
                // getResources() includes resources inherited from the page tree.
                if (page.getResources() == null) {
                    page.setResources(new PDResources());
                    changed = true;
                }
            }
            if (!changed)
                return pdf;
            Log.debug("Added empty resources to pages without resources.");
            ByteArrayOutputStream os = new ByteArrayOutputStream(pdf.length + 1024);
            document.save(os);
            return os.toByteArray();
        }
    }

    /**
     * Makes the PDF as PDF/A-3 conform as possible, see {@link PdfStandard#fixPdfaIssues}.
     *
     * @param pdf The PDF.
     * @return The same array if nothing needs a change, otherwise the fixed PDF.
     */
    static byte[] fixPdfaIssues(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            try {
                if (PdfStandard.fixPdfaIssues(document).isEmpty())
                    return pdf;
            } catch (FontEmbedException e) {
                throw new NotConformException(e.getMessage());
            }
            ByteArrayOutputStream os = new ByteArrayOutputStream(pdf.length + 1024);
            document.save(os);
            return os.toByteArray();
        }
    }

    /**
     * Like {@link #attachXml(byte[], byte[])}, but the result is validated.
     *
     * @throws NotConformException If the result is no valid ZUGFeRD/Factur-X document (e.g. the PDF is not PDF/A-3 conform).
     */
    public static byte[] attachXmlStrict(byte[] pdf, byte[] xml) throws IOException {
        byte[] result = attachXml(pdf, xml);
        Validation v = validate(result, "result");
        if (!v.valid()) {
            // Same message may occur several times (e.g. for each page).
            // Warnings don't make a document invalid, so they only hide the real reason.
            Set<String> errors = new LinkedHashSet<>(v.messages());
            errors.removeIf(m -> m.startsWith(ESeverity.warning.toString()));
            String reason = errors.isEmpty() ? I18n.t("einvoice.seeReport") : String.join("\n", errors);
            throw new NotConformException(I18n.t("einvoice.notConform", reason));
        }
        return result;
    }

    /**
     * Attaches an invoice XML to the document, see {@link #attachXml}.
     *
     * @param document The document, it is closed by this call.
     * @param xml      The invoice.
     * @return The new document, the caller has to close it.
     */
    public static PDDocument attach(PDDocument document, byte[] xml) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(5 * 1024 * 1024);
        document.save(os);
        document.close();
        return Loader.loadPDF(attachXml(os.toByteArray(), xml));
    }

    /**
     * Checks if the metadata declares the invoice (Factur-X/ZUGFeRD extension schema).
     */
    public static boolean hasInvoiceMetadata(PDDocument document) throws IOException {
        PDMetadata metadata = document.getDocumentCatalog().getMetadata();
        if (metadata == null)
            return false;
        String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
        return mentionsInvoiceSchema(xmp);
    }

    /**
     * Parts of the namespaces of the invoice metadata: Factur-X / ZUGFeRD 2.1+ ("urn:factur-x:pdfa:..."),
     * ZUGFeRD 2.0 ("urn:zugferd:pdfa:...") and ZUGFeRD 1.0 ("urn:ferd:pdfa:...").
     */
    private static final List<String> INVOICE_SCHEMA_MARKERS = List.of("factur-x", "zugferd", "urn:ferd:");

    /**
     * Checks if the text (a namespace or the complete XMP) refers to an invoice metadata schema.
     */
    private static boolean mentionsInvoiceSchema(String text) {
        return text != null && INVOICE_SCHEMA_MARKERS.stream().anyMatch(text::contains);
    }

    /**
     * Checks if the catalog lists associated files (/AF), required by PDF/A-3 for the invoice.
     */
    private static boolean hasAssociatedFiles(PDDocument document) {
        COSArray af = document.getDocumentCatalog().getCOSObject().getCOSArray(COSName.getPDFName("AF"));
        return af != null && af.size() > 0;
    }

    /**
     * Repairs a document that has an invoice XML, but no matching metadata.
     * This happens if a document without a ZUGFeRD metadata was merged with an invoice.
     *
     * @param document The document, it is closed by this call if a repair was needed.
     * @return The document to use further.
     */
    public static PDDocument repair(PDDocument document) throws IOException {
        if (hasInvoice(document) && !(hasInvoiceMetadata(document) && hasAssociatedFiles(document))) {
            Log.info("Invoice metadata or association is missing, recreating it.");
            byte[] xml = extractXml(document).orElseThrow();
            // The old attachment lacks the association to the document, don't let it conflict with the new one.
            removeXml(document);
            return attach(document, xml);
        }
        return document;
    }

    private static IZUGFeRDExporter loadExporter(byte[] pdf) throws IOException {
        try {
            // load() returns the exporter that fits to the PDF/A version, only that one can be used further.
            return new ZUGFeRDExporterFromPDFA().ignorePDFAErrors().load(pdf);
        } catch (IllegalArgumentException e) {
            // Not a PDF/A at all. Attach anyway, validation will report the missing conformance.
            return new ZUGFeRDExporterFromA3().ignorePDFAErrors().load(pdf);
        }
    }

    /**
     * Validates a PDF with its invoice (PDF/A-3 and EN 16931 rules).
     *
     * @param pdf The complete PDF.
     * @param name Name for the report.
     */
    public static Validation validate(byte[] pdf, String name) {
        ZUGFeRDValidator validator = new ZUGFeRDValidator();
        String report = validator.validate(new ByteArrayInputStream(pdf), name);
        List<String> messages = new ArrayList<>();
        for (ValidationResultItem item : validator.getContext().getResults()) {
            ESeverity severity = item.getSeverity();
            if (severity != ESeverity.notice)
                messages.add(String.format("%s: %s%s", severity, cleanMessage(item.getMessage()),
                        item.getLocation() == null ? "" : " at " + readableLocation(item.getLocation())));
        }
        // The PDF/A check is only part of the report: wasCompletelyValid() and the results don't cover it.
        Matcher failed = PDFA_FAILURE.matcher(report);
        while (failed.find())
            messages.add("PDF/A: " + failed.group(1));
        Matcher errors = REPORT_ERROR.matcher(report);
        while (errors.find())
            messages.add("ERROR: " + errors.group(1).trim());

        boolean valid = validator.wasCompletelyValid()
                && !report.contains("status=\"invalid\"")
                && !report.contains("isCompliant=false");
        return new Validation(valid, messages, report);
    }

    private static final Pattern LOCATION_STEP = Pattern.compile("\\*:([A-Za-z]+)\\[namespace-uri\\(\\)='[^']*'\\]\\[(\\d+)\\]");

    /**
     * Collapses white space and removes the name of the used rule file.
     */
    private static String cleanMessage(String message) {
        if (message == null)
            return "";
        String m = message.replaceAll("\\s+", " ").replace(" (still status warning)", "").trim();
        int idx = m.indexOf(" from /");
        return idx < 0 ? m : m.substring(0, idx).trim();
    }

    /**
     * Converts the verbose XPath of the validator to a short one like "SellerTradeParty[1]/ID[1]".
     */
    private static String readableLocation(String xpath) {
        Matcher m = LOCATION_STEP.matcher(xpath);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            sb.append(sb.isEmpty() ? "" : "/").append(m.group(1)).append('[').append(m.group(2)).append(']');
        return sb.isEmpty() ? xpath : sb.toString();
    }

    private static final Pattern PDFA_FAILURE = Pattern.compile("status=failed, message=(.*?), location=");
    private static final Pattern REPORT_ERROR = Pattern.compile("<(?:error|exception)[^>]*>([^<]*)</(?:error|exception)>");
}

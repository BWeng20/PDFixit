package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoice;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.type.AbstractField;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpSerializer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Writes the document information (title, author, ...) of a document.<br>
 * The values are stored in the document information dictionary and, if the document has XMP metadata, also in the
 * matching XMP properties. PDF/A (and so ZUGFeRD/Factur-X) requires both to be equal, and the XMP metadata must
 * not be removed: it declares the PDF/A conformance and the e-invoice.
 */
public final class DocumentInformation {

    /**
     * The values to write. Blank values remove the entry.
     */
    public record Values(String title, String author, String subject, String keywords,
                         String creator, String producer) {
    }

    private DocumentInformation() {
    }

    /**
     * Writes the values. Creation and modification date are set to now.
     *
     * @param document The document.
     * @param values   The values.
     */
    public static void apply(PDDocument document, Values values) {
        Calendar now = now();
        apply(document, values, now, now);
    }

    /**
     * Copies the document information (title, author, ..., producer and creation date) to another document, e.g. one
     * that was rewritten by a library that replaced them. The modification date is set to now.
     *
     * @param from The document to read the information from.
     * @param to   The document to write to.
     * @return False if the source has no document information, nothing is changed then.
     */
    public static boolean copy(PDDocument from, PDDocument to) {
        PDDocumentInformation info = from.getDocumentInformation();
        if (info == null)
            return false;
        Calendar created = info.getCreationDate();
        Calendar now = now();
        if (created != null)
            created.set(Calendar.MILLISECOND, 0);
        apply(to, new Values(info.getTitle(), info.getAuthor(), info.getSubject(), info.getKeywords(),
                info.getCreator(), info.getProducer()), created == null ? now : created, now);
        return true;
    }

    private static Calendar now() {
        Calendar now = Calendar.getInstance();
        // The info dictionary stores seconds only, XMP must be equal.
        now.set(Calendar.MILLISECOND, 0);
        return now;
    }

    private static void apply(PDDocument document, Values values, Calendar created, Calendar now) {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setCreationDate(created);
        info.setModificationDate(now);
        info.setTitle(blankToNull(values.title()));
        info.setAuthor(blankToNull(values.author()));
        info.setSubject(blankToNull(values.subject()));
        info.setKeywords(blankToNull(values.keywords()));
        info.setCreator(blankToNull(values.creator()));
        info.setProducer(blankToNull(values.producer()));
        document.setDocumentInformation(info);

        PDMetadata metadata = document.getDocumentCatalog().getMetadata();
        if (metadata == null)
            return;
        try {
            DomXmpParser parser = new DomXmpParser();
            parser.setStrictParsing(false);
            XMPMetadata xmp = parser.parse(metadata.toByteArray());
            updateXmp(xmp, values, created, now);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            new XmpSerializer().serialize(xmp, os, true);
            metadata.importXMPMetadata(os.toByteArray());
        } catch (Exception e) {
            if (EInvoice.hasInvoice(document) || isPdfA(metadata)) {
                // Without the metadata the document is no PDF/A (and no e-invoice) anymore: keep it.
                Log.error("Failed to update the XMP metadata, it may differ from the document information: %s",
                        e.getMessage());
            } else {
                // Not readable and not needed: remove it, so it can't contradict the new values.
                Log.debug("Removing unreadable XMP metadata: %s", e.getMessage());
                document.getDocumentCatalog().setMetadata(null);
            }
        }
        if (PdfStandard.pdfaPart(document) == 4)
            removeInfo(document);
    }

    /**
     * PDF/A-4 doesn't allow a document information dictionary (the XMP metadata carries the values).
     */
    static void removeInfo(PDDocument document) {
        document.getDocument().getTrailer().removeItem(org.apache.pdfbox.cos.COSName.INFO);
    }

    private static boolean isPdfA(PDMetadata metadata) {
        try {
            return new String(metadata.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains("pdfaid:");
        } catch (Exception e) {
            return false;
        }
    }

    private static void updateXmp(XMPMetadata xmp, Values values, Calendar created, Calendar now) {
        DublinCoreSchema dc = xmp.getDublinCoreSchema();
        if (dc == null)
            dc = xmp.createAndAddDublinCoreSchema();
        remove(dc, DublinCoreSchema.TITLE);
        remove(dc, DublinCoreSchema.CREATOR);
        remove(dc, DublinCoreSchema.DESCRIPTION);
        if (blankToNull(values.title()) != null)
            dc.setTitle(values.title().trim());
        if (blankToNull(values.author()) != null)
            // PDF/A: dc:creator must contain exactly one entry, the Author of the info dictionary.
            dc.addCreator(values.author().trim());
        if (blankToNull(values.subject()) != null)
            dc.setDescription(values.subject().trim());

        AdobePDFSchema pdf = xmp.getAdobePDFSchema();
        if (pdf == null)
            pdf = xmp.createAndAddAdobePDFSchema();
        remove(pdf, AdobePDFSchema.KEYWORDS);
        remove(pdf, AdobePDFSchema.PRODUCER);
        if (blankToNull(values.keywords()) != null)
            pdf.setKeywords(values.keywords().trim());
        if (blankToNull(values.producer()) != null)
            pdf.setProducer(values.producer().trim());

        XMPBasicSchema basic = xmp.getXMPBasicSchema();
        if (basic == null)
            basic = xmp.createAndAddXMPBasicSchema();
        remove(basic, XMPBasicSchema.CREATORTOOL);
        if (blankToNull(values.creator()) != null)
            basic.setCreatorTool(values.creator().trim());
        basic.setCreateDate(created);
        basic.setModifyDate(now);
        basic.setMetadataDate(now);
    }

    /**
     * Removes all properties with the local name.
     */
    private static void remove(XMPSchema schema, String name) {
        List<AbstractField> toRemove = new ArrayList<>();
        for (AbstractField f : schema.getAllProperties()) {
            if (name.equals(f.getPropertyName()))
                toRemove.add(f);
        }
        toRemove.forEach(schema::removeProperty);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

package com.bw.pdfixit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.mustangproject.ZUGFeRD.PDFAConformanceLevel;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3;
import org.apache.xmpbox.XMPMetadata;

import java.io.IOException;

/**
 * Uses the PDF/A-3 preparation of Mustang (XMP identification, document information, sRGB output intent,
 * CIDSet removal, ...) on an open document, without attaching an invoice.<br>
 * Mustang offers this only as part of its export, so the protected parts are used.
 */
final class MustangPdfaConverter extends ZUGFeRDExporterFromA3 {

    private MustangPdfaConverter(PDDocument document) {
        this.doc = document;
        // Keep what the document already says (producer, creator, dates, title), only fill the gaps.
        setOverwrite(false);
        setAttachZUGFeRDHeaders(false);
        setConformanceLevel(PDFAConformanceLevel.BASIC);
        var info = document.getDocumentInformation();
        if (info.getProducer() != null && !info.getProducer().isBlank())
            setProducer(info.getProducer());
        else
            setProducer("PDFixit");
        if (info.getCreator() != null && !info.getCreator().isBlank())
            setCreator(info.getCreator());
        else
            setCreator("PDFixit");
        setCreatorTool("PDFixit");
    }

    /**
     * Without an invoice no extension schema is needed. Mustang would write an empty one then, which veraPDF
     * rejects (ISO 19005-3 6.6.2.3.3).
     */
    @Override
    protected void addXMP(XMPMetadata metadata) {
    }

    /**
     * Declares PDF/A-3b and makes the metadata consistent. The document is changed in place.
     */
    static void convert(PDDocument document) throws IOException {
        // Mustang fails with a NullPointerException on pages without any resources (even inherited ones).
        for (PDPage page : document.getPages())
            if (page.getResources() == null)
                page.setResources(new PDResources());
        // Mustang may replace values of the document information, so they are restored afterwards.
        final var saved = new org.apache.pdfbox.cos.COSDictionary(document.getDocumentInformation().getCOSObject());
        try {
            new MustangPdfaConverter(document).prepareDocument();
        } catch (IOException e) {
            // Unreadable XMP: replace it.
            Log.debug("Unreadable XMP metadata is replaced: %s", e.getMessage());
            document.getDocumentCatalog().setMetadata(null);
            new MustangPdfaConverter(document).prepareDocument();
        }
        restoreInformation(document, saved);
    }

    /**
     * Writes the document information the document had before (including custom entries) to the info dictionary
     * and the XMP metadata.
     */
    private static void restoreInformation(PDDocument document, org.apache.pdfbox.cos.COSDictionary saved) {
        document.setDocumentInformation(new org.apache.pdfbox.pdmodel.PDDocumentInformation(saved));
        DocumentInformation.copy(document, document);
        // copy() writes the standard entries only.
        var info = document.getDocumentInformation().getCOSObject();
        for (var entry : saved.entrySet())
            if (!info.containsKey(entry.getKey()) && !entry.getKey().getName().equals("ModDate"))
                info.setItem(entry.getKey(), entry.getValue());
    }
}

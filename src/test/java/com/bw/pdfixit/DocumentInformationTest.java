package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoiceTest;
import com.bw.pdfixit.invoice.EInvoice;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.xml.DomXmpParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

class DocumentInformationTest {

    private static final DocumentInformation.Values VALUES = new DocumentInformation.Values(
            "My Title", "Some Author", "The Subject", "a, b", "Creator App", "Producer App");

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        doc.save(os);
        return os.toByteArray();
    }

    private static XMPMetadata xmp(PDDocument doc) throws Exception {
        return new DomXmpParser().parse(doc.getDocumentCatalog().getMetadata().toByteArray());
    }

    /**
     * The bug: writing the document information removed the XMP metadata, saved e-invoices were invalid.
     */
    @Test
    void invoiceStaysValid() throws Exception {
        byte[] pdf = EInvoiceTest.invoicePdf(1);
        Assertions.assertTrue(EInvoice.validate(pdf, "before").valid(), "test needs a valid invoice");

        byte[] result;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DocumentInformation.apply(doc, VALUES);
            result = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertNotNull(doc.getDocumentCatalog().getMetadata(), "XMP metadata must be kept");
            Assertions.assertTrue(EInvoice.hasInvoiceMetadata(doc));
        }
        EInvoice.Validation v = EInvoice.validate(result, "after");
        Assertions.assertTrue(v.valid(), String.join("\n", v.messages()));
    }

    @Test
    void infoAndXmpAreEqual() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.invoicePdf(1))) {
            DocumentInformation.apply(doc, VALUES);
            PDDocumentInformation info = doc.getDocumentInformation();
            Assertions.assertEquals("My Title", info.getTitle());
            Assertions.assertEquals("Some Author", info.getAuthor());
            Assertions.assertEquals("The Subject", info.getSubject());
            Assertions.assertEquals("a, b", info.getKeywords());
            Assertions.assertEquals("Creator App", info.getCreator());
            Assertions.assertEquals("Producer App", info.getProducer());

            XMPMetadata xmp = xmp(doc);
            Assertions.assertEquals("My Title", xmp.getDublinCoreSchema().getTitle());
            Assertions.assertEquals(java.util.List.of("Some Author"), xmp.getDublinCoreSchema().getCreators());
            Assertions.assertEquals("The Subject", xmp.getDublinCoreSchema().getDescription());
            Assertions.assertEquals("a, b", xmp.getAdobePDFSchema().getKeywords());
            Assertions.assertEquals("Producer App", xmp.getAdobePDFSchema().getProducer());
            Assertions.assertEquals("Creator App", xmp.getXMPBasicSchema().getCreatorTool());
            Assertions.assertEquals(info.getCreationDate().getTimeInMillis(),
                    xmp.getXMPBasicSchema().getCreateDate().getTimeInMillis());
            Assertions.assertEquals(info.getModificationDate().getTimeInMillis(),
                    xmp.getXMPBasicSchema().getModifyDate().getTimeInMillis());
        }
    }

    @Test
    void blankValuesRemoveEntries() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.invoicePdf(1))) {
            DocumentInformation.apply(doc, VALUES);
            DocumentInformation.apply(doc, new DocumentInformation.Values("", " ", null, "", "", ""));
            PDDocumentInformation info = doc.getDocumentInformation();
            Assertions.assertNull(info.getTitle());
            Assertions.assertNull(info.getAuthor());
            Assertions.assertNull(info.getProducer());

            XMPMetadata xmp = xmp(doc);
            Assertions.assertNull(xmp.getDublinCoreSchema().getTitle());
            Assertions.assertTrue(xmp.getDublinCoreSchema().getCreators() == null
                    || xmp.getDublinCoreSchema().getCreators().isEmpty());
            // A schema without properties may be dropped completely.
            Assertions.assertTrue(xmp.getAdobePDFSchema() == null || xmp.getAdobePDFSchema().getProducer() == null);
            Assertions.assertNull(xmp.getXMPBasicSchema().getCreatorTool());
        }
    }

    @Test
    void documentWithoutMetadata_getsNone() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.plainPdf(1))) {
            Assertions.assertNull(doc.getDocumentCatalog().getMetadata());
            DocumentInformation.apply(doc, VALUES);
            Assertions.assertNull(doc.getDocumentCatalog().getMetadata());
            Assertions.assertEquals("My Title", doc.getDocumentInformation().getTitle());
        }
    }
}

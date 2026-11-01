package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoiceTest;
import com.bw.pdfixit.invoice.EInvoice;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Attaching an invoice to real PDF/A-1b, 2b and 3b documents.
 */
class PdfaVersionsTest {

    /**
     * Creates a minimal PDF/A document of the given part, without text (no fonts needed).
     */
    static byte[] pdfa(int part) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);

            XMPMetadata xmp = XMPMetadata.createXMPMetadata();
            PDFAIdentificationSchema id = xmp.createAndAddPDFAIdentificationSchema();
            id.setPart(part);
            id.setConformance("B");
            ByteArrayOutputStream xmpBytes = new ByteArrayOutputStream();
            new XmpSerializer().serialize(xmp, xmpBytes, true);
            PDMetadata metadata = new PDMetadata(doc);
            metadata.importXMPMetadata(xmpBytes.toByteArray());
            doc.getDocumentCatalog().setMetadata(metadata);

            ICC_Profile srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
            PDOutputIntent intent = new PDOutputIntent(doc, new ByteArrayInputStream(srgb.getData()));
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            doc.getDocumentCatalog().addOutputIntent(intent);

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            // PDF/A-1 forbids xref streams
            doc.save(os, CompressParameters.NO_COMPRESSION);
            return os.toByteArray();
        }
    }

    private static int part(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), "UTF-8");
            var m = java.util.regex.Pattern.compile("pdfaid:part>\\s*(\\d)|pdfaid:part=\"(\\d)\"").matcher(xmp);
            Assertions.assertTrue(m.find(), xmp);
            return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        }
    }

    @Test
    void test_attachToPdfa1() throws Exception {
        attachToPdfa(1);
    }

    @Test
    void test_attachToPdfa2() throws Exception {
        attachToPdfa(2);
    }

    @Test
    void test_attachToPdfa3() throws Exception {
        attachToPdfa(3);
    }

    private void attachToPdfa(int part) throws Exception {
        byte[] source = pdfa(part);
        System.out.println("PDFA part " + part + " source valid as PDF/A: "
                + !EInvoice.validate(source, "src.pdf").report().contains("isCompliant=false"));

        byte[] result = EInvoice.attachXmlStrict(source, EInvoiceTest.sampleXml());

        EInvoice.Validation v = EInvoice.validate(result, "result.pdf");
        Assertions.assertTrue(v.valid(), v.messages() + "\n" + v.report());
        Assertions.assertEquals(3, part(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertTrue(EInvoice.hasInvoice(doc));
            Assertions.assertTrue(EInvoice.hasInvoiceMetadata(doc));
        }
    }
}

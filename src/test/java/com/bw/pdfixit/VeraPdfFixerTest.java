package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoiceTest;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.model.RenderQueue;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * The metadata fixer of veraPDF, run on request.
 */
class VeraPdfFixerTest {

    /**
     * A PDF/A-1b document whose document information differs from the metadata.
     */
    private static byte[] mismatchingPdfa1() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.plainPdf(1))) {
            PdfStandard.convertTo(doc, PdfStandardProfile.PDFA_1B);
            doc.getDocumentInformation().setTitle("Title only in the info dictionary");
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os, CompressParameters.NO_COMPRESSION);
            return os.toByteArray();
        }
    }

    @Test
    void test_fixerOnConformDocument() throws Exception {
        byte[] pdf = PdfaVersionsTest.pdfa(2);
        PdfaValidator.MetadataFix fix = PdfaValidator.fixMetadata(pdf);
        Assertions.assertNull(fix.pdf(), "nothing to fix: " + fix.status());
        Assertions.assertTrue(fix.fixes().isEmpty());
    }

    @Test
    void test_fixerSkipsUndeclaredDocument() throws Exception {
        PdfaValidator.MetadataFix fix = PdfaValidator.fixMetadata(EInvoiceTest.plainPdf(1));
        Assertions.assertNull(fix.pdf());
    }

    @Test
    void test_fixerRepairsMetadataMismatch() throws Exception {
        byte[] pdf = mismatchingPdfa1();
        Assertions.assertFalse(PdfaValidator.validate(pdf).isConform(), "the document is broken");
        PdfaValidator.MetadataFix fix = PdfaValidator.fixMetadata(pdf);
        System.out.println("veraPDF metadata fixer: " + fix.status() + " " + fix.fixes());
        Assertions.assertNotNull(fix.pdf(), fix.status());
        Assertions.assertFalse(fix.fixes().isEmpty());
        Assertions.assertTrue(PdfaValidator.validate(fix.pdf()).isConform(), PdfaValidator.validate(fix.pdf()).toString());
    }

    @Test
    void test_proxyFixesOnRequest() throws Exception {
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(Loader.loadPDF(mismatchingPdfa1()), new MergeOptions());
            Assertions.assertFalse(proxy.validatePdfa().isConform());
            PdfaValidator.MetadataFix fix = proxy.fixWithVeraPdf();
            Assertions.assertFalse(fix.fixes().isEmpty(), fix.status());
            Assertions.assertTrue(proxy.isModified());
            Assertions.assertTrue(proxy.validatePdfa().isConform());
            Assertions.assertTrue(proxy.fixWithVeraPdf().fixes().isEmpty(), "nothing left to fix");
        } finally {
            proxy.close();
        }
    }

    /**
     * A PDF/A-2b document with JavaScript: not conform, and not repairable by the metadata fixer.
     */
    private static byte[] withJavaScript() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.plainPdf(1))) {
            PdfStandard.convertTo(doc, PdfStandardProfile.PDFA_2B);
            var action = new org.apache.pdfbox.cos.COSDictionary();
            action.setName(org.apache.pdfbox.cos.COSName.S, "JavaScript");
            action.setString(org.apache.pdfbox.cos.COSName.getPDFName("JS"), "app.alert('x')");
            doc.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("OpenAction"), action);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os, CompressParameters.NO_COMPRESSION);
            return os.toByteArray();
        }
    }

    @Test
    void test_removedIdentificationIsNotApplied() throws Exception {
        byte[] pdf = withJavaScript();
        Assertions.assertFalse(PdfaValidator.validate(pdf).isConform());
        PdfaValidator.MetadataFix fix = PdfaValidator.fixMetadata(pdf);
        System.out.println("veraPDF metadata fixer on a document with JavaScript: " + fix.status() + " " + fix.fixes());
        Assertions.assertFalse(fix.applied(), "the identification would be removed: " + fix.status());

        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(Loader.loadPDF(pdf), new MergeOptions());
            Assertions.assertFalse(proxy.fixWithVeraPdf().applied());
            Assertions.assertEquals(2, PdfStandard.pdfaPart(proxy.getDocument()), "still PDF/A-2");
            Assertions.assertFalse(proxy.isModified());
        } finally {
            proxy.close();
        }
    }
}

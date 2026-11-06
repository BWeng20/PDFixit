package com.bw.pdfixit.model;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

/**
 * The Invoice tab (DocumentProxy.attachInvoice) keeps the conformance letter of the document.
 */
class AttachInvoiceLevelTest {

    @Test
    void test_attachInvoiceKeepsTheLetter() throws Exception {
        for (PdfStandardProfile level : new PdfStandardProfile[]{PdfStandardProfile.PDFA_3A, PdfStandardProfile.PDFA_3B,
                PdfStandardProfile.PDFA_3U}) {
            byte[] pdf;
            try (PDDocument doc = Loader.loadPDF(EInvoiceTest.notEmbeddedFontPdf(Standard14Fonts.FontName.HELVETICA, "Hello"))) {
                PdfStandard.convertTo(doc, level);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                doc.save(os, CompressParameters.NO_COMPRESSION);
                pdf = os.toByteArray();
            }
            DocumentProxy proxy = new DocumentProxy(new RenderQueue());
            try {
                proxy.loaderFinished(Loader.loadPDF(pdf), new MergeOptions());
                proxy.attachInvoice(EInvoiceTest.sampleXml());
                Assertions.assertTrue(EInvoice.hasInvoice(proxy.getDocument()));
                Assertions.assertEquals(level, PdfStandard.profile(proxy.getDocument()), level.toString());
            } finally {
                proxy.close();
            }
        }
    }
}

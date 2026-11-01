package com.bw.pdfixit.model;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ModifiedFlagTest {

    @Test
    void test_modifiedAfterChangeUntilCleared() {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage());
        doc.addPage(new PDPage());
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(doc, new MergeOptions());
            Assertions.assertFalse(proxy.isModified(), "loading is no change");

            proxy.rotatePage(1, 90);
            Assertions.assertTrue(proxy.isModified());

            proxy.clearModified();
            Assertions.assertFalse(proxy.isModified());

            proxy.deletePage(2);
            Assertions.assertTrue(proxy.isModified());
        } finally {
            proxy.close();
        }
    }

    @Test
    void test_attachInvoiceIsAbortedByTheStandardCheck() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage());
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(doc, new MergeOptions());
            proxy.invoiceStandardCheck = () -> "Aborted: the document is not PDF/A-3.";
            Assertions.assertThrows(com.bw.pdfixit.invoice.EInvoice.NotConformException.class,
                    () -> proxy.attachInvoice(com.bw.pdfixit.invoice.EInvoiceTest.sampleXml()));
            Assertions.assertFalse(proxy.isModified(), "aborted, nothing changed");
            Assertions.assertEquals(0, com.bw.pdfixit.PdfStandard.pdfaPart(proxy.getDocument()));
        } finally {
            proxy.close();
        }
    }

    @Test
    void test_convertToStandardMarksModifiedOnlyIfChanged() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage());
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(doc, new MergeOptions());
            Assertions.assertTrue(proxy.convertToStandard(null).isEmpty(), "declares no PDF/A, nothing to remove");
            Assertions.assertFalse(proxy.isModified());

            Assertions.assertFalse(proxy.convertToStandard(com.bw.pdfixit.PdfStandardProfile.PDFA_2B).isEmpty());
            Assertions.assertTrue(proxy.isModified());
            Assertions.assertEquals(com.bw.pdfixit.PdfStandardProfile.PDFA_2B,
                    com.bw.pdfixit.PdfStandard.profile(proxy.getDocument()));

            proxy.clearModified();
            Assertions.assertTrue(proxy.convertToStandard(com.bw.pdfixit.PdfStandardProfile.PDFA_2B).isEmpty(),
                    "already PDF/A-2b");
            Assertions.assertFalse(proxy.isModified());
        } finally {
            proxy.close();
        }
    }
}

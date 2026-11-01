package com.bw.pdfixit.model;

import com.bw.pdfixit.invoice.EInvoice;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the page order of zipper merges. Each page carries a label like "A1" (original) or "B1" (new).
 */
class ZipperMergeTest {

    private static final COSName LABEL = COSName.getPDFName("TestLabel");

    private static PDDocument document(String prefix, int pageCount) {
        PDDocument d = new PDDocument();
        for (int i = 1; i <= pageCount; ++i) {
            PDPage p = new PDPage();
            p.getCOSObject().setString(LABEL, prefix + i);
            d.addPage(p);
        }
        return d;
    }

    private static String merge(int oldPages, int newPages, int start, int segment, int gap) {
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(document("A", oldPages), new MergeOptions());
            MergeOptions mo = new MergeOptions();
            mo.startPageNb = start;
            mo.segmentLength = segment;
            mo.gapLength = gap;
            proxy.loaderFinished(document("B", newPages), mo);
            assertNull(proxy.error);

            List<String> labels = new ArrayList<>();
            for (PDPage p : proxy.getDocument().getPages())
                labels.add(p.getCOSObject().getString(LABEL));
            assertEquals(oldPages + newPages, proxy.getPageCount());
            return String.join(" ", labels);
        } finally {
            proxy.close();
        }
    }

    @Test
    void segmentsOfTwo_gapOfOne() {
        assertEquals("A1 B1 B2 A2 B3 B4 A3 B5 B6 A4 A5 A6", merge(6, 6, 2, 2, 1));
    }

    @Test
    void gapLargerThanOne() {
        assertEquals("A1 B1 B2 A2 A3 A4 B3 B4 A5 A6 A7 B5 B6 A8 A9 A10", merge(10, 6, 2, 2, 3));
    }

    @Test
    void originalPagesRunOut_restIsAppended() {
        assertEquals("A1 B1 B2 A2 A3 A4 B3 B4 B5 B6", merge(4, 6, 2, 2, 3));
    }

    @Test
    void startAtFirstPage() {
        assertEquals("B1 A1 B2 A2 B3 A3", merge(3, 3, 1, 1, 1));
    }

    @Test
    void startBeforeLastPage() {
        assertEquals("A1 A2 B1 B2 A3", merge(3, 2, 3, 5, 1));
    }

    @Test
    void noSegments_insertsOneBlock() {
        assertEquals("A1 B1 B2 B3 A2 A3", merge(3, 3, 2, -1, -1));
    }

    @Test
    void startBehindLastPage_appends() {
        assertEquals("A1 A2 B1 B2", merge(2, 2, 3, 1, 1));
    }

    @Test
    void removeInvoice_notifiesConsumers() throws Exception {
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(org.apache.pdfbox.Loader.loadPDF(
                    EInvoice.attachXml(plainPdf(), invoiceXml())), new MergeOptions());
            int[] calls = {0};
            proxy.addDocumentConsumer(new DocumentProxy.DocumentConsumer() {
                @Override
                public void documentLoaded(PDDocument document) {
                    ++calls[0];
                }

                @Override
                public void failed(String error) {
                }
            });
            int before = calls[0];
            org.junit.jupiter.api.Assertions.assertTrue(proxy.removeInvoice());
            assertEquals(before + 1, calls[0]);
        } finally {
            proxy.close();
        }
    }

    private static byte[] plainPdf() throws java.io.IOException {
        try (PDDocument d = document("A", 1)) {
            java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
            d.save(os);
            return os.toByteArray();
        }
    }

    private static byte[] invoiceXml() throws java.io.IOException {
        try (var is = ZipperMergeTest.class.getResourceAsStream("/factur-x-minimum.xml")) {
            return java.util.Objects.requireNonNull(is).readAllBytes();
        }
    }

    @Test
    void appendCheck() throws Exception {
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.appendCheck = doc -> doc.getNumberOfPages() > 1 ? "too many pages" : null;
            PDDocument first = document("A", 3);
            // The first document is not appended: always accepted.
            assertNull(proxy.checkAppend(first));
            proxy.loaderFinished(first, new MergeOptions());
            try (PDDocument small = document("B", 1); PDDocument big = document("C", 2)) {
                assertNull(proxy.checkAppend(small));
                assertEquals("too many pages", proxy.checkAppend(big));
            }
            proxy.appendCheck = null;
            try (PDDocument big = document("C", 2)) {
                assertNull(proxy.checkAppend(big));
            }
        } finally {
            proxy.close();
        }
    }
}

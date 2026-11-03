package com.bw.pdfixit.model;

import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A repair replaces the document of the proxy. The render thread must not go on with the old, closed document:
 * the pages must get their images again.
 */
public class RepairRedrawTest {

    private static Page waitForImage(DocumentProxy proxy) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            Page page = proxy.getPage(1);
            if (page.image != null || page.error != null)
                return page;
            Thread.sleep(20);
        }
        return proxy.getPage(1);
    }

    @Test
    void test_pagesAreDrawnAgainAfterRepair() throws Exception {
        byte[] pdf = EInvoiceTest.notEmbeddedFontPdf(Standard14Fonts.FontName.HELVETICA, "Rechnung 4711");
        RenderQueue queue = new RenderQueue();
        queue.start();
        DocumentProxy proxy = new DocumentProxy(queue);
        try {
            proxy.loaderFinished(Loader.loadPDF(pdf), new MergeOptions());
            queue.addDocument(proxy);
            Page page = waitForImage(proxy);
            Assertions.assertNotNull(page.image, "first rendering: " + page.error);

            var changes = proxy.fixPdfa();
            Assertions.assertFalse(changes.isEmpty(), "the repair replaces the document");
            page = waitForImage(proxy);
            Assertions.assertNull(page.error, page.error);
            Assertions.assertNotNull(page.image, "rendered again after the repair");
        } finally {
            proxy.close();
            queue.stop();
        }
    }
}

package com.bw.pdfixit.model;

import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rotating, moving and deleting pages can't change the conformance to PDF/A: they are announced, but don't count
 * as a change of the content, so the UI starts no veraPDF check for them.
 */
class ContentGenerationTest {

    @Test
    void pageOperationsDontCountAsContentChange() throws Exception {
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(Loader.loadPDF(EInvoiceTest.invoicePdf(3)), new MergeOptions());
            AtomicInteger announced = new AtomicInteger();
            proxy.addDocumentConsumer(new DocumentProxy.DocumentConsumer() {
                @Override
                public void documentLoaded(PDDocument document) {
                    announced.incrementAndGet();
                }

                @Override
                public void failed(String error) {
                }
            });
            final int generation = proxy.getContentGeneration();
            // A new listener is told about the loaded document right away.
            final int before = announced.get();

            proxy.rotatePage(1, 90);
            proxy.rotatePages(270);
            proxy.movePage(2, -1);
            proxy.deletePage(3);
            Assertions.assertEquals(generation, proxy.getContentGeneration(), "page operations");
            // The UI is still told, e.g. to update the page count.
            Assertions.assertEquals(before + 4, announced.get());

            Assertions.assertTrue(proxy.removeInvoice());
            Assertions.assertEquals(generation + 1, proxy.getContentGeneration(), "invoice removed");
            proxy.renderPageToImage(1);
            Assertions.assertEquals(generation + 2, proxy.getContentGeneration(), "page replaced by an image");
        } finally {
            proxy.close();
        }
    }
}

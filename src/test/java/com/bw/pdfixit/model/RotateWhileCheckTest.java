package com.bw.pdfixit.model;

import com.bw.pdfixit.PdfaValidator;
import com.bw.pdfixit.ValidationResult;
import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Each rotation starts a veraPDF check in the background, which saves the document. A further rotation changed
 * the pages during that save: PDFBox is not thread safe and wrote a broken file, veraPDF reported errors that
 * were gone at the next check. The first rotation of a page adds the /Rotate entry, that broke the save.
 */
class RotateWhileCheckTest {

    @Test
    void checkWhileRotatingSeesAValidDocument() throws Exception {
        final int pageCount = 80;
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        proxy.loaderFinished(Loader.loadPDF(EInvoiceTest.invoicePdf(pageCount)), new MergeOptions());
        AtomicBoolean rotating = new AtomicBoolean(true);
        List<Exception> rotateErrors = new CopyOnWriteArrayList<>();
        Thread rotator = new Thread(() -> {
            int page = 1;
            while (rotating.get()) {
                try {
                    proxy.rotatePage(page, 90);
                } catch (Exception e) {
                    rotateErrors.add(e);
                }
                page = page % pageCount + 1;
            }
        });
        try {
            rotator.start();
            // The first save is the one that failed: the pages get their /Rotate entry meanwhile.
            for (int i = 0; i < 3; ++i) {
                ValidationResult v = PdfaValidator.validate(proxy.getBytes());
                Assertions.assertEquals(ValidationResult.State.CONFORM, v.getState(), "check #" + i + ": " + v.toLines());
            }
        } finally {
            rotating.set(false);
            rotator.join();
            proxy.close();
        }
        Assertions.assertEquals(List.of(), rotateErrors);
    }
}

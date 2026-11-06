package com.bw.pdfixit.model;

import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A repair replaces the document of the proxy and closes the old one. A check that saves the document meanwhile
 * (see {@link DocumentProxy#getBytes}) must get a complete document, never a closed or half changed one.
 */
class ReplaceWhileCheckTest {

    @Test
    void savingWhileTheDocumentIsRepairedWritesValidFiles() throws Exception {
        byte[] pdf = EInvoiceTest.notEmbeddedFontPdf(Standard14Fonts.FontName.HELVETICA, "Rechnung 4711");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int round = 0; round < 10; ++round) {
            DocumentProxy proxy = new DocumentProxy(new RenderQueue());
            proxy.loaderFinished(Loader.loadPDF(pdf), new MergeOptions());
            AtomicBoolean saving = new AtomicBoolean(true);
            Thread saver = new Thread(() -> {
                while (saving.get()) {
                    try (PDDocument copy = Loader.loadPDF(proxy.getBytes())) {
                        if (copy.getNumberOfPages() != 1)
                            errors.add(new AssertionError("pages: " + copy.getNumberOfPages()));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }
            });
            try {
                saver.start();
                Assertions.assertFalse(proxy.fixPdfa().isEmpty(), "the repair replaces the document");
            } finally {
                saving.set(false);
                saver.join();
                proxy.close();
            }
        }
        Assertions.assertEquals(List.of(), errors.stream().map(Throwable::toString).distinct().toList());
    }
}

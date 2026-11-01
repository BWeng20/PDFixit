package com.bw.pdfixit.model;

import com.bw.pdfixit.fonts.PdfFontEmbedder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * After a font was replaced the pages must be drawn with the new font.
 */
public class ReplaceFontRedrawTest {

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test
    void test_replacedFontIsDrawnAgain() throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                cs.newLineAtOffset(40, 700);
                cs.showText("Rechnung 4711");
                cs.endText();
            }
            // Saved and loaded: the fonts are indirect objects now, which the document caches.
            doc.save(os);
        }

        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(Loader.loadPDF(os.toByteArray()), new MergeOptions());
            int[] before = pixels(new PDFRenderer(proxy.getDocument()).renderImageWithDPI(0, 72));
            int generation = proxy.getRedrawGeneration();

            var font = proxy.getFonts().get(0);
            proxy.replaceFont(font.dictionary(),
                    new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.MONO, true, false));

            Assertions.assertTrue(proxy.getRedrawGeneration() > generation);
            Assertions.assertEquals("Cousine-Bold", proxy.getFonts().get(0).name());
            int[] after = pixels(new PDFRenderer(proxy.getDocument()).renderImageWithDPI(0, 72));
            Assertions.assertFalse(Arrays.equals(before, after), "the page must show the new font");
        } finally {
            proxy.close();
        }
    }
}

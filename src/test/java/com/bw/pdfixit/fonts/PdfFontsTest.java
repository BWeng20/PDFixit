package com.bw.pdfixit.fonts;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

class PdfFontsTest {

    private static void write(PDDocument doc, PDPage page, PDType1Font font, String text) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    /**
     * Page 1: Helvetica. Page 2: Helvetica and Times-Bold. Page 3: Helvetica.
     */
    private static PDDocument document() throws IOException {
        PDDocument doc = new PDDocument();
        for (int i = 0; i < 3; ++i)
            doc.addPage(new PDPage());
        // The same font object on several pages is one font of the document.
        PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font times = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
        write(doc, doc.getPage(0), helvetica, "one");
        write(doc, doc.getPage(1), helvetica, "two");
        write(doc, doc.getPage(1), times, "two");
        write(doc, doc.getPage(2), helvetica, "three");
        return doc;
    }

    @Test
    void test_pageRanges() {
        Assertions.assertEquals("", PdfFonts.pageRanges(List.of()));
        Assertions.assertEquals("4", PdfFonts.pageRanges(List.of(4)));
        Assertions.assertEquals("1, 2", PdfFonts.pageRanges(List.of(2, 1)));
        Assertions.assertEquals("1-3, 5, 7-9", PdfFonts.pageRanges(List.of(1, 2, 3, 5, 7, 8, 9)));
    }

    @Test
    void test_list() throws IOException {
        try (PDDocument doc = document()) {
            List<PdfFonts.FontInfo> fonts = PdfFonts.list(doc);
            Assertions.assertEquals(2, fonts.size(), fonts.toString());
            Assertions.assertEquals("Helvetica", fonts.get(0).name());
            Assertions.assertEquals("1-3", fonts.get(0).pageText());
            Assertions.assertEquals("Type 1", fonts.get(0).type());
            Assertions.assertFalse(fonts.get(0).embedded());
            Assertions.assertEquals("Times-Bold", fonts.get(1).name());
            Assertions.assertEquals(List.of(2), fonts.get(1).pages());
            Assertions.assertNull(fonts.get(0).cannotReplaceReason());
        }
    }

    @Test
    void test_compositeFontNeedsToUnicodeToBeReplaceable() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var ttf = new org.apache.fontbox.ttf.TTFParser().parse(new RandomAccessReadBuffer(
                    PdfFontsTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")));
            PDType0Font font = PDType0Font.load(doc, ttf, true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.showText("Composite");
                cs.endText();
            }
            doc.save(os);
        }
        try (PDDocument doc = Loader.loadPDF(os.toByteArray())) {
            List<PdfFonts.FontInfo> fonts = PdfFonts.list(doc);
            Assertions.assertEquals(1, fonts.size(), fonts.toString());
            Assertions.assertEquals("Type 0 (CID)", fonts.get(0).type());
            Assertions.assertTrue(fonts.get(0).embedded());
            // The characters are known by the ToUnicode table.
            Assertions.assertNull(fonts.get(0).cannotReplaceReason());
            COSDictionary dict = fonts.get(0).dictionary();
            dict.removeItem(org.apache.pdfbox.cos.COSName.TO_UNICODE);
            Assertions.assertTrue(fonts.get(0).cannotReplaceReason().contains("ToUnicode"));
            Assertions.assertThrows(FontEmbedException.class,
                    () -> PdfFontEmbedder.replace(doc, dict, PdfFontEmbedder.bundled().get(0)));
        }
    }
}

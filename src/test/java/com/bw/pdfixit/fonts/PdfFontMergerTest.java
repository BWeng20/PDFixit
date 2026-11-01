package com.bw.pdfixit.fonts;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

class PdfFontMergerTest {

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        doc.save(os);
        return os.toByteArray();
    }

    private static void write(PDDocument doc, PDPage page, PDFont font, String text) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 14);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    private static int[] pixels(PDDocument doc, int page) throws IOException {
        BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(page, 72);
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /**
     * Three pages, each with its own font object of the same font.
     */
    private static byte[] standardFonts() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; ++i) {
                PDPage page = new PDPage();
                doc.addPage(page);
                write(doc, page, new PDType1Font(Standard14Fonts.FontName.HELVETICA), "Page " + i);
            }
            return save(doc);
        }
    }

    @Test
    void test_identicalFontsAreMerged() throws Exception {
        byte[] data = standardFonts();
        byte[] merged;
        try (PDDocument doc = Loader.loadPDF(data)) {
            Assertions.assertEquals(3, PdfFonts.list(doc).size());
            int[][] before = {pixels(doc, 0), pixels(doc, 1), pixels(doc, 2)};

            PdfFontMerger.Result found = PdfFontMerger.find(doc);
            Assertions.assertEquals(1, found.groups());
            Assertions.assertEquals(2, found.duplicates());
            Assertions.assertEquals(3, PdfFonts.list(doc).size(), "find changes nothing");

            PdfFontMerger.Result result = PdfFontMerger.merge(doc);
            Assertions.assertEquals(found, result);
            var fonts = PdfFonts.list(doc);
            Assertions.assertEquals(1, fonts.size());
            Assertions.assertEquals("1-3", fonts.get(0).pageText());
            for (int i = 0; i < 3; ++i)
                Assertions.assertArrayEquals(before[i], pixels(doc, i), "page " + (i + 1) + " looks the same");
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.find(doc));
            merged = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(merged)) {
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            String text = new PDFTextStripper().getText(doc);
            Assertions.assertTrue(text.contains("Page 0") && text.contains("Page 1") && text.contains("Page 2"), text);
            Assertions.assertTrue(merged.length < data.length, merged.length + " < " + data.length);
        }
    }

    @Test
    void test_embeddedFontProgramsAreWrittenOnce() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 2; ++i) {
                PDPage page = new PDPage();
                doc.addPage(page);
                var ttf = new TTFParser().parse(new RandomAccessReadBuffer(
                        PdfFontMergerTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")));
                // Loaded twice: two fonts with the same subset.
                write(doc, page, PDType0Font.load(doc, ttf, true), "Same text on every page");
            }
            data = save(doc);
        }
        byte[] merged;
        try (PDDocument doc = Loader.loadPDF(data)) {
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
            PdfFontMerger.Result result = PdfFontMerger.merge(doc);
            Assertions.assertEquals(1, result.duplicates());
            Assertions.assertTrue(result.savedBytes() > 1000, "saved " + result.savedBytes());
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            merged = save(doc);
        }
        Assertions.assertTrue(merged.length < data.length - 1000, merged.length + " vs " + data.length);
        try (PDDocument doc = Loader.loadPDF(merged)) {
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            Assertions.assertTrue(new PDFTextStripper().getText(doc).contains("Same text on every page"));
        }
    }

    @Test
    void test_differentFontsAreKept() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.showText("a");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                cs.showText("b");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 12);
                cs.showText("c");
                cs.endText();
            }
            data = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(data)) {
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.merge(doc));
            Assertions.assertEquals(3, PdfFonts.list(doc).size());
        }
    }

    @Test
    void test_fontsWithOtherWidthsAreKept() throws Exception {
        try (PDDocument doc = Loader.loadPDF(standardFonts())) {
            var fonts = PdfFonts.list(doc);
            // Same name, but another content: a width entry.
            fonts.get(2).dictionary().setInt(org.apache.pdfbox.cos.COSName.FIRST_CHAR, 7);
            PdfFontMerger.Result result = PdfFontMerger.merge(doc);
            Assertions.assertEquals(1, result.duplicates());
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
        }
    }

    /**
     * Two fonts replaced by the same bundled font are the same font, also if the old fonts were different
     * (here a bold one: its descriptor must not keep the weight of the old font).
     */
    @Test
    void test_replacedByTheSameFontMerges() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.showText("regular ");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                cs.showText("bold");
                cs.endText();
            }
            data = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(data)) {
            var regular = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, false, false);
            var bold = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, true, false);
            var fonts = PdfFonts.list(doc);
            Assertions.assertEquals(2, fonts.size());
            PdfFontEmbedder.replace(doc, fonts.get(0).dictionary(), regular);
            // The second one is a bold Arimo first (weight 700 in its descriptor), then it is replaced by the regular one.
            PdfFontEmbedder.replace(doc, fonts.get(1).dictionary(), bold);
            Assertions.assertEquals(700, fonts.get(1).dictionary().getCOSDictionary(org.apache.pdfbox.cos.COSName.FONT_DESC)
                    .getInt(org.apache.pdfbox.cos.COSName.FONT_WEIGHT));
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.find(doc));
            PdfFontEmbedder.replace(doc, fonts.get(1).dictionary(), regular);
            Assertions.assertEquals(400, fonts.get(1).dictionary().getCOSDictionary(org.apache.pdfbox.cos.COSName.FONT_DESC)
                    .getInt(org.apache.pdfbox.cos.COSName.FONT_WEIGHT));

            PdfFontMerger.Result result = PdfFontMerger.merge(doc);
            Assertions.assertEquals(1, result.duplicates(), "same font, same content");
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
        }
    }

    /**
     * Word writes a /Name for every font (/F1, /F2, ...) and fonts may have old hints in the descriptor:
     * both don't make the fonts different.
     */
    @Test
    void test_nameAndDescriptorHintsDontMatter() throws Exception {
        try (PDDocument doc = Loader.loadPDF(standardFonts())) {
            var fonts = PdfFonts.list(doc);
            Assertions.assertEquals(3, fonts.size());
            for (int i = 0; i < fonts.size(); ++i)
                fonts.get(i).dictionary().setName(org.apache.pdfbox.cos.COSName.NAME, "F" + (i + 1));
            // Give the fonts a descriptor with different hints.
            for (int i = 0; i < fonts.size(); ++i) {
                var desc = new org.apache.pdfbox.cos.COSDictionary();
                desc.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.FONT_DESC);
                desc.setName(org.apache.pdfbox.cos.COSName.FONT_NAME, "Helvetica");
                desc.setInt(org.apache.pdfbox.cos.COSName.FLAGS, 32);
                desc.setInt(org.apache.pdfbox.cos.COSName.getPDFName("AvgWidth"), 400 + i);
                desc.setInt(org.apache.pdfbox.cos.COSName.getPDFName("XHeight"), 500 + i);
                fonts.get(i).dictionary().setItem(org.apache.pdfbox.cos.COSName.FONT_DESC, desc);
            }
            Assertions.assertEquals(2, PdfFontMerger.merge(doc).duplicates());
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
        }
    }

    /**
     * What counts: another flag or another width makes a different font.
     */
    @Test
    void test_realDifferencesStillCount() throws Exception {
        try (PDDocument doc = Loader.loadPDF(standardFonts())) {
            var fonts = PdfFonts.list(doc);
            for (int i = 0; i < fonts.size(); ++i) {
                var desc = new org.apache.pdfbox.cos.COSDictionary();
                desc.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.FONT_DESC);
                desc.setName(org.apache.pdfbox.cos.COSName.FONT_NAME, "Helvetica");
                desc.setInt(org.apache.pdfbox.cos.COSName.FLAGS, 32 + (i == 2 ? 64 : 0));
                fonts.get(i).dictionary().setItem(org.apache.pdfbox.cos.COSName.FONT_DESC, desc);
            }
            Assertions.assertEquals(1, PdfFontMerger.merge(doc).duplicates());
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
        }
    }
}

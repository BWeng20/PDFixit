package com.bw.pdfixit.fonts;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Composite fonts (as written by Word) with different subsets of the same font.
 */
class PdfFontMergerCidTest {

    private static final PdfFontEmbedder.Variant REGULAR = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, false, false);
    private static final PdfFontEmbedder.Variant BOLD = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, true, false);

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        doc.save(os);
        return os.toByteArray();
    }

    private static PDType0Font font(PDDocument doc, boolean subset) throws IOException {
        var ttf = new TTFParser().parse(new RandomAccessReadBuffer(
                PdfFontMergerCidTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")));
        return PDType0Font.load(doc, ttf, subset);
    }

    private static void write(PDDocument doc, PDFont font, String text) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 14);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    /**
     * One page for each text, each with its own font. The codes are the glyph numbers of Arimo, so they mean the same
     * in every font (as in the PDFs of Word).
     */
    private static byte[] document(boolean subset, String... texts) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String text : texts)
                write(doc, font(doc, subset), text);
            return save(doc);
        }
    }

    private static int[] pixels(PDDocument doc, int page) throws IOException {
        BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(page, 72);
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /**
     * Replaces all fonts by the same bundled font, as the user does in the font dialog.
     */
    private static void replaceAll(PDDocument doc, PdfFontEmbedder.Variant variant) throws IOException {
        for (var f : PdfFonts.list(doc))
            PdfFontEmbedder.replace(doc, f.dictionary(), variant);
    }

    private static List<String> fontProblems(byte[] pdf) throws Exception {
        VeraGreenfieldFoundryProvider.initialise();
        PDFAFlavour flavour = PDFAFlavour.PDFA_3_B;
        try (PDFAParser parser = Foundries.defaultInstance().createParser(new ByteArrayInputStream(pdf), flavour)) {
            PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false);
            List<String> problems = new ArrayList<>();
            for (var a : validator.validate(parser).getTestAssertions())
                if (a.getRuleId().getClause().startsWith("6.2.11"))
                    problems.add(a.getRuleId().getClause() + ": " + a.getMessage());
            return problems;
        }
    }

    @Test
    void test_fontWithTheCodesOfAnotherIsRedirected() throws Exception {
        // "Hello" has the same codes in both fonts, the second font has more.
        byte[] data = document(true, "Hello", "Hello World");
        byte[] merged;
        byte[] replaced;
        try (PDDocument doc = Loader.loadPDF(data)) {
            replaceAll(doc, REGULAR);
            replaced = save(doc);
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
            int[][] before = {pixels(doc, 0), pixels(doc, 1)};

            PdfFontMerger.Result found = PdfFontMerger.find(doc);
            Assertions.assertEquals(1, found.duplicates());
            Assertions.assertTrue(found.savedBytes() > 1000, "saved " + found.savedBytes());
            Assertions.assertEquals(2, PdfFonts.list(doc).size(), "find changes nothing");

            Assertions.assertEquals(found, PdfFontMerger.merge(doc));
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            for (int i = 0; i < 2; ++i)
                Assertions.assertArrayEquals(before[i], pixels(doc, i), "page " + (i + 1));
            merged = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(merged)) {
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            Assertions.assertEquals("Hello\nHello World", new PDFTextStripper().getText(doc).strip().replace("\r\n", "\n"));
        }
        Assertions.assertTrue(merged.length < replaced.length - 1000, merged.length + " < " + replaced.length);
    }

    @Test
    void test_fontsWithOtherCodesAreWrittenAgainWithTheCodesOfBoth() throws Exception {
        // "Cat" and "Dog" have codes the other font doesn't have, but nothing contradicts.
        byte[] data = document(true, "Hello Cat", "Hello Dog");
        byte[] merged;
        try (PDDocument doc = Loader.loadPDF(data)) {
            replaceAll(doc, REGULAR);
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
            int[][] before = {pixels(doc, 0), pixels(doc, 1)};

            PdfFontMerger.Result result = PdfFontMerger.merge(doc);
            Assertions.assertEquals(1, result.duplicates(), result.toString());
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            for (int i = 0; i < 2; ++i)
                Assertions.assertArrayEquals(before[i], pixels(doc, i), "page " + (i + 1));
            merged = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(merged)) {
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
            String text = new PDFTextStripper().getText(doc);
            Assertions.assertTrue(text.contains("Hello Cat") && text.contains("Hello Dog"), text);
            // Nothing left to merge, and the result is still a valid font.
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.find(doc));
        }
        Assertions.assertEquals(List.of(), fontProblems(merged));
    }

    @Test
    void test_contradictingCodesAreKept() throws Exception {
        byte[] data = document(true, "ab", "ba");
        try (PDDocument doc = Loader.loadPDF(data)) {
            var fonts = PdfFonts.list(doc);
            // Made up: the same codes mean other characters in the second font.
            PdfFontEmbedder.rewriteCid(doc, fonts.get(0).dictionary(), REGULAR, new java.util.TreeMap<>(java.util.Map.of(1, "a", 2, "b")));
            PdfFontEmbedder.rewriteCid(doc, fonts.get(1).dictionary(), REGULAR, new java.util.TreeMap<>(java.util.Map.of(1, "b", 2, "a")));
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.merge(doc));
            Assertions.assertEquals(2, PdfFonts.list(doc).size());

            // Same characters, but another width for a code.
            PdfFontEmbedder.rewriteCid(doc, fonts.get(1).dictionary(), REGULAR, new java.util.TreeMap<>(java.util.Map.of(1, "a", 2, "b")));
            Assertions.assertEquals(1, PdfFontMerger.find(doc).duplicates());
            var w = (org.apache.pdfbox.cos.COSArray) ((org.apache.pdfbox.cos.COSDictionary) ((org.apache.pdfbox.cos.COSArray)
                    fonts.get(1).dictionary().getDictionaryObject(org.apache.pdfbox.cos.COSName.DESCENDANT_FONTS)).getObject(0))
                    .getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("W"));
            var first = (org.apache.pdfbox.cos.COSArray) w.getObject(1);
            first.set(0, org.apache.pdfbox.cos.COSInteger.get(((org.apache.pdfbox.cos.COSNumber) first.getObject(0)).intValue() + 100));
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.find(doc));
        }
    }

    @Test
    void test_otherFontsAreKept() throws Exception {
        byte[] data = document(true, "Hello", "Hello World");
        try (PDDocument doc = Loader.loadPDF(data)) {
            var fonts = PdfFonts.list(doc);
            PdfFontEmbedder.replace(doc, fonts.get(0).dictionary(), REGULAR);
            // Another weight is another font, although the codes would fit.
            PdfFontEmbedder.replace(doc, fonts.get(1).dictionary(), BOLD);
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.merge(doc));
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
        }
    }

    @Test
    void test_subsetsOfUnknownOriginAreNotRewritten() throws Exception {
        // Not made by the font dialog, so it is not known that the programs are subsets of the bundled font:
        // "Cat" and "Dog" would need a new font program. A font which contains the other is still redirected.
        try (PDDocument doc = Loader.loadPDF(document(true, "Hello Cat", "Hello Dog"))) {
            Assertions.assertEquals(PdfFontMerger.Result.NONE, PdfFontMerger.merge(doc));
            Assertions.assertEquals(2, PdfFonts.list(doc).size());
        }
        try (PDDocument doc = Loader.loadPDF(document(true, "Hello", "Hello World"))) {
            Assertions.assertEquals(1, PdfFontMerger.merge(doc).duplicates());
            Assertions.assertEquals(1, PdfFonts.list(doc).size());
        }
    }
}

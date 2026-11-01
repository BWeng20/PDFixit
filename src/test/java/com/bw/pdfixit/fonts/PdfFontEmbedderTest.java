package com.bw.pdfixit.fonts;

import com.bw.pdfixit.PdfStandard;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class PdfFontEmbedderTest {

    private static final String TEXT = "Rechnung für Müller, 12,50 € (Netto) - fi";

    private static PDDocument document(String text, Standard14Fonts.FontName... fonts) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float y = 700;
            for (Standard14Fonts.FontName f : fonts) {
                cs.beginText();
                cs.setFont(new PDType1Font(f), 12);
                cs.newLineAtOffset(72, y);
                cs.showText(text);
                cs.endText();
                y -= 20;
            }
        }
        return doc;
    }

    /**
     * Font rule violations (clause 6.2.11, 6.3 of PDF/A-1) of veraPDF.
     */
    private static List<String> fontProblems(byte[] pdf) throws Exception {
        VeraGreenfieldFoundryProvider.initialise();
        PDFAFlavour flavour = PDFAFlavour.PDFA_3_B;
        try (PDFAParser parser = Foundries.defaultInstance().createParser(new ByteArrayInputStream(pdf), flavour)) {
            PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false);
            ValidationResult result = validator.validate(parser);
            List<String> problems = new ArrayList<>();
            for (TestAssertion a : result.getTestAssertions()) {
                String clause = a.getRuleId().getClause();
                if (clause.startsWith("6.2.11"))
                    problems.add(clause + "-" + a.getRuleId().getTestNumber() + ": " + a.getMessage());
            }
            return problems;
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        doc.save(os);
        return os.toByteArray();
    }

    @Test
    void test_embedsStandardFonts() throws Exception {
        byte[] original;
        try (PDDocument doc = document(TEXT, Standard14Fonts.FontName.HELVETICA, Standard14Fonts.FontName.TIMES_BOLD_ITALIC,
                Standard14Fonts.FontName.COURIER)) {
            original = save(doc);
        }
        Assertions.assertFalse(fontProblems(original).isEmpty(), "test needs a document with missing fonts");

        byte[] fixed;
        try (PDDocument doc = Loader.loadPDF(original)) {
            var changes = PdfStandard.fixPdfaIssues(doc);
            Assertions.assertTrue(changes.keySet().stream().anyMatch(k -> k.startsWith("Embedded font Arimo for Helvetica")),
                    changes.toString());
            Assertions.assertTrue(changes.keySet().stream().anyMatch(k -> k.startsWith("Embedded font Tinos-BoldItalic")),
                    changes.toString());
            Assertions.assertTrue(changes.keySet().stream().anyMatch(k -> k.startsWith("Embedded font Cousine for Courier")),
                    changes.toString());
            Assertions.assertTrue(PdfStandard.fixPdfaIssues(doc).isEmpty(), "nothing left to fix");
            fixed = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(fixed));

        // The text is unchanged and the font programs are subsets.
        try (PDDocument doc = Loader.loadPDF(fixed)) {
            String text = new PDFTextStripper().getText(doc);
            Assertions.assertTrue(text.contains(TEXT), text);
            Assertions.assertTrue(fixed.length < original.length + 300_000, "size " + fixed.length);
        }
    }

    @Test
    void test_symbolFontIsRefusedAndNothingChanged() throws Exception {
        byte[] original;
        try (PDDocument doc = document("αβ",Standard14Fonts.FontName.SYMBOL)) {
            original = save(doc);
        }
        try (PDDocument doc = Loader.loadPDF(original)) {
            FontEmbedException e = Assertions.assertThrows(FontEmbedException.class, () -> PdfFontEmbedder.embed(doc,
                    fontsOf(doc)));
            Assertions.assertTrue(e.getMessage().contains("Symbol"), e.getMessage());
            for (COSDictionary f : fontsOf(doc))
                Assertions.assertFalse(PdfFontEmbedder.isEmbedded(f), "must be unchanged");
        }
    }

    @Test
    void test_embeddedFontsAreKept() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var ttf = new org.apache.fontbox.ttf.TTFParser().parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(
                    PdfFontEmbedderTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")));
            PDFont font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf, true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.showText("Embedded");
                cs.endText();
            }
            // The subset is written when saving.
            byte[] saved = save(doc);
            try (PDDocument loaded = Loader.loadPDF(saved)) {
                Assertions.assertTrue(PdfStandard.fixPdfaIssues(loaded).keySet().stream()
                        .noneMatch(k -> k.startsWith("Embedded font")));
            }
        }
    }

    @Test
    void test_bestMatch() throws Exception {
        try (PDDocument doc = document(TEXT, Standard14Fonts.FontName.HELVETICA_BOLD,
                Standard14Fonts.FontName.TIMES_ITALIC, Standard14Fonts.FontName.COURIER)) {
            var fonts = PdfFonts.list(doc);
            var byName = new java.util.HashMap<String, PdfFontEmbedder.Variant>();
            fonts.forEach(f -> byName.put(f.name(), PdfFontEmbedder.bestMatch(f.dictionary())));
            Assertions.assertEquals(new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, true, false), byName.get("Helvetica-Bold"));
            Assertions.assertEquals(new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SERIF, false, true), byName.get("Times-Italic"));
            Assertions.assertEquals(new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.MONO, false, false), byName.get("Courier"));
        }
        Assertions.assertEquals(12, PdfFontEmbedder.bundled().size());
        Assertions.assertEquals("Tinos Bold Italic",
                new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SERIF, true, true).displayName());
    }

    @Test
    void test_replaceWithChosenFont() throws Exception {
        byte[] saved;
        try (PDDocument doc = document(TEXT, Standard14Fonts.FontName.HELVETICA)) {
            var font = PdfFonts.list(doc).get(0);
            // Not the best match (Arimo), but what the user chose.
            var tinos = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SERIF, true, true);
            PdfFontEmbedder.replace(doc, font.dictionary(), tinos);
            var replaced = PdfFonts.list(doc).get(0);
            Assertions.assertTrue(replaced.embedded());
            Assertions.assertEquals("Tinos-BoldItalic", replaced.name());
            saved = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(saved));
        try (PDDocument doc = Loader.loadPDF(saved)) {
            Assertions.assertTrue(new PDFTextStripper().getText(doc).contains(TEXT));
            // An embedded font with an encoding can be replaced again.
            var font = PdfFonts.list(doc).get(0);
            Assertions.assertNull(font.cannotReplaceReason());
            PdfFontEmbedder.replace(doc, font.dictionary(),
                    new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.MONO, false, false));
            Assertions.assertEquals("Cousine", PdfFonts.list(doc).get(0).name());
            saved = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(saved));
    }

    @Test
    void test_replaceRefusesSymbol() throws Exception {
        try (PDDocument doc = document("αβ", Standard14Fonts.FontName.SYMBOL)) {
            var font = PdfFonts.list(doc).get(0);
            Assertions.assertNotNull(font.cannotReplaceReason());
            Assertions.assertThrows(FontEmbedException.class,
                    () -> PdfFontEmbedder.replace(doc, font.dictionary(), PdfFontEmbedder.bundled().get(0)));
            Assertions.assertFalse(PdfFontEmbedder.isEmbedded(font.dictionary()));
        }
    }

    /**
     * A document with a composite font (Identity-H, ToUnicode) from the bundled Arimo, saved and loaded.
     */
    private static byte[] cidDocument(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var ttf = new org.apache.fontbox.ttf.TTFParser().parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(
                    PdfFontEmbedderTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")));
            PDFont font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf, true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            return save(doc);
        }
    }

    private static COSDictionary cidDictionary(COSDictionary type0) {
        return (COSDictionary) ((COSArray) type0.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
    }

    @Test
    void test_replaceCompositeFont() throws Exception {
        byte[] saved = cidDocument(TEXT);
        byte[] result;
        try (PDDocument doc = Loader.loadPDF(saved)) {
            var font = PdfFonts.list(doc).get(0);
            Assertions.assertEquals("Type 0 (CID)", font.type());
            Assertions.assertNull(font.cannotReplaceReason());
            PdfFontEmbedder.replace(doc, font.dictionary(),
                    new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.MONO, true, false));
            Assertions.assertEquals("Cousine-Bold", PdfFonts.list(doc).get(0).name());
            result = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertTrue(new PDFTextStripper().getText(doc).contains(TEXT));
            Assertions.assertEquals("Type 0 (CID)", PdfFonts.list(doc).get(0).type());
            // Looks different: monospaced and bold.
            var before = new org.apache.pdfbox.rendering.PDFRenderer(Loader.loadPDF(saved)).renderImageWithDPI(0, 72);
            var after = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 72);
            Assertions.assertFalse(java.util.Arrays.equals(
                    before.getRGB(0, 0, before.getWidth(), before.getHeight(), null, 0, before.getWidth()),
                    after.getRGB(0, 0, after.getWidth(), after.getHeight(), null, 0, after.getWidth())));
        }
    }

    @Test
    void test_embedNotEmbeddedCompositeFont() throws Exception {
        byte[] saved = cidDocument(TEXT);
        byte[] result;
        try (PDDocument doc = Loader.loadPDF(saved)) {
            COSDictionary cid = cidDictionary(PdfFonts.list(doc).get(0).dictionary());
            // As if the producer had not embedded the font.
            cid.getCOSDictionary(COSName.FONT_DESC).removeItem(COSName.FONT_FILE2);
            cid.setItem(COSName.getPDFName("CIDToGIDMap"), COSName.getPDFName("Identity"));
            Assertions.assertFalse(PdfFontEmbedder.isEmbedded(PdfFonts.list(doc).get(0).dictionary()));

            var changes = PdfStandard.fixPdfaIssues(doc);
            Assertions.assertTrue(changes.keySet().stream().anyMatch(k -> k.startsWith("Embedded font Arimo")), changes.toString());
            Assertions.assertTrue(PdfFonts.list(doc).get(0).embedded());
            result = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertTrue(new PDFTextStripper().getText(doc).contains(TEXT));
        }
    }

    @Test
    void test_compositeFontWithoutToUnicodeIsRefused() throws Exception {
        try (PDDocument doc = Loader.loadPDF(cidDocument(TEXT))) {
            COSDictionary font = PdfFonts.list(doc).get(0).dictionary();
            font.removeItem(COSName.TO_UNICODE);
            cidDictionary(font).getCOSDictionary(COSName.FONT_DESC).removeItem(COSName.FONT_FILE2);
            FontEmbedException e = Assertions.assertThrows(FontEmbedException.class, () -> PdfStandard.fixPdfaIssues(doc));
            Assertions.assertTrue(e.getMessage().contains("ToUnicode"), e.getMessage());
            Assertions.assertFalse(PdfFontEmbedder.isEmbedded(font), "unchanged");
        }
    }

    @Test
    void test_compositeFontWithCharactersOfTheFontOnlyIsRefusedForOthers() throws Exception {
        try (PDDocument doc = Loader.loadPDF(cidDocument("Abc"))) {
            COSDictionary font = PdfFonts.list(doc).get(0).dictionary();
            // Let the first character of the table be a Chinese one, which the bundled fonts don't have.
            COSStream toUnicode = (COSStream) font.getDictionaryObject(COSName.TO_UNICODE);
            String cmap;
            try (var is = toUnicode.createInputStream()) {
                cmap = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            String changed = cmap.replaceFirst(
                    "(beginbf(?:char|range)\\s*<[0-9A-Fa-f]+>\\s*(?:<[0-9A-Fa-f]+>\\s*)?)<[0-9A-Fa-f]+>", "$1<65E5>");
            Assertions.assertNotEquals(cmap, changed, cmap);
            try (var os = toUnicode.createOutputStream()) {
                os.write(changed.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            FontEmbedException e = Assertions.assertThrows(FontEmbedException.class, () -> PdfFontEmbedder.replace(doc, font,
                    PdfFontEmbedder.bundled().get(0)));
            Assertions.assertTrue(e.getMessage().contains("\u65e5"), e.getMessage());
            Assertions.assertEquals("Arimo", PdfFonts.list(doc).get(0).name().replaceAll("^[A-Z]{6}\\+", ""), "unchanged");
        }
    }

    private static List<COSDictionary> fontsOf(PDDocument doc) {
        List<COSDictionary> list = new ArrayList<>();
        var res = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
        for (COSName n : res.keySet())
            list.add((COSDictionary) res.getDictionaryObject(n));
        return list;
    }
}

package com.bw.pdfixit.fonts;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class ImportedFontsTest {

    @TempDir
    Path dir;

    /**
     * Copies a bundled font to a file of the user.
     */
    private Path fontFile(String name, String bundled) throws IOException {
        try (InputStream is = ImportedFontsTest.class.getResourceAsStream("/fonts/" + bundled + ".ttf")) {
            Path file = dir.resolve(name);
            Files.write(file, is.readAllBytes());
            return file;
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        doc.save(os);
        return os.toByteArray();
    }

    private static byte[] standardFontDocument(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            return save(doc);
        }
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

    // ---------------------------------------------------------------- the list

    @Test
    void test_storeKeepsNameAndPathOnly() throws Exception {
        Path store = dir.resolve("fonts.json");
        Assertions.assertEquals(List.of(), ImportedFonts.load(store));

        ImportedFonts.save(store, List.of(new ImportedFonts.Entry("My Font", "C:/fonts/my.ttf"),
                new ImportedFonts.Entry("Other", "D:/x/other.ttf")));
        String json = Files.readString(store);
        Assertions.assertTrue(json.contains("\"fonts\"") && json.contains("\"name\" : \"My Font\"")
                && json.contains("\"path\" : \"C:/fonts/my.ttf\""), json);
        Assertions.assertEquals(List.of(new ImportedFonts.Entry("My Font", "C:/fonts/my.ttf"),
                new ImportedFonts.Entry("Other", "D:/x/other.ttf")), ImportedFonts.load(store));
    }

    @Test
    void test_unknownContentIsKept_andBrokenEntriesAreSkipped() throws Exception {
        Path store = dir.resolve("fonts.json");
        Files.writeString(store, "{\"other\": {\"a\": 1}, \"fonts\": [{\"name\": \"A\", \"path\": \"a.ttf\", \"future\": true}, "
                + "{\"name\": \"no path\"}, null]}");
        Assertions.assertEquals(List.of(new ImportedFonts.Entry("A", "a.ttf")), ImportedFonts.load(store));

        ImportedFonts.save(store, List.of(new ImportedFonts.Entry("B", "b.ttf")));
        String json = Files.readString(store);
        Assertions.assertTrue(json.contains("\"other\""), "other content is kept: " + json);
        Assertions.assertEquals(List.of(new ImportedFonts.Entry("B", "b.ttf")), ImportedFonts.load(store));
    }

    @Test
    void test_theListIsReadWithoutOpeningTheFonts() throws Exception {
        Path store = dir.resolve("fonts.json");
        // Files which are not there: reading the list must not need them.
        ImportedFonts.save(store, List.of(new ImportedFonts.Entry("Gone", dir.resolve("gone.ttf").toString())));
        List<ImportedFont> fonts = ImportedFonts.fonts(store);
        Assertions.assertEquals(1, fonts.size());
        Assertions.assertEquals("Gone", fonts.get(0).displayName());
        Assertions.assertFalse(fonts.get(0).exists());
        // Only now the font is read.
        FontEmbedException e = Assertions.assertThrows(FontEmbedException.class, () -> fonts.get(0).open());
        Assertions.assertTrue(e.getMessage().contains("doesn't exist"), e.getMessage());
    }

    @Test
    void test_importAddsOnceAndRemoveKeepsTheFile() throws Exception {
        Path store = dir.resolve("fonts.json");
        Path font = fontFile("My Font.ttf", "Arimo-Bold");
        Path text = dir.resolve("notes.ttf");
        Files.writeString(text, "this is no font");

        ImportedFonts.ImportResult result = ImportedFonts.importFiles(store, List.of(font, text));
        Assertions.assertEquals(1, result.added().size());
        Assertions.assertEquals(1, result.errors().size(), result.errors().toString());
        Assertions.assertTrue(result.errors().get(0).startsWith("notes.ttf"), result.errors().get(0));
        // The name comes from the font.
        Assertions.assertTrue(result.added().get(0).getName().startsWith("Arimo"), result.added().get(0).getName());
        Assertions.assertEquals(font.toAbsolutePath().normalize(), result.added().get(0).getPath());

        // The same file twice is not an error and not added again.
        ImportedFonts.ImportResult again = ImportedFonts.importFiles(store, List.of(font));
        Assertions.assertEquals(0, again.added().size());
        Assertions.assertEquals(0, again.errors().size());
        Assertions.assertEquals(1, ImportedFonts.load(store).size());

        Assertions.assertTrue(ImportedFonts.remove(store, result.added().get(0)));
        Assertions.assertFalse(ImportedFonts.remove(store, result.added().get(0)));
        Assertions.assertEquals(List.of(), ImportedFonts.load(store));
        Assertions.assertTrue(Files.exists(font), "the font file is not deleted");
    }

    // ---------------------------------------------------------------- what can be used

    @Test
    void test_embeddingFlags() {
        Assertions.assertTrue(ImportedFont.embeddingAllowed(0));
        Assertions.assertTrue(ImportedFont.embeddingAllowed(0x0004));
        Assertions.assertTrue(ImportedFont.embeddingAllowed(0x0008));
        Assertions.assertFalse(ImportedFont.embeddingAllowed(0x0002), "restricted license embedding");
        Assertions.assertFalse(ImportedFont.embeddingAllowed(0x0100), "no subsetting");
    }

    @Test
    void test_fontWhichForbidsEmbeddingIsRefused() throws Exception {
        Path file = fontFile("Restricted.ttf", "Arimo-Regular");
        byte[] data = Files.readAllBytes(file);
        // Table directory: 12 bytes header, then 16 bytes per table (tag, checksum, offset, length).
        int tables = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        int os2 = -1;
        for (int i = 0; i < tables; ++i) {
            int entry = 12 + 16 * i;
            if ("OS/2".equals(new String(data, entry, 4, StandardCharsets.ISO_8859_1)))
                os2 = ((data[entry + 8] & 0xFF) << 24) | ((data[entry + 9] & 0xFF) << 16) | ((data[entry + 10] & 0xFF) << 8) | (data[entry + 11] & 0xFF);
        }
        Assertions.assertTrue(os2 > 0);
        // fsType is at offset 8 of the OS/2 table.
        data[os2 + 8] = 0;
        data[os2 + 9] = 2;
        Files.write(file, data);
        FontEmbedException e = Assertions.assertThrows(FontEmbedException.class, () -> new ImportedFont(null, file).open());
        Assertions.assertTrue(e.getMessage().contains("doesn't allow"), e.getMessage());
        Assertions.assertThrows(FontEmbedException.class, () -> ImportedFont.fromFile(file));
    }

    @Test
    void test_styleComesFromTheFont() throws Exception {
        Object[][] cases = {
                {"Arimo-Regular", PdfFontEmbedder.Family.SANS, false, false},
                {"Arimo-BoldItalic", PdfFontEmbedder.Family.SANS, true, true},
                {"Tinos-Italic", PdfFontEmbedder.Family.SERIF, false, true},
                {"Tinos-Bold", PdfFontEmbedder.Family.SERIF, true, false},
                {"Cousine-Regular", PdfFontEmbedder.Family.MONO, false, false}};
        for (Object[] c : cases) {
            ImportedFont font = new ImportedFont(null, fontFile(c[0] + ".ttf", (String) c[0]));
            try (TrueTypeFont ttf = font.open()) {
                Assertions.assertEquals(new PdfFontEmbedder.Variant((PdfFontEmbedder.Family) c[1], (boolean) c[2], (boolean) c[3]),
                        font.style(ttf), (String) c[0]);
            }
        }
    }

    // ---------------------------------------------------------------- replacing by an imported font

    @Test
    void test_replaceBySimpleFont_importedFontIsEmbedded() throws Exception {
        ImportedFont imported = ImportedFont.fromFile(fontFile("Corporate.ttf", "Tinos-BoldItalic"));
        byte[] result;
        try (PDDocument doc = Loader.loadPDF(standardFontDocument("Rechnung für Müller, 12,50 €"))) {
            var font = PdfFonts.list(doc).get(0);
            PdfFontEmbedder.replace(doc, font.dictionary(), imported);
            var replaced = PdfFonts.list(doc).get(0);
            Assertions.assertTrue(replaced.embedded());
            Assertions.assertEquals("Tinos-BoldItalic", replaced.name());
            var desc = replaced.dictionary().getCOSDictionary(org.apache.pdfbox.cos.COSName.FONT_DESC);
            // The flags come from the file.
            Assertions.assertEquals(700, desc.getInt(org.apache.pdfbox.cos.COSName.FONT_WEIGHT));
            Assertions.assertTrue((desc.getInt(org.apache.pdfbox.cos.COSName.FLAGS) & 64) != 0, "italic");
            Assertions.assertTrue((desc.getInt(org.apache.pdfbox.cos.COSName.FLAGS) & 2) != 0, "serif");
            result = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertTrue(new PDFTextStripper().getText(doc).contains("Rechnung für Müller, 12,50 €"));
        }
    }

    @Test
    void test_replaceByCompositeFont_importedFontIsEmbedded() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var ttf = new TTFParser().parse(new RandomAccessReadBuffer(
                    ImportedFontsTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")));
            var font = PDType0Font.load(doc, ttf, true);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Hello World");
                cs.endText();
            }
            data = save(doc);
        }
        ImportedFont imported = ImportedFont.fromFile(fontFile("Mono.ttf", "Cousine-Bold"));
        byte[] result;
        try (PDDocument doc = Loader.loadPDF(data)) {
            PdfFontEmbedder.replace(doc, PdfFonts.list(doc).get(0).dictionary(), imported);
            Assertions.assertEquals("Cousine-Bold", PdfFonts.list(doc).get(0).name());
            result = save(doc);
        }
        Assertions.assertEquals(List.of(), fontProblems(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertTrue(new PDFTextStripper().getText(doc).contains("Hello World"));
        }
    }

    @Test
    void test_fontWithoutTheCharactersIsRefusedAndNothingChanges() throws Exception {
        // A font with a few letters only.
        Path small = dir.resolve("small.ttf");
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(
                ImportedFontsTest.class.getResourceAsStream("/fonts/Arimo-Regular.ttf")))) {
            TTFSubsetter subsetter = new TTFSubsetter(ttf);
            subsetter.addAll(Set.of((int) ' ', (int) 'a', (int) 'b', (int) 'c'));
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            subsetter.writeToStream(os);
            Files.write(small, os.toByteArray());
        }
        ImportedFont imported = new ImportedFont("Small", small);
        try (PDDocument doc = Loader.loadPDF(standardFontDocument("abc xyz"))) {
            var font = PdfFonts.list(doc).get(0);
            // The font has no widths: it is not known which characters are used, so all plain ASCII characters count.
            FontEmbedException e = Assertions.assertThrows(FontEmbedException.class,
                    () -> PdfFontEmbedder.replace(doc, font.dictionary(), imported));
            Assertions.assertTrue(e.getMessage().startsWith("Font Helvetica: these characters are not in Small: "), e.getMessage());
            Assertions.assertFalse(PdfFontEmbedder.isEmbedded(font.dictionary()), "unchanged");

            // With widths only the characters count which have one: here a, b, c and z.
            var dict = font.dictionary();
            var widths = new org.apache.pdfbox.cos.COSArray();
            for (char c = 'a'; c <= 'z'; ++c)
                widths.add(org.apache.pdfbox.cos.COSInteger.get(c <= 'c' ? 500 : 0));
            dict.setInt(org.apache.pdfbox.cos.COSName.FIRST_CHAR, 'a');
            dict.setInt(org.apache.pdfbox.cos.COSName.LAST_CHAR, 'z');
            dict.setItem(org.apache.pdfbox.cos.COSName.WIDTHS, widths);
            PdfFontEmbedder.replace(doc, dict, imported);
            Assertions.assertTrue(PdfFontEmbedder.isEmbedded(dict), "a, b and c are enough");
        }
        try (PDDocument doc = Loader.loadPDF(standardFontDocument("abc xyz"))) {
            var dict = PdfFonts.list(doc).get(0).dictionary();
            var widths = new org.apache.pdfbox.cos.COSArray();
            for (char c = 'a'; c <= 'z'; ++c)
                widths.add(org.apache.pdfbox.cos.COSInteger.get(c <= 'c' || c == 'z' ? 500 : 0));
            dict.setInt(org.apache.pdfbox.cos.COSName.FIRST_CHAR, 'a');
            dict.setInt(org.apache.pdfbox.cos.COSName.LAST_CHAR, 'z');
            dict.setItem(org.apache.pdfbox.cos.COSName.WIDTHS, widths);
            FontEmbedException e = Assertions.assertThrows(FontEmbedException.class,
                    () -> PdfFontEmbedder.replace(doc, dict, imported));
            Assertions.assertTrue(e.getMessage().endsWith("not in Small: z"), e.getMessage());
            Assertions.assertFalse(PdfFontEmbedder.isEmbedded(dict), "unchanged");
        }
    }

    @Test
    void test_fileWhichIsGoneIsReportedWhenUsed() throws Exception {
        ImportedFont gone = new ImportedFont("Gone", dir.resolve("gone.ttf"));
        try (PDDocument doc = Loader.loadPDF(standardFontDocument("abc"))) {
            var font = PdfFonts.list(doc).get(0);
            FontEmbedException e = Assertions.assertThrows(FontEmbedException.class,
                    () -> PdfFontEmbedder.replace(doc, font.dictionary(), gone));
            Assertions.assertTrue(e.getMessage().contains("doesn't exist"), e.getMessage());
            Assertions.assertFalse(PdfFontEmbedder.isEmbedded(font.dictionary()));
        }
    }
}

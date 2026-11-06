package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.PdfaValidator;
import com.bw.pdfixit.Redactor;
import com.bw.pdfixit.ValidationResult;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.PdfFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

class OverlayTest {

    @TempDir
    Path tmp;

    private static final Locale DE = Locale.GERMAN;

    static Map<String, String> values() {
        Map<String, String> v = new HashMap<>();
        v.put("number", "T-2024-17");
        v.put("issueDate", "01.10.2024");
        v.put("deliveryDate", "30.09.2024");
        v.put("buyerReference", "04011000-12345-34");
        v.put("seller.address", "Kelomi Testfirma GmbH\nRasoweg 12\n12345 Vizostadt");
        v.put("buyer.address", "Banuke Testfirma AG\nz. Hd. Łucja Żółć\nLunweg 3\n54321 Mitestadt");
        return v;
    }

    /**
     * The result of drawing the fixed elements over a prepared template.
     */
    record Result(byte[] pdf, List<Problem> problems) {
    }

    static Result draw(byte[] template, Layout layout, Locale language, Map<String, String> values,
                       PdfStandardProfile level, List<ImportedFont> fonts) throws Exception {
        try (var prepared = TemplatePreparer.prepare(template, null, level)) {
            PDDocument doc = prepared.document();
            Overlay overlay = new Overlay(doc, layout, language, fonts);
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; ++i)
                overlay.drawFixed(doc.getPage(i), i > 0, values, i + 1, n);
            if (level == PdfStandardProfile.PDFA_3A)
                PdfTagger.tagPages(doc);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os);
            return new Result(os.toByteArray(), overlay.problems());
        }
    }

    static Result draw(Layout layout, Locale language, Map<String, String> values) throws Exception {
        return draw(TestTemplates.letterhead(false), layout, language, values, PdfStandardProfile.PDFA_3U, List.of());
    }

    static Element fixed(Layout l, String id, boolean next) {
        return l.fields.stream().filter(e -> id.equals(e.id) && e.next == next).findFirst().orElseThrow();
    }

    /**
     * The text inside a rectangle of the layout.
     */
    static String textIn(byte[] pdf, int page, Rect r) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            Rectangle2D p = r.toPoints();
            stripper.addRegion("r", new Rectangle2D.Double(p.getX() - 0.5, p.getY() - 0.5, p.getWidth() + 1,
                    p.getHeight() + 1));
            stripper.extractRegions(doc.getPage(page - 1));
            return stripper.getTextForRegion("r");
        }
    }

    /**
     * Where a text is on a page, in points from the top left corner (the box of the glyphs, about).
     */
    static Rectangle2D find(byte[] pdf, int page, String text) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (Redactor.Region r : Redactor.findText(doc, Pattern.compile(Pattern.quote(text))))
                if (r.pageNb == page) {
                    PDPage p = doc.getPage(page - 1);
                    Rectangle2D view = Redactor.pageToView(r.area, p.getCropBox(), p.getRotation());
                    // findText adds a point around the glyphs.
                    return new Rectangle2D.Double(view.getX() + 1, view.getY() + 1, view.getWidth() - 2,
                            view.getHeight() - 2);
                }
        }
        return null;
    }

    static List<Problem> errors(Result r) {
        return r.problems().stream().filter(p -> p.severity() == Problem.Severity.ERROR).toList();
    }

    static void assertError(Result r, String element, int page, String text) {
        List<Problem> errors = errors(r);
        Assertions.assertEquals(1, errors.size(), errors.toString());
        Assertions.assertEquals(element, errors.get(0).element(), errors.toString());
        Assertions.assertEquals(page, errors.get(0).page(), errors.toString());
        Assertions.assertTrue(errors.get(0).message().contains(text), errors.get(0).message());
    }

    @Test
    void test_fieldsInTheirRegions() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Result r = draw(l, DE, values());
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());

        String number = textIn(r.pdf(), 1, fixed(l, "number", false).rect);
        Assertions.assertTrue(number.contains("Rechnungsnummer") && number.contains("T-2024-17"), number);
        // Right aligned: the value ends at the right edge of the region.
        Rectangle2D value = find(r.pdf(), 1, "T-2024-17");
        Assertions.assertEquals(Rect.toPoints(200), value.getMaxX(), 1);
        // The label starts at the left edge.
        Assertions.assertEquals(Rect.toPoints(125), find(r.pdf(), 1, "Rechnungsnummer").getX(), 1);
        Assertions.assertTrue(textIn(r.pdf(), 1, fixed(l, "issueDate", false).rect).contains("01.10.2024"));

        // The return address on one line, no label.
        String sender = textIn(r.pdf(), 1, fixed(l, "seller.address", false).rect);
        Assertions.assertTrue(sender.contains("Kelomi Testfirma GmbH · Rasoweg 12 · 12345 Vizostadt"), sender);
        Assertions.assertFalse(sender.contains("Absender"), sender);
        // The buyer line by line, no label.
        String buyer = textIn(r.pdf(), 1, fixed(l, "buyer.address", false).rect);
        Assertions.assertTrue(buyer.contains("Banuke Testfirma AG") && buyer.contains("z. Hd. Łucja Żółć")
                && buyer.contains("54321 Mitestadt"), buyer);
        Assertions.assertFalse(buyer.contains("Empfänger"), buyer);
        Assertions.assertEquals(Rect.toPoints(25), find(r.pdf(), 1, "Lunweg 3").getX(), 1);

        // The following page: number and page.
        Assertions.assertTrue(textIn(r.pdf(), 2, fixed(l, "number", true).rect).contains("T-2024-17"));
        Assertions.assertTrue(textIn(r.pdf(), 2, fixed(l, "page", true).rect).contains("Seite 2 von 2"));
        Assertions.assertNull(find(r.pdf(), 1, "Seite 1 von"), "page is only on the following pages");
        assertNoTextOutside(r.pdf(), l);
    }

    /**
     * Every word of the result is a word of the template or lies inside a region of the layout.
     */
    static void assertNoTextOutside(byte[] pdf, Layout l) throws Exception {
        List<Redactor.Region> template;
        try (PDDocument doc = Loader.loadPDF(TestTemplates.letterhead(false))) {
            template = Redactor.findText(doc, Pattern.compile("\\S+"));
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (Redactor.Region word : Redactor.findText(doc, Pattern.compile("\\S+"))) {
                if (template.stream().anyMatch(t -> t.pageNb == word.pageNb && t.area.intersects(word.area)))
                    continue;
                boolean next = word.pageNb > 1;
                double cx = word.area.getCenterX(), cy = word.area.getCenterY();
                boolean inside = false;
                for (Element e : l.fields)
                    if (e.next == next && (e.rect.toPoints().contains(cx, cy)
                            || e.label != null && e.label.rect != null && e.label.rect.toPoints().contains(cx, cy)))
                        inside = true;
                Assertions.assertTrue(inside, "text outside the regions on page " + word.pageNb + " at " + word.area);
            }
        }
    }

    @Test
    void test_labelsInEnglish() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Result r = draw(l, Locale.ENGLISH, values());
        Assertions.assertTrue(textIn(r.pdf(), 1, fixed(l, "number", false).rect).contains("Invoice number"));
        Assertions.assertTrue(textIn(r.pdf(), 1, fixed(l, "issueDate", false).rect).contains("Invoice date"));
        Assertions.assertTrue(textIn(r.pdf(), 2, fixed(l, "page", true).rect).contains("Page 2 of 2"));
        // A language without translation prints English.
        r = draw(l, Locale.FRENCH, values());
        Assertions.assertTrue(textIn(r.pdf(), 1, fixed(l, "number", false).rect).contains("Invoice number"));
    }

    @Test
    void test_everyFieldHasDefaultLabels() {
        for (LayoutField f : LayoutField.values()) {
            String de = Overlay.defaultLabel(f.id, DE);
            String en = Overlay.defaultLabel(f.id, Locale.ENGLISH);
            Assertions.assertFalse(de.startsWith("["), f.id);
            Assertions.assertFalse(en.startsWith("["), f.id);
        }
    }

    @Test
    void test_labelOptions() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        fixed(l, "number", false).label = new Label();
        fixed(l, "number", false).label.text = "Rechnung Nr.";
        fixed(l, "issueDate", false).label = new Label(false);
        fixed(l, "buyer.address", false).label = new Label(true);
        Result r = draw(l, DE, values());
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
        String number = textIn(r.pdf(), 1, fixed(l, "number", false).rect);
        Assertions.assertTrue(number.contains("Rechnung Nr.") && !number.contains("Rechnungsnummer"), number);
        String date = textIn(r.pdf(), 1, fixed(l, "issueDate", false).rect);
        Assertions.assertTrue(date.contains("01.10.2024") && !date.contains("Rechnungsdatum"), date);
        // A block gets its label on a line of its own above the value.
        Rectangle2D label = find(r.pdf(), 1, "Empfänger");
        Rectangle2D first = find(r.pdf(), 1, "Banuke Testfirma AG");
        Assertions.assertTrue(label.getMaxY() <= first.getY() + 1, label + " " + first);
        Assertions.assertEquals(label.getX(), first.getX(), 1);
    }

    @Test
    void test_emptyValueHasNoLabel() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Map<String, String> v = values();
        v.remove("deliveryDate");
        v.put("buyerReference", "  ");
        Result r = draw(l, DE, v);
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
        Assertions.assertEquals("", textIn(r.pdf(), 1, fixed(l, "deliveryDate", false).rect).trim());
        Assertions.assertEquals("", textIn(r.pdf(), 1, fixed(l, "buyerReference", false).rect).trim());
    }

    @Test
    void test_movedLabel() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Element number = fixed(l, "number", false);
        number.label = new Label();
        number.label.rect = new Rect(90, 50, 34, 5);
        Result r = draw(l, DE, values());
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
        Assertions.assertEquals(Rect.toPoints(90), find(r.pdf(), 1, "Rechnungsnummer").getX(), 1);
        Assertions.assertFalse(textIn(r.pdf(), 1, number.rect).contains("Rechnungsnummer"));
        Assertions.assertEquals(Rect.toPoints(200), find(r.pdf(), 1, "T-2024-17").getMaxX(), 1);
        assertNoTextOutside(r.pdf(), l);

        number.label.rect = new Rect(90, 50, 15, 5);
        assertError(draw(l, DE, values()), "number", 1, "label");
    }

    /**
     * The default layout without the number on the following pages, so that a problem of the number is reported
     * once.
     */
    static Layout numberOnce() {
        Layout l = Layout.din5008(Layout.Form.B);
        l.fields.remove(fixed(l, "number", true));
        return l;
    }

    @Test
    void test_valueTooWide() throws Exception {
        Layout l = numberOnce();
        Map<String, String> v = values();
        v.put("number", "A-VERY-LONG-INVOICE-NUMBER-2024-0000017");
        assertError(draw(l, DE, v), "number", 1, "too wide");
        // The value alone would fit, but not with its label.
        v.put("number", "2024-0000000017-ABCDEF");
        assertError(draw(l, DE, v), "number", 1, "too wide");
        fixed(l, "number", false).label = new Label(false);
        Assertions.assertTrue(draw(l, DE, v).problems().isEmpty());
        // On a following page: reported with its page.
        v.put("number", "A-VERY-LONG-INVOICE-NUMBER-2024-0000017");
        l = Layout.din5008(Layout.Form.B);
        l.fields.remove(fixed(l, "number", false));
        assertError(draw(l, DE, v), "number", 2, "too wide");
    }

    @Test
    void test_blockTooHigh() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Map<String, String> v = values();
        // 27.3 mm hold 6 lines of 10 pt.
        v.put("buyer.address", "1\n2\n3\n4\n5\n6");
        Assertions.assertTrue(draw(l, DE, v).problems().isEmpty());
        v.put("buyer.address", "1\n2\n3\n4\n5\n6\n7");
        assertError(draw(l, DE, v), "buyer.address", 1, "needs 7 lines");
        // With the label one line less.
        v.put("buyer.address", "1\n2\n3\n4\n5\n6");
        fixed(l, "buyer.address", false).label = new Label(true);
        assertError(draw(l, DE, v), "buyer.address", 1, "needs 7 lines");
        // A one-line field with a font too high for its region.
        l = numberOnce();
        fixed(l, "number", false).size = 14f;
        fixed(l, "number", false).label = new Label(false);
        assertError(draw(l, DE, values()), "number", 1, "too high");
    }

    @Test
    void test_blockWraps() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Element text = new Element();
        text.type = Element.Type.TEXT;
        text.rect = new Rect(25, 200, 60, 40);
        text.text.put("de", "Wir danken für Ihren Auftrag und freuen uns auf die weitere Zusammenarbeit mit Ihnen.");
        text.text.put("en", "Thank you");
        text.align = Element.Align.CENTER;
        l.fields.add(text);
        Result r = draw(l, DE, values());
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
        String inside = textIn(r.pdf(), 1, text.rect);
        Assertions.assertTrue(inside.contains("Wir danken") && inside.contains("Zusammenarbeit"), inside);
        Assertions.assertTrue(inside.strip().lines().count() >= 2, inside);
        assertNoTextOutside(r.pdf(), l);
        // Centered.
        Rectangle2D first = find(r.pdf(), 1, "Wir danken");
        double center = Rect.toPoints(25 + 30);
        Assertions.assertTrue(first.getX() > Rect.toPoints(25) + 2, first.toString());
        Assertions.assertTrue(Math.abs(center - first.getCenterX()) < Rect.toPoints(30), first.toString());
        Assertions.assertTrue(textIn(draw(l, Locale.ENGLISH, values()).pdf(), 1, text.rect).contains("Thank you"));

        text.rect = new Rect(25, 200, 60, 8);
        assertError(draw(l, DE, values()), "text", 1, "lines");
    }

    @Test
    void test_unknownAndMisplacedFields() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        l.fields.add(Element.field("bankName", false, new Rect(25, 270, 50, 5)));
        assertError(draw(l, DE, values()), "bankName", 0, "Unknown");
        l = Layout.din5008(Layout.Form.B);
        l.fields.add(Element.field("lines", true, new Rect(25, 100, 165, 50)));
        assertError(draw(l, DE, values()), "lines", 0, "body");
    }

    @Test
    void test_missingGlyph() throws Exception {
        Layout l = numberOnce();
        Map<String, String> v = values();
        v.put("number", "T-17 漢");
        Result r = draw(l, DE, v);
        assertError(r, "number", 1, "U+6F22");
        Assertions.assertTrue(textIn(r.pdf(), 1, fixed(l, "number", false).rect).contains("T-17"));
    }

    @Test
    void test_unknownFont() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        l.fontName = "Comic Neue";
        Result r = draw(l, DE, values());
        assertError(r, null, 0, "Comic Neue");
        // Drawn anyway (with Arimo), so a preview shows it.
        Assertions.assertTrue(textIn(r.pdf(), 1, fixed(l, "number", false).rect).contains("T-2024-17"));
    }

    @Test
    void test_bundledAndImportedFonts() throws Exception {
        Path ttf = tmp.resolve("Hausschrift.ttf");
        try (InputStream is = getClass().getResourceAsStream("/fonts/Tinos-Regular.ttf")) {
            Files.copy(is, ttf);
        }
        List<ImportedFont> imported = List.of(new ImportedFont("Hausschrift", ttf));
        for (String font : List.of("Arimo", "Tinos", "Hausschrift")) {
            Layout l = Layout.din5008(Layout.Form.B);
            l.fontName = font;
            fixed(l, "number", false).bold = true;
            Result r = draw(TestTemplates.letterhead(false), l, DE, values(), PdfStandardProfile.PDFA_3U, imported);
            Assertions.assertTrue(r.problems().isEmpty(), font + ": " + r.problems());
            try (PDDocument doc = Loader.loadPDF(r.pdf())) {
                List<String> names = PdfFonts.list(doc).stream().map(PdfFonts.FontInfo::name).toList();
                String expected = font.equals("Hausschrift") ? "Tinos" : font;
                Assertions.assertTrue(names.stream().anyMatch(n -> n.contains(expected)), names.toString());
                if (!font.equals("Hausschrift"))
                    Assertions.assertTrue(names.stream().anyMatch(n -> n.contains(expected + "-Bold")), names.toString());
                Assertions.assertTrue(PdfFonts.list(doc).stream().allMatch(PdfFonts.FontInfo::embedded));
            }
            ValidationResult v = PdfaValidator.validate(r.pdf());
            Assertions.assertTrue(v.isConform(), font + ": " + v);
        }
    }

    /**
     * Fixed text and pictures with the fields, over a tagged template, in every level: valid PDF/A.
     */
    @Test
    void test_validInEveryLevel() throws Exception {
        Layout l = layoutWithPictures();
        for (PdfStandardProfile level : TemplatePreparer.LEVELS) {
            Result r = draw(TestTemplates.letterhead(true), l, DE, values(), level, List.of());
            Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
            ValidationResult v = PdfaValidator.validate(r.pdf());
            Assertions.assertTrue(v.isConform(), level + ": " + v);
        }
    }

    static Layout layoutWithPictures() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        l.putImage("signature", new LayoutImage(LayoutImage.PNG, TestTemplates.signaturePng()));
        l.putImage("stamp", new LayoutImage(LayoutImage.JPEG, TestTemplates.stampJpeg()));
        for (boolean next : new boolean[]{false, true}) {
            Element signature = new Element();
            signature.type = Element.Type.IMAGE;
            signature.image = "signature";
            signature.next = next;
            signature.rect = new Rect(25, 240, 50, 30);
            signature.align = Element.Align.RIGHT;
            l.fields.add(signature);
        }
        Element stamp = new Element();
        stamp.type = Element.Type.IMAGE;
        stamp.image = "stamp";
        stamp.rect = new Rect(140, 240, 30, 20);
        l.fields.add(stamp);
        Element text = new Element();
        text.type = Element.Type.TEXT;
        text.text.put("", "Mit freundlichen Grüßen");
        text.rect = new Rect(25, 230, 80, 5);
        l.fields.add(text);
        return l;
    }

    /**
     * The signature on both pages is one image object, in an ICCBased colour space; it keeps its aspect ratio.
     */
    @Test
    void test_picturesEmbeddedOnce() throws Exception {
        Result r = draw(layoutWithPictures(), DE, values());
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
        Set<COSBase> objects = new HashSet<>();
        int uses = 0;
        try (PDDocument doc = Loader.loadPDF(r.pdf())) {
            for (PDPage page : doc.getPages()) {
                var xobjects = page.getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT);
                for (COSName name : page.getResources().getXObjectNames()) {
                    // The signature (the logo of the template is DeviceRGB, allowed with its sRGB intent).
                    if (!(page.getResources().getXObject(name) instanceof PDImageXObject image)
                            || image.getWidth() != 200 || image.getHeight() != 80)
                        continue;
                    ++uses;
                    COSBase raw = xobjects.getItem(name);
                    objects.add(raw instanceof COSObject o ? o.getObject() : raw);
                    Assertions.assertInstanceOf(PDICCBased.class, image.getColorSpace());
                }
            }
        }
        Assertions.assertEquals(2, uses);
        Assertions.assertEquals(1, objects.size());
    }

    @Test
    void test_missingPicture() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Element image = new Element();
        image.type = Element.Type.IMAGE;
        image.image = "logo";
        image.rect = new Rect(25, 240, 50, 30);
        l.fields.add(image);
        assertError(draw(l, DE, values()), "image logo", 0, "missing");
        l.putImage("logo", new LayoutImage(LayoutImage.PNG, new byte[]{1, 2, 3}));
        assertError(draw(l, DE, values()), "image logo", 1, "can't be used");
    }

    /**
     * A print shop letterhead with a CMYK output intent: valid, because pictures use ICCBased sRGB. Needs a CMYK
     * profile on the machine.
     */
    @Test
    void test_cmykTemplate() throws Exception {
        Path profile = TestTemplates.cmykProfile();
        Assumptions.assumeTrue(profile != null, "No CMYK profile on this machine.");
        byte[] template = TestTemplates.cmykLetterhead(profile);
        for (PdfStandardProfile level : TemplatePreparer.LEVELS) {
            Result r = draw(template, layoutWithPictures(), DE, values(), level, List.of());
            ValidationResult v = PdfaValidator.validate(r.pdf());
            Assertions.assertTrue(v.isConform(), level + ": " + v);
        }
    }

    /**
     * The layout is on the displayed page, also if the page is rotated.
     */
    @Test
    void test_rotatedPage() throws Exception {
        for (int rotation : new int[]{90, 180, 270}) {
            Layout l = Layout.din5008(Layout.Form.B);
            Element number = fixed(l, "number", false);
            number.rect = new Rect(25, 20, 80, 5);
            number.align = Element.Align.LEFT;
            Result r = draw(TestTemplates.rotated(rotation), l, DE, values(), PdfStandardProfile.PDFA_3U, List.of());
            Assertions.assertTrue(r.problems().isEmpty(), rotation + ": " + r.problems());
            Rectangle2D value = find(r.pdf(), 1, "T-2024-17");
            Rectangle2D label = find(r.pdf(), 1, "Rechnungsnummer");
            Assertions.assertNotNull(value, String.valueOf(rotation));
            Rectangle2D region = number.rect.toPoints();
            Assertions.assertEquals(region.getX(), label.getX(), 1.5, rotation + ": " + label);
            Assertions.assertTrue(label.getMaxX() < value.getX(), rotation + ": " + label + " " + value);
            Assertions.assertTrue(region.contains(value.getCenterX(), value.getCenterY()), rotation + ": " + value);
            // The check uses the same coordinates: the region is inside the page and free of template text.
            Assertions.assertTrue(TemplateCheck.check(TestTemplates.rotated(rotation), l).stream()
                    .noneMatch(p -> "number".equals(p.element())), rotation + "");
        }
    }
}

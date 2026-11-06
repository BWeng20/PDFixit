package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.PdfaValidator;
import com.bw.pdfixit.ValidationResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

class TemplateEngineTest {

    @TempDir
    Path tmp;

    private static final Locale DE = Locale.GERMAN;

    static String item(int i) {
        return "Beratungsleistung Nr. " + i;
    }

    /**
     * Made-up content with the given number of items.
     */
    static InvoiceContent content(int items) {
        InvoiceContent c = new InvoiceContent();
        c.values.putAll(OverlayTest.values());
        c.values.put("note", "Leistungen gemäß Vertrag vom 01.09.2024.");
        c.values.put("paymentTerms", "Zahlbar innerhalb von 14 Tagen ohne Abzug.");
        c.values.put("seller.accountHolder", "Kelomi Testfirma GmbH");
        c.values.put("seller.iban", "DE71 3933 6053 0432 7394 89");
        c.values.put("seller.bic", "COBADEFFXXX");
        for (int i = 1; i <= items; ++i)
            c.addLine(String.valueOf(i), item(i), "8", "Std.", "95,00 €", "19 %", "760,00 €");
        c.totals.add(new InvoiceContent.TotalsRow("Nettobetrag", "1.520,00 €", false));
        c.totals.add(new InvoiceContent.TotalsRow("USt. 19 %", "288,80 €", false));
        c.totals.add(new InvoiceContent.TotalsRow("Gesamtbetrag", "1.808,80 €", true));
        return c;
    }

    /**
     * The default layout with a closing text and a signature in the body flow.
     */
    static Layout layout() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        Element closing = new Element();
        closing.type = Element.Type.TEXT;
        closing.text.put("de", "Mit freundlichen Grüßen");
        closing.text.put("en", "Kind regards");
        l.parts.add(closing);
        Element signature = new Element();
        signature.type = Element.Type.IMAGE;
        signature.image = "signature";
        signature.width = 40.0;
        l.parts.add(signature);
        l.putImage("signature", new LayoutImage(LayoutImage.PNG, TestTemplates.signaturePng()));
        return l;
    }

    static TemplateEngine.Result render(byte[] template, Layout l, InvoiceContent c, PdfStandardProfile level)
            throws Exception {
        return TemplateEngine.render(template, l, c, DE, level, List.of());
    }

    static TemplateEngine.Result render(Layout l, InvoiceContent c) throws Exception {
        return render(TestTemplates.letterhead(false), l, c, PdfStandardProfile.PDFA_3U);
    }

    static String pageText(byte[] pdf, int page) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setLineSeparator("\n");
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(doc);
        }
    }

    static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * The page a text is on, 0 if none; fails if it is on several pages.
     */
    static int pageOf(byte[] pdf, String text) throws Exception {
        int found = 0;
        for (int p = 1; p <= pages(pdf); ++p)
            if (pageText(pdf, p).contains(text)) {
                Assertions.assertEquals(0, found, text + " on pages " + found + " and " + p);
                found = p;
            }
        return found;
    }

    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1))
            ++n;
        return n;
    }

    static void assertNoProblems(TemplateEngine.Result r) {
        Assertions.assertTrue(r.problems().isEmpty(), r.problems().toString());
        Assertions.assertFalse(r.choiceNeeded());
    }

    @Test
    void test_onePage() throws Exception {
        TemplateEngine.Result r = render(layout(), content(3));
        assertNoProblems(r);
        Assertions.assertEquals(1, r.pageCount());
        Assertions.assertEquals(1, pages(r.pdf()), "page 2 of the template is dropped");
        String text = pageText(r.pdf(), 1);
        for (String s : List.of("Bezeichnung", "Einheit", "USt.", "Std.", "19 %", "Einzelpreis", item(3), "Gesamtbetrag", "1.808,80 €", "Hinweis",
                "Leistungen gemäß Vertrag", "Zahlungsbedingungen", "IBAN", "DE71 3933 6053 0432 7394 89",
                "Mit freundlichen Grüßen", "T-2024-17"))
            Assertions.assertTrue(text.contains(s), s + " in\n" + text);
        // The order of the parts.
        Assertions.assertTrue(text.indexOf("Leistungen gemäß") < text.indexOf(item(1)));
        Assertions.assertTrue(text.indexOf(item(3)) < text.indexOf("Gesamtbetrag"));
        Assertions.assertTrue(text.indexOf("Gesamtbetrag") < text.indexOf("Zahlbar innerhalb"));
        Assertions.assertTrue(text.indexOf("COBADEFFXXX") < text.indexOf("Mit freundlichen Grüßen"));
        // Inside the body region.
        Rectangle2D last = OverlayTest.find(r.pdf(), 1, "Mit freundlichen Grüßen");
        Rect body = layout().region(false).rect();
        Assertions.assertTrue(body.toPoints().contains(last), last.toString());
        Assertions.assertEquals(Rect.toPoints(body.x()), OverlayTest.find(r.pdf(), 1, item(1)).getX(),
                Rect.toPoints(BodyFlow.COLUMN_WIDTHS.get(Layout.Column.POS)) + 1);
    }

    @Test
    void test_manyItems() throws Exception {
        int previous = 0;
        for (int items : new int[]{10, 60, 150}) {
            TemplateEngine.Result r = render(layout(), content(items));
            assertNoProblems(r);
            int n = pages(r.pdf());
            Assertions.assertEquals(r.pageCount(), n);
            Assertions.assertTrue(n > previous, items + " items: " + n + " pages");
            previous = n;
            // Every item exactly once and in order.
            int lastPage = 1;
            for (int i = 1; i <= items; ++i) {
                int p = pageOf(r.pdf(), item(i) + " ");
                Assertions.assertTrue(p >= lastPage, item(i));
                lastPage = p;
            }
            for (int p = 1; p <= n; ++p) {
                String text = pageText(r.pdf(), p);
                // The table header on every page with items.
                if (text.contains("Beratungsleistung"))
                    Assertions.assertEquals(1, count(text, "Bezeichnung"), "page " + p);
                if (p > 1) {
                    Assertions.assertTrue(text.contains("Seite " + p + " von " + n), text);
                    Assertions.assertTrue(text.contains("T-2024-17"), text);
                }
            }
        }
    }

    /**
     * Whatever the number of items: the totals stand with the last row, and everything after them (payment terms,
     * bank details, closing text, signature) stands with the totals.
     */
    @Test
    void test_totalsNeverAlone() throws Exception {
        Layout l = layout();
        for (int items = 14; items <= 44; ++items) {
            TemplateEngine.Result r = render(l, content(items));
            assertNoProblems(r);
            int totals = pageOf(r.pdf(), "Gesamtbetrag");
            Assertions.assertEquals(pageOf(r.pdf(), item(items) + " "), totals, items + " items");
            Assertions.assertEquals(totals, pageOf(r.pdf(), "COBADEFFXXX"), items + " items");
            Assertions.assertEquals(totals, pageOf(r.pdf(), "Mit freundlichen Grüßen"), items + " items");
            Assertions.assertEquals(totals, r.pageCount(), items + " items");
            // The signature is on the last page, below the closing text.
            try (PDDocument doc = Loader.loadPDF(r.pdf())) {
                Assertions.assertTrue(hasSignature(doc.getPage(totals - 1)), items + " items");
            }
        }
    }

    static boolean hasSignature(PDPage page) throws Exception {
        for (COSName name : page.getResources().getXObjectNames())
            if (page.getResources().getXObject(name) instanceof PDImageXObject image
                    && image.getWidth() == 200 && image.getHeight() == 80)
                return true;
        return false;
    }

    @Test
    void test_singleLineFieldsShareAColumn() throws Exception {
        TemplateEngine.Result r = render(layout(), content(3));
        double holder = OverlayTest.find(r.pdf(), 1, "Kelomi Testfirma GmbH\u0000".replace("\u0000", "")).getX();
        double iban = OverlayTest.find(r.pdf(), 1, "DE71 3933").getX();
        double bic = OverlayTest.find(r.pdf(), 1, "COBADEFFXXX").getX();
        Assertions.assertEquals(iban, bic, 0.5);
        // Behind the widest label (Kontoinhaber).
        double label = OverlayTest.find(r.pdf(), 1, "Kontoinhaber").getMaxX();
        Assertions.assertTrue(iban > label, iban + " " + label);
        Assertions.assertTrue(holder > 0);
    }

    @Test
    void test_labelAboveAndOffset() throws Exception {
        Layout l = layout();
        Element iban = l.parts.stream().filter(e -> "seller.iban".equals(e.id)).findFirst().orElseThrow();
        iban.label = new Label();
        iban.label.position = Label.Position.ABOVE;
        iban.label.dx = 10;
        TemplateEngine.Result r = render(l, content(3));
        assertNoProblems(r);
        Rectangle2D label = OverlayTest.find(r.pdf(), 1, "IBAN");
        Rectangle2D value = OverlayTest.find(r.pdf(), 1, "DE71 3933");
        Assertions.assertTrue(label.getMaxY() <= value.getY() + 1, label + " " + value);
        double left = Rect.toPoints(l.region(false).rect().x());
        Assertions.assertEquals(left + Rect.toPoints(10), label.getX(), 1);
        Assertions.assertEquals(left, value.getX(), 1);
    }

    @Test
    void test_deletedLabels() throws Exception {
        Layout l = layout();
        for (Element e : l.parts)
            if (e.type == Element.Type.FIELD)
                e.label = new Label(false);
        TemplateEngine.Result r = render(l, content(3));
        assertNoProblems(r);
        String text = pageText(r.pdf(), 1);
        for (String s : List.of("Bezeichnung", "Gesamtbetrag", "Nettobetrag", "Hinweis", "IBAN", "Kontoinhaber",
                "Zahlungsbedingungen"))
            Assertions.assertFalse(text.contains(s), s);
        for (String s : List.of(item(1), "1.808,80 €", "DE71 3933", "Leistungen gemäß"))
            Assertions.assertTrue(text.contains(s), s);
        // The header row also switches off by the table option.
        l = layout();
        l.tableHeader = false;
        text = pageText(render(l, content(3)).pdf(), 1);
        Assertions.assertFalse(text.contains("Bezeichnung"));
        Assertions.assertTrue(text.contains("Gesamtbetrag"));
    }

    @Test
    void test_englishAndEmptyParts() throws Exception {
        InvoiceContent c = content(2);
        c.values.remove("note");
        c.values.put("paymentTerms", " ");
        TemplateEngine.Result r = TemplateEngine.render(TestTemplates.letterhead(false), layout(), c, Locale.ENGLISH,
                PdfStandardProfile.PDFA_3U, List.of());
        assertNoProblems(r);
        String text = pageText(r.pdf(), 1);
        Assertions.assertTrue(text.contains("Kind regards") && text.contains("Description") && text.contains("Invoice number"));
        Assertions.assertFalse(text.contains("Note") || text.contains("Payment terms"), text);
    }

    @Test
    void test_longNoteSplitsWithItsLabel() throws Exception {
        InvoiceContent c = content(2);
        StringBuilder note = new StringBuilder();
        for (int i = 1; i <= 90; ++i)
            note.append("Notizzeile ").append(i).append('\n');
        c.values.put("note", note.toString());
        TemplateEngine.Result r = render(layout(), c);
        assertNoProblems(r);
        Assertions.assertTrue(r.pageCount() >= 2);
        Assertions.assertEquals(pageOf(r.pdf(), "Hinweis"), pageOf(r.pdf(), "Notizzeile 1\n"));
        Assertions.assertTrue(pageOf(r.pdf(), "Notizzeile 90\n") > 1);
    }

    // ------------------------------------------------------------------------------------------- continuation

    @Test
    void test_continuationSources() throws Exception {
        Path other = tmp.resolve("folgeseite.pdf");
        Files.write(other, TestTemplates.continuationPdf());
        byte[] template = TestTemplates.letterhead(false);
        for (Layout.Continuation source : Layout.Continuation.values()) {
            Layout l = layout();
            l.continuation = source;
            l.continuationFile = other;
            TemplateEngine.Result r = render(template, l, content(40), PdfStandardProfile.PDFA_3U);
            assertNoProblems(r);
            Assertions.assertTrue(r.pageCount() >= 2, source.toString());
            for (int p = 2; p <= r.pageCount(); ++p) {
                String text = pageText(r.pdf(), p);
                // Fixed fields of the first page are never on a following page.
                Assertions.assertFalse(text.contains("Rechnungsdatum"), source + " page " + p);
                Assertions.assertTrue(text.contains("Seite " + p), source + " page " + p);
                Assertions.assertEquals(source == Layout.Continuation.PAGE1, text.contains("Tel. +49 123 456789"),
                        source + ": sender block of page 1");
                Assertions.assertEquals(source == Layout.Continuation.PDF, text.contains("Kelomi - Fortsetzung"),
                        source + ": the other PDF");
                Assertions.assertEquals(source == Layout.Continuation.TEMPLATE || source == Layout.Continuation.PAGE1,
                        text.contains("www.example.com"),
                        source + ": footer");
            }
            Assertions.assertTrue(pageText(r.pdf(), 1).contains("Tel. +49 123 456789"));
            Assertions.assertFalse(pageText(r.pdf(), 1).contains("Kelomi - Fortsetzung"));
        }
    }

    @Test
    void test_derivedRegion() throws Exception {
        Layout l = layout();
        l.regions.removeIf(Region::next);
        Rect first = l.region(false).rect();
        Assertions.assertEquals(new Rect(first.x(), 25, first.width(), first.bottom() - 25),
                TemplateEngine.nextRegion(l, Layout.Continuation.TEMPLATE));
        Assertions.assertEquals(first, TemplateEngine.nextRegion(l, Layout.Continuation.PAGE1));
        Assertions.assertEquals(new Rect(25, 35, 165, 230),
                TemplateEngine.nextRegion(layout(), Layout.Continuation.PAGE1), "the region of the layout");

        // The first row on page 2 starts below the top of the derived region.
        TemplateEngine.Result r = render(l, content(40));
        assertNoProblems(r);
        String page2 = pageText(r.pdf(), 2);
        int firstOnPage2 = 0;
        for (int i = 1; i <= 40 && firstOnPage2 == 0; ++i)
            if (page2.contains(item(i) + " "))
                firstOnPage2 = i;
        Rectangle2D row = OverlayTest.find(r.pdf(), 2, item(firstOnPage2));
        Rectangle2D header = OverlayTest.find(r.pdf(), 2, "Bezeichnung");
        Assertions.assertTrue(header.getY() >= Rect.toPoints(25) - 1 && header.getY() < Rect.toPoints(35), header.toString());
        Assertions.assertTrue(row.getY() > header.getMaxY());

        // Page 1 again: the derived region is the one of page 1.
        l.continuation = Layout.Continuation.PAGE1;
        r = render(l, content(40));
        header = OverlayTest.find(r.pdf(), 2, "Bezeichnung");
        Assertions.assertTrue(header.getY() >= Rect.toPoints(first.y()) - 1, header.toString());
    }

    @Test
    void test_choiceNeeded() throws Exception {
        byte[] onePage = TestTemplates.onePageLetterhead();
        Layout l = layout();
        // Fits on one page: no choice needed.
        TemplateEngine.Result r = render(onePage, l, content(3), PdfStandardProfile.PDFA_3U);
        assertNoProblems(r);
        r = render(onePage, l, content(40), PdfStandardProfile.PDFA_3U);
        Assertions.assertTrue(r.choiceNeeded());
        Assertions.assertTrue(r.hasErrors());
        Assertions.assertNull(r.pdf());
        Assertions.assertTrue(r.pageCount() > 1);
        // Page 2 of the template chosen, but there is none.
        l.continuation = Layout.Continuation.TEMPLATE;
        Assertions.assertTrue(render(onePage, l, content(40), PdfStandardProfile.PDFA_3U).choiceNeeded());
        // The chosen PDF is missing.
        l.continuation = Layout.Continuation.PDF;
        l.continuationFile = tmp.resolve("missing.pdf");
        Assertions.assertTrue(render(onePage, l, content(40), PdfStandardProfile.PDFA_3U).choiceNeeded());
        // A choice.
        l.continuation = Layout.Continuation.BLANK;
        assertNoProblems(render(onePage, l, content(40), PdfStandardProfile.PDFA_3U));
    }

    // ----------------------------------------------------------------------------------------------- PDF/A

    /**
     * Several pages over a tagged template with links, in every level: valid; the copies share the template images and
     * the signature, links are copied with their page, structure only with level a and one key per page.
     */
    @Test
    void test_validInEveryLevel() throws Exception {
        Path other = tmp.resolve("folgeseite.pdf");
        Files.write(other, TestTemplates.continuationPdf());
        for (Layout.Continuation source : List.of(Layout.Continuation.TEMPLATE, Layout.Continuation.PAGE1,
                Layout.Continuation.PDF))
            for (PdfStandardProfile level : TemplatePreparer.LEVELS) {
                String what = source + " " + level;
                Layout l = OverlayTest.layoutWithPictures();
                l.parts.addAll(layout().parts.subList(Layout.DEFAULT_PARTS.size(), layout().parts.size()));
                l.images.putAll(layout().images);
                l.continuation = source;
                l.continuationFile = other;
                TemplateEngine.Result r = render(TestTemplates.letterhead(true), l, content(90), level);
                assertNoProblems(r);
                Assertions.assertTrue(r.pageCount() >= 3, what);
                ValidationResult v = PdfaValidator.validate(r.pdf());
                Assertions.assertTrue(v.isConform(), what + ": " + v);
                try (PDDocument doc = Loader.loadPDF(r.pdf())) {
                    Assertions.assertEquals(level, PdfStandard.profile(doc), what);
                    Assertions.assertEquals(level == PdfStandardProfile.PDFA_3A, PdfTagger.isTagged(doc), what);
                    Set<Integer> keys = new HashSet<>();
                    Set<COSBase> images = new HashSet<>();
                    int links = 0;
                    for (PDPage page : doc.getPages()) {
                        if (page.getCOSObject().containsKey(COSName.STRUCT_PARENTS))
                            Assertions.assertTrue(keys.add(page.getCOSObject().getInt(COSName.STRUCT_PARENTS)), what);
                        var xobjects = page.getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT);
                        if (xobjects != null)
                            for (COSName name : xobjects.keySet()) {
                                COSBase raw = xobjects.getItem(name);
                                if (page.getResources().getXObject(name) instanceof PDImageXObject)
                                    images.add(raw instanceof COSObject o ? o.getObject() : raw);
                            }
                        for (PDAnnotation a : page.getAnnotations()) {
                            ++links;
                            Object target = a.getCOSObject().getDictionaryObject(COSName.P);
                            int index = -1;
                            for (int k = 0; k < doc.getNumberOfPages(); ++k)
                                if (doc.getPage(k).getCOSObject() == target)
                                    index = k;
                            // /P is optional (the original links have none); a copy points to its own page.
                            Assertions.assertTrue(target == null || target == page.getCOSObject(), what
                                    + ": annotation of page " + doc.getPages().indexOf(page) + " points to page " + index);
                        }
                    }
                    Assertions.assertEquals(level == PdfStandardProfile.PDFA_3A ? doc.getNumberOfPages() : 0,
                            keys.size(), what);
                    // Logo of the template, signature, stamp: each stored once.
                    Assertions.assertEquals(3, images.size(), what);
                    // Links: page 1 and every page copied from a template page.
                    int expected = source == Layout.Continuation.PDF ? 1 : doc.getNumberOfPages();
                    Assertions.assertEquals(expected, links, what);
                }
            }
    }

    // ---------------------------------------------------------------------------------------------- problems

    @Test
    void test_problemsWithPage() throws Exception {
        // A total that doesn't fit its column, on the second page.
        InvoiceContent c = content(40);
        c.lines.get(39).put(Layout.Column.TOTAL, "1.234.567.890,00 €");
        TemplateEngine.Result r = render(layout(), c);
        List<Problem> errors = new ArrayList<>(r.problems());
        Assertions.assertEquals(1, errors.size(), errors.toString());
        Assertions.assertEquals("lines", errors.get(0).element());
        Assertions.assertEquals(r.pageCount(), errors.get(0).page());
        Assertions.assertTrue(errors.get(0).message().contains("too wide"), errors.get(0).message());
        Assertions.assertNotNull(r.pdf(), "drawn anyway");

        // A picture higher than the region.
        Layout l = layout();
        l.parts.get(l.parts.size() - 1).width = 160.0;
        Layout tall = l;
        tall.images.put("signature", new LayoutImage(LayoutImage.PNG, TestTemplates.signaturePng()));
        Element image = tall.parts.get(tall.parts.size() - 1);
        image.width = 160.0;
        // 200 x 80 pixels at 160 mm: 64 mm high, fits. Make the region small instead.
        tall.regions.replaceAll(reg -> new Region(reg.next(), new Rect(reg.rect().x(), reg.rect().y(), reg.rect().width(), 50)));
        r = render(tall, content(1));
        Assertions.assertTrue(r.problems().stream().anyMatch(p -> p.message().contains("higher than the body region")
                && "image signature".equals(p.element())), r.problems().toString());

        // Too many columns for a narrow region.
        l = layout();
        l.columns.clear();
        l.columns.addAll(List.of(Layout.Column.values()));
        l.regions.replaceAll(reg -> new Region(reg.next(), new Rect(reg.rect().x(), reg.rect().y(), 120, reg.rect().height())));
        r = render(l, content(3));
        Assertions.assertTrue(r.problems().stream().anyMatch(p -> p.message().contains("don't fit the width")),
                r.problems().toString());
    }

    /**
     * The engine tells where it drew labels and values; the areas hold the drawn texts.
     */
    @Test
    void test_placements() throws Exception {
        Layout l = layout();
        TemplateEngine.Result r = render(l, content(40));
        assertNoProblems(r);
        int number = l.fields.indexOf(l.fields.stream().filter(e -> "number".equals(e.id) && !e.next).findFirst().orElseThrow());
        int iban = l.parts.indexOf(l.parts.stream().filter(e -> "seller.iban".equals(e.id)).findFirst().orElseThrow());
        int signature = l.parts.size() - 1;
        assertPlaced(r, true, number, true, 1, "Rechnungsnummer");
        assertPlaced(r, true, number, false, 1, "T-2024-17");
        int last = r.pageCount();
        assertPlaced(r, false, iban, true, last, "IBAN");
        assertPlaced(r, false, iban, false, last, "DE71 3933");
        Placement picture = r.placements().stream().filter(p -> !p.fixed() && p.index() == signature).findFirst().orElseThrow();
        Assertions.assertEquals(last, picture.page());
        Assertions.assertEquals(Rect.toPoints(40), picture.area().getWidth(), 0.1);
        // The table header on every page.
        int lines = Layout.DEFAULT_PARTS.indexOf("lines");
        Assertions.assertEquals(r.pageCount(), r.placements().stream().filter(p -> !p.fixed() && p.index() == lines && p.label()).count());
    }

    static void assertPlaced(TemplateEngine.Result r, boolean fixed, int index, boolean label, int page, String text)
            throws Exception {
        Placement placement = r.placements().stream().filter(p -> p.fixed() == fixed && p.index() == index
                && p.label() == label && p.page() == page).findFirst().orElseThrow(() -> new AssertionError(text));
        Rectangle2D found = OverlayTest.find(r.pdf(), page, text);
        Rectangle2D area = placement.area();
        Rectangle2D grown = new Rectangle2D.Double(area.getX() - 1, area.getY() - 1, area.getWidth() + 2, area.getHeight() + 2);
        Assertions.assertTrue(grown.contains(found.getCenterX(), found.getCenterY()), text + ": " + area + " " + found);
        Assertions.assertEquals(found.getX(), area.getX(), 1.5, text);
    }

    @Test
    void test_noBodyRegion() throws Exception {
        Layout l = layout();
        l.regions.clear();
        TemplateEngine.Result r = render(l, content(3));
        Assertions.assertEquals(1, r.problems().size(), r.problems().toString());
        Assertions.assertTrue(r.problems().get(0).message().contains("no body region"));
        Assertions.assertTrue(pageText(r.pdf(), 1).contains("T-2024-17"));
    }
}

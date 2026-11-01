package com.bw.pdfixit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

class RedactorTest {

    private static PDDocument createDocument() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, 700);
            cs.showText("Public text and secret code 4711 here");
            cs.endText();
        }
        return doc;
    }

    private static String text(PDDocument doc) throws IOException {
        return new PDFTextStripper().getText(doc);
    }

    @Test
    void test_findText() throws IOException {
        try (PDDocument doc = createDocument()) {
            List<Redactor.Region> regions = Redactor.findText(doc, Pattern.compile("secret code \\d+"));
            Assertions.assertEquals(1, regions.size());
            Rectangle2D r = regions.get(0).area;
            Assertions.assertEquals(1, regions.get(0).pageNb);
            // y is measured from the top: 792 - 700 = 92 for the baseline
            Assertions.assertTrue(r.getMinY() < 92 && r.getMaxY() > 92, r.toString());
            Assertions.assertTrue(r.getMinX() > 72, r.toString());
            Assertions.assertEquals(0, Redactor.findText(doc, Pattern.compile("nothing")).size());
        }
    }

    @Test
    void test_redactText() throws IOException {
        try (PDDocument doc = createDocument()) {
            Assertions.assertTrue(text(doc).contains("4711"));

            Redactor.redact(doc, Redactor.findText(doc, Pattern.compile("secret code \\d+")), 72);

            Assertions.assertEquals(1, doc.getNumberOfPages());
            // The page is an image now: no text at all, not even the public one.
            Assertions.assertFalse(text(doc).contains("4711"));
            Assertions.assertFalse(text(doc).contains("Public"));
        }
    }

    @Test
    void test_redactArea() throws IOException {
        try (PDDocument doc = createDocument()) {
            Redactor.redact(doc, List.of(new Redactor.Region(1, new Rectangle2D.Double(0, 0, 100, 100))), 72);
            Assertions.assertEquals(1, doc.getNumberOfPages());
            Assertions.assertTrue(text(doc).isBlank());
        }
    }

    @Test
    void test_pageOutOfRange() throws IOException {
        try (PDDocument doc = createDocument()) {
            Assertions.assertThrows(IllegalArgumentException.class, () ->
                    Redactor.redact(doc, List.of(new Redactor.Region(2, new Rectangle2D.Double(0, 0, 1, 1))), 72));
        }
    }

    @Test
    void test_viewToPageRoundTrip() {
        PDRectangle box = new PDRectangle(200, 300);
        Rectangle2D page = new Rectangle2D.Double(10, 20, 30, 40);
        for (int rotation : new int[]{0, 90, 180, 270}) {
            Rectangle2D view = Redactor.pageToView(page, box, rotation);
            Assertions.assertEquals(page, Redactor.viewToPage(view, box, rotation), "rotation " + rotation);
        }
        // Rotated by 90° clockwise, the upper left corner of the page is the upper right corner of the view.
        Rectangle2D view = Redactor.pageToView(new Rectangle2D.Double(0, 0, 10, 10), box, 90);
        Assertions.assertEquals(new Rectangle2D.Double(290, 0, 10, 10), view);
    }

    @Test
    void test_parseArea() {
        Redactor.Region r = Main.parseRedactArea("2:10,20.5,30,40");
        Assertions.assertEquals(2, r.pageNb);
        Assertions.assertEquals(new Rectangle2D.Double(10, 20.5, 30, 40), r.area);
        Assertions.assertThrows(IllegalArgumentException.class, () -> Main.parseRedactArea("2:1,2,3"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Main.parseRedactArea("x:1,2,3,4"));
    }
}

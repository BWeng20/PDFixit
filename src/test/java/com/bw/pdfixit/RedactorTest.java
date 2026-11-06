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

import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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

    /**
     * A page that is black everywhere.
     */
    private static PDDocument createBlackDocument() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(new PDRectangle(300, 300));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(Color.BLACK);
            cs.addRect(0, 0, 300, 300);
            cs.fill();
        }
        return doc;
    }

    private static boolean isWhite(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) & 0xff) >= 200;
    }

    @Test
    void test_rotatedAreaCoversEveryTouchedPixel() throws IOException {
        Redactor.Region region = new Redactor.Region(1, new Rectangle2D.Double(80, 120, 140, 30), Math.toRadians(30));
        try (PDDocument doc = createBlackDocument()) {
            Redactor.redact(doc, List.of(region), 72, Color.WHITE);
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Shape shape = region.getShape();

            int covered = 0;
            for (int y = 0; y < image.getHeight(); ++y) {
                for (int x = 0; x < image.getWidth(); ++x) {
                    if (shape.intersects(x, y, 1, 1)) {
                        Assertions.assertTrue(isWhite(image, x, y), "pixel " + x + "," + y + " is touched but not covered");
                        ++covered;
                    } else if (!shape.getBounds2D().createIntersection(new Rectangle2D.Double(x - 3, y - 3, 7, 7)).isEmpty()) {
                        continue;
                    } else {
                        // Far away from the area: nothing changed.
                        Assertions.assertFalse(isWhite(image, x, y), "pixel " + x + "," + y + " is covered");
                    }
                }
            }
            Assertions.assertTrue(covered > 3000, "area is about 140 x 30 pixels");
            // A rotated rectangle is not its bounding box: a corner of the box stays untouched.
            Rectangle2D box = shape.getBounds2D();
            Assertions.assertFalse(isWhite(image, (int) box.getMinX() + 2, (int) box.getMinY() + 2));
        }
    }

    @Test
    void test_areaMayLeaveThePage() throws IOException {
        try (PDDocument doc = createBlackDocument()) {
            Redactor.redact(doc, List.of(
                    new Redactor.Region(1, new Rectangle2D.Double(250, 250, 200, 100), Math.toRadians(45)),
                    new Redactor.Region(1, new Rectangle2D.Double(-500, -500, 10, 10), Math.toRadians(10))), 72, Color.WHITE);
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Assertions.assertEquals(300, image.getWidth());
            Assertions.assertTrue(isWhite(image, 299, 299));
            Assertions.assertFalse(isWhite(image, 10, 10));
        }
    }

    @Test
    void test_rotatedAreaOnRotatedPage() {
        PDRectangle box = new PDRectangle(200, 300);
        for (int rotation : new int[]{0, 90, 180, 270}) {
            double angle = Math.toRadians(20);

            // The same area seen on the rotated page, with the same angle: its corners must be the corners
            // of the page area, turned like the page.
            Redactor.Region page = new Redactor.Region(1, new Rectangle2D.Double(10, 20, 60, 30), angle);
            Rectangle2D viewRect = Redactor.pageToView(page.area, box, rotation);
            Shape view = new Redactor.Region(1, viewRect, angle).getShape();

            List<Point2D> expected = new ArrayList<>();
            for (Point2D p : corners(page.getShape()))
                expected.add(pageToViewPoint(p, box, rotation));
            for (Point2D e : expected)
                Assertions.assertTrue(corners(view).stream().anyMatch(c -> c.distance(e) < 1e-9),
                        "rotation " + rotation + " corner " + e);
        }
    }

    private static List<Point2D> corners(Shape shape) {
        List<Point2D> result = new ArrayList<>();
        double[] c = new double[6];
        for (var it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            if (it.currentSegment(c) != java.awt.geom.PathIterator.SEG_CLOSE)
                result.add(new Point2D.Double(c[0], c[1]));
        }
        // A closed rectangle path repeats its first corner.
        if (result.size() == 5)
            result.remove(4);
        return result;
    }

    /**
     * The turn of a point, like the page is turned clockwise for the display.
     */
    private static Point2D pageToViewPoint(Point2D p, PDRectangle box, int rotation) {
        return switch (rotation) {
            case 90 -> new Point2D.Double(box.getHeight() - p.getY(), p.getX());
            case 180 -> new Point2D.Double(box.getWidth() - p.getX(), box.getHeight() - p.getY());
            case 270 -> new Point2D.Double(p.getY(), box.getWidth() - p.getX());
            default -> p;
        };
    }

    @Test
    void test_antialiasedEdgeIsSmooth() throws IOException {
        Redactor.Region region = new Redactor.Region(1, new Rectangle2D.Double(80, 120, 140, 30), Math.toRadians(30));
        try (PDDocument doc = createBlackDocument()) {
            Redactor.redact(doc, List.of(region), 72, Color.WHITE, true);
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Assertions.assertTrue(isWhite(image, 150, 135), "the middle is covered");
            Assertions.assertFalse(isWhite(image, 10, 10), "far away nothing changed");
            // On the slanted edges some pixels are only partly covered: neither black nor white.
            int grey = 0;
            for (int y = 0; y < image.getHeight(); ++y) {
                for (int x = 0; x < image.getWidth(); ++x) {
                    int v = image.getRGB(x, y) & 0xff;
                    if (v > 40 && v < 215)
                        ++grey;
                }
            }
            Assertions.assertTrue(grey > 20, "smooth edge, grey pixels: " + grey);
        }
    }
}

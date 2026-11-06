package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Synthetic letterheads for the tests, made with PDFBox. Never use a real template in tests.
 */
public final class TestTemplates {

    /**
     * Where the sender block of {@link #letterhead} stands: x, y, width, height in mm.
     */
    public static final Rect SENDER = new Rect(140, 30, 50, 15);

    private TestTemplates() {
    }

    public static float mm(double v) {
        return (float) Rect.toPoints(v);
    }

    /**
     * y from the top in mm to PDF y.
     */
    public static float top(PDPage page, double mm) {
        return page.getMediaBox().getHeight() - mm(mm);
    }

    /**
     * An A4 letterhead with two pages: page 1 with a logo, the sender block (an embedded simple TrueType font, like
     * Word writes it) in {@link #SENDER}, fold marks and a footer (Helvetica, not embedded) with a link; page 2 with a
     * small header at the top left and the footer.
     *
     * @param tagged True to give it the formal structure of {@link PdfTagger}.
     */
    public static byte[] letterhead(boolean tagged) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage p1 = new PDPage(PDRectangle.A4);
            PDPage p2 = new PDPage(PDRectangle.A4);
            doc.addPage(p1);
            doc.addPage(p2);
            PDFont helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont tinos;
            try (InputStream is = TestTemplates.class.getResourceAsStream("/fonts/Tinos-Regular.ttf")) {
                tinos = PDTrueTypeFont.load(doc, is, WinAnsiEncoding.INSTANCE);
            }
            PDImageXObject logo = LosslessFactory.createFromImage(doc, logo());
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.drawImage(logo, mm(140), top(p1, 12 + 16), mm(50), mm(16));
                cs.beginText();
                cs.setFont(tinos, 8);
                cs.newLineAtOffset(mm(SENDER.x()), top(p1, SENDER.y() + 3));
                for (String s : new String[]{"Kelomi Testfirma GmbH", "Rasoweg 12", "12345 Vizostadt", "Tel. +49 123 456789"}) {
                    cs.showText(s);
                    cs.newLineAtOffset(0, -10);
                }
                cs.endText();
                cs.setStrokingColor(0.6f);
                for (double y : new double[]{105, 148.5, 210}) {
                    cs.moveTo(mm(4), top(p1, y));
                    cs.lineTo(mm(9), top(p1, y));
                }
                cs.stroke();
                footer(cs, helvetica);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                cs.beginText();
                cs.setFont(tinos, 9);
                cs.newLineAtOffset(mm(20), top(p2, 12));
                cs.showText("Kelomi Testfirma GmbH");
                cs.endText();
                footer(cs, helvetica);
            }
            link(p1);
            link(p2);
            if (tagged)
                PdfTagger.tagPages(doc);
            return save(doc);
        }
    }

    /**
     * The first page of {@link #letterhead} alone.
     */
    public static byte[] onePageLetterhead() throws IOException {
        try (PDDocument doc = Loader.loadPDF(letterhead(false))) {
            doc.removePage(1);
            return save(doc);
        }
    }

    /**
     * The letterhead converted to a PDF/A level (from the untagged one; level a gets the formal structure).
     */
    public static byte[] letterhead(PdfStandardProfile level) throws IOException {
        try (PDDocument doc = Loader.loadPDF(letterhead(false))) {
            PdfStandard.convertTo(doc, level);
            return save(doc);
        }
    }

    /**
     * A PDF for the following pages: a header line in Helvetica Bold (not embedded) at 15 mm from the top.
     */
    public static byte[] continuationPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 10);
                cs.newLineAtOffset(mm(20), top(page, 15));
                cs.showText("Kelomi - Fortsetzung");
                cs.endText();
            }
            return save(doc);
        }
    }

    /**
     * The letterhead encrypted.
     *
     * @param userPassword The password needed to open it, empty for none (only an owner password).
     */
    public static byte[] encrypted(String userPassword) throws IOException {
        try (PDDocument doc = Loader.loadPDF(letterhead(false))) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os);
            return os.toByteArray();
        }
    }

    /**
     * A one-page letterhead with a font that can't be embedded (ZapfDingbats has no substitute).
     */
    public static byte[] withDingbats() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS), 12);
                cs.newLineAtOffset(mm(20), top(page, 280));
                cs.showText("✁✂");
                cs.endText();
            }
            return save(doc);
        }
    }

    /**
     * A CMYK ICC profile of the machine (Windows), null if there is none. No profile is bundled with the tests.
     */
    public static java.nio.file.Path cmykProfile() {
        java.nio.file.Path p = java.nio.file.Path.of("C:/Windows/System32/spool/drivers/color/RSWOP.icm");
        return java.nio.file.Files.isRegularFile(p) ? p : null;
    }

    /**
     * A one-page PDF/A-3b letterhead with a CMYK output intent (as from a print shop), only grey and CMYK colours.
     */
    public static byte[] cmykLetterhead(java.nio.file.Path profile) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont tinos;
            try (InputStream is = TestTemplates.class.getResourceAsStream("/fonts/Tinos-Regular.ttf")) {
                tinos = PDTrueTypeFont.load(doc, is, WinAnsiEncoding.INSTANCE);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(tinos, 14);
                cs.setNonStrokingColor(0.8f, 0.1f, 0.6f, 0.1f);
                cs.newLineAtOffset(mm(140), top(page, 20));
                cs.showText("Kelomi");
                cs.endText();
                cs.setStrokingColor(0f, 0.5f, 0.3f, 0.2f);
                cs.moveTo(mm(20), mm(14));
                cs.lineTo(mm(190), mm(14));
                cs.stroke();
            }
            var xmp = org.apache.xmpbox.XMPMetadata.createXMPMetadata();
            var id = xmp.createAndAddPDFAIdentificationSchema();
            id.setPart(3);
            id.setConformance("B");
            ByteArrayOutputStream xmpBytes = new ByteArrayOutputStream();
            new org.apache.xmpbox.xml.XmpSerializer().serialize(xmp, xmpBytes, true);
            var metadata = new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
            metadata.importXMPMetadata(xmpBytes.toByteArray());
            doc.getDocumentCatalog().setMetadata(metadata);
            try (InputStream icc = java.nio.file.Files.newInputStream(profile)) {
                var intent = new org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent(doc, icc);
                intent.setInfo("U.S. Web Coated (SWOP) v2");
                intent.setOutputCondition("U.S. Web Coated (SWOP) v2");
                intent.setOutputConditionIdentifier("CGATS TR 001");
                intent.setRegistryName("http://www.color.org");
                doc.getDocumentCatalog().addOutputIntent(intent);
            }
            return save(doc);
        }
    }

    /**
     * A one-page A4 letterhead that is displayed rotated by the angle (the page itself is portrait), with the sender
     * block as in {@link #letterhead}, written in the coordinates of the unrotated page.
     */
    public static byte[] rotated(int rotation) throws IOException {
        try (PDDocument doc = Loader.loadPDF(onePageLetterhead())) {
            doc.getPage(0).setRotation(rotation);
            return save(doc);
        }
    }

    /**
     * A picture with transparency, e.g. a signature.
     */
    public static byte[] signaturePng() throws IOException {
        BufferedImage img = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x10, 0x30, 0x90, 200));
        g.setStroke(new java.awt.BasicStroke(5));
        g.drawLine(10, 60, 190, 20);
        g.dispose();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", os);
        return os.toByteArray();
    }

    /**
     * A JPEG picture.
     */
    public static byte[] stampJpeg() throws IOException {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 120, 90));
        g.fillRect(0, 0, 120, 80);
        g.dispose();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpeg", os);
        return os.toByteArray();
    }

    static BufferedImage logo() {
        BufferedImage img = new BufferedImage(200, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 64);
        g.setColor(new Color(0x44, 0xaa, 0x77));
        g.fillRoundRect(0, 0, 64, 64, 12, 12);
        g.dispose();
        return img;
    }

    private static void footer(PDPageContentStream cs, PDFont font) throws IOException {
        cs.setStrokingColor(0.3f, 0.6f, 0.45f);
        cs.moveTo(mm(20), mm(14));
        cs.lineTo(mm(190), mm(14));
        cs.stroke();
        cs.beginText();
        cs.setFont(font, 7);
        cs.newLineAtOffset(mm(20), mm(10));
        cs.showText("Kelomi Testfirma GmbH - Rasoweg 12 - 12345 Vizostadt - www.example.com");
        cs.endText();
    }

    private static void link(PDPage page) throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(mm(20), mm(8), mm(80), mm(4)));
        PDActionURI action = new PDActionURI();
        action.setURI("https://example.com");
        link.setAction(action);
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        link.setPrinted(true);
        page.getAnnotations().add(link);
    }

    public static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        // PDF/A-1 forbids xref streams.
        doc.save(os, CompressParameters.NO_COMPRESSION);
        return os.toByteArray();
    }
}

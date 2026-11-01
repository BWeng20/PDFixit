package com.bw.jPdfTool;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

class PdfRiskTest {

    private static PDDocument createDocument(String text) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
        return doc;
    }

    private static boolean has(List<String> risks, String part) {
        return risks.stream().anyMatch(r -> r.contains(part));
    }

    @Test
    void test_plainDocumentHasNoRisk() throws IOException {
        try (PDDocument doc = createDocument("Hello world")) {
            Assertions.assertEquals(List.of(), PdfRisk.inspect(doc, null));
        }
    }

    @Test
    void test_encrypted() throws IOException {
        byte[] data;
        try (PDDocument doc = createDocument("Hello world")) {
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", ap);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            data = out.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(data, "user")) {
            List<String> risks = PdfRisk.inspect(doc, null);
            Assertions.assertTrue(has(risks, "encrypted"), risks.toString());
            Assertions.assertTrue(has(risks, "printing"), risks.toString());
        }
    }

    @Test
    void test_xfa() throws IOException {
        try (PDDocument doc = createDocument("Hello world")) {
            PDAcroForm form = new PDAcroForm(doc);
            form.getCOSObject().setItem(COSName.XFA, new COSArray());
            doc.getDocumentCatalog().setAcroForm(form);
            List<String> risks = PdfRisk.inspect(doc, null);
            Assertions.assertTrue(has(risks, "XFA"), risks.toString());
        }
    }

    @Test
    void test_tagsLostOnlyIfRemoved() throws IOException {
        try (PDDocument tagged = createDocument("Hello world"); PDDocument plain = createDocument("Hello world")) {
            tagged.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
            Assertions.assertTrue(PdfRisk.isTagged(tagged));
            Assertions.assertFalse(PdfRisk.isTagged(plain));
            // Tags kept, nothing to say. Input not tagged, nothing was lost.
            Assertions.assertEquals(List.of(), PdfRisk.tagsLost(true, tagged));
            Assertions.assertEquals(List.of(), PdfRisk.tagsLost(false, plain));
            // Tags gone.
            Assertions.assertEquals(List.of(PdfRisk.tagsLostWarning()), PdfRisk.tagsLost(true, plain));
            Assertions.assertEquals(List.of(PdfRisk.tagsLostWarning()), PdfRisk.tagsLost(true, null));
            // Not part of the warnings on load.
            Assertions.assertEquals(List.of(), PdfRisk.inspect(tagged, null));
        }
    }

    @Test
    void test_splitWarningMatchesResult() throws IOException {
        try (PDDocument doc = createDocument("Hello world")) {
            doc.addPage(new PDPage());
            doc.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
            var parts = new org.apache.pdfbox.multipdf.Splitter().split(doc);
            // Whatever PDFBox does with the tags, the warning matches the result.
            Assertions.assertEquals(PdfRisk.isTagged(parts.get(0)),
                    PdfRisk.tagsLost(PdfRisk.isTagged(doc), parts.get(0)).isEmpty());
            parts.forEach(IOUtils::closeQuietly);
        }
    }

    @Test
    void test_mergeWarningMatchesResult() throws IOException {
        try (PDDocument main = createDocument("a"); PDDocument other = createDocument("b")) {
            main.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
            boolean tagged = PdfRisk.isTagged(main) || PdfRisk.isTagged(other);
            new org.apache.pdfbox.multipdf.PDFMergerUtility().appendDocument(main, other);
            // Whatever PDFBox does with the tags, the warning matches the result.
            Assertions.assertEquals(PdfRisk.isTagged(main), PdfRisk.tagsLost(tagged, main).isEmpty());
        }
    }

    @Test
    void test_unsupportedImageFilter() throws IOException {
        try (PDDocument doc = createDocument("Hello world")) {
            Assertions.assertFalse(PdfRisk.hasUnsupportedImages(doc));
            COSStream image = doc.getDocument().createCOSStream();
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.FILTER, COSName.JBIG2_DECODE);
            PDPage page = doc.getPage(0);
            PDResources resources = page.getResources();
            org.apache.pdfbox.cos.COSDictionary xo = new org.apache.pdfbox.cos.COSDictionary();
            xo.setItem(COSName.getPDFName("Im1"), image);
            resources.getCOSObject().setItem(COSName.XOBJECT, xo);
            Assertions.assertTrue(PdfRisk.hasUnsupportedImages(doc));
            Assertions.assertTrue(has(PdfRisk.inspect(doc, null), "JBIG2"));
        }
    }

    @Test
    void test_damagedFile() throws IOException {
        byte[] data;
        try (PDDocument doc = createDocument("Hello world")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            data = out.toByteArray();
        }
        // Break the cross reference table: PDFBox must rebuild it.
        String s = new String(data, StandardCharsets.ISO_8859_1);
        int startxref = s.lastIndexOf("startxref");
        byte[] broken = (s.substring(0, startxref) + "startxref\n7\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);

        Assertions.assertFalse(PdfRisk.hasBrokenXref(data));
        Assertions.assertTrue(PdfRisk.hasBrokenXref(broken));
        try (PDDocument doc = Loader.loadPDF(broken)) {
            Assertions.assertTrue(has(PdfRisk.inspect(doc, broken), "damaged"));
        }
    }

    @Test
    void test_pagesWithoutText() throws IOException {
        try (PDDocument doc = createDocument("Some text on the page")) {
            doc.addPage(new PDPage());
            List<Integer> pages = Redactor.pagesWithoutText(doc);
            Assertions.assertEquals(List.of(2), pages);
            Assertions.assertTrue(Redactor.textSearchWarning(pages).startsWith("Page 2 has no extractable text"));
            Assertions.assertNull(Redactor.textSearchWarning(List.of()));
        }
    }
}

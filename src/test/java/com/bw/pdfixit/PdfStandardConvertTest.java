package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Converting documents between the PDF/A levels, checked with veraPDF.
 */
class PdfStandardConvertTest {

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        // PDF/A-1 forbids xref streams.
        doc.save(os, CompressParameters.NO_COMPRESSION);
        return os.toByteArray();
    }

    private static byte[] convert(byte[] pdf, PdfStandardProfile target) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PdfStandard.convertTo(doc, target);
            return save(doc);
        }
    }

    private static PdfStandardProfile profile(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PdfStandard.profile(doc);
        }
    }

    /**
     * The levels a document without tags can reach and veraPDF accepts.
     */
    private static final List<PdfStandardProfile> REACHABLE = List.of(PdfStandardProfile.PDFA_1B,
            PdfStandardProfile.PDFA_2B, PdfStandardProfile.PDFA_2U, PdfStandardProfile.PDFA_3B,
            PdfStandardProfile.PDFA_3U, PdfStandardProfile.PDFA_4);

    @Test
    void test_plainToEveryLevel() throws Exception {
        for (PdfStandardProfile target : REACHABLE) {
            byte[] result = convert(EInvoiceTest.plainPdf(2), target);
            Assertions.assertEquals(target, profile(result), target.toString());
            ValidationResult verification = PdfaValidator.validate(result);
            Assertions.assertTrue(verification.isConform(), target + ": " + verification);
        }
    }

    @Test
    void test_everyLevelToEveryLevel() throws Exception {
        for (int from = 1; from <= 3; ++from)
            for (PdfStandardProfile target : REACHABLE) {
                byte[] result = convert(PdfaVersionsTest.pdfa(from), target);
                Assertions.assertEquals(target, profile(result), from + " to " + target);
                ValidationResult verification = PdfaValidator.validate(result);
                Assertions.assertTrue(verification.isConform(), from + " to " + target + ": " + verification);
            }
    }

    /**
     * Level a gets a formal structure: a section per page, named by the template, with the content as one paragraph.
     */
    @Test
    void test_levelAGetsFormalStructure() throws Exception {
        byte[] text = EInvoiceTest.notEmbeddedFontPdf(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA, "Hello");
        for (PdfStandardProfile target : List.of(PdfStandardProfile.PDFA_1A, PdfStandardProfile.PDFA_2A,
                PdfStandardProfile.PDFA_3A)) {
            byte[] result = convert(text, target);
            ValidationResult verification = PdfaValidator.validate(result);
            Assertions.assertTrue(verification.isConform(), target + ": " + verification);
            try (PDDocument doc = Loader.loadPDF(result)) {
                Assertions.assertTrue(PdfTagger.isTagged(doc));
                Assertions.assertTrue(doc.getDocumentCatalog().getMarkInfo().isMarked());
                var root = doc.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
                var document = (org.apache.pdfbox.cos.COSDictionary) root.getDictionaryObject(COSName.K);
                var sections = (org.apache.pdfbox.cos.COSArray) document.getDictionaryObject(COSName.K);
                Assertions.assertEquals(1, sections.size());
                var section = (org.apache.pdfbox.cos.COSDictionary) sections.getObject(0);
                Assertions.assertEquals(I18n.t("tag.page", 1), section.getString(COSName.T));
            }
        }
    }

    /**
     * The repair adds the formal structure to a document that declares level a, and only to such a document.
     */
    @Test
    void test_repairTagsDeclaredLevelA() throws Exception {
        byte[] text = EInvoiceTest.notEmbeddedFontPdf(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA, "Hello");
        byte[] untagged;
        try (PDDocument doc = Loader.loadPDF(convert(text, PdfStandardProfile.PDFA_1A))) {
            doc.getDocumentCatalog().getCOSObject().removeItem(COSName.getPDFName("StructTreeRoot"));
            doc.getDocumentCatalog().getCOSObject().removeItem(COSName.getPDFName("MarkInfo"));
            untagged = save(doc);
        }
        Assertions.assertFalse(PdfaValidator.validate(untagged).isConform(), "level a without structure");
        PdfStandard.Repair repair = PdfStandard.repairPdfa(untagged);
        Assertions.assertTrue(repair.changes().containsKey(I18n.t("fix.formalTags")), repair.changes().toString());
        ValidationResult verification = PdfaValidator.validate(repair.pdf());
        Assertions.assertTrue(verification.isConform(), verification.toString());

        // Level b is not tagged by the repair.
        byte[] b = convert(text, PdfStandardProfile.PDFA_1B);
        Assertions.assertNull(PdfStandard.repairPdfa(b).pdf(), "nothing to repair");
    }

    @Test
    void test_pageNameTemplates() {
        Assertions.assertTrue(I18n.t("tag.page", 3).endsWith("3"));
        Assertions.assertFalse(I18n.t("tag.page", 3).contains("{"));
    }

    @Test
    void test_existingStructureIsNotReplaced() throws Exception {
        byte[] tagged = convert(EInvoiceTest.plainPdf(2), PdfStandardProfile.PDFA_1A);
        try (PDDocument doc = Loader.loadPDF(tagged)) {
            Assertions.assertEquals(0, PdfTagger.tagPages(doc), "tagged already");
            Assertions.assertFalse(PdfStandard.needsFormalTags(doc, PdfStandardProfile.PDFA_1A));
        }
    }

    @Test
    void test_sameLevelIsNotChanged() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.invoicePdf(1))) {
            Assertions.assertEquals(PdfStandardProfile.PDFA_3U, PdfStandard.profile(doc));
            Assertions.assertTrue(PdfStandard.convertTo(doc, PdfStandardProfile.PDFA_3U).isEmpty());
            Assertions.assertTrue(PdfStandard.describe(doc).contains("PDF/A-3u"));
        }
    }

    @Test
    void test_invoiceIsRemovedForPdfa2() throws Exception {
        byte[] invoice = EInvoiceTest.invoicePdf(1);
        try (PDDocument doc = Loader.loadPDF(invoice)) {
            PdfStandard.Impact impact = PdfStandard.impact(doc, PdfStandardProfile.PDFA_2B);
            Assertions.assertTrue(impact.lost().stream().anyMatch(s -> s.contains("e-invoice")), impact.toString());
            Map<String, Integer> changes = PdfStandard.convertTo(doc, PdfStandardProfile.PDFA_2B);
            Assertions.assertTrue(changes.containsKey("Removed invoice"), changes.toString());
            Assertions.assertFalse(EInvoice.hasInvoice(doc));
            Assertions.assertEquals(PdfStandardProfile.PDFA_2B, PdfStandard.profile(doc));
            ValidationResult verification = PdfaValidator.validate(save(doc));
            Assertions.assertTrue(verification.isConform(), verification.toString());
        }
    }

    @Test
    void test_invoiceIsKeptForPdfa3AndPdfa4Impact() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.invoicePdf(1))) {
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_3B).lost().isEmpty());
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_4F).lost().isEmpty());
            Assertions.assertFalse(PdfStandard.impact(doc, PdfStandardProfile.PDFA_4).lost().isEmpty());
        }
    }

    @Test
    void test_pdfa1SwitchesCompressionOffInfo() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.plainPdf(1))) {
            Assertions.assertFalse(PdfStandard.impact(doc, PdfStandardProfile.PDFA_1B).info().isEmpty());
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_1B).info().stream()
                    .anyMatch(i -> i.startsWith("PDF/A-1b ")), "names the standard");
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFX_3).info().stream()
                    .anyMatch(i -> i.startsWith("PDF/X-3 ")), "PDF/X-3 forbids compression too");
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_2B).info().isEmpty());
        }
    }

    @Test
    void test_removePdfa() throws Exception {
        byte[] result = convert(PdfaVersionsTest.pdfa(2), null);
        Assertions.assertNull(profile(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            Assertions.assertFalse(PdfStandard.describe(doc).contains("PDF/A"), PdfStandard.describe(doc));
            Assertions.assertTrue(PdfStandard.removePdfa(doc).isEmpty(), "nothing left to remove");
        }
    }

    @Test
    void test_removePdfaWarnsAboutInvoice() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.invoicePdf(1))) {
            Assertions.assertFalse(PdfStandard.impact(doc, null).lost().isEmpty());
        }
    }

    @Test
    void test_previewDoesNotChangeTheSource() throws Exception {
        byte[] pdf = EInvoiceTest.plainPdf(1);
        byte[] copy = pdf.clone();
        Map<String, Integer> changes = PdfStandard.preview(pdf, PdfStandardProfile.PDFA_2B);
        Assertions.assertTrue(changes.containsKey("Declared PDF/A-2b"), changes.toString());
        Assertions.assertArrayEquals(copy, pdf);
        Assertions.assertEquals(0, partOf(pdf));
    }

    private static int partOf(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PdfStandard.pdfaPart(doc);
        }
    }

    @Test
    void test_pdfaid_attributeForm() {
        String xmp = "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"x\" pdfaid:part=\"3\" pdfaid:conformance=\"B\"/>";
        String a4 = PdfStandard.setPdfaid(PdfStandard.setPdfaid(xmp, "part", "4"), "rev", "2020");
        Assertions.assertTrue(a4.contains("pdfaid:part=\"4\" pdfaid:rev=\"2020\""), a4);
        String none = PdfStandard.removePdfaid(PdfStandard.removePdfaid(xmp, "part"), "conformance");
        Assertions.assertFalse(none.contains("pdfaid:part"), none);
        Assertions.assertTrue(none.endsWith("/>"), none);
    }

    @Test
    void test_ofPdfa() {
        Assertions.assertTrue(PdfStandardProfile.ofPdfa(5, "B").isEmpty());
        Assertions.assertEquals(PdfStandardProfile.PDFA_4, PdfStandardProfile.ofPdfa(4, null).orElseThrow());
        Assertions.assertEquals(PdfStandardProfile.PDFA_3B, PdfStandardProfile.ofPdfa(3, null).orElseThrow());
        Assertions.assertEquals(PdfStandardProfile.PDFA_3U, PdfStandardProfile.ofPdfa(3, "u").orElseThrow());
        Assertions.assertTrue(PdfStandardProfile.ofPdfa(1, "U").isEmpty(), "PDF/A-1 has no level u");
    }

    @Test
    void test_verifiable() {
        Assertions.assertTrue(PdfStandardProfile.PDFA_3B.verifiable());
        Assertions.assertNull(PdfStandardProfile.PDFA_3B.notVerifiableReason());
        Assertions.assertFalse(PdfStandardProfile.PDFX_4.verifiable());
        Assertions.assertTrue(PdfStandardProfile.PDFX_4.notVerifiableReason().contains("veraPDF"));
        Assertions.assertTrue(PdfStandardProfile.PDFE_1.notVerifiableReason().contains("PDF/E"));
        Assertions.assertFalse(PdfStandardProfile.PDFUA_1.verifiable());
    }

    @Test
    void test_everyPdfaLevelIsDeclared() throws Exception {
        // Including the levels a tool can't fulfil: they are declared, the verification tells the rest.
        for (PdfStandardProfile target : PdfStandardProfile.pdfaProfiles())
            Assertions.assertEquals(target, profile(convert(EInvoiceTest.plainPdf(1), target)), target.toString());
    }

    @Test
    void test_everyOtherStandardIsDeclaredAndDetected() throws Exception {
        for (PdfStandardProfile target : PdfStandardProfile.values()) {
            if (target.isPdfa())
                continue;
            byte[] result = convert(EInvoiceTest.plainPdf(1), target);
            Assertions.assertEquals(target, profile(result), target.toString());
        }
    }

    @Test
    void test_otherStandardReplacesPdfa() throws Exception {
        byte[] x = convert(PdfaVersionsTest.pdfa(2), PdfStandardProfile.PDFX_4);
        try (PDDocument doc = Loader.loadPDF(x)) {
            Assertions.assertEquals(0, PdfStandard.pdfaPart(doc));
            Assertions.assertEquals(PdfStandardProfile.PDFX_4, PdfStandard.profile(doc));
            Assertions.assertEquals("PDF/X-4", doc.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion"));
            Assertions.assertEquals("False", doc.getDocumentInformation().getTrapped());
            Assertions.assertNotNull(doc.getPage(0).getCOSObject().getDictionaryObject(COSName.getPDFName("TrimBox")));
            Assertions.assertTrue(PdfStandard.hasRgbOutputIntent(doc));
        }
        // And back to PDF/A.
        Assertions.assertEquals(PdfStandardProfile.PDFA_3B, profile(convert(x, PdfStandardProfile.PDFA_3B)));
        try (PDDocument doc = Loader.loadPDF(convert(x, PdfStandardProfile.PDFA_3B))) {
            Assertions.assertFalse(PdfStandard.describe(doc).contains("PDF/X"), PdfStandard.describe(doc));
        }
    }

    @Test
    void test_universalAccessBasics() throws Exception {
        try (PDDocument doc = Loader.loadPDF(convert(EInvoiceTest.plainPdf(1), PdfStandardProfile.PDFUA_1))) {
            Assertions.assertNotNull(doc.getDocumentCatalog().getLanguage());
            Assertions.assertTrue(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
        }
    }

    @Test
    void test_noneRemovesAllDeclarations() throws Exception {
        byte[] result = convert(convert(PdfaVersionsTest.pdfa(2), PdfStandardProfile.PDFX_3), null);
        Assertions.assertNull(profile(result));
    }

    @Test
    void test_impactHintsForStandardsThatCantBeVerified() throws Exception {
        try (PDDocument doc = Loader.loadPDF(EInvoiceTest.plainPdf(1))) {
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFX_4).info().stream()
                    .anyMatch(i -> i.contains("veraPDF")));
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_3A).notRepaired().stream()
                    .anyMatch(i -> i.contains("tagged")));
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_3B).notRepaired().isEmpty());
            Assertions.assertTrue(PdfStandard.impact(doc, PdfStandardProfile.PDFA_4F).notRepaired().stream()
                    .anyMatch(i -> i.contains("embedded file")));
        }
    }
}

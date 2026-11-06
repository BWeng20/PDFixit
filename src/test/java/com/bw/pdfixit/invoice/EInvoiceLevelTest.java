package com.bw.pdfixit.invoice;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.PdfaValidator;
import com.bw.pdfixit.ValidationResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Attaching an invoice keeps the conformance letter of the document (a, b, u); generated invoices use the level they
 * are given.
 */
class EInvoiceLevelTest {

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        // PDF/A-1 forbids xref streams.
        doc.save(os, CompressParameters.NO_COMPRESSION);
        return os.toByteArray();
    }

    /**
     * A page with text, declared as the given standard (null: none).
     */
    static byte[] declared(PdfStandardProfile profile) throws Exception {
        byte[] text = EInvoiceTest.notEmbeddedFontPdf(Standard14Fonts.FontName.HELVETICA, "Hello");
        if (profile == null)
            return text;
        try (PDDocument doc = Loader.loadPDF(text)) {
            PdfStandard.convertTo(doc, profile);
            return save(doc);
        }
    }

    private static PdfStandardProfile profile(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PdfStandard.profile(doc);
        }
    }

    /**
     * The declared level and the level after attaching.
     */
    private static Map<PdfStandardProfile, PdfStandardProfile> expected() {
        Map<PdfStandardProfile, PdfStandardProfile> m = new LinkedHashMap<>();
        m.put(PdfStandardProfile.PDFA_1A, PdfStandardProfile.PDFA_3A);
        m.put(PdfStandardProfile.PDFA_1B, PdfStandardProfile.PDFA_3B);
        m.put(PdfStandardProfile.PDFA_2A, PdfStandardProfile.PDFA_3A);
        m.put(PdfStandardProfile.PDFA_2B, PdfStandardProfile.PDFA_3B);
        m.put(PdfStandardProfile.PDFA_2U, PdfStandardProfile.PDFA_3U);
        m.put(PdfStandardProfile.PDFA_3A, PdfStandardProfile.PDFA_3A);
        m.put(PdfStandardProfile.PDFA_3B, PdfStandardProfile.PDFA_3B);
        m.put(PdfStandardProfile.PDFA_3U, PdfStandardProfile.PDFA_3U);
        m.put(null, PdfStandardProfile.PDFA_3B);
        return m;
    }

    @Test
    void test_levelOf() throws Exception {
        for (var e : expected().entrySet())
            Assertions.assertEquals(e.getValue(), EInvoice.levelOf(declared(e.getKey())), String.valueOf(e.getKey()));
        Assertions.assertEquals(PdfStandardProfile.PDFA_3B, EInvoice.levelOf(declared(PdfStandardProfile.PDFA_4)));
    }

    /**
     * attachXml and attachXmlStrict without a level: the letter of the document is kept, the result is valid.
     */
    @Test
    void test_attachKeepsTheLetter() throws Exception {
        for (var e : expected().entrySet()) {
            String what = String.valueOf(e.getKey());
            byte[] result = EInvoice.attachXmlStrict(declared(e.getKey()), EInvoiceTest.sampleXml());
            Assertions.assertEquals(e.getValue(), profile(result), what);
            try (PDDocument doc = Loader.loadPDF(result)) {
                Assertions.assertEquals(e.getValue() == PdfStandardProfile.PDFA_3A, PdfTagger.isTagged(doc), what);
            }
        }
    }

    /**
     * EInvoice.attach (used by EInvoice.repair and the Invoice tab) keeps the letter, too.
     */
    @Test
    void test_attachDocument() throws Exception {
        for (PdfStandardProfile level : new PdfStandardProfile[]{PdfStandardProfile.PDFA_3A, PdfStandardProfile.PDFA_3U}) {
            PDDocument doc = Loader.loadPDF(declared(level));
            try (PDDocument result = EInvoice.attach(doc, EInvoiceTest.sampleXml())) {
                Assertions.assertEquals(level, PdfStandard.profile(result));
            }
        }
    }

    /**
     * An explicit level, whatever the document declares; level a gets a structure if the document has none.
     */
    @Test
    void test_explicitLevel() throws Exception {
        byte[] b = declared(PdfStandardProfile.PDFA_3B);
        for (PdfStandardProfile level : new PdfStandardProfile[]{PdfStandardProfile.PDFA_3A, PdfStandardProfile.PDFA_3B,
                PdfStandardProfile.PDFA_3U}) {
            byte[] result = EInvoice.attachXmlStrict(b, EInvoiceTest.sampleXml(), level);
            Assertions.assertEquals(level, profile(result));
        }
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> EInvoice.attachXml(b, EInvoiceTest.sampleXml(), PdfStandardProfile.PDFA_2B));
    }

    /**
     * The repair that switches a PDF/A-1 or -2 document with attachments to PDF/A-3 keeps the letter.
     */
    @Test
    void test_repairUpgradeKeepsTheLetter() throws Exception {
        Map<PdfStandardProfile, PdfStandardProfile> cases = new LinkedHashMap<>();
        cases.put(PdfStandardProfile.PDFA_2A, PdfStandardProfile.PDFA_3A);
        cases.put(PdfStandardProfile.PDFA_2U, PdfStandardProfile.PDFA_3U);
        cases.put(PdfStandardProfile.PDFA_2B, PdfStandardProfile.PDFA_3B);
        for (var e : cases.entrySet()) {
            byte[] withFile;
            try (PDDocument doc = Loader.loadPDF(declared(e.getKey()))) {
                PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream("hello".getBytes()));
                file.setSubtype("text/plain");
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile("hello.txt");
                spec.setFileUnicode("hello.txt");
                spec.setEmbeddedFile(file);
                spec.setEmbeddedFileUnicode(file);
                PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
                tree.setNames(Map.of("hello.txt", spec));
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
                names.setEmbeddedFiles(tree);
                doc.getDocumentCatalog().setNames(names);
                withFile = save(doc);
            }
            PdfStandard.Repair repair = PdfStandard.repairPdfa(withFile, true);
            Assertions.assertNotNull(repair.pdf());
            Assertions.assertEquals(e.getValue(), profile(repair.pdf()), e.getKey().toString());
            ValidationResult v = PdfaValidator.validate(repair.pdf());
            Assertions.assertTrue(v.isConform(), e.getKey() + ": " + v);
        }
    }
}

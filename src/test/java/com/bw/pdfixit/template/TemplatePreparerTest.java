package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.PdfaValidator;
import com.bw.pdfixit.ValidationResult;
import com.bw.pdfixit.fonts.PdfFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

class TemplatePreparerTest {

    private static Map<String, byte[]> templates() throws Exception {
        Map<String, byte[]> t = new LinkedHashMap<>();
        t.put("plain", TestTemplates.letterhead(false));
        t.put("tagged", TestTemplates.letterhead(true));
        t.put("3a", TestTemplates.letterhead(PdfStandardProfile.PDFA_3A));
        t.put("1b", TestTemplates.letterhead(PdfStandardProfile.PDFA_1B));
        return t;
    }

    private static byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        doc.save(os);
        return os.toByteArray();
    }

    private static void assertNoStructure(PDDocument doc, String what) {
        Assertions.assertFalse(PdfTagger.isTagged(doc), what);
        Assertions.assertNull(doc.getDocumentCatalog().getCOSObject().getItem(COSName.STRUCT_TREE_ROOT), what);
        Assertions.assertNull(doc.getDocumentCatalog().getCOSObject().getItem(COSName.MARK_INFO), what);
        for (PDPage page : doc.getPages())
            Assertions.assertFalse(page.getCOSObject().containsKey(COSName.STRUCT_PARENTS), what);
    }

    /**
     * Every start state to every level: the level is declared, the tags are gone, the fonts embedded. With b and u the
     * prepared template is valid already; a needs the formal structure, which the engine adds at the end.
     */
    @Test
    void test_everyTemplateToEveryLevel() throws Exception {
        for (var t : templates().entrySet())
            for (PdfStandardProfile level : TemplatePreparer.LEVELS) {
                String what = t.getKey() + " to " + level;
                try (TemplatePreparer.Prepared prepared = TemplatePreparer.prepare(t.getValue(), null, level)) {
                    PDDocument doc = prepared.document();
                    Assertions.assertEquals(2, prepared.templatePages(), what);
                    Assertions.assertFalse(prepared.continuationPdf(), what);
                    Assertions.assertEquals(level, PdfStandard.profile(doc), what);
                    assertNoStructure(doc, what);
                    Assertions.assertTrue(PdfFonts.list(doc).stream().allMatch(PdfFonts.FontInfo::embedded), what);
                    if (level == PdfStandardProfile.PDFA_3A)
                        PdfTagger.tagPages(doc);
                    ValidationResult v = PdfaValidator.validate(save(doc));
                    Assertions.assertTrue(v.isConform(), what + ": " + v);
                }
            }
    }

    @Test
    void test_tagsRemoved() throws Exception {
        try (var prepared = TemplatePreparer.prepare(TestTemplates.letterhead(true), null, PdfStandardProfile.PDFA_3U)) {
            Assertions.assertTrue(prepared.hadTags());
        }
        for (PdfStandardProfile level : TemplatePreparer.LEVELS)
            try (var prepared = TemplatePreparer.prepare(TestTemplates.letterhead(false), null, level)) {
                Assertions.assertFalse(prepared.hadTags(), level.toString());
                Assertions.assertFalse(prepared.changes().isEmpty());
            }
    }

    /**
     * The structure entries of the catalog, the pages, the annotations and the XObjects.
     */
    @Test
    void test_removeTags() throws Exception {
        try (PDDocument doc = Loader.loadPDF(TestTemplates.letterhead(true))) {
            PDPage page = doc.getPage(0);
            page.getAnnotations().get(0).getCOSObject().setInt(COSName.STRUCT_PARENT, 7);
            var xobjects = page.getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT);
            var logo = (org.apache.pdfbox.cos.COSStream) xobjects.getDictionaryObject(xobjects.keySet().iterator().next());
            logo.setInt(COSName.STRUCT_PARENT, 8);
            // Catalog: tree and mark info; two pages; one annotation; one image.
            Assertions.assertEquals(6, PdfTagger.removeTags(doc));
            assertNoStructure(doc, "removed");
            Assertions.assertFalse(page.getAnnotations().get(0).getCOSObject().containsKey(COSName.STRUCT_PARENT));
            Assertions.assertFalse(logo.containsKey(COSName.STRUCT_PARENT));
            Assertions.assertEquals(0, PdfTagger.removeTags(doc));
        }
    }

    @Test
    void test_continuationPdf() throws Exception {
        for (PdfStandardProfile level : TemplatePreparer.LEVELS)
            try (var prepared = TemplatePreparer.prepare(TestTemplates.letterhead(true), TestTemplates.continuationPdf(),
                    level)) {
                PDDocument doc = prepared.document();
                Assertions.assertEquals(3, doc.getNumberOfPages());
                Assertions.assertEquals(2, prepared.templatePages());
                Assertions.assertTrue(prepared.continuationPdf());
                // Its Helvetica Bold is embedded by the conversion.
                Assertions.assertTrue(PdfFonts.list(doc).stream().allMatch(PdfFonts.FontInfo::embedded),
                        PdfFonts.list(doc).toString());
                assertNoStructure(doc, level.toString());
                if (level == PdfStandardProfile.PDFA_3A)
                    PdfTagger.tagPages(doc);
                ValidationResult v = PdfaValidator.validate(save(doc));
                Assertions.assertTrue(v.isConform(), level + ": " + v);
            }
    }

    @Test
    void test_securityRemoved() throws Exception {
        try (var prepared = TemplatePreparer.prepare(TestTemplates.encrypted(""), null, PdfStandardProfile.PDFA_3B);
             PDDocument saved = Loader.loadPDF(save(prepared.document()))) {
            Assertions.assertFalse(saved.isEncrypted());
        }
        Assertions.assertThrows(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException.class,
                () -> TemplatePreparer.prepare(TestTemplates.encrypted("secret"), null, PdfStandardProfile.PDFA_3B));
    }

    @Test
    void test_onlyInvoiceLevels() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TemplatePreparer.prepare(TestTemplates.letterhead(false), null, PdfStandardProfile.PDFA_2B));
    }

    @Test
    void test_fontThatCantBeEmbedded() {
        Assertions.assertThrows(com.bw.pdfixit.fonts.FontEmbedException.class,
                () -> TemplatePreparer.prepare(TestTemplates.withDingbats(), null, PdfStandardProfile.PDFA_3U));
    }
}

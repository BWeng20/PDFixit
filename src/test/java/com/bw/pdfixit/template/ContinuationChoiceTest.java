package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandardProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The answer to the continuation query is applied to the layout and remembered for the template.
 */
class ContinuationChoiceTest {

    @TempDir
    Path tmp;

    private Path template() throws Exception {
        Path file = tmp.resolve("Brief.pdf");
        Files.write(file, TestTemplates.onePageLetterhead());
        return file;
    }

    @Test
    void test_apply() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        ContinuationChoice.apply(l, Layout.Continuation.PAGE1, null);
        Assertions.assertEquals(Layout.Continuation.PAGE1, l.continuation);
        Assertions.assertNull(l.continuationFile);

        Path other = tmp.resolve("other.pdf");
        ContinuationChoice.apply(l, Layout.Continuation.PDF, other);
        Assertions.assertEquals(Layout.Continuation.PDF, l.continuation);
        Assertions.assertEquals(other.toAbsolutePath().normalize(), l.continuationFile);

        // Another choice drops the file.
        ContinuationChoice.apply(l, Layout.Continuation.BLANK, null);
        Assertions.assertNull(l.continuationFile);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ContinuationChoice.apply(l, Layout.Continuation.PDF, null));
    }

    @Test
    void test_rememberedInTheLayoutFile() throws Exception {
        Path template = template();
        Layout l = Layout.din5008(Layout.Form.B);
        Path written = ContinuationChoice.applyAndRemember(l, Layout.Continuation.BLANK, null, template);
        Assertions.assertTrue(Files.isRegularFile(written));
        LayoutFiles.Found found = LayoutFiles.find(template);
        Assertions.assertEquals(written, found.file());
        Assertions.assertEquals(Layout.Continuation.BLANK, found.layout().continuation);
    }

    @Test
    void test_rememberedPdfIsFound() throws Exception {
        Path template = template();
        Path other = tmp.resolve("Folgeseite.pdf");
        Files.write(other, TestTemplates.continuationPdf());
        Layout l = Layout.din5008(Layout.Form.B);
        ContinuationChoice.applyAndRemember(l, Layout.Continuation.PDF, other, template);
        Layout read = LayoutFiles.find(template).layout();
        Assertions.assertEquals(Layout.Continuation.PDF, read.continuation);
        Assertions.assertEquals(other.toAbsolutePath().normalize(), read.continuationFile.toAbsolutePath().normalize());
    }

    @Test
    void test_choiceAnswersTheQuery() throws Exception {
        byte[] onePage = TestTemplates.onePageLetterhead();
        Layout l = TemplateEngineTest.layout();
        Assertions.assertTrue(TemplateEngineTest.render(onePage, l, TemplateEngineTest.content(40),
                PdfStandardProfile.PDFA_3U).choiceNeeded());
        ContinuationChoice.apply(l, Layout.Continuation.PAGE1, null);
        TemplateEngine.Result r = TemplateEngineTest.render(onePage, l, TemplateEngineTest.content(40),
                PdfStandardProfile.PDFA_3U);
        TemplateEngineTest.assertNoProblems(r);
        Assertions.assertTrue(r.pageCount() > 1);
    }
}

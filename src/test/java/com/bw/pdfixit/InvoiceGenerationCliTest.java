package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.template.InvoiceRendererTest;
import com.bw.pdfixit.template.Layout;
import com.bw.pdfixit.template.TestTemplates;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The CLI command {@code --generateInvoice}.
 */
class InvoiceGenerationCliTest {

    @TempDir
    Path dir;

    private Path write(String name, byte[] content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content);
        return p;
    }

    private int run(String... args) {
        Main main = new Main();
        int exitCode = new CommandLine(main).execute(args);
        return exitCode != 0 ? exitCode : main.executeCommands();
    }

    private Path invoice(int items) throws Exception {
        InvoiceData d = InvoiceRendererTest.sample();
        for (int i = 2; i < items; ++i)
            d.lines.add(new InvoiceData.Line("Position " + (i + 1), BigDecimal.ONE, "C62", new BigDecimal("10.00"),
                    new BigDecimal("19")));
        return write("invoice.xml", d.toXml());
    }

    private Path onePage() throws Exception {
        return write("one.pdf", TestTemplates.onePageLetterhead());
    }

    private Path twoPage() throws Exception {
        return write("two.pdf", TestTemplates.letterhead(false));
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static PdfStandardProfile level(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return PdfStandard.profile(doc);
        }
    }

    private static int pages(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    private static boolean valid(Path pdf) throws IOException {
        return EInvoice.validate(Files.readAllBytes(pdf), "out.pdf").valid();
    }

    @Test
    void test_generateIsValidEInvoice() throws Exception {
        Path out = dir.resolve("out.pdf");
        Path template = twoPage();
        Assertions.assertEquals(0, run("--generateInvoice", invoice(2).toString(), "--template", template.toString(),
                "--pdfaLevel", "u", "--language", "de", "--out", out.toString()));
        Assertions.assertEquals(PdfStandardProfile.PDFA_3U, level(out));
        Assertions.assertTrue(valid(out));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            Assertions.assertTrue(EInvoice.hasInvoice(doc));
        }
        Assertions.assertTrue(text(out).contains("T-2024-17"));
        Assertions.assertTrue(text(out).contains("Rechnungsnummer"), text(out));
        // The template is not changed, no layout file appears beside it.
        Assertions.assertFalse(Files.exists(dir.resolve("two.json")));
    }

    @Test
    void test_shortOptionsAndDefaults() throws Exception {
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(0, run("-gi", invoice(2).toString(), "-tp", twoPage().toString(), "-lg", "en",
                "-out", out.toString()));
        Assertions.assertEquals(Preferences.getInstance().getInvoicePdfaLevel(), level(out));
        Assertions.assertTrue(text(out).contains("Invoice number"), text(out));
    }

    @Test
    void test_everyLevel() throws Exception {
        Path xml = invoice(2);
        Path template = twoPage();
        for (String letter : new String[]{"a", "b", "u", "A"}) {
            Path out = dir.resolve("out" + letter + ".pdf");
            Assertions.assertEquals(0, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                    "--pdfaLevel", letter, "--language", "de", "--out", out.toString()), letter);
            Assertions.assertEquals(letter.toUpperCase(), level(out).conformance(), letter);
            Assertions.assertEquals(3, level(out).part(), letter);
            Assertions.assertTrue(valid(out), letter);
        }
    }

    @Test
    void test_longInvoiceOnOnePageTemplateNeedsContinuation() throws Exception {
        Path xml = invoice(60);
        Path template = onePage();
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(20, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--language", "de", "--out", out.toString()));
        Assertions.assertFalse(Files.exists(out));

        for (String source : new String[]{"page1", "blank"}) {
            Assertions.assertEquals(0, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                    "--continuation", source, "--pdfaLevel", "b", "--language", "de", "--out", out.toString()), source);
            Assertions.assertTrue(pages(out) > 1, source);
            Assertions.assertTrue(valid(out), source);
        }
        // The option does not create a layout file.
        Assertions.assertFalse(Files.exists(dir.resolve("one.json")));
    }

    @Test
    void test_continuationFromPdf() throws Exception {
        Path continuation = write("next.pdf", TestTemplates.continuationPdf());
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(0, run("--generateInvoice", invoice(60).toString(), "--template", onePage().toString(),
                "--continuation", continuation.toString(), "--pdfaLevel", "b", "--language", "de", "--out", out.toString()));
        Assertions.assertTrue(pages(out) > 1);
        Assertions.assertTrue(text(out).contains("Fortsetzung"), text(out));
        Assertions.assertTrue(valid(out));
    }

    @Test
    void test_twoPageTemplateNeedsNoOption() throws Exception {
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(0, run("--generateInvoice", invoice(60).toString(), "--template", twoPage().toString(),
                "--pdfaLevel", "b", "--language", "de", "--out", out.toString()));
        Assertions.assertTrue(pages(out) > 1);
    }

    @Test
    void test_layoutBesideTemplateAndOptionLayout() throws Exception {
        Path template = onePage();
        Layout layout = Layout.din5008(Layout.Form.B);
        layout.continuation = Layout.Continuation.BLANK;
        Path beside = dir.resolve("one.json");
        Files.write(beside, layout.toJson(dir));
        Path out = dir.resolve("out.pdf");
        // Found beside the template: no option needed.
        Assertions.assertEquals(0, run("--generateInvoice", invoice(60).toString(), "--template", template.toString(),
                "--pdfaLevel", "b", "--language", "de", "--out", out.toString()));
        Assertions.assertTrue(pages(out) > 1);

        // --layout names another file; the JSON beside the template is foreign here and left alone.
        Path other = dir.resolve("other-layout.json");
        Layout withPage1 = Layout.din5008(Layout.Form.A);
        withPage1.continuation = Layout.Continuation.PAGE1;
        Files.write(other, withPage1.toJson(dir));
        Files.write(beside, "{\"nothing\": \"to do with us\"}".getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals(0, run("--generateInvoice", invoice(60).toString(), "--template", template.toString(),
                "--layout", other.toString(), "--pdfaLevel", "b", "--language", "de", "--out", out.toString()));
        Assertions.assertTrue(pages(out) > 1);
        Assertions.assertEquals("{\"nothing\": \"to do with us\"}", Files.readString(beside));

        // The layout names its source already: the option is ignored.
        Assertions.assertEquals(0, run("--generateInvoice", invoice(60).toString(), "--template", template.toString(),
                "--layout", other.toString(), "--continuation", "blank", "--pdfaLevel", "b", "--language", "de",
                "--out", out.toString()));
    }

    @Test
    void test_brokenLayoutIsAnError() throws Exception {
        Path broken = write("broken.json", "{ not json".getBytes(StandardCharsets.UTF_8));
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(19, run("--generateInvoice", invoice(2).toString(), "--template", twoPage().toString(),
                "--layout", broken.toString(), "--out", out.toString()));
        Assertions.assertFalse(Files.exists(out));
    }

    @Test
    void test_layoutOfAnotherTemplateIsAnError() throws Exception {
        // The layout remembers a three-page template, the template has two pages.
        Layout layout = Layout.din5008(Layout.Form.B);
        layout.pageCount = 3;
        layout.pageWidth = 210.0;
        layout.pageHeight = 297.0;
        Path file = write("l.json", layout.toJson(dir));
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(19, run("--generateInvoice", invoice(2).toString(), "--template", twoPage().toString(),
                "--layout", file.toString(), "--out", out.toString()));
        Assertions.assertFalse(Files.exists(out));
    }

    @Test
    void test_missingOrInvalidArguments() throws Exception {
        Path xml = invoice(2);
        Path template = twoPage();
        Path out = dir.resolve("out.pdf");
        // No template; options without --generateInvoice.
        Assertions.assertEquals(17, run("--generateInvoice", xml.toString(), "--out", out.toString()));
        Assertions.assertEquals(17, run("--template", template.toString(), "--out", out.toString()));
        Assertions.assertEquals(17, run("--pdfaLevel", "a", "--out", out.toString()));
        // No output.
        Assertions.assertEquals(3, run("--generateInvoice", xml.toString(), "--template", template.toString()));
        // Unknown values.
        Assertions.assertEquals(17, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--pdfaLevel", "x", "--out", out.toString()));
        Assertions.assertEquals(17, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--language", "fr", "--out", out.toString()));
        Assertions.assertEquals(17, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--continuation", "somewhere", "--language", "de", "--out", out.toString()));
        // Missing files.
        Assertions.assertEquals(10, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--continuation", dir.resolve("gone.pdf").toString(), "--language", "de", "--out", out.toString()));
        Assertions.assertEquals(10, run("--generateInvoice", dir.resolve("gone.xml").toString(), "--template",
                template.toString(), "--out", out.toString()));
        Assertions.assertEquals(10, run("--generateInvoice", xml.toString(), "--template",
                dir.resolve("gone.pdf").toString(), "--out", out.toString()));
        Assertions.assertEquals(10, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--layout", dir.resolve("gone.json").toString(), "--out", out.toString()));
        // Input files and options of other commands.
        Assertions.assertEquals(17, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--out", out.toString(), template.toString()));
        Assertions.assertEquals(17, run("--generateInvoice", xml.toString(), "--template", template.toString(),
                "--split", "1", "--out", out.toString()));
        Assertions.assertFalse(Files.exists(out));
    }

    @Test
    void test_invalidXmlAndIncompleteData() throws Exception {
        Path template = twoPage();
        Path out = dir.resolve("out.pdf");
        Path notXml = write("bad.xml", "this is no xml".getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals(18, run("--generateInvoice", notXml.toString(), "--template", template.toString(),
                "--out", out.toString()));
        InvoiceData d = InvoiceRendererTest.sample();
        d.buyer.name = "";
        Path incomplete = write("incomplete.xml", d.toXml());
        Assertions.assertEquals(18, run("--generateInvoice", incomplete.toString(), "--template", template.toString(),
                "--out", out.toString()));
        Assertions.assertFalse(Files.exists(out));
    }
}

package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoiceTest;
import com.bw.pdfixit.invoice.EInvoice;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class EInvoiceCliTest {

    @TempDir
    Path dir;

    private Path write(String name, byte[] content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content);
        return p;
    }

    /**
     * Parses and executes like {@link Main#main}.
     */
    private int run(String... args) {
        Main main = new Main();
        int exitCode = new CommandLine(main).execute(args);
        return exitCode != 0 ? exitCode : main.executeCommands();
    }

    private static boolean hasInvoice(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return EInvoice.hasInvoice(doc);
        }
    }

    @Test
    void test_attachValidateExtract() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.plainPdf(2));
        Path xml = write("invoice.xml", EInvoiceTest.sampleXml());
        Path out = dir.resolve("out.pdf");

        Assertions.assertEquals(0, run("-ai", xml.toString(), "-vi", "-out", out.toString(), in.toString()));
        Assertions.assertTrue(hasInvoice(out));

        Path extracted = dir.resolve("extracted.xml");
        Path out2 = dir.resolve("out2.pdf");
        Assertions.assertEquals(0, run("-ei", extracted.toString(), "-out", out2.toString(), out.toString()));
        Assertions.assertArrayEquals(EInvoiceTest.sampleXml(), Files.readAllBytes(extracted));
    }

    @Test
    void test_attachFailsIfNotConform() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.nonConformPdf());
        Path xml = write("invoice.xml", EInvoiceTest.sampleXml());
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(13, run("-ai", xml.toString(), "-out", out.toString(), in.toString()));
        Assertions.assertFalse(Files.exists(out));
    }

    @Test
    void test_extractWithoutInvoice() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.plainPdf(1));
        Assertions.assertEquals(8, run("-ei", dir.resolve("x.xml").toString(), "-out", dir.resolve("o.pdf").toString(), in.toString()));
    }

    @Test
    void test_validateInvalid() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.plainPdf(1));
        Assertions.assertEquals(12, run("-vi", "-out", dir.resolve("o.pdf").toString(), in.toString()));
    }

    @Test
    void test_splitKeepsInvoiceInFirstPart() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.invoicePdf(2));
        Assertions.assertEquals(0, run("-s", "1", "-out", dir.resolve("part.pdf").toString(), in.toString()));
        Path part1 = dir.resolve("part001.pdf");
        Path part2 = dir.resolve("part002.pdf");
        Assertions.assertTrue(hasInvoice(part1));
        Assertions.assertFalse(hasInvoice(part2));
    }

    @Test
    void test_mergeKeepsInvoice() throws Exception {
        Path plain = write("plain.pdf", EInvoiceTest.plainPdf(1));
        Path invoice = write("invoice.pdf", EInvoiceTest.invoicePdf(1));
        Path out = dir.resolve("out.pdf");
        // The invoice comes second: the metadata of the first document doesn't know it.
        Assertions.assertEquals(0, run("-vi", "-out", out.toString(), plain.toString(), invoice.toString()));
        Assertions.assertTrue(hasInvoice(out));
    }

    @Test
    void test_mergeOfTwoInvoicesKeepsFirst() throws Exception {
        Path a = write("a.pdf", EInvoice.attachXml(EInvoiceTest.plainPdf(1),
                new String(EInvoiceTest.sampleXml(), "UTF-8").replace("INV-2024-001", "INV-A").getBytes("UTF-8")));
        Path b = write("b.pdf", EInvoice.attachXml(EInvoiceTest.plainPdf(1),
                new String(EInvoiceTest.sampleXml(), "UTF-8").replace("INV-2024-001", "INV-B").getBytes("UTF-8")));
        Path out = dir.resolve("out.pdf");
        Assertions.assertEquals(0, run("-out", out.toString(), a.toString(), b.toString()));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String xml = new String(EInvoice.extractXml(doc).orElseThrow(), "UTF-8");
            Assertions.assertTrue(xml.contains("INV-A"));
            Assertions.assertFalse(xml.contains("INV-B"));
        }
    }

    @Test
    void test_redactRequiresDecisionForInvoice() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.invoicePdf(1));
        Path out = dir.resolve("out.pdf");

        Assertions.assertEquals(9, run("-ra", "1:0,0,50,50", "-out", out.toString(), in.toString()));
        Assertions.assertFalse(Files.exists(out));

        Assertions.assertEquals(0, run("-ra", "1:0,0,50,50", "-ri", "-out", out.toString(), in.toString()));
        Assertions.assertFalse(hasInvoice(out));
    }
}

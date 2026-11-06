package com.bw.pdfixit;

import com.bw.pdfixit.invoice.EInvoiceTest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class XmpCliTest {

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

    @Test
    void extractXmp() throws Exception {
        byte[] pdf = EInvoiceTest.invoicePdf(1);
        Path in = write("in.pdf", pdf);
        Path xmp = dir.resolve("meta.xmp");

        Assertions.assertEquals(0, run("-ex", xmp.toString(), "-out", dir.resolve("out.pdf").toString(),
                in.toString()));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Assertions.assertArrayEquals(doc.getDocumentCatalog().getMetadata().toByteArray(),
                    Files.readAllBytes(xmp));
        }
    }

    @Test
    void extractXmp_failsWithoutMetadata() throws Exception {
        Path in = write("in.pdf", EInvoiceTest.plainPdf(1));
        Path xmp = dir.resolve("meta.xmp");

        Assertions.assertEquals(16, run("--extractXmp", xmp.toString(), "-out", dir.resolve("out.pdf").toString(),
                in.toString()));
        Assertions.assertFalse(Files.exists(xmp));
    }
}

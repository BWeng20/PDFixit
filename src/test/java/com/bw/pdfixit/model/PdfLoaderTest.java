package com.bw.pdfixit.model;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfLoaderTest {

    private static byte[] protectedPdf(String owner, String user) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, ap);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void pdfboxDoesNotTryTheEmptyPassword() throws IOException {
        byte[] pdf = protectedPdf("owner", "");
        // The reason for the loader: PDFBox fails with a wrong password, even if the file needs none to open.
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(pdf, "wrong").close());
    }

    @Test
    void onlyPermissionsLoadsWithWrongPassword() throws IOException {
        byte[] pdf = protectedPdf("owner", "");
        try (PDDocument doc = PdfLoader.load(pdf, "wrong")) {
            assertEquals(1, doc.getNumberOfPages());
        }
        try (PDDocument doc = PdfLoader.load(pdf, null)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void openPasswordIsNeeded() throws IOException {
        byte[] pdf = protectedPdf("owner", "user");
        assertThrows(InvalidPasswordException.class, () -> PdfLoader.load(pdf, "wrong").close());
        assertThrows(InvalidPasswordException.class, () -> PdfLoader.load(pdf, null).close());
        try (PDDocument doc = PdfLoader.load(pdf, "user")) {
            assertEquals(1, doc.getNumberOfPages());
        }
        try (PDDocument doc = PdfLoader.load(pdf, "owner")) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }
}

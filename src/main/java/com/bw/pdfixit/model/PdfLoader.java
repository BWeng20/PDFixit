package com.bw.pdfixit.model;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;

/**
 * Loads a PDF with a password, the owner or the user password.
 */
public final class PdfLoader {

    private PdfLoader() {
    }

    /**
     * Loads the PDF. A wrong password is no problem if the file needs none to open it:
     * PDFBox only tries the given password, but a file that only restricts the permissions opens with an empty
     * password.
     *
     * @param data     The content of the file.
     * @param password The password, null for none.
     * @return The document.
     * @throws InvalidPasswordException If the file needs a password to open it and the password doesn't match.
     */
    public static PDDocument load(byte[] data, String password) throws IOException {
        if (password == null || password.isEmpty())
            return Loader.loadPDF(data);
        try {
            return Loader.loadPDF(data, password);
        } catch (InvalidPasswordException wrong) {
            try {
                return Loader.loadPDF(data);
            } catch (InvalidPasswordException needed) {
                throw wrong;
            }
        }
    }
}

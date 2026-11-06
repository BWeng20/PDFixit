package com.bw.pdfixit.template;

import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Prepares a template in memory for the engine; the file is never changed:
 * <ol>
 * <li>the first page of the continuation PDF is imported as last page (before the conversion, so that its fonts are
 * embedded, too)</li>
 * <li>the document is converted to the PDF/A-3 level of the invoice ({@link PdfStandard#convertTo})</li>
 * <li>the logical structure is removed ({@link PdfTagger#removeTags})</li>
 * </ol>
 * A security handler without password is removed: the result is never encrypted.
 */
public final class TemplatePreparer {

    /**
     * The levels an invoice can have.
     */
    public static final List<PdfStandardProfile> LEVELS = List.of(PdfStandardProfile.PDFA_3A,
            PdfStandardProfile.PDFA_3B, PdfStandardProfile.PDFA_3U);

    private TemplatePreparer() {
    }

    /**
     * The prepared template. Closing it closes the document.
     *
     * @param document        The template pages, followed by the page of the continuation PDF if there is one.
     * @param templatePages   The number of pages of the template.
     * @param continuationPdf True if the last page is the one of the continuation PDF.
     * @param changes         What the conversion changed.
     * @param hadTags         True if the template had a logical structure (it was removed).
     */
    public record Prepared(PDDocument document, int templatePages, boolean continuationPdf,
                           Map<String, Integer> changes, boolean hadTags) implements Closeable {
        @Override
        public void close() throws IOException {
            document.close();
        }
    }

    /**
     * Prepares a template.
     *
     * @param template        The template PDF.
     * @param continuationPdf The PDF of the following pages, null if there is none.
     * @param level           {@link PdfStandardProfile#PDFA_3A}, {@link PdfStandardProfile#PDFA_3B} or
     *                        {@link PdfStandardProfile#PDFA_3U}.
     * @throws com.bw.pdfixit.fonts.FontEmbedException If a font of the template can't be embedded.
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException If the template needs a password.
     */
    public static Prepared prepare(byte[] template, byte[] continuationPdf, PdfStandardProfile level)
            throws IOException {
        if (!LEVELS.contains(level))
            throw new IllegalArgumentException("Not a level of an invoice: " + level);
        PDDocument document = Loader.loadPDF(template);
        try {
            int templatePages = document.getNumberOfPages();
            if (continuationPdf != null)
                document = withContinuationPage(document, continuationPdf);
            if (document.isEncrypted())
                document.setAllSecurityToBeRemoved(true);
            // The conversion may add a structure (level a), so look before.
            boolean hadTags = PdfTagger.isTagged(document);
            Map<String, Integer> changes = PdfStandard.convertTo(document, level);
            PdfTagger.removeTags(document);
            return new Prepared(document, templatePages, continuationPdf != null, changes, hadTags);
        } catch (IOException | RuntimeException e) {
            document.close();
            throw e;
        }
    }

    /**
     * Imports the first page of the other PDF as last page. The result is written and read again, so that it doesn't
     * depend on the other document any more.
     *
     * @param document The template, it is closed.
     * @return The new document.
     */
    private static PDDocument withContinuationPage(PDDocument document, byte[] continuationPdf) throws IOException {
        try (PDDocument other = Loader.loadPDF(continuationPdf)) {
            if (other.getNumberOfPages() == 0)
                throw new IOException("The PDF for the following pages has no pages.");
            if (other.isEncrypted())
                other.setAllSecurityToBeRemoved(true);
            document.importPage(other.getPage(0));
            if (document.isEncrypted())
                document.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            document.save(os);
            return Loader.loadPDF(os.toByteArray());
        } finally {
            document.close();
        }
    }
}

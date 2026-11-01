package com.bw.pdfixit.cli;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfRisk;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.model.PdfLoadWorker;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * PDF load worker for CLI (not concurrently).
 */
public class CliPdfLoader implements PdfLoadWorker {

    private final DocumentProxy documentProxy;
    protected final Path file;
    protected final MergeOptions mo;
    protected Consumer<PDDocument> consumer;
    private final String ownerPassword;
    private boolean finished = false;

    public CliPdfLoader(DocumentProxy proxy, Path file, MergeOptions mo, String ownerPassword) {
        this.documentProxy = proxy;
        this.file = file;
        this.mo = mo;
        this.ownerPassword = ownerPassword;
    }

    @Override
    public void execute() {
        PDDocument document = null;
        try {
            byte[] data = Files.readAllBytes(file);
            document = ownerPassword != null
                    ? Loader.loadPDF(data, ownerPassword)
                    : Loader.loadPDF(data);
            documentProxy.error = null;
            documentProxy.reportRisks(file.getFileName().toString(), PdfRisk.inspect(document, data));
        } catch (InvalidPasswordException ep) {
            documentProxy.error = I18n.t("load.wrongPassword");
        } catch (NoSuchFileException fe) {
            documentProxy.error = I18n.t("load.notExist", file.getFileName());
        } catch (Exception e) {
            documentProxy.error = e.getMessage();
        }
        this.finished = true;

        try {
            if (!documentProxy.isClosed()) {
                if (document != null) {
                    if (consumer != null)
                        consumer.accept(document);
                }
                Log.debug("DONE (%s)", file);
                documentProxy.loaderFinished( document, mo);
            }
        } catch (Exception e) {
            documentProxy.error = e.getMessage();
            documentProxy.fireDocumentLoaded();
        }
    }

    @Override
    public void cancel() {
        // Not asynchronous, nothing to stop.
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}

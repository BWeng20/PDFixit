package com.bw.pdfixit.cli;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfRisk;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.Redactor;
import com.bw.pdfixit.SafeFile;
import com.bw.pdfixit.SignatureTool;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.model.RenderQueue;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tool to execute CLI commands.
 */
public class CommandExecuter {

    protected final List<Path> files;
    protected DocumentProxy documentProxy;

    public CommandExecuter(List<Path> files) {
        this.files = new ArrayList<>(files);
    }

    /**
     * Thrown if a file can't be loaded or merged.
     */
    public static class LoadException extends java.io.IOException {
        public LoadException(String message) {
            super(message);
        }
    }

    /**
     * Loads all files.
     *
     * @throws LoadException If a file can't be loaded or merged. Further files are not loaded.
     */
    public void loadDocuments(String password2Load, MergeOptions mo) throws LoadException {

        if (documentProxy != null)
            documentProxy.close();

        // Currently we simply need an instance bit don't actually render .
        RenderQueue renderQueue = new RenderQueue();
        documentProxy = new DocumentProxy(renderQueue);

        if (mo == null)
            mo = new MergeOptions();

        // TODO: We could use multiple threads here, but for now keep it simple.
        // TODO: Support setting MergeOptions from arguments.
        for (Path p : files) {
            CliPdfLoader lw = new CliPdfLoader(documentProxy, p, mo, password2Load);
            lw.execute();
            // The next loader resets the error, so check each file.
            if (documentProxy.error != null)
                throw new LoadException(I18n.t("cli.loadFailed", p,
                        documentProxy.error.replace('\n', ' ')));
        }
    }

    /**
     * Permanently removes text and areas from the effective document.
     *
     * @param patterns Text patterns to redact.
     * @param areas    Areas to redact.
     * @return The number of redacted areas.
     */
    public int redact(List<Pattern> patterns, List<Redactor.Region> areas) throws Exception {
        PDDocument document = documentProxy == null ? null : documentProxy.getDocument();
        if (document == null)
            return 0;
        List<Redactor.Region> regions = new ArrayList<>(areas);
        for (Pattern p : patterns) {
            regions.addAll(Redactor.findText(document, p));
        }
        if (!patterns.isEmpty()) {
            String warning = Redactor.textSearchWarning(Redactor.pagesWithoutText(document));
            if (warning != null)
                Log.error("Warning: %s", warning);
        }
        Preferences prefs = Preferences.getInstance();
        final boolean tagged = PdfRisk.isTagged(document);
        Redactor.redact(document, regions, prefs.getDpi(), prefs.getRedactionColor());
        if (!regions.isEmpty())
            documentProxy.reportRisks(I18n.t("risk.redaction"), PdfRisk.tagsLost(tagged, null));
        return regions.size();
    }

    /**
     * Checks if the effective document contains an invoice.
     */
    public boolean hasInvoice() {
        return documentProxy != null && documentProxy.getDocument() != null
                && EInvoice.hasInvoice(documentProxy.getDocument());
    }

    /**
     * Writes the invoice XML of the effective document to a file.
     *
     * @return False if the document has no invoice.
     */
    public boolean extractInvoice(Path xmlFile) throws Exception {
        PDDocument document = documentProxy == null ? null : documentProxy.getDocument();
        if (document == null)
            return false;
        var xml = EInvoice.extractXml(document);
        if (xml.isEmpty())
            return false;
        Files.write(xmlFile, xml.get());
        return true;
    }

    /**
     * Attaches an invoice XML (ZUGFeRD/Factur-X, CII syntax) to the effective document.
     * An existing invoice is replaced.
     */
    public void attachInvoice(Path xmlFile) throws Exception {
        if (documentProxy != null && documentProxy.getDocument() != null)
            documentProxy.attachInvoice(Files.readAllBytes(xmlFile));
    }

    /**
     * Removes the invoice from the effective document.
     *
     * @return True if there was an invoice.
     */
    public boolean removeInvoice() throws Exception {
        return documentProxy != null && documentProxy.getDocument() != null && documentProxy.removeInvoice();
    }

    /**
     * Validates a stored PDF with its invoice.
     */
    public EInvoice.Validation validateInvoice(Path pdfFile) throws Exception {
        return EInvoice.validate(Files.readAllBytes(pdfFile), pdfFile.getFileName().toString());
    }

    /**
     * Validates a stored PDF with veraPDF, see {@link com.bw.pdfixit.PdfaValidator}.
     *
     * @return The violated rules, empty if the PDF is conform.
     */
    public com.bw.pdfixit.ValidationResult validatePdfa(Path pdfFile) throws Exception {
        return com.bw.pdfixit.PdfaValidator.validate(Files.readAllBytes(pdfFile));
    }

    /**
     * Split the effective document. The effective document itself is not changed.
     */
    public void split(int pagePerDocument,
                      String ownerPwd, String userPwd,
                      AccessPermission ap, int encryptionKeyLength,
                      Path baseFileName,
                      Path signatureKeyPath, char[] signatureKeyPwd) throws Exception {

        if (documentProxy != null && documentProxy.getDocument() != null) {
            final boolean doSign = signatureKeyPath != null;

            String fprefix;
            String fpostfix;
            String fname = baseFileName.getFileName().toString();
            int idx = fname.lastIndexOf('.');
            if (idx < 0) {
                fprefix = baseFileName.toAbsolutePath().toString();
                fpostfix = ".pdf";
            } else {
                fprefix = baseFileName.toAbsolutePath().getParent().resolve(fname.substring(0, idx)).toString();
                fpostfix = fname.substring(idx);
            }

            int fileCount = 0;
            String alias = null;
            SignatureTool createSignature = null;

            if (doSign) {
                createSignature = new SignatureTool();
                try (InputStream is = Files.newInputStream(signatureKeyPath)) {
                    alias = createSignature.addKey(is, signatureKeyPwd);
                }
            }

            // Split a copy, the parts share resources with it, so it has to stay open until all parts are stored.
            try (PDDocument source = documentProxy.getCopy()) {
                final byte[] invoiceXml = EInvoice.extractXml(source).orElse(null);
                Splitter splitter = new Splitter();
                splitter.setSplitAtPage(pagePerDocument);
                List<PDDocument> splittedDocs = splitter.split(source);
                if (!splittedDocs.isEmpty())
                    documentProxy.reportRisks(I18n.t("ui.split.risk"), PdfRisk.tagsLost(PdfRisk.isTagged(source), splittedDocs.get(0)));
                for (PDDocument part : splittedDocs) {
                    PDDocument doc = part;
                    if (invoiceXml != null && fileCount == 0) {
                        Log.error("The document contains an invoice. It is only kept in the first of the split documents.");
                        doc = EInvoice.attach(doc, invoiceXml);
                    }
                    String docFile = String.format("%s%03d%s", fprefix, ++fileCount, fpostfix);
                    if (doSign) {
                        saveDocument(doc, ownerPwd, userPwd, ap, encryptionKeyLength, Paths.get(docFile), createSignature, alias, signatureKeyPwd);
                    } else {
                        saveDocument(doc, ownerPwd, userPwd, ap, encryptionKeyLength, Paths.get(docFile), null, null, null);
                    }
                    Log.info("Stored file '%s'", docFile);
                }
            }
        }
    }

    /**
     * Save the effective document.
     * The document is closed during the call.
     */
    public void save(String ownerPwd, String userPwd,
                     AccessPermission ap, int encryptionKeyLength,
                     Path file,
                     Path signatureKeyPath, char[] signatureKeyPwd
    ) throws Exception {
        if (documentProxy != null && documentProxy.getDocument() != null) {
            PDDocument document = documentProxy.getCopy();
            final boolean doSign = signatureKeyPath != null;
            if (doSign) {
                SignatureTool createSignature = new SignatureTool();

                String alias;
                try (InputStream is = Files.newInputStream(signatureKeyPath)) {
                    alias = createSignature.addKey(is, signatureKeyPwd);
                }
                saveDocument(document, ownerPwd, userPwd, ap, encryptionKeyLength, file, createSignature, alias, signatureKeyPwd);

            } else {
                saveDocument(document, ownerPwd, userPwd, ap, encryptionKeyLength, file, null, null, null);
            }
        }
    }


    protected void saveDocument(PDDocument document, String ownerPwd, String userPwd,
                                AccessPermission ap, int encryptionKeyLength,
                                Path file,
                                SignatureTool createSignature, String keyAlias, char[] signatureKeyPwd) throws Exception {

        try {
            final boolean doSign = createSignature != null;

            document = EInvoice.repair(document);

            if ((!ownerPwd.isEmpty()) || (!userPwd.isEmpty())) {
                if (EInvoice.hasInvoice(document))
                    Log.error("The document contains an invoice. Encryption is not allowed for PDF/A-3, so it is no valid ZUGFeRD/Factur-X document any more.");
                StandardProtectionPolicy spp = new StandardProtectionPolicy(ownerPwd, userPwd, ap);
                spp.setEncryptionKeyLength(encryptionKeyLength);
                document.protect(spp);

                if (doSign) {
                    // For some reason the encryption breaks if we sign fresh protected document.
                    // If we reload it (in protected state) it works.
                    ByteArrayOutputStream os = new ByteArrayOutputStream(5 * 1024 * 1024);
                    document.save(os, CompressParameters.NO_COMPRESSION);
                    document.close();
                    document = Loader.loadPDF(os.toByteArray(), ownerPwd.isEmpty() ? userPwd : ownerPwd);
                }
            }

            // Signing happens while saving, if it fails, an existing file must stay as it is.
            final PDDocument toSave = document;
            if (doSign)
                createSignature.addSignature(toSave, keyAlias, signatureKeyPwd);
            SafeFile.write(file, os -> {
                if (doSign)
                    toSave.saveIncremental(os);
                else
                    toSave.save(os);
            });
        } finally {
            if (document != null)
                document.close();
        }
    }


    public void close() {
        if (documentProxy != null) {
            documentProxy.close();
        }
    }
}

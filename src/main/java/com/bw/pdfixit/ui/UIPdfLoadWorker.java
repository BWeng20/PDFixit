package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfRisk;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.model.MergeOptions;
import com.bw.pdfixit.model.PdfLoadWorker;
import com.bw.pdfixit.model.PdfLoader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class UIPdfLoadWorker extends SwingWorker<PDDocument, Void> implements PdfLoadWorker {

    private final DocumentProxy documentProxy;
    protected boolean passwordNeeded = false;
    protected final Path file;
    protected final MergeOptions mo;
    protected Consumer<PDDocument> consumer;
    private final JPasswordField passwordField = new JPasswordField();
    private final String ownerPassword;
    private boolean finished = false;
    private volatile List<String> risks = List.of();

    public UIPdfLoadWorker(DocumentProxy documentProxy, Path file,
                           String ownerPassword,
                           MergeOptions mo, Consumer<PDDocument> consumer) {
        this.documentProxy = documentProxy;
        this.file = file;
        this.mo = mo;
        this.consumer = consumer;
        this.ownerPassword = ownerPassword;
    }

    @Override
    protected PDDocument doInBackground() {
        try {
            byte[] data = Files.readAllBytes(file);
            PDDocument document = PdfLoader.load(data, ownerPassword);
            documentProxy.error = null;
            // Before the security is removed, the original state is still visible.
            risks = PdfRisk.inspect(document, data);
            document.setAllSecurityToBeRemoved(true);
            return document;
        } catch (InvalidPasswordException ep) {
            documentProxy.error = I18n.t("load.wrongPassword");
            passwordNeeded = true;
            return null;
        } catch (NoSuchFileException fe) {
            documentProxy.error = I18n.t("load.notExist", file.getFileName());
            return null;
        } catch (Exception e) {
            documentProxy.error = inFile(e.getMessage() == null ? e.toString() : e.getMessage());
            return null;
        }
    }

    /**
     * The message with the name of the file in the first line: several files may be loaded.
     */
    private String inFile(String message) {
        return file.getFileName() + "\n" + message;
    }

    /**
     * Helper to request the owner password from user.
     */
    protected void requestOwnerPassword(Path file, MergeOptions mo, Consumer<PDDocument> consumer) {
        SwingUtilities.invokeLater(() -> {
            Object[] message = {I18n.t("load.enterPassword"), passwordField};
            int option = JOptionPane.showConfirmDialog(
                    null, message, I18n.t("load.passwordNeeded"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
            );

            if (option == JOptionPane.OK_OPTION) {
                String pw = new String(passwordField.getPassword());
                // retry
                documentProxy.loadWorker.add(new UIPdfLoadWorker(documentProxy, file, pw, mo, consumer));
                documentProxy.startNextLoader();
            } else {
                documentProxy.error = inFile(I18n.t("load.protected"));
                documentProxy.fireDocumentLoaded();
                // Reported: the effective document is unchanged, the error must not block later updates.
                documentProxy.error = null;
            }
        });
    }

    @Override
    protected void done() {
        try {
            finished = true;
            PDDocument document = get();
            if (!documentProxy.isClosed()) {
                if (passwordNeeded) {
                    requestOwnerPassword(file, mo, consumer);
                } else {
                    String refused = documentProxy.checkAppend(document);
                    if (refused != null) {
                        // The check has told the user why, the effective document is unchanged.
                        Log.info("Not appended (%s): %s", file, refused);
                        IOUtils.closeQuietly(document);
                    } else {
                        if (document != null) {
                            documentProxy.reportRisks(file.getFileName().toString(), risks);
                            if (consumer != null)
                                consumer.accept(document);

                        }
                        Log.debug("DONE (%s)", file);
                        documentProxy.loaderFinished(document, mo);
                    }
                }
            }
        } catch (Exception e) {
            documentProxy.error = e.getMessage();
            documentProxy.fireDocumentLoaded();
        }
        // An error is reported now (or later by the password request). It must not block the later updates of the
        // document, which are only told while no error is set.
        documentProxy.error = null;
        documentProxy.startNextLoader();
    }

    @Override
    public void cancel() {
        this.finished = true;
        this.cancel(true);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }
}

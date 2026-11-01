package com.bw.pdfixit.model;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.DocumentInformation;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.fonts.FontSource;
import com.bw.pdfixit.fonts.PdfFontEmbedder;
import com.bw.pdfixit.fonts.PdfFontMerger;
import com.bw.pdfixit.fonts.PdfFonts;
import com.bw.pdfixit.PdfRisk;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.Redactor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Simple proxy for lazy asynchronous handling of the pdf documents.
 */
public class DocumentProxy {

    public final List<Page> pages = new ArrayList<>();

    /**
     * True if the document was changed since it was loaded or {@link #clearModified} was called.
     */
    private volatile boolean modified;

    public boolean isModified() {
        return modified;
    }

    /**
     * Marks the document as changed, e.g. if it was carried over from another instance of the user interface.
     */
    public void setModified() {
        modified = true;
    }

    /**
     * Call after the document was saved.
     */
    public void clearModified() {
        modified = false;
    }
    private final List<DocumentConsumer> docConsumerList = new ArrayList<>();
    private boolean closed = false;
    private PDDocument document;
    private final RenderQueue renderQueue;
    private final List<DocumentProxy.PageConsumer> pageConsumerList = new ArrayList<>();

    public final Deque<PdfLoadWorker> loadWorker = new ArrayDeque<>();
    public PdfLoadWorker currentLoader;

    public String error;

    /**
     * Optional check for documents that shall be appended to the effective document.
     * Returns an error message to refuse the document, null to accept it.
     */
    public java.util.function.Function<PDDocument, String> appendCheck;

    /**
     * Optional check before an invoice is added (attached, or by appending a document that has one): an invoice needs
     * PDF/A-3, so the hook may ask whether the document can be switched, and does so.
     * Returns an error message to abort the operation, null to continue.
     */
    public java.util.function.Supplier<String> invoiceStandardCheck;

    /**
     * Called (from any thread) with what was changed if the document had to be fixed for PDF/A before an
     * invoice was attached, see {@link #attachInvoice}.
     */
    public java.util.function.Consumer<Map<String, Integer>> pdfaFixListener;

    /**
     * Called (from any thread) with the file name and the warnings of {@link com.bw.pdfixit.PdfRisk}
     * for each loaded file. Without a listener the warnings are written to stderr.
     */
    public java.util.function.BiConsumer<String, List<String>> riskListener;

    /**
     * Reports what is unsupported or gets lost in a loaded file, see {@link #riskListener}.
     */
    public void reportRisks(String fileName, List<String> risks) {
        if (risks.isEmpty())
            return;
        if (riskListener != null)
            riskListener.accept(fileName, risks);
        else
            risks.forEach(r -> Log.error("Warning (%s): %s", fileName, r));
    }

    /**
     * Checks a loaded document before it is appended, see {@link #appendCheck}.
     *
     * @return An error message if the document is refused, null if it can be used.
     * The first document is always accepted, it is not appended.
     */
    public String checkAppend(PDDocument candidate) {
        if (candidate == null || document == null || appendCheck == null)
            return null;
        return appendCheck.apply(candidate);
    }

    public DocumentProxy(RenderQueue renderQueue) {
        this.renderQueue = renderQueue;
    }

    /**
     * Starts the next background loader task.
     * Document for document and if all documents are loaded, renders all pages.<br>
     * This is done parallel to the UI that shows the known pages.
     */
    public synchronized void startNextLoader() {
        if (currentLoader == null || currentLoader.isFinished()) {
            do {
                currentLoader = loadWorker.pollFirst();
            } while (currentLoader != null && currentLoader.isFinished());
            if (currentLoader != null) {
                currentLoader.execute();
            }
        }
        if (currentLoader == null && DocumentProxy.this.document != null) {
            int pageCount = DocumentProxy.this.document.getNumberOfPages();
            while (pages.size() < pageCount) {
                Page page = new Page(DocumentProxy.this, pages.size() + 1, pageCount);
                pages.add(page);
            }
            renderQueue.addDocument(this);
        }
    }

    /**
     * Get the effective document
     *
     * @return Null if no document was loaded yet.
     */
    public PDDocument getDocument() {
        return document;
    }

    /**
     * Set the effective document
     */
    public void setDocument(PDDocument document) {
        this.document = document;
    }


    /**
     * Adds a Page consumer.<br>
     * The method calls the consumer directly from this inside call
     * for already available pages.
     *
     * @param consumer The consumer.
     */
    public void addPageConsumer(PageConsumer consumer) {
        ensuredNotClosed();
        synchronized (pageConsumerList) {
            pageConsumerList.remove(consumer);
            pageConsumerList.add(consumer);
        }
        synchronized (this) {
            for (Page p : pages)
                if (p.image != null)
                    consumer.pageRendered(p);
        }
    }

    public void removePageConsumer(PageConsumer consumer) {
        synchronized (pageConsumerList) {
            pageConsumerList.remove(consumer);
        }
    }

    private boolean notificationTriggered = false;
    private final List<Page> renderedPages = new LinkedList<>();

    /**
     * Called by renderer if a page was finished.<br>
     * Called from worker threads.
     */
    public void firePageRendered(Page page) {
        if (closed || page == null)
            return;
        synchronized (renderedPages) {
            renderedPages.add(page);
            if (!notificationTriggered) {
                notificationTriggered = true;
                SwingUtilities.invokeLater(() -> {
                    List<Page> pages;
                    synchronized (renderedPages) {
                        notificationTriggered = false;
                        pages = new ArrayList<>(renderedPages);
                        renderedPages.clear();
                    }
                    List<PageConsumer> l;
                    synchronized (pageConsumerList) {
                        l = new ArrayList<>(pageConsumerList);
                    }
                    Log.debug("%d pages finished", pages.size());
                    for (Page p : pages) {
                        for (var pc : l) {
                            pc.pageRendered(p);
                        }
                    }
                });
            }
        }
    }

    /**
     * Adds a consumer. Calls the consumer directly from this call if the document was loaded.
     *
     * @param consumer The consumer.
     */
    public void addDocumentConsumer(DocumentConsumer consumer) {
        ensuredNotClosed();
        synchronized (docConsumerList) {
            docConsumerList.remove(consumer);
            docConsumerList.add(consumer);
        }
        synchronized (this) {
            if (document != null) {
                consumer.documentLoaded(document);
            } else if (error != null) {
                consumer.failed(error);
            }
        }
    }

    public void removeDocumentConsumer(DocumentConsumer consumer) {
        synchronized (docConsumerList) {
            docConsumerList.remove(consumer);
        }
    }

    public void fireDocumentLoaded() {
        if (closed)
            return;
        List<DocumentConsumer> l;
        synchronized (docConsumerList) {
            l = new ArrayList<>(docConsumerList);
        }
        for (var dc : l) {
            if (error == null) {
                if (document != null)
                    dc.documentLoaded(document);
            } else
                dc.failed(error);
        }
        List<Page> toFire;
        synchronized (this) {
            toFire = new ArrayList<>(pages);
        }
        for (Page p : toFire) {
            if (p.image != null)
                firePageRendered(p);
        }
    }

    /**
     * CLose all resources and stops any background activity.<br>
     * The proxy is not usable after this call and will throw IllegalStateExceptions if you try.
     */
    public synchronized void close() {
        closed = true;
        docConsumerList.clear();
        pageConsumerList.clear();
        if (document != null) {
            try {
                document.close();
            } catch (Exception e) {
                Log.error("Error closing document: %s", e.getMessage());
            }
            document = null;
        }
        loadWorker.clear();
        if (currentLoader != null) {
            var cl = currentLoader;
            currentLoader = null;
            if (!cl.isFinished())
                cl.cancel();
        }
    }

    /**
     * Moves a page by offset. The ensure no cache issues, the complete document is re-rendered.
     *
     * @param pageNb The 1-based page number.
     * @param offset The offset
     */
    public void movePage(int pageNb, int offset) {
        ensuredDocument();
        final int pageCount = pages.size();
        if (pageNb < 1 || pageNb > pageCount) {
            throw new IllegalArgumentException("Page " + pageNb + " is out of range");
        }

        PDPageTree tree = document.getDocumentCatalog().getPages();
        final int pageIndex = pageNb - 1;
        final int targetPrevPageIndex = (offset < 0) ? (pageIndex + offset - 1) : (pageIndex + offset);

        if (targetPrevPageIndex != pageIndex) {
            if (targetPrevPageIndex >= 0) {
                if (targetPrevPageIndex < pageCount) {
                    PDPage pdPage = tree.get(pageIndex);
                    PDPage prevPage = tree.get(targetPrevPageIndex);
                    tree.remove(pageIndex);
                    tree.insertAfter(pdPage, prevPage);
                } else
                    return;
            } else if (pageIndex > 0) {
                PDPage pdPage = tree.get(pageIndex);
                PDPage nextPage = tree.get(0);
                tree.remove(pageIndex);
                tree.insertBefore(pdPage, nextPage);
            } else
                return;

            synchronized (this) {
                pages.clear();
                for (int i = 0; i < pageCount; ++i) {
                    Page page = new Page(DocumentProxy.this, i + 1, pageCount);
                    pages.add(page);
                }
            }
            modified = true;
            refirePages();
        }
    }

    /**
     * Get the rotation of a page.
     *
     * @param pageNb The 1-based page number.
     * @return The rotation in degree 0,90,180 or 270.
     */
    public int getPageRotation(int pageNb) {
        ensuredDocument();

        if (document.getNumberOfPages() >= pageNb) {
            int pageIndex = pageNb - 1;
            PDPage pd = document.getPage(pageIndex);
            return pd.getRotation();
        } else
            return 0;
    }

    public void rotatePage(int pageNb, int degree) {
        ensuredDocument();

        if (document.getNumberOfPages() >= pageNb) {
            int pageIndex = pageNb - 1;

            Page p = pages.get(pageIndex);
            p.image = null;
            p.scale = 0;
            PDPage pd = document.getPage(pageIndex);
            pd.setRotation(pd.getRotation() + degree);
            modified = true;
            refirePages();
        }
    }

    public void rotatePages(int degree) {
        ensuredDocument();

        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); ++pageIndex) {
            Page p = pages.get(pageIndex);
            p.image = null;
            p.scale = 0;
            PDPage pd = document.getPage(pageIndex);
            pd.setRotation(pd.getRotation() + degree);
        }
        modified = true;
        refirePages();
    }

    private void renderPageToImage(PDFRenderer pdfRenderer, final int pageIndex) throws IOException {

        final PDPage oldPage = document.getPage(pageIndex);

        int rotation = oldPage.getRotation();
        oldPage.setRotation(0);

        PDRectangle mediaBox = oldPage.getMediaBox();
        PDPage newPage = new PDPage(mediaBox);

        int dpi = Preferences.getInstance().getDpi();

        BufferedImage bim = pdfRenderer.renderImageWithDPI(pageIndex, dpi);
        PDImageXObject pdImage = LosslessFactory.createFromImage(document, bim);

        try (PDPageContentStream contentStream = new PDPageContentStream(document, newPage)) {
            contentStream.drawImage(pdImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
        }
        newPage.setRotation(rotation);

        var pageTree = document.getPages();
        pageTree.insertAfter(newPage, oldPage);
        pageTree.remove(oldPage);

        // should visibly the same, but to ensure WYSIWYG, force to re-render the page
        pages.get(pageIndex).image = null;
    }

    public void renderPageToImage(int pageNb) {
        ensuredDocument();
        if (document.getNumberOfPages() >= pageNb) {

            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageIndex = pageNb - 1;

            try {
                final boolean tagged = PdfRisk.isTagged(document);
                renderPageToImage(pdfRenderer, pageIndex);
                modified = true;
                // The page is an image now, its tags are gone.
                reportRisks(I18n.t("risk.toImage"), PdfRisk.tagsLost(tagged, null));
                refirePages();
            } catch (Exception e) {
                Log.exception(e);
            }
        }
    }

    public void renderAllPagesToImage() {
        ensuredDocument();
        try {
            final boolean tagged = PdfRisk.isTagged(document);
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); ++pageIndex) {
                renderPageToImage(pdfRenderer, pageIndex);
            }
            modified = true;
            reportRisks(I18n.t("risk.toImage"), PdfRisk.tagsLost(tagged, null));
        } catch (Exception e) {
            Log.exception(e);
        }
        refirePages();

    }

    /**
     * Permanently removes the regions, see {@link Redactor}. The affected pages are replaced by images.
     *
     * @param regions Regions in unrotated page coordinates.
     */
    public void redact(List<Redactor.Region> regions) throws IOException {
        ensuredDocument();
        Preferences prefs = Preferences.getInstance();
        final boolean tagged = PdfRisk.isTagged(document);
        Redactor.redact(document, regions, prefs.getDpi(), prefs.getRedactionColor());
        if (!regions.isEmpty())
            modified = true;
        // The redacted pages are images now, their tags are gone.
        if (!regions.isEmpty())
            reportRisks(I18n.t("risk.redaction"), PdfRisk.tagsLost(tagged, null));
        synchronized (this) {
            for (Redactor.Region r : regions) {
                Page p = pages.get(r.pageNb - 1);
                p.image = null;
                p.scale = 0;
            }
        }
        refirePages();
    }

    /**
     * Attaches an invoice XML (ZUGFeRD/Factur-X) to the effective document, see {@link EInvoice#attachXml}.
     * An existing invoice is replaced. The visible pages are not changed.<br>
     * The document is fixed for PDF/A first, see {@link #fixPdfa}, then the invoice is attached.
     * The {@link #pdfaFixListener} gets what was fixed.
     *
     * @throws EInvoice.NotConformException If the result was no valid invoice. The PDF/A fixes are kept then.
     */
    public void attachInvoice(byte[] xml) throws IOException {
        ensuredDocument();
        String refused = invoiceStandardCheck == null ? null : invoiceStandardCheck.get();
        if (refused != null)
            throw new EInvoice.NotConformException(refused);
        Map<String, Integer> changes;
        try {
            changes = fixPdfa();
        } catch (com.bw.pdfixit.fonts.FontEmbedException e) {
            throw new EInvoice.NotConformException(e.getMessage());
        }
        if (!changes.isEmpty() && pdfaFixListener != null)
            pdfaFixListener.accept(changes);
        PDDocument newDocument = Loader.loadPDF(EInvoice.attachXmlStrict(getBytes(), xml));
        PDDocument oldDocument;
        synchronized (this) {
            // The library replaces the producer etc., keep the ones of the document.
            DocumentInformation.copy(document, newDocument);
            oldDocument = document;
            document = newDocument;
        }
        IOUtils.closeQuietly(oldDocument);
        modified = true;
        fireDocumentLoaded();
    }

    /**
     * Fixes what PDF/A forbids, see {@link PdfStandard#fixPdfaIssues}. The thumbnails are redrawn if the
     * document was changed.
     *
     * @return What was changed and how often, empty if nothing.
     */
    public Map<String, Integer> fixPdfa() throws IOException {
        ensuredDocument();
        Map<String, Integer> changes = PdfStandard.fixPdfaIssues(document);
        if (!changes.isEmpty()) {
            modified = true;
            redrawAllPages();
        }
        return changes;
    }

    /**
     * Runs the metadata fixer of veraPDF on the document, if it declares PDF/A. The fixed document replaces the
     * current one, unless veraPDF could not repair it, see {@link com.bw.pdfixit.PdfaValidator.MetadataFix#applied}.
     * The thumbnails are redrawn if the document was changed. The listeners are not told: the caller shows the result.
     *
     * @return What veraPDF did, the fixes are empty if it changed nothing.
     */
    public com.bw.pdfixit.PdfaValidator.MetadataFix fixWithVeraPdf() throws IOException {
        ensuredDocument();
        Map<String, Integer> changes = new java.util.LinkedHashMap<>();
        com.bw.pdfixit.PdfaValidator.MetadataFix fix = applyVeraPdfFixer(changes);
        if (!changes.isEmpty()) {
            modified = true;
            redrawAllPages();
        }
        return fix;
    }

    private com.bw.pdfixit.PdfaValidator.MetadataFix applyVeraPdfFixer(Map<String, Integer> changes)
            throws IOException {
        if (PdfStandard.pdfaPart(document) == 0)
            return new com.bw.pdfixit.PdfaValidator.MetadataFix(null, "The document declares no PDF/A", List.of());
        com.bw.pdfixit.PdfaValidator.MetadataFix fix = com.bw.pdfixit.PdfaValidator.fixMetadata(getBytes());
        Log.info("veraPDF metadata fixer: %s %s", fix.status(), fix.fixes());
        if (fix.pdf() == null)
            return fix;
        PDDocument fixed = Loader.loadPDF(fix.pdf());
        PDDocument old;
        synchronized (this) {
            old = document;
            document = fixed;
        }
        IOUtils.closeQuietly(old);
        fix.fixes().forEach(f -> changes.merge("veraPDF: " + f, 1, Integer::sum));
        if (fix.fixes().isEmpty())
            changes.merge(I18n.t("ui.fixer.fixed"), 1, Integer::sum);
        return fix;
    }

    /**
     * Shows what {@link #fixPdfa} would change, without touching the document.
     *
     * @return What would be changed and how often, empty if nothing or if the document declares no PDF/A.
     */
    public Map<String, Integer> previewPdfaRepair() throws IOException {
        ensuredDocument();
        PdfStandardProfile profile = PdfStandard.profile(document);
        return profile == null || !profile.isPdfa() ? Map.of() : PdfStandard.preview(getBytes(), profile);
    }

    /**
     * Converts the document to a PDF/A level, see {@link PdfStandard#convertTo}. The thumbnails are redrawn and
     * listeners are told if the document was changed.
     *
     * @param target The level, null to remove the PDF/A declaration.
     * @return What was changed and how often, empty if nothing.
     */
    public Map<String, Integer> convertToStandard(PdfStandardProfile target) throws IOException {
        return convertToStandard(target, null);
    }

    /**
     * Like {@link #convertToStandard(PdfStandardProfile)}, and sets the PDF version.
     *
     * @param version The PDF version, null to keep it.
     */
    public Map<String, Integer> convertToStandard(PdfStandardProfile target, Float version) throws IOException {
        Map<String, Integer> changes = convertToStandardQuietly(target, version);
        if (!changes.isEmpty())
            fireDocumentLoaded();
        return changes;
    }

    /**
     * Like {@link #convertToStandard}, but the listeners are not told: for operations which continue with the document.
     */
    public Map<String, Integer> convertToStandardQuietly(PdfStandardProfile target) throws IOException {
        return convertToStandardQuietly(target, null);
    }

    /**
     * Like {@link #convertToStandardQuietly(PdfStandardProfile)}, and sets the PDF version.
     */
    public Map<String, Integer> convertToStandardQuietly(PdfStandardProfile target, Float version) throws IOException {
        ensuredDocument();
        // A conversion that fails halfway would leave the document changed (e.g. declared as PDF/A), so it is
        // tried on a copy first.
        try (PDDocument copy = Loader.loadPDF(getBytes())) {
            PdfStandard.convertTo(copy, target, version);
        }
        Map<String, Integer> changes = PdfStandard.convertTo(document, target, version);
        if (!changes.isEmpty()) {
            modified = true;
            redrawAllPages();
        }
        return changes;
    }

    /**
     * Checks the document with veraPDF, see {@link com.bw.pdfixit.PdfaValidator}.
     *
     * @return The result of the verification.
     */
    public com.bw.pdfixit.ValidationResult validatePdfa() throws IOException {
        ensuredDocument();
        return com.bw.pdfixit.PdfaValidator.validate(getBytes());
    }

    /**
     * Incremented each time the content of the pages was changed in place (e.g. a font was replaced).
     * A page rendered while this changes is rendered again, see {@link RenderQueue}.
     */
    private volatile int redrawGeneration;

    public int getRedrawGeneration() {
        return redrawGeneration;
    }

    /**
     * Redraws all thumbnails after the content of the pages was changed in place.<br>
     * The document caches the loaded fonts and other resources, so the cache is dropped too, otherwise the
     * pages would be drawn with the old objects.
     */
    private void redrawAllPages() {
        ++redrawGeneration;
        document.setResourceCache(org.apache.pdfbox.pdmodel.ResourceCacheFactory.createResourceCache());
        synchronized (this) {
            for (Page p : pages) {
                p.image = null;
                p.scale = 0;
            }
        }
        refirePages();
    }

    /**
     * Lists the fonts of the effective document.
     */
    public List<PdfFonts.FontInfo> getFonts() {
        ensuredDocument();
        return PdfFonts.list(document);
    }

    /**
     * Counts the fonts which exist more than once with the same content, see {@link PdfFontMerger}.
     */
    public PdfFontMerger.Result findDuplicateFonts() throws IOException {
        ensuredDocument();
        return PdfFontMerger.find(document);
    }

    /**
     * Merges fonts which exist more than once with the same content, see {@link PdfFontMerger}.
     * The pages look the same, so the thumbnails stay.
     */
    public PdfFontMerger.Result mergeDuplicateFonts() throws IOException {
        ensuredDocument();
        PdfFontMerger.Result result = PdfFontMerger.merge(document);
        if (result.groups() > 0)
            modified = true;
        return result;
    }

    /**
     * Replaces the font program of a font by a bundled font, see {@link PdfFontEmbedder#replace}.
     * The thumbnails are redrawn.
     *
     * @param font The font dictionary, see {@link #getFonts}.
     */
    public void replaceFont(org.apache.pdfbox.cos.COSDictionary font, FontSource source) throws IOException {
        ensuredDocument();
        PdfFontEmbedder.replace(document, font, source);
        modified = true;
        redrawAllPages();
    }

    /**
     * Removes the invoice XML from the effective document.
     *
     * @return True if there was an invoice.
     */
    public boolean removeInvoice() throws IOException {
        ensuredDocument();
        boolean removed = EInvoice.removeXml(document);
        if (removed)
            modified = true;
        if (removed)
            // Let the UI update what depends on the invoice (e.g. the standards label).
            fireDocumentLoaded();
        return removed;
    }

    public void deletePage(int pageNb) {

        ensuredDocument();

        if (document.getNumberOfPages() >= pageNb) {

            int pageIndex = pageNb - 1;

            // 0-based index!
            document.removePage(pageIndex);
            modified = true;
            pages.remove(pageIndex);
            int pageCount = pages.size();
            for (int i = 0; i < pageCount; ++i) {
                Page p = pages.get(i);
                p.pageNb = i + 1;
                p.pageCount = pageCount;
            }
            refirePages();
        }
    }

    private void refirePages() {
        fireDocumentLoaded();
        boolean imageMissing = false;
        List<Page> pagesToFire;
        synchronized (this) {
            pagesToFire = new ArrayList<>(pages);
        }
        for (Page p : pagesToFire) {
            if (p.image == null) {
                imageMissing = true;
                break;
            }
        }
        if (imageMissing) {
            renderQueue.addDocument(this);
        }
    }

    public void ensuredNotClosed() {
        if (closed)
            throw new IllegalStateException("Document is closed");
    }

    protected void ensuredDocument() {
        ensuredNotClosed();
        if (document == null)
            throw new IllegalStateException("No Document loaded");
    }

    public synchronized Page getPage(int pageNb) {
        ensuredDocument();
        if (pageNb <= pages.size())
            return pages.get(pageNb - 1);
        else
            return null;
    }


    public boolean isClosed() {
        return closed;
    }

    public int getPageCount() {
        return pages.size();
    }

    public synchronized boolean needsRendering() {
        // Closed documents don't have pages to render!
        if (!closed) {
            for (Page p : pages) {
                if (p.image == null && p.error == null)
                    return true;
            }
        }
        return false;
    }

    public synchronized void loaderFinished(PDDocument document, MergeOptions mo) {
        boolean fireLoaded = false;
        int oldPageCount = -1;
        if (!isClosed()) {
            {
                if (document != null) {
                    int pageCount;
                    if (this.getDocument() == null) {
                        this.setDocument(document);
                        this.pages.clear();
                        pageCount = this.getDocument().getNumberOfPages();
                    } else {
                        // Append pages
                        oldPageCount = this.getDocument().getNumberOfPages();
                        try {
                            if (EInvoice.hasInvoice(this.getDocument()) && EInvoice.hasInvoice(document)) {
                                // Both would use the same attachment name, one invoice must go.
                                Log.error("Both documents contain an invoice. Only the invoice of the first document is kept.");
                                EInvoice.removeXml(document);
                            }
                            final boolean tagged = PdfRisk.isTagged(this.getDocument()) || PdfRisk.isTagged(document);
                            PDFMergerUtility pdfMergerUtility = new PDFMergerUtility();
                            pdfMergerUtility.appendDocument(this.getDocument(), document);
                            modified = true;
                            reportRisks(I18n.t("risk.merge"), PdfRisk.tagsLost(tagged, this.getDocument()));
                        } catch (Exception e) {
                            Log.error("Failed to merge. %s", e.getMessage());
                            this.error = I18n.t("proxy.mergeFailed", e.getMessage());
                            oldPageCount = -1;
                        } finally {
                            IOUtils.closeQuietly(document);
                        }
                        // Count what was really appended, the merge may have failed.
                        pageCount = this.getDocument().getNumberOfPages();
                    }
                    // zipper merge if requested (start is 1-based "insert before page", so the last page is allowed)
                    if (oldPageCount > 0 && mo.startPageNb > 0 && mo.startPageNb <= oldPageCount) {
                        zipperMerge(oldPageCount, pageCount, mo);
                        this.pages.clear();
                    }

                    while (this.pages.size() < pageCount) {
                        Page page = new Page(this, this.pages.size() + 1, pageCount);
                        this.pages.add(page);
                    }

                    // Tell anyone, that a new document is loaded.
                    fireLoaded = true;
                } else if (this.error != null) {
                    fireLoaded = true;
                }
            }
        }
        if (fireLoaded) {
            fireDocumentLoaded();
        }
    }


    /**
     * Moves the appended pages between the original pages.<br>
     * The appended pages start at index oldPageCount. Starting before page {@link MergeOptions#startPageNb},
     * {@link MergeOptions#segmentLength} new pages are inserted, then {@link MergeOptions#gapLength}
     * original pages are skipped, and so on. New pages left over when the original pages run out stay at the end.
     * A segment length &lt;= 0 inserts all new pages in one block.
     *
     * @param oldPageCount Number of pages before the merge.
     * @param pageCount    Number of pages after the merge.
     */
    private void zipperMerge(int oldPageCount, int pageCount, MergeOptions mo) {
        PDPageTree tree = this.getDocument().getDocumentCatalog().getPages();
        int targetPageIndex = mo.startPageNb - 1;
        int segmentCount = 0;

        // Moving a page in front of the remaining new pages doesn't shift them,
        // so the next new page to move is always at the next index.
        for (int pageToMoveIndex = oldPageCount;
             pageToMoveIndex < pageCount && targetPageIndex < pageToMoveIndex;
             ++pageToMoveIndex) {

            PDPage pdPage = tree.get(pageToMoveIndex);
            tree.remove(pdPage);
            if (targetPageIndex > 0)
                tree.insertAfter(pdPage, tree.get(targetPageIndex - 1));
            else
                tree.insertBefore(pdPage, tree.get(0));
            ++targetPageIndex;

            if (mo.segmentLength > 0 && ++segmentCount >= mo.segmentLength) {
                segmentCount = 0;
                if (mo.gapLength > 0)
                    targetPageIndex += mo.gapLength;
            }
        }
    }

    /**
     * Interface to notify about rendered pages.
     */
    public interface PageConsumer {
        void pageRendered(Page page);
    }

    /**
     * Interface to notify if the document was loaded.
     */
    public interface DocumentConsumer {

        /**
         * Load operation was successful .
         *
         * @param document The effective main document, never null
         */
        void documentLoaded(PDDocument document);

        void failed(String error);
    }

    /**
     * Brute force copy. Writes to byte array and load it.
     *
     * @throws IOException If some write operation failed.
     */
    public PDDocument getCopy() throws IOException {
        byte[] bytes = getBytes();
        return bytes == null ? null : Loader.loadPDF(bytes);
    }

    /**
     * Writes the effective document (without any security) to memory.
     *
     * @return The PDF or null if no document is loaded.
     * @throws IOException If some write operation failed.
     */
    public byte[] getBytes() throws IOException {
        if (document != null) {
            document.setAllSecurityToBeRemoved(true);
            ByteArrayOutputStream os = new ByteArrayOutputStream(5 * 1024 * 1024);
            document.save(os, CompressParameters.NO_COMPRESSION);
            return os.toByteArray();
        } else
            return null;
    }

}

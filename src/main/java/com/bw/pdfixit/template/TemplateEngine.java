package com.bw.pdfixit.template;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.ImportedFonts;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates the visible pages of an invoice from a template, a layout and the content:
 * <ol>
 * <li>prepare the template ({@link TemplatePreparer}: continuation PDF, PDF/A level, tags removed)</li>
 * <li>lay out the body ({@link BodyFlow}), which gives the page count</li>
 * <li>build the pages: page 1 of the template, then copies of the page of the following pages</li>
 * <li>draw the fixed elements ({@link Overlay}) and the body on each page, now that the page count is known</li>
 * <li>level a: the formal structure ({@link PdfTagger#tagPages})</li>
 * </ol>
 * The result is not attached to an invoice XML yet. Errors don't stop the drawing, so a preview shows them; the
 * caller must not use a result with errors as an invoice.
 */
public final class TemplateEngine {

    private TemplateEngine() {
    }

    /**
     * The result.
     *
     * @param pdf          The generated PDF, null if {@link #choiceNeeded}.
     * @param pageCount    The number of pages of the invoice.
     * @param problems     Errors and warnings with element and page.
     * @param choiceNeeded True if the invoice needs more than one page and the source of the following pages is not
     *                     known: the template has one page and the layout has no {@link Layout#continuation} (or the
     *                     PDF it names can't be read). The user has to choose, then the engine runs again.
     * @param placements   Where labels and values were drawn (for the layout editor), empty if there is no PDF.
     */
    public record Result(byte[] pdf, int pageCount, List<Problem> problems, boolean choiceNeeded,
                         List<Placement> placements) {
        public boolean hasErrors() {
            return choiceNeeded || problems.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR);
        }
    }

    /**
     * Generates the invoice pages with the imported fonts of the user.
     */
    public static Result render(byte[] template, Layout layout, InvoiceContent content, Locale language,
                                PdfStandardProfile level) throws IOException {
        return render(template, layout, content, language, level, ImportedFonts.fonts());
    }

    /**
     * Generates the invoice pages.
     *
     * @param template The template PDF.
     * @param language The printed language.
     * @param level    The PDF/A level, see {@link TemplatePreparer#LEVELS}.
     * @param fonts    The imported fonts the font of the layout may refer to.
     * @throws com.bw.pdfixit.fonts.FontEmbedException If a font of the template can't be embedded.
     * @throws org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException If the template needs a password.
     */
    public static Result render(byte[] template, Layout layout, InvoiceContent content, Locale language,
                                PdfStandardProfile level, List<ImportedFont> fonts) throws IOException {
        byte[] continuationPdf = null;
        if (layout.continuation == Layout.Continuation.PDF && layout.continuationFile != null) {
            try {
                continuationPdf = Files.readAllBytes(layout.continuationFile);
            } catch (IOException e) {
                // The user is asked again, see below.
            }
        }
        try (TemplatePreparer.Prepared prepared = TemplatePreparer.prepare(template, continuationPdf, level)) {
            PDDocument doc = prepared.document();
            PDPage first = doc.getPage(0);
            Layout.Continuation source = source(layout, prepared.templatePages(), continuationPdf != null);

            Overlay overlay = new Overlay(doc, layout, language, fonts);
            List<Problem> problems = overlay.problems();
            Region firstRegion = layout.region(false);
            Rect nextRegion = nextRegion(layout, source);
            List<List<BodyFlow.Placed>> body;
            BodyFlow flow = null;
            if (firstRegion == null) {
                if (!layout.parts.isEmpty())
                    problems.add(new Problem(Problem.Severity.ERROR, I18n.t("template.check.noBody"), null));
                body = List.of(List.of());
            } else {
                // The units are made for one width: the narrower region decides.
                double width = Math.min(firstRegion.rect().width(), nextRegion.width());
                flow = new BodyFlow(overlay, content, width);
                flow.build();
                body = flow.paginate(firstRegion.rect().height(), nextRegion.height());
            }
            int n = body.size();
            if (n > 1 && source == null)
                return new Result(null, n, problems, true, List.of());

            List<PDPage> pages = new ArrayList<>();
            pages.add(first);
            PDPage sourcePage = switch (source == null ? Layout.Continuation.BLANK : source) {
                case TEMPLATE -> doc.getPage(1);
                case PAGE1 -> first;
                case PDF -> doc.getPage(doc.getNumberOfPages() - 1);
                case BLANK -> null;
            };
            for (int i = 1; i < n; ++i)
                pages.add(sourcePage == null ? blankPage(first) : copyPage(sourcePage));
            // The page tree is the result now: template pages that are not used as such are dropped.
            List<PDPage> old = new ArrayList<>();
            doc.getPages().forEach(old::add);
            for (PDPage p : old)
                doc.removePage(p);
            for (PDPage p : pages)
                doc.addPage(p);

            for (int i = 0; i < n; ++i) {
                PDPage page = pages.get(i);
                overlay.drawFixed(page, i > 0, content.values, i + 1, n);
                if (flow != null)
                    flow.draw(page, i == 0 ? firstRegion.rect() : nextRegion, body.get(i), i + 1);
            }
            if (level == PdfStandardProfile.PDFA_3A)
                PdfTagger.tagPages(doc);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os);
            return new Result(os.toByteArray(), n, problems, false, List.copyOf(overlay.placements()));
        }
    }

    /**
     * The source of the following pages: the one of the layout, else page 2 of the template if it has one. Null if
     * the user has to choose: no choice and a one-page template, page 2 chosen but the template has one page, or the
     * chosen PDF can't be read.
     */
    static Layout.Continuation source(Layout layout, int templatePages, boolean continuationPdfRead) {
        Layout.Continuation c = layout.continuation;
        if (c == null)
            return templatePages >= 2 ? Layout.Continuation.TEMPLATE : null;
        if (c == Layout.Continuation.TEMPLATE && templatePages < 2)
            return null;
        if (c == Layout.Continuation.PDF && !continuationPdfRead)
            return null;
        return c;
    }

    /**
     * The body region of the following pages: the one of the layout; without one it is derived from the first:
     * the same region if page 1 is used again, otherwise the same left edge and width, from 25 mm below the top to
     * the lower edge of the first region.
     *
     * @return The region, null if the layout has no body region at all.
     */
    static Rect nextRegion(Layout layout, Layout.Continuation source) {
        Region next = layout.region(true);
        if (next != null)
            return next.rect();
        Region first = layout.region(false);
        if (first == null)
            return null;
        Rect r = first.rect();
        if (source == Layout.Continuation.PAGE1 || r.bottom() <= 25)
            return r;
        return new Rect(r.x(), 25, r.width(), r.bottom() - 25);
    }

    /**
     * An empty page like the first one (size, crop box, rotation).
     */
    static PDPage blankPage(PDPage first) {
        PDPage page = new PDPage(first.getMediaBox());
        page.setCropBox(first.getCropBox());
        page.setRotation(first.getRotation());
        page.setResources(new PDResources());
        return page;
    }

    /**
     * A shallow copy of a page: it shares the content streams and the objects of the resources (fonts and images are
     * stored once), with its own content array, its own resource sub-dictionaries (the overlay adds entries there),
     * its own copies of the annotations and without {@code StructParents}.
     */
    static PDPage copyPage(PDPage source) {
        COSDictionary d = new COSDictionary(source.getCOSObject());
        d.removeItem(COSName.PARENT);
        d.removeItem(COSName.STRUCT_PARENTS);
        COSBase contents = source.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof COSArray a) {
            COSArray copy = new COSArray();
            for (int i = 0; i < a.size(); ++i)
                copy.add(a.get(i));
            d.setItem(COSName.CONTENTS, copy);
        }
        // Also inherited resources and boxes become entries of the copy.
        PDResources resources = source.getResources();
        if (resources != null) {
            COSDictionary copy = new COSDictionary(resources.getCOSObject());
            for (COSName key : resources.getCOSObject().keySet())
                if (resources.getCOSObject().getDictionaryObject(key) instanceof COSDictionary sub
                        && !(sub instanceof COSStream))
                    copy.setItem(key, new COSDictionary(sub));
            d.setItem(COSName.RESOURCES, copy);
        }
        d.setItem(COSName.MEDIA_BOX, source.getMediaBox().getCOSObject());
        d.setItem(COSName.CROP_BOX, source.getCropBox().getCOSObject());
        d.setInt(COSName.ROTATE, source.getRotation());
        COSArray annotations = source.getCOSObject().getCOSArray(COSName.ANNOTS);
        if (annotations != null) {
            COSArray copy = new COSArray();
            for (int i = 0; i < annotations.size(); ++i)
                if (annotations.getObject(i) instanceof COSDictionary a) {
                    COSDictionary c = new COSDictionary(a);
                    c.setItem(COSName.P, d);
                    c.removeItem(COSName.STRUCT_PARENT);
                    // A popup belongs to its markup annotation on the original page.
                    c.removeItem(COSName.POPUP);
                    copy.add(c);
                }
            d.setItem(COSName.ANNOTS, copy);
        }
        return new PDPage(d);
    }
}

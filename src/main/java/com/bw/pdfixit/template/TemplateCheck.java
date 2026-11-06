package com.bw.pdfixit.template;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.PdfTagger;
import com.bw.pdfixit.Redactor;
import com.bw.pdfixit.fonts.PdfFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a template and its layout when the template is chosen, before an invoice is generated. No UI involved.<br>
 * Errors stop the generation; warnings and infos are shown to the user.
 */
public final class TemplateCheck {

    /**
     * Two page sizes differ if they differ by more than this (mm).
     */
    private static final double SIZE_TOLERANCE = 0.5;

    private static final Pattern WORD = Pattern.compile("\\S+");

    private TemplateCheck() {
    }

    /**
     * True if one of the problems is an error.
     */
    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR);
    }

    /**
     * Checks a template file with the layout found for it.
     */
    public static List<Problem> check(Path template, LayoutFiles.Found found) {
        List<Problem> problems = new ArrayList<>();
        if (found.isDefault())
            problems.add(info("template.check.defaultLayout"));
        if (found.foreignBeside() != null)
            problems.add(info("template.check.foreignJson", found.foreignBeside().getFileName(),
                    found.file() != null ? found.file() : LayoutFiles.defaultDirectory()));
        byte[] pdf;
        try {
            pdf = Files.readAllBytes(template);
        } catch (IOException e) {
            problems.add(error("template.check.unreadable", e.getMessage()));
            return problems;
        }
        problems.addAll(check(pdf, found.layout()));
        return problems;
    }

    /**
     * Checks a template with a layout.
     *
     * @param template The template PDF.
     * @param layout   The layout.
     */
    public static List<Problem> check(byte[] template, Layout layout) {
        List<Problem> problems = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(template)) {
            check(document, layout, problems);
        } catch (InvalidPasswordException e) {
            problems.add(error("template.check.encrypted"));
        } catch (IOException e) {
            problems.add(error("template.check.unreadable", e.getMessage()));
        }
        return problems;
    }

    private static void check(PDDocument document, Layout layout, List<Problem> problems) throws IOException {
        int pages = document.getNumberOfPages();
        if (pages == 0) {
            problems.add(error("template.check.noPages"));
            return;
        }
        PDPage first = document.getPage(0);
        double[] size = sizeMm(first);
        if (layout.pageCount != null && layout.pageWidth != null && layout.pageHeight != null
                && (layout.pageCount != pages || Math.abs(layout.pageWidth - size[0]) > SIZE_TOLERANCE
                || Math.abs(layout.pageHeight - size[1]) > SIZE_TOLERANCE))
            problems.add(error("template.check.otherFile", layout.pageCount, mm(layout.pageWidth),
                    mm(layout.pageHeight), pages, mm(size[0]), mm(size[1])));
        if (PdfTagger.isTagged(document))
            problems.add(info("template.check.tagsRemoved"));
        for (PdfFonts.FontInfo font : PdfFonts.list(document)) {
            String reason = font.embedded() ? null : font.cannotReplaceReason();
            if (reason != null)
                problems.add(error("template.check.font", font.name(), reason));
        }

        // The page of the following pages: its size and its text.
        PDDocument otherDocument = null;
        try {
            PDPage nextPage = null;
            Layout.Continuation continuation = layout.continuation;
            if (continuation == null && pages >= 2)
                continuation = Layout.Continuation.TEMPLATE;
            if (continuation == Layout.Continuation.TEMPLATE) {
                if (pages >= 2)
                    nextPage = document.getPage(1);
                else
                    problems.add(error("template.check.noPage2"));
            } else if (continuation == Layout.Continuation.PAGE1) {
                nextPage = first;
            } else if (continuation == Layout.Continuation.PDF) {
                otherDocument = loadContinuation(layout, problems);
                if (otherDocument != null)
                    nextPage = otherDocument.getPage(0);
            }
            double[] nextSize = nextPage == null ? size : sizeMm(nextPage);
            List<Redactor.Region> words = Redactor.findText(document, WORD);
            List<Rectangle2D> firstText = text(words, 1, first);
            List<Rectangle2D> nextText = List.of();
            if (nextPage == first)
                nextText = firstText;
            else if (nextPage != null && otherDocument == null)
                nextText = text(words, 2, nextPage);
            else if (nextPage != null)
                nextText = text(Redactor.findText(otherDocument, WORD), 1, nextPage);
            checkElements(layout, problems, size, nextSize, firstText, nextText);
        } finally {
            if (otherDocument != null)
                otherDocument.close();
        }
    }

    private static PDDocument loadContinuation(Layout layout, List<Problem> problems) {
        Path file = layout.continuationFile;
        if (file == null) {
            problems.add(error("template.check.continuationMissing", "-"));
            return null;
        }
        try {
            PDDocument other = Loader.loadPDF(Files.readAllBytes(file));
            if (other.getNumberOfPages() > 0)
                return other;
            other.close();
        } catch (IOException e) {
            // Reported below.
        }
        problems.add(error("template.check.continuationMissing", file));
        return null;
    }

    private static void checkElements(Layout layout, List<Problem> problems, double[] size, double[] nextSize,
                                      List<Rectangle2D> firstText, List<Rectangle2D> nextText) {
        Set<String> fixedIds = new HashSet<>();
        for (Element e : layout.fields) {
            checkKind(layout, e, true, problems);
            if (e.type == Element.Type.FIELD && !fixedIds.add(e.id + (e.next ? "/next" : "")))
                problems.add(error(e, "template.check.duplicate"));
            double[] s = e.next ? nextSize : size;
            List<Rectangle2D> text = e.next ? nextText : firstText;
            checkRect(e, e.rect, s, text, problems);
            if (e.label != null && e.label.rect != null && e.isLabelShown())
                checkRect(e, e.label.rect, s, text, problems);
        }
        Set<String> flowingIds = new HashSet<>();
        for (Element e : layout.parts) {
            checkKind(layout, e, false, problems);
            if (e.type != Element.Type.FIELD)
                continue;
            if (fixedIds.contains(e.id) || fixedIds.contains(e.id + "/next"))
                problems.add(error(e, "template.check.fixedAndFlowing"));
            if (!flowingIds.add(e.id))
                problems.add(error(e, "template.check.duplicate"));
        }
        if (!layout.parts.isEmpty() && layout.region(false) == null)
            problems.add(error("template.check.noBody"));
        for (Region r : layout.regions) {
            String name = I18n.t("template.check.body");
            double[] s = r.next() ? nextSize : size;
            if (!r.rect().isInside(s[0], s[1]))
                problems.add(new Problem(Problem.Severity.ERROR, I18n.t("template.check.outsidePage", name), name));
            else if (containsText(r.rect(), r.next() ? nextText : firstText))
                problems.add(new Problem(Problem.Severity.WARNING, I18n.t("template.check.templateText", name), name));
        }
    }

    /**
     * Checks the id, the placement and the picture of an element.
     */
    private static void checkKind(Layout layout, Element e, boolean fixed, List<Problem> problems) {
        switch (e.type) {
            case FIELD -> {
                LayoutField f = e.field();
                if (f == null)
                    problems.add(error(e, "template.check.unknownField"));
                else if (fixed && f.placement == LayoutField.Placement.FLOWING_ONLY)
                    problems.add(error(e, "template.check.flowingOnly"));
                else if (!fixed && f.placement == LayoutField.Placement.FIXED_ONLY)
                    problems.add(error(e, "template.check.fixedOnly"));
            }
            case IMAGE -> {
                if (!layout.images.containsKey(e.image))
                    problems.add(error(e, "template.check.missingImage"));
            }
            case TEXT -> {
            }
        }
    }

    private static void checkRect(Element e, Rect rect, double[] size, List<Rectangle2D> text,
                                  List<Problem> problems) {
        if (!rect.isInside(size[0], size[1]))
            problems.add(error(e, "template.check.outsidePage"));
        else if (containsText(rect, text))
            problems.add(new Problem(Problem.Severity.WARNING, I18n.t("template.check.templateText", e.name()), e.name()));
    }

    private static boolean containsText(Rect rect, List<Rectangle2D> text) {
        Rectangle2D r = rect.toPoints();
        for (Rectangle2D t : text)
            if (r.intersects(t))
                return true;
        return false;
    }

    /**
     * The areas of the text of a page in points from the top left corner of the displayed page.
     */
    private static List<Rectangle2D> text(List<Redactor.Region> words, int pageNb, PDPage page) {
        List<Rectangle2D> result = new ArrayList<>();
        PDRectangle box = page.getCropBox();
        for (Redactor.Region r : words)
            if (r.pageNb == pageNb) {
                Rectangle2D view = Redactor.pageToView(r.area, box, page.getRotation());
                // findText adds a point of margin around each line, don't let it touch a neighbouring region.
                result.add(new Rectangle2D.Double(view.getX() + 1, view.getY() + 1,
                        Math.max(0, view.getWidth() - 2), Math.max(0, view.getHeight() - 2)));
            }
        return result;
    }

    /**
     * Width and height of the displayed page in mm.
     */
    static double[] sizeMm(PDPage page) {
        PDRectangle box = page.getCropBox();
        double w = Rect.toMm(box.getWidth());
        double h = Rect.toMm(box.getHeight());
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        return rotation == 90 || rotation == 270 ? new double[]{h, w} : new double[]{w, h};
    }

    private static String mm(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static Problem error(String key, Object... args) {
        return new Problem(Problem.Severity.ERROR, I18n.t(key, args), null);
    }

    private static Problem error(Element e, String key) {
        return new Problem(Problem.Severity.ERROR, I18n.t(key, e.name()), e.name());
    }

    private static Problem info(String key, Object... args) {
        return new Problem(Problem.Severity.INFO, I18n.t(key, args), null);
    }
}

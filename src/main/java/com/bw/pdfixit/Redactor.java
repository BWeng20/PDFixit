package com.bw.pdfixit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Permanently removes content from pages.<br>
 * A redacted page is rendered to an image, the redacted areas are painted black and the page is replaced by that
 * image. Text, vector and image objects below the black boxes (and everything else on the page) are
 * therefore really gone - not just covered.<br>
 * Annotations (links, form fields) of redacted pages are dropped.
 * <p>
 * All areas are given in points (1/72 inch), relative to the upper left corner of the unrotated crop box
 * (see {@link #viewToPage} to convert from a rotated page).
 */
public final class Redactor {

    private Redactor() {
    }

    /**
     * An area on a page.
     */
    public static class Region {
        /**
         * 1-based page number.
         */
        public final int pageNb;
        public final Rectangle2D area;

        public Region(int pageNb, Rectangle2D area) {
            this.pageNb = pageNb;
            this.area = area;
        }

    }

    /**
     * Finds all occurrences of a pattern in the text of the document.
     * Matches may span words and lines, for each line of a match one region is returned.
     *
     * @param document The document.
     * @param pattern  The pattern to search for.
     * @return The areas of the matches, never null.
     */
    public static List<Region> findText(PDDocument document, Pattern pattern) throws IOException {
        List<Region> result = new ArrayList<>();
        PageTextCollector collector = new PageTextCollector(pattern, result);
        try {
            collector.getText(document);
        } catch (SearchTimeoutException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result;
    }

    /**
     * Finds pages, where a text search can't work: they have (almost) no extractable text, e.g. scans.
     *
     * @param document The document.
     * @return The 1-based numbers of the pages with less than a few characters.
     */
    public static List<Integer> pagesWithoutText(PDDocument document) throws IOException {
        List<Integer> result = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        for (int i = 1; i <= document.getNumberOfPages(); ++i) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text = stripper.getText(document);
            if (text.codePoints().filter(c -> !Character.isWhitespace(c)).count() < MIN_TEXT_CHARS)
                result.add(i);
        }
        return result;
    }

    private static final int MIN_TEXT_CHARS = 5;

    /**
     * Describes the result of {@link #pagesWithoutText} for the user.
     *
     * @return The warning or null if there is nothing to warn about.
     */
    public static String textSearchWarning(List<Integer> pages) {
        if (pages.isEmpty())
            return null;
        StringBuilder s = new StringBuilder(I18n.t(pages.size() == 1 ? "redactor.page" : "redactor.pages") + " ");
        int shown = Math.min(pages.size(), 10);
        for (int i = 0; i < shown; ++i)
            s.append(i == 0 ? "" : ", ").append(pages.get(i));
        if (shown < pages.size())
            s.append(", … (").append(I18n.t("redactor.total", pages.size())).append(")");
        return s.append(" ").append(I18n.t(pages.size() == 1 ? "redactor.noText.one" : "redactor.noText.many")).toString();
    }

    /**
     * Converts a rectangle from the coordinates of the displayed (rotated) page to the unrotated page.
     * Both are in points with the origin in the upper left corner.
     *
     * @param view     The rectangle as seen on the rotated page.
     * @param box      The crop box of the page (unrotated).
     * @param rotation The rotation of the page.
     * @return The rectangle in unrotated coordinates.
     */
    public static Rectangle2D viewToPage(Rectangle2D view, PDRectangle box, int rotation) {
        final double w = box.getWidth();
        final double h = box.getHeight();
        double u1 = view.getMinX(), v1 = view.getMinY(), u2 = view.getMaxX(), v2 = view.getMaxY();
        double x1, y1, x2, y2;
        switch (((rotation % 360) + 360) % 360) {
            case 90 -> {
                x1 = v1;
                y1 = h - u1;
                x2 = v2;
                y2 = h - u2;
            }
            case 180 -> {
                x1 = w - u1;
                y1 = h - v1;
                x2 = w - u2;
                y2 = h - v2;
            }
            case 270 -> {
                x1 = w - v1;
                y1 = u1;
                x2 = w - v2;
                y2 = u2;
            }
            default -> {
                x1 = u1;
                y1 = v1;
                x2 = u2;
                y2 = v2;
            }
        }
        Rectangle2D result = new Rectangle2D.Double();
        result.setFrameFromDiagonal(x1, y1, x2, y2);
        return result;
    }

    /**
     * Inverse of {@link #viewToPage}.
     */
    public static Rectangle2D pageToView(Rectangle2D page, PDRectangle box, int rotation) {
        final double w = box.getWidth();
        final double h = box.getHeight();
        double x1 = page.getMinX(), y1 = page.getMinY(), x2 = page.getMaxX(), y2 = page.getMaxY();
        double u1, v1, u2, v2;
        switch (((rotation % 360) + 360) % 360) {
            case 90 -> {
                u1 = h - y1;
                v1 = x1;
                u2 = h - y2;
                v2 = x2;
            }
            case 180 -> {
                u1 = w - x1;
                v1 = h - y1;
                u2 = w - x2;
                v2 = h - y2;
            }
            case 270 -> {
                u1 = y1;
                v1 = w - x1;
                u2 = y2;
                v2 = w - x2;
            }
            default -> {
                u1 = x1;
                v1 = y1;
                u2 = x2;
                v2 = y2;
            }
        }
        Rectangle2D result = new Rectangle2D.Double();
        result.setFrameFromDiagonal(u1, v1, u2, v2);
        return result;
    }

    /**
     * Redacts the regions. Pages without regions are not touched.
     *
     * @param document The document, modified in place.
     * @param regions  The regions to redact.
     * @param dpi      Resolution of the replacement images.
     */
    public static void redact(PDDocument document, List<Region> regions, int dpi) throws IOException {
        redact(document, regions, dpi, Color.BLACK);
    }

    /**
     * Redacts the regions. Pages without regions are not touched.
     *
     * @param document The document, modified in place.
     * @param regions  The regions to redact.
     * @param dpi      Resolution of the replacement images.
     * @param color    Color of the redacted areas.
     */
    public static void redact(PDDocument document, List<Region> regions, int dpi, Color color) throws IOException {
        TreeMap<Integer, List<Rectangle2D>> byPage = new TreeMap<>();
        for (Region r : regions) {
            if (r.pageNb < 1 || r.pageNb > document.getNumberOfPages())
                throw new IllegalArgumentException("Page " + r.pageNb + " is out of range");
            byPage.computeIfAbsent(r.pageNb, k -> new ArrayList<>()).add(r.area);
        }
        PDFRenderer renderer = new PDFRenderer(document);
        for (var e : byPage.entrySet()) {
            redactPage(document, renderer, e.getKey() - 1, e.getValue(), dpi, color);
        }
    }

    private static void redactPage(PDDocument document, PDFRenderer renderer, int pageIndex,
                                   List<Rectangle2D> areas, int dpi, Color color) throws IOException {
        final PDPage oldPage = document.getPage(pageIndex);
        final int rotation = oldPage.getRotation();
        final PDRectangle box = oldPage.getCropBox();

        oldPage.setRotation(0);
        BufferedImage image;
        try {
            image = renderer.renderImageWithDPI(pageIndex, dpi);
        } finally {
            oldPage.setRotation(rotation);
        }

        final double scale = dpi / 72.0;
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            for (Rectangle2D a : areas) {
                int x1 = (int) Math.floor(a.getMinX() * scale);
                int y1 = (int) Math.floor(a.getMinY() * scale);
                int x2 = (int) Math.ceil(a.getMaxX() * scale);
                int y2 = (int) Math.ceil(a.getMaxY() * scale);
                g.fillRect(x1, y1, x2 - x1, y2 - y1);
            }
        } finally {
            g.dispose();
        }

        PDPage newPage = new PDPage(new PDRectangle(box.getWidth(), box.getHeight()));
        PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, newPage)) {
            contentStream.drawImage(pdImage, 0, 0, box.getWidth(), box.getHeight());
        }
        newPage.setRotation(rotation);

        var pageTree = document.getPages();
        pageTree.insertAfter(newPage, oldPage);
        pageTree.remove(oldPage);
    }

    /**
     * Collects the text of each page together with the position of each character and searches for the pattern.
     */
    /**
     * Maximum time to search one page. A pattern with catastrophic backtracking would block the caller otherwise.
     */
    private static final long SEARCH_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    /**
     * Thrown by {@link DeadlineCharSequence} if a search takes too long.
     */
    private static final class SearchTimeoutException extends RuntimeException {
        SearchTimeoutException() {
            super("The search pattern takes too long, use a simpler one", null, false, false);
        }
    }

    /**
     * A text that aborts the regular expression engine after a deadline: the engine reads the text with charAt.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence text;
        private final long deadline;
        private int reads;

        DeadlineCharSequence(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() - deadline > 0)
                throw new SearchTimeoutException();
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static class PageTextCollector extends PDFTextStripper {

        private final Pattern pattern;
        private final List<Region> result;

        private final StringBuilder text = new StringBuilder();
        /**
         * Positions for each character in text, empty for separators.
         */
        private final List<List<TextPosition>> positions = new ArrayList<>();

        PageTextCollector(Pattern pattern, List<Region> result) {
            this.pattern = pattern;
            this.result = result;
        }

        private PDPage currentPage;

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPage = page;
            text.setLength(0);
            positions.clear();
            super.startPage(page);
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            // Ligatures and the like may result in different lengths. Then be conservative and use all positions.
            final boolean exact = string.length() == textPositions.size();
            for (int i = 0; i < string.length(); ++i) {
                text.append(string.charAt(i));
                positions.add(exact ? List.of(textPositions.get(i)) : textPositions);
            }
        }

        @Override
        protected void writeWordSeparator() {
            text.append(' ');
            positions.add(List.of());
        }

        @Override
        protected void writeLineSeparator() {
            text.append(' ');
            positions.add(List.of());
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            Matcher m = pattern.matcher(new DeadlineCharSequence(text, System.nanoTime() + SEARCH_TIMEOUT_NANOS));
            while (m.find()) {
                if (m.end() > m.start())
                    addRegions(m.start(), m.end());
            }
        }

        private void addRegion(Rectangle2D viewArea) {
            // The text positions are relative to the rotated page.
            result.add(new Region(getCurrentPageNo(),
                    viewToPage(viewArea, currentPage.getCropBox(), currentPage.getRotation())));
        }

        private void addRegions(int start, int end) {
            Rectangle2D line = null;
            double lineY = 0;
            for (int i = start; i < end; ++i) {
                for (TextPosition tp : positions.get(i)) {
                    double h = tp.getHeightDir();
                    // Upper edge: baseline minus height. Add some for descenders and rounding.
                    Rectangle2D r = new Rectangle2D.Double(tp.getXDirAdj() - 1, tp.getYDirAdj() - h - 1,
                            tp.getWidthDirAdj() + 2, h * 1.3 + 2);
                    if (line != null && Math.abs(tp.getYDirAdj() - lineY) <= h / 2) {
                        line.add(r);
                    } else {
                        if (line != null)
                            addRegion(line);
                        line = r;
                        lineY = tp.getYDirAdj();
                    }
                }
            }
            if (line != null)
                addRegion(line);
        }
    }
}

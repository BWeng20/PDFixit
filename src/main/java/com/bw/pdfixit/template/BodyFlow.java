package com.bw.pdfixit.template;

import com.bw.pdfixit.I18n;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The body of an invoice: the flowing parts of the layout ({@link Layout#parts}) laid out in the body regions and
 * split over the pages.<br>
 * The parts are cut into <i>units</i>, the pieces that are never split: a line of a field, a line of a text block, the
 * header or a row of the table, the totals block, a picture. A unit that must stay on the page of the unit before it
 * is marked ({@link Unit#keepWithPrevious}):
 * <ul>
 * <li>the label of a block and its first line, the header of the table and its first row</li>
 * <li>the totals with the last row of the table, and every part after the totals with the part before it</li>
 * <li>free elements (closing text, signature) with the part before them, all lines of a free text together</li>
 * </ul>
 * The table header is repeated at the top of every page the table continues on. A chain of units that doesn't fit on
 * an empty page is split anyway; a single unit that doesn't fit an empty region is an error.<br>
 * First {@link #build()} measures, {@link #paginate} gives the pages (so the page count is known), then
 * {@link #draw} draws page by page.
 */
final class BodyFlow {

    /**
     * Space between two parts in mm; none between consecutive single-line fields (e.g. the bank details).
     */
    static final double PARAGRAPH_GAP = 3;

    /**
     * Vertical padding of a table row (sum of above and below) in mm.
     */
    static final double ROW_PADDING = 1.6;

    /**
     * Horizontal space between two table columns in mm.
     */
    static final double COLUMN_GAP = 2;

    /**
     * Width of the totals block at the right of the body in mm.
     */
    static final double TOTALS_WIDTH = 90;

    /**
     * Width of a flowing picture without a width in the layout, in mm.
     */
    static final double DEFAULT_IMAGE_WIDTH = 40;

    /**
     * The widths of the table columns in mm; the description takes the rest.
     */
    static final Map<Layout.Column, Double> COLUMN_WIDTHS = new EnumMap<>(Map.of(
            Layout.Column.POS, 10.0,
            Layout.Column.QUANTITY, 16.0,
            Layout.Column.UNIT, 20.0,
            Layout.Column.PRICE, 24.0,
            Layout.Column.VAT_PERCENT, 14.0,
            Layout.Column.TOTAL, 26.0));

    /**
     * The minimum width of the description column in mm.
     */
    static final double MIN_NAME_WIDTH = 30;

    private static final float TOLERANCE = 0.05f;

    /**
     * A text in a unit.
     *
     * @param x        From the left edge of the region.
     * @param baseline Below the top of the unit.
     * @param label    True for a label (also a column header or a caption of the totals), see {@link Placement}.
     */
    record Run(PDFont font, float size, float x, float baseline, String text, boolean label) {
    }

    /**
     * A horizontal line in a unit, {@code y} below the top of the unit.
     */
    record Rule(float x1, float x2, float y, float lineWidth, float gray) {
    }

    /**
     * A picture in a unit, {@code y} is its top below the top of the unit.
     */
    record Picture(PDImageXObject image, float x, float y, float width, float height) {
    }

    static final class Unit {
        final Element element;
        float height;
        /**
         * Space above the unit; dropped at the top of a page.
         */
        float gapBefore;
        boolean keepWithPrevious;
        final List<Run> runs = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        Picture picture;
        /**
         * For a table row: the header that is repeated when the row starts a page.
         */
        Unit header;
        /**
         * An error found while measuring, reported with the page the unit is drawn on.
         */
        String errorKey;
        Object[] errorArgs = {};

        Unit(Element element) {
            this.element = element;
        }

        void error(String key, Object... args) {
            if (errorKey == null) {
                errorKey = key;
                errorArgs = args;
            }
        }
    }

    /**
     * A unit on a page.
     *
     * @param y        The top of the unit below the top of the region.
     * @param tooHigh  True if the unit is higher than the region.
     */
    record Placed(Unit unit, float y, boolean tooHigh) {
    }

    private final Overlay overlay;
    private final InvoiceContent content;
    /**
     * Units whose error was reported (a repeated header is drawn several times).
     */
    private final java.util.Set<Unit> reported = new java.util.HashSet<>();
    /**
     * The width the units are made for, in points.
     */
    private final float width;
    final List<Unit> units = new ArrayList<>();

    /**
     * @param width The width of the body regions in mm.
     */
    BodyFlow(Overlay overlay, InvoiceContent content, double width) {
        this.overlay = overlay;
        this.content = content;
        this.width = (float) Rect.toPoints(width);
    }

    private float pt(double mm) {
        return (float) Rect.toPoints(mm);
    }

    private static float lineHeight(float size) {
        return size * TextLayout.LINE_SPACING;
    }

    // ------------------------------------------------------------------------------------------------- measure

    /**
     * True if the part prints something (or must report an error).
     */
    private boolean produces(Element e) {
        return switch (e.type) {
            case FIELD -> {
                LayoutField f = e.field();
                if (f == null || f.placement == LayoutField.Placement.FIXED_ONLY)
                    yield true;
                if (f == LayoutField.LINES)
                    yield !content.lines.isEmpty();
                if (f == LayoutField.TOTALS)
                    yield !content.totals.isEmpty();
                String v = content.values.get(e.id);
                yield v != null && !v.isBlank();
            }
            case TEXT -> !e.text(overlay.language.getLanguage()).isBlank();
            case IMAGE -> true;
        };
    }

    private static boolean isLine(Element e) {
        LayoutField f = e.field();
        return f != null && (f.kind == LayoutField.Kind.LINE || e.singleLine);
    }

    private static boolean labelLeft(Element e) {
        return e.isLabelShown() && (e.label == null || e.label.position == null
                || e.label.position == Label.Position.LEFT);
    }

    /**
     * Cuts the parts into units.
     */
    void build() throws IOException {
        List<Element> parts = new ArrayList<>();
        for (Element e : overlay.layout.parts)
            if (produces(e))
                parts.add(e);
        boolean afterTotals = false;
        boolean hadTable = false;
        boolean previousWasLine = false;
        for (int i = 0; i < parts.size(); ++i) {
            Element e = parts.get(i);
            int first = units.size();
            LayoutField f = e.field();
            boolean line = false;
            switch (e.type) {
                case FIELD -> {
                    if (f == null) {
                        overlay.reportOnce(e, "template.check.unknownField");
                        continue;
                    }
                    if (f.placement == LayoutField.Placement.FIXED_ONLY) {
                        overlay.reportOnce(e, "template.check.fixedOnly");
                        continue;
                    }
                    if (f == LayoutField.LINES) {
                        table(e);
                        hadTable = true;
                    } else if (f == LayoutField.TOTALS) {
                        totals(e);
                    } else if (isLine(e)) {
                        lineField(e, labelColumn(parts, i));
                        line = true;
                    } else {
                        block(e, content.values.get(e.id), e.isLabelShown() ? overlay.labelText(e) : null, false);
                    }
                }
                case TEXT -> block(e, e.text(overlay.language.getLanguage()), null, true);
                case IMAGE -> image(e);
            }
            if (units.size() == first)
                continue;
            Unit start = units.get(first);
            start.gapBefore = line && previousWasLine ? 0 : pt(PARAGRAPH_GAP);
            start.keepWithPrevious = afterTotals || e.type != Element.Type.FIELD
                    || (f == LayoutField.TOTALS && hadTable);
            if (afterTotals)
                for (int k = first; k < units.size(); ++k)
                    units.get(k).keepWithPrevious = true;
            previousWasLine = line;
            if (f == LayoutField.TOTALS)
                afterTotals = true;
        }
        if (!units.isEmpty())
            units.get(0).keepWithPrevious = false;
    }

    /**
     * The x where the values of a run of consecutive single-line fields with the label in front start: behind the
     * widest label. 0 if the part has no label in front.
     */
    private float labelColumn(List<Element> parts, int index) throws IOException {
        if (!labelLeft(parts.get(index)))
            return 0;
        int from = index, to = index;
        while (from > 0 && isLine(parts.get(from - 1)) && labelLeft(parts.get(from - 1)))
            --from;
        while (to + 1 < parts.size() && isLine(parts.get(to + 1)) && labelLeft(parts.get(to + 1)))
            ++to;
        float column = 0;
        for (int i = from; i <= to; ++i) {
            Element e = parts.get(i);
            String label = TextLayout.withoutMissing(overlay.fonts.regular(), TextLayout.clean(overlay.labelText(e)));
            float dx = e.label == null ? 0 : pt(e.label.dx);
            column = Math.max(column, dx + TextLayout.width(overlay.fonts.regular(), overlay.size(e), label));
        }
        return column + pt(Overlay.LABEL_GAP);
    }

    /**
     * Checks the characters of a text; the first missing one is noted as error of the unit.
     */
    private String glyphs(Unit u, PDFont font, String text) {
        String missing = TextLayout.missingCharacter(font, text);
        if (missing == null)
            return text;
        u.error("template.fit.missingGlyph", overlay.fonts.name(),
                missing + String.format(" (U+%04X)", missing.codePointAt(0)));
        return TextLayout.withoutMissing(font, text);
    }

    private float alignedX(Element.Align align, float from, float to, float w) {
        return switch (align == null ? Element.Align.LEFT : align) {
            case LEFT -> from;
            case CENTER -> from + (to - from - w) / 2;
            case RIGHT -> to - w;
        };
    }

    /**
     * A single-line field: label and value on one line, or the label above.
     */
    private void lineField(Element e, float column) throws IOException {
        float size = overlay.size(e);
        float lh = lineHeight(size);
        PDFont font = overlay.fonts.get(e.bold);
        PDFont labelFont = overlay.fonts.regular();
        Unit u = new Unit(e);
        String value = glyphs(u, font, TextLayout.clean(content.values.get(e.id)));
        value = String.join(Overlay.LINE_JOINER, value.lines().map(String::trim).filter(s -> !s.isEmpty()).toList());
        float valueStart = 0;
        if (e.isLabelShown()) {
            String label = glyphs(u, labelFont, TextLayout.clean(overlay.labelText(e)).replace('\n', ' '));
            float dx = e.label == null ? 0 : pt(e.label.dx);
            float lw = TextLayout.width(labelFont, size, label);
            if (labelLeft(e)) {
                u.runs.add(new Run(labelFont, size, dx, TextLayout.ascent(labelFont, size), label, true));
                valueStart = column;
            } else {
                Unit lu = new Unit(e);
                lu.runs.add(new Run(labelFont, size, dx, TextLayout.ascent(labelFont, size), label, true));
                lu.height = lh;
                if (dx + lw > width + TOLERANCE)
                    lu.error("template.fit.labelTooWide");
                units.add(lu);
                u.keepWithPrevious = true;
            }
        }
        float vw = TextLayout.width(font, size, value);
        if (valueStart + vw > width + TOLERANCE)
            u.error("template.fit.tooWide");
        u.runs.add(new Run(font, size, alignedX(e.align, valueStart, width, vw), TextLayout.ascent(font, size), value, false));
        u.height = lh;
        units.add(u);
    }

    /**
     * A wrapping text: the label on a line of its own, then one unit per line.
     *
     * @param together True to keep all lines together (free text), false to allow a page break between lines.
     */
    private void block(Element e, String text, String label, boolean together) throws IOException {
        float size = overlay.size(e);
        float lh = lineHeight(size);
        PDFont font = overlay.fonts.get(e.bold);
        PDFont labelFont = overlay.fonts.regular();
        boolean keep = false;
        if (label != null) {
            Unit lu = new Unit(e);
            label = glyphs(lu, labelFont, TextLayout.clean(label).replace('\n', ' '));
            float dx = e.label == null ? 0 : pt(e.label.dx);
            if (dx + TextLayout.width(labelFont, size, label) > width + TOLERANCE)
                lu.error("template.fit.labelTooWide");
            lu.runs.add(new Run(labelFont, size, dx, TextLayout.ascent(labelFont, size), label, true));
            lu.height = lh;
            units.add(lu);
            keep = true;
        }
        Unit check = new Unit(e);
        String clean = glyphs(check, font, TextLayout.clean(text));
        boolean first = true;
        for (String line : TextLayout.wrap(font, size, clean, width)) {
            Unit u = new Unit(e);
            if (first && check.errorKey != null)
                u.error(check.errorKey, check.errorArgs);
            float w = TextLayout.width(font, size, line);
            u.runs.add(new Run(font, size, alignedX(e.align, 0, width, w), TextLayout.ascent(font, size), line, false));
            u.height = lh;
            u.keepWithPrevious = keep || (together && !first);
            keep = false;
            first = false;
            units.add(u);
        }
    }

    /**
     * A picture: the width of the layout, the height from the aspect ratio.
     */
    private void image(Element e) throws IOException {
        PDImageXObject image = overlay.image(e, 0);
        if (image == null)
            return;
        Unit u = new Unit(e);
        float w = pt(e.width != null ? e.width : DEFAULT_IMAGE_WIDTH);
        if (w > width + TOLERANCE)
            u.error("template.fit.tooWide");
        float h = w * image.getHeight() / image.getWidth();
        u.picture = new Picture(image, alignedX(e.align, 0, width, w), 0, w, h);
        u.height = h;
        units.add(u);
    }

    /**
     * Whether a column is right-aligned (the numbers).
     */
    private static boolean rightAligned(Layout.Column c) {
        return c != Layout.Column.POS && c != Layout.Column.NAME && c != Layout.Column.UNIT;
    }

    /**
     * The table of items: the header (if shown) and one unit per row.
     */
    private void table(Element e) throws IOException {
        List<Layout.Column> columns = overlay.layout.columns;
        float size = overlay.size(e);
        float lh = lineHeight(size);
        float pad = pt(ROW_PADDING);
        float gap = pt(COLUMN_GAP);
        PDFont font = overlay.fonts.regular();
        PDFont bold = overlay.fonts.bold();
        // The columns: x and width; the description takes what the others leave.
        float fixed = 0;
        for (Layout.Column c : columns)
            if (c != Layout.Column.NAME)
                fixed += pt(COLUMN_WIDTHS.get(c));
        float nameWidth = columns.contains(Layout.Column.NAME) ? width - fixed : 0;
        // Per column: x, width, and the space at its left: a left-aligned column behind a right-aligned one keeps
        // the gap at its left (e.g. the unit behind the quantity).
        Map<Layout.Column, float[]> geometry = new EnumMap<>(Layout.Column.class);
        float x = 0;
        Layout.Column previous = null;
        for (Layout.Column c : columns) {
            float w = c == Layout.Column.NAME ? nameWidth : pt(COLUMN_WIDTHS.get(c));
            float left = previous != null && rightAligned(previous) && !rightAligned(c) ? gap : 0;
            geometry.put(c, new float[]{x, w, left});
            x += w;
            previous = c;
        }
        boolean tooNarrow = nameWidth < pt(MIN_NAME_WIDTH) && columns.contains(Layout.Column.NAME)
                || x > width + TOLERANCE;

        Unit header = null;
        if (overlay.layout.tableHeader && e.isLabelShown()) {
            header = new Unit(e);
            float baseline = pad / 2 + TextLayout.ascent(bold, size);
            for (Layout.Column c : columns) {
                float[] g = geometry.get(c);
                String text = glyphs(header, bold, I18n.t(overlay.language, "print.column." + c.json));
                cell(header, bold, size, c, g, baseline, text, true);
            }
            header.height = lh + pad;
            header.rules.add(new Rule(0, width, header.height, 0.6f, 0f));
            if (tooNarrow)
                header.error("template.fit.tableTooNarrow");
            units.add(header);
            tooNarrow = false;
        }
        boolean first = true;
        for (Map<Layout.Column, String> row : content.lines) {
            Unit u = new Unit(e);
            if (tooNarrow)
                u.error("template.fit.tableTooNarrow");
            tooNarrow = false;
            int lines = 1;
            float baseline = pad / 2 + TextLayout.ascent(font, size);
            for (Layout.Column c : columns) {
                float[] g = geometry.get(c);
                String text = glyphs(u, font, TextLayout.clean(row.getOrDefault(c, "")));
                if (c == Layout.Column.NAME || c == Layout.Column.UNIT) {
                    // Texts wrap; the numbers must fit their column.
                    List<String> wrapped = TextLayout.wrap(font, size, text, Math.max(1, g[1] - gap - g[2]));
                    for (int k = 0; k < wrapped.size(); ++k)
                        u.runs.add(new Run(font, size, g[0] + g[2], baseline + k * lh, wrapped.get(k), false));
                    lines = Math.max(lines, wrapped.size());
                } else {
                    text = text.replace('\n', ' ');
                    if (TextLayout.width(font, size, text) > g[1] - gap - g[2] + TOLERANCE)
                        u.error("template.fit.tooWide");
                    cell(u, font, size, c, g, baseline, text, false);
                }
            }
            u.height = lines * lh + pad;
            u.rules.add(new Rule(0, width, u.height, 0.3f, 0.7f));
            u.header = header;
            u.keepWithPrevious = first && header != null;
            first = false;
            units.add(u);
        }
    }

    private void cell(Unit u, PDFont font, float size, Layout.Column c, float[] g, float baseline, String text,
                      boolean label) throws IOException {
        if (text.isEmpty())
            return;
        float w = TextLayout.width(font, size, text);
        // Right-aligned columns keep the gap at their left, the others at their right (and at their left behind a
        // right-aligned column).
        float x = rightAligned(c) ? g[0] + g[1] - w : g[0] + g[2];
        u.runs.add(new Run(font, size, x, baseline, text, label));
    }

    /**
     * The totals: one unit, right of the body; the emphasized row bold with a rule above.
     */
    private void totals(Element e) throws IOException {
        float size = overlay.size(e);
        float lh = lineHeight(size);
        float blockWidth = Math.min(pt(TOTALS_WIDTH), width);
        float x0 = width - blockWidth;
        boolean captions = e.isLabelShown();
        Unit u = new Unit(e);
        float y = 0;
        for (InvoiceContent.TotalsRow row : content.totals) {
            PDFont font = overlay.fonts.get(row.emphasized());
            if (row.emphasized() && y > 0) {
                y += pt(1);
                u.rules.add(new Rule(x0, width, y, 0.6f, 0f));
                y += pt(1);
            }
            float baseline = y + TextLayout.ascent(font, size);
            String amount = glyphs(u, font, TextLayout.clean(row.amount()).replace('\n', ' '));
            float aw = TextLayout.width(font, size, amount);
            u.runs.add(new Run(font, size, width - aw, baseline, amount, false));
            float used = aw;
            if (captions && row.caption() != null) {
                String caption = glyphs(u, font, TextLayout.clean(row.caption()).replace('\n', ' '));
                u.runs.add(new Run(font, size, x0, baseline, caption, true));
                used += TextLayout.width(font, size, caption) + pt(COLUMN_GAP);
            }
            if (used > blockWidth + TOLERANCE)
                u.error("template.fit.tooWide");
            y += lh;
        }
        u.height = y;
        units.add(u);
    }

    // ------------------------------------------------------------------------------------------------ paginate

    /**
     * Distributes the units over the pages.
     *
     * @param firstHeight The height of the body region of the first page in mm.
     * @param nextHeight  The height of the body region of the following pages in mm.
     * @return The units of each page; at least one page.
     */
    List<List<Placed>> paginate(double firstHeight, double nextHeight) {
        Pager pager = new Pager(pt(firstHeight), pt(nextHeight));
        int n = units.size();
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && units.get(j).keepWithPrevious)
                ++j;
            if (pager.fits(i, j)) {
                pager.place(i, j);
            } else if (pager.mayStartNewPage() && pager.fitsOnNewPage(i, j)) {
                pager.newPage();
                pager.place(i, j);
            } else {
                // The chain doesn't fit on a page at all: split it where needed.
                for (int k = i; k < j; ++k) {
                    if (!pager.fits(k, k + 1) && pager.mayStartNewPage())
                        pager.newPage();
                    pager.place(k, k + 1);
                }
            }
            i = j;
        }
        return pager.pages;
    }

    private final class Pager {
        final float firstHeight, nextHeight;
        final List<List<Placed>> pages = new ArrayList<>();
        List<Placed> current = new ArrayList<>();
        float y;

        Pager(float firstHeight, float nextHeight) {
            this.firstHeight = firstHeight;
            this.nextHeight = nextHeight;
            pages.add(current);
        }

        float available() {
            return pages.size() == 1 ? firstHeight : nextHeight;
        }

        /**
         * A new page helps unless the current page is an empty following page. An empty first page is left if its
         * region is too small (its body stays empty).
         */
        boolean mayStartNewPage() {
            return !current.isEmpty() || pages.size() == 1;
        }

        void newPage() {
            current = new ArrayList<>();
            pages.add(current);
            y = 0;
        }

        /**
         * The bottom of the units from..to placed from (empty, y).
         */
        float end(int from, int to, boolean empty, float start) {
            float end = start;
            for (int k = from; k < to; ++k) {
                Unit u = units.get(k);
                if (!empty)
                    end += u.gapBefore;
                if (empty && u.header != null)
                    end += u.header.height;
                end += u.height;
                empty = false;
            }
            return end;
        }

        boolean fits(int from, int to) {
            return end(from, to, current.isEmpty(), y) <= available() + TOLERANCE;
        }

        boolean fitsOnNewPage(int from, int to) {
            return end(from, to, true, 0) <= nextHeight + TOLERANCE;
        }

        void place(int from, int to) {
            for (int k = from; k < to; ++k) {
                Unit u = units.get(k);
                if (current.isEmpty() && u.header != null) {
                    current.add(new Placed(u.header, y, false));
                    y += u.header.height;
                }
                if (!current.isEmpty())
                    y += u.gapBefore;
                boolean tooHigh = y + u.height > available() + TOLERANCE;
                current.add(new Placed(u, y, tooHigh));
                y += u.height;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- draw

    /**
     * The index of a part in {@link Layout#parts} (the same object), -1 if it isn't there.
     */
    private int partIndex(Element e) {
        List<Element> parts = overlay.layout.parts;
        for (int i = 0; i < parts.size(); ++i)
            if (parts.get(i) == e)
                return i;
        return -1;
    }

    /**
     * Draws the units of a page into its body region.
     *
     * @param pageNumber For the reports (1-based).
     */
    void draw(PDPage page, Rect region, List<Placed> placed, int pageNumber) throws IOException {
        if (placed.isEmpty())
            return;
        try (PDPageContentStream cs = new PDPageContentStream(overlay.document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            Overlay.Canvas canvas = new Overlay.Canvas(cs, page, overlay, pageNumber);
            PDRectangle box = canvas.box(region);
            float x0 = box.getLowerLeftX();
            float top = box.getUpperRightY();
            for (Placed p : placed) {
                Unit u = p.unit();
                float unitTop = top - p.y();
                canvas.target(false, partIndex(u.element));
                for (Run r : u.runs) {
                    canvas.label = r.label();
                    canvas.text(r.font(), r.size(), x0 + r.x(), unitTop - r.baseline(), r.text());
                }
                for (Rule r : u.rules) {
                    cs.setStrokingColor(r.gray());
                    cs.setLineWidth(r.lineWidth());
                    cs.moveTo(x0 + r.x1(), unitTop - r.y());
                    cs.lineTo(x0 + r.x2(), unitTop - r.y());
                    cs.stroke();
                }
                if (u.picture != null) {
                    Picture pic = u.picture;
                    cs.drawImage(pic.image(), x0 + pic.x(), unitTop - pic.y() - pic.height(), pic.width(),
                            pic.height());
                    canvas.label = false;
                    canvas.place(x0 + pic.x(), unitTop - pic.y(), pic.width(), pic.height());
                }
                String name = u.element.name();
                if (u.errorKey != null && reported.add(u)) {
                    Object[] args = new Object[u.errorArgs.length + 2];
                    args[0] = name;
                    args[1] = pageNumber;
                    System.arraycopy(u.errorArgs, 0, args, 2, u.errorArgs.length);
                    overlay.error(u.element, pageNumber, u.errorKey, args);
                }
                if (p.tooHigh())
                    overlay.error(u.element, pageNumber, "template.fit.bodyTooHigh", name, pageNumber);
            }
        }
    }
}

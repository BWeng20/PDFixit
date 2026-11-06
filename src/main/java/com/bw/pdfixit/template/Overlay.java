package com.bw.pdfixit.template;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.ImportedFonts;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Draws the fixed elements of a layout on top of the pages of a prepared template (see {@link TemplatePreparer}). The
 * content of the template is never read or changed; new content is appended.<br>
 * Values are not shrunk or clipped: a value that doesn't fit its region is drawn anyway (so a preview shows it) and
 * reported as an error, see {@link #problems()}.<br>
 * Text is black (DeviceGray); pictures and colours use an ICCBased sRGB colour space, so the result is valid PDF/A
 * also with a CMYK output intent. One instance per document: fonts and pictures are embedded once.
 */
public class Overlay {

    /**
     * The gap between a label and its value on the same line, in mm.
     */
    public static final double LABEL_GAP = 2;

    /**
     * Joins the lines of a value that is printed on one line, e.g. the return address.
     */
    public static final String LINE_JOINER = " · ";

    /**
     * Rounding tolerance when checking that something fits, in points.
     */
    private static final float TOLERANCE = 0.05f;

    final PDDocument document;
    final Layout layout;
    final Locale language;
    final LayoutFonts fonts;
    private final List<Problem> problems = new ArrayList<>();
    /**
     * Where the labels and values were drawn, see {@link Placement}.
     */
    private final List<Placement> placements = new ArrayList<>();
    private final Set<String> reported = new HashSet<>();
    private final Map<String, PDImageXObject> images = new HashMap<>();
    private PDColorSpace srgb;

    /**
     * An overlay with the imported fonts of the user.
     *
     * @param language The printed language (labels, page numbers, texts of free elements).
     */
    public Overlay(PDDocument document, Layout layout, Locale language) {
        this(document, layout, language, ImportedFonts.fonts());
    }

    /**
     * @param imported The imported fonts the font of the layout may refer to.
     */
    public Overlay(PDDocument document, Layout layout, Locale language, List<ImportedFont> imported) {
        this.document = document;
        this.layout = layout;
        this.language = language;
        this.fonts = new LayoutFonts(document, layout.fontName, imported);
        if (fonts.problem != null)
            problems.add(new Problem(Problem.Severity.ERROR, fonts.problem, null));
    }

    /**
     * Everything found so far that doesn't fit or can't be drawn.
     */
    public List<Problem> problems() {
        return problems;
    }

    /**
     * Where the labels and values were drawn so far.
     */
    public List<Placement> placements() {
        return placements;
    }

    /**
     * Adds an area to the placement of an element part on a page, see {@link Placement}.
     */
    void place(boolean fixed, int index, boolean label, int page, Rectangle2D area) {
        for (int i = 0; i < placements.size(); ++i) {
            Placement p = placements.get(i);
            if (p.fixed() == fixed && p.index() == index && p.label() == label && p.page() == page) {
                placements.set(i, new Placement(fixed, index, label, page, p.area().createUnion(area)));
                return;
            }
        }
        placements.add(new Placement(fixed, index, label, page, area));
    }

    /**
     * The default label of a field in a language, e.g. "Rechnungsnummer" for "number".
     */
    public static String defaultLabel(String id, Locale language) {
        return I18n.t(language, "print.label." + id);
    }

    /**
     * The label text of a field: the one of the layout or the default one.
     */
    public String labelText(Element e) {
        if (e.label != null && e.label.text != null && !e.label.text.isBlank())
            return e.label.text;
        return defaultLabel(e.id, language);
    }

    /**
     * The value of the page number, e.g. "Seite 1 von 2".
     */
    public String pageText(int page, int pageCount) {
        return I18n.t(language, "print.page", page, pageCount);
    }

    /**
     * Draws the fixed elements of the first page or of a following page.
     *
     * @param page       The page.
     * @param next       False for the first page, true for a following page.
     * @param values     The values by field id; a missing or blank value is not printed, and neither is its label.
     *                   The value of {@code page} is made here.
     * @param pageNumber The number of the page (1-based).
     * @param pageCount  The number of pages of the invoice.
     */
    public void drawFixed(PDPage page, boolean next, Map<String, String> values, int pageNumber, int pageCount)
            throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
                true, true)) {
            Canvas canvas = new Canvas(cs, page, this, pageNumber);
            for (int index = 0; index < layout.fields.size(); ++index) {
                Element e = layout.fields.get(index);
                if (e.next != next)
                    continue;
                canvas.target(true, index);
                switch (e.type) {
                    case FIELD -> {
                        LayoutField f = e.field();
                        if (f == null) {
                            reportOnce(e, "template.check.unknownField");
                            continue;
                        }
                        if (f.placement == LayoutField.Placement.FLOWING_ONLY) {
                            reportOnce(e, "template.check.flowingOnly");
                            continue;
                        }
                        String value = f == LayoutField.PAGE ? pageText(pageNumber, pageCount) : values.get(e.id);
                        if (value == null || value.isBlank())
                            continue;
                        boolean line = f.kind == LayoutField.Kind.LINE || e.singleLine;
                        drawText(canvas, e, value, line, e.isLabelShown() ? labelText(e) : null, pageNumber);
                    }
                    case TEXT -> {
                        String text = e.text(language.getLanguage());
                        if (!text.isBlank())
                            drawText(canvas, e, text, false, null, pageNumber);
                    }
                    case IMAGE -> drawImage(canvas, e, pageNumber);
                }
            }
        }
    }

    void reportOnce(Element e, String key) {
        if (reported.add(key + "/" + e.name()))
            problems.add(new Problem(Problem.Severity.ERROR, I18n.t(key, e.name()), e.name()));
    }

    void error(Element e, int page, String key, Object... args) {
        problems.add(new Problem(Problem.Severity.ERROR, I18n.t(key, args), e.name(), page));
    }

    float size(Element e) {
        return e.size != null ? e.size : layout.fontSize;
    }

    /**
     * Draws a value with its label.
     *
     * @param line  True if the value is one line (lines of the value are joined), false if it wraps.
     * @param label The label text, null for none.
     */
    private void drawText(Canvas canvas, Element e, String value, boolean line, String label, int page)
            throws IOException {
        float size = size(e);
        PDFont font = fonts.get(e.bold);
        PDFont labelFont = fonts.regular();
        value = checkGlyphs(e, page, font, TextLayout.clean(value));
        if (line)
            value = String.join(LINE_JOINER, value.lines().map(String::trim).filter(s -> !s.isEmpty()).toList());
        if (label != null)
            label = checkGlyphs(e, page, labelFont, TextLayout.clean(label).replace('\n', ' '));

        PDRectangle region = canvas.box(e.rect);
        float x = region.getLowerLeftX();
        float width = region.getWidth();
        float top = region.getUpperRightY();
        float lineHeight = size * TextLayout.LINE_SPACING;
        Element.Align align = e.align == null ? Element.Align.LEFT : e.align;
        int labelLines = 0;

        canvas.label = true;
        if (label != null && e.label != null && e.label.rect != null) {
            // A label that was moved: in its own rectangle.
            PDRectangle box = canvas.box(e.label.rect);
            float w = TextLayout.width(labelFont, size, label);
            if (w > box.getWidth() + TOLERANCE || lineHeight > box.getHeight() + TOLERANCE)
                error(e, page, "template.fit.labelTooWide", e.name(), page);
            canvas.text(labelFont, size, box.getLowerLeftX(), box.getUpperRightY() - TextLayout.ascent(labelFont, size),
                    label);
        } else if (label != null && line) {
            // In front of the value on the same line.
            float w = TextLayout.width(labelFont, size, label);
            canvas.text(labelFont, size, x, top - TextLayout.ascent(labelFont, size), label);
            float gap = (float) Rect.toPoints(LABEL_GAP);
            x += w + gap;
            width -= w + gap;
        } else if (label != null) {
            // On a line of its own above the value.
            if (TextLayout.width(labelFont, size, label) > width + TOLERANCE)
                error(e, page, "template.fit.labelTooWide", e.name(), page);
            canvas.text(labelFont, size, x, top - TextLayout.ascent(labelFont, size), label);
            labelLines = 1;
        }

        canvas.label = false;
        List<String> lines;
        if (line) {
            lines = List.of(value);
            if (TextLayout.width(font, size, value) > width + TOLERANCE)
                error(e, page, "template.fit.tooWide", e.name(), page);
        } else {
            lines = TextLayout.wrap(font, size, value, Math.max(width, 1));
        }
        int room = (int) Math.floor((region.getHeight() + TOLERANCE) / lineHeight);
        if (labelLines + lines.size() > room) {
            if (line && labelLines == 0)
                error(e, page, "template.fit.tooHighLine", e.name(), page);
            else
                error(e, page, "template.fit.tooHigh", e.name(), page, labelLines + lines.size(), room);
        }
        float baseline = top - TextLayout.ascent(font, size) - labelLines * lineHeight;
        for (String l : lines) {
            float w = TextLayout.width(font, size, l);
            float lx = switch (align) {
                case LEFT -> x;
                case CENTER -> x + (width - w) / 2;
                case RIGHT -> x + width - w;
            };
            canvas.text(font, size, lx, baseline, l);
            baseline -= lineHeight;
        }
    }

    /**
     * Reports the first character the font doesn't have and removes all of them.
     */
    private String checkGlyphs(Element e, int page, PDFont font, String text) {
        String missing = TextLayout.missingCharacter(font, text);
        if (missing == null)
            return text;
        error(e, page, "template.fit.missingGlyph", e.name(), page, fonts.name(),
                missing + String.format(" (U+%04X)", missing.codePointAt(0)));
        return TextLayout.withoutMissing(font, text);
    }

    /**
     * Draws a picture into its rectangle: as large as possible with its aspect ratio, at the top, aligned
     * horizontally.
     */
    private void drawImage(Canvas canvas, Element e, int page) throws IOException {
        PDImageXObject image = image(e, page);
        if (image == null)
            return;
        PDRectangle box = canvas.box(e.rect);
        float scale = Math.min(box.getWidth() / image.getWidth(), box.getHeight() / image.getHeight());
        float w = image.getWidth() * scale;
        float h = image.getHeight() * scale;
        Element.Align align = e.align == null ? Element.Align.LEFT : e.align;
        float x = switch (align) {
            case LEFT -> box.getLowerLeftX();
            case CENTER -> box.getLowerLeftX() + (box.getWidth() - w) / 2;
            case RIGHT -> box.getUpperRightX() - w;
        };
        canvas.cs.drawImage(image, x, box.getUpperRightY() - h, w, h);
        canvas.place(x, box.getUpperRightY(), w, h);
    }

    /**
     * The picture of an image element, created once per document.
     *
     * @return The image, null if it can't be used (reported).
     */
    PDImageXObject image(Element e, int page) throws IOException {
        if (images.containsKey(e.image))
            return images.get(e.image);
        PDImageXObject image = null;
        LayoutImage data = layout.images.get(e.image);
        if (data == null) {
            reportOnce(e, "template.check.missingImage");
        } else {
            try {
                if (LayoutImage.JPEG.equals(data.format())) {
                    image = JPEGFactory.createFromByteArray(document, data.data());
                } else {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data.data()));
                    if (img == null)
                        throw new IOException("not readable");
                    image = LosslessFactory.createFromImage(document, img);
                }
                if (image.getColorSpace() instanceof PDDeviceCMYK) {
                    error(e, page, "template.fit.image", e.name(), "CMYK");
                    image = null;
                } else if (image.getColorSpace() instanceof PDDeviceRGB) {
                    // DeviceRGB is forbidden in PDF/A with a CMYK output intent.
                    image.setColorSpace(srgb());
                }
            } catch (IOException | RuntimeException ex) {
                error(e, page, "template.fit.image", e.name(), ex.getMessage());
                image = null;
            }
        }
        images.put(e.image, image);
        return image;
    }

    /**
     * The ICCBased sRGB colour space of the document, one ICC stream for all uses.
     */
    PDColorSpace srgb() throws IOException {
        if (srgb == null) {
            COSStream stream = document.getDocument().createCOSStream();
            try (InputStream icc = PdfStandard.openSrgbProfile();
                 OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                icc.transferTo(os);
            }
            stream.setInt(COSName.N, 3);
            COSArray array = new COSArray();
            array.add(COSName.ICCBASED);
            array.add(stream);
            srgb = PDICCBased.create(array, null);
        }
        return srgb;
    }

    /**
     * The content stream of a page with the coordinates of the displayed page: the origin in the lower left corner of
     * the visible page, also if the page is rotated or its crop box doesn't start at 0, 0.
     */
    static final class Canvas {
        final PDPageContentStream cs;
        /**
         * Width and height of the displayed page in points.
         */
        final float width, height;
        private final Overlay owner;
        private final int pageNumber;
        /**
         * The element the text that is drawn now belongs to, see {@link Placement}.
         */
        private boolean fixed;
        private int index = -1;
        boolean label;

        /**
         * @param owner      Receives the placements of what is drawn.
         * @param pageNumber The number of the page (1-based).
         */
        Canvas(PDPageContentStream cs, PDPage page, Overlay owner, int pageNumber) throws IOException {
            this.cs = cs;
            this.owner = owner;
            this.pageNumber = pageNumber;
            PDRectangle box = page.getCropBox();
            float llx = box.getLowerLeftX(), lly = box.getLowerLeftY(), w = box.getWidth(), h = box.getHeight();
            int rotation = ((page.getRotation() % 360) + 360) % 360;
            Matrix m = switch (rotation) {
                case 90 -> new Matrix(0, 1, -1, 0, llx + w, lly);
                case 180 -> new Matrix(-1, 0, 0, -1, llx + w, lly + h);
                case 270 -> new Matrix(0, -1, 1, 0, llx, lly + h);
                default -> new Matrix(1, 0, 0, 1, llx, lly);
            };
            boolean turned = rotation == 90 || rotation == 270;
            width = turned ? h : w;
            height = turned ? w : h;
            cs.transform(m);
            cs.setNonStrokingColor(0f);
        }

        /**
         * A rectangle of the layout in the coordinates of the canvas.
         */
        PDRectangle box(Rect r) {
            float w = (float) Rect.toPoints(r.width());
            float h = (float) Rect.toPoints(r.height());
            return new PDRectangle((float) Rect.toPoints(r.x()), height - (float) Rect.toPoints(r.y()) - h, w, h);
        }

        /**
         * The element the following texts belong to.
         *
         * @param fixed True for an element of {@link Layout#fields}, false for one of {@link Layout#parts}.
         */
        void target(boolean fixed, int index) {
            this.fixed = fixed;
            this.index = index;
            this.label = false;
        }

        /**
         * Records a drawn area (in the coordinates of the canvas) for the current element.
         */
        void place(float x, float top, float w, float h) {
            if (index >= 0)
                owner.place(fixed, index, label, pageNumber, new Rectangle2D.Double(x, height - top, w, h));
        }

        void text(PDFont font, float size, float x, float baseline, String text) throws IOException {
            if (text.isEmpty())
                return;
            float ascent = TextLayout.ascent(font, size);
            place(x, baseline + ascent, TextLayout.width(font, size, text), ascent + TextLayout.descent(font, size));
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, baseline);
            cs.showText(text);
            cs.endText();
        }
    }
}

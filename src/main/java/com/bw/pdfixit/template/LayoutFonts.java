package com.bw.pdfixit.template;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.fonts.FontSource;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.PdfFontEmbedder;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.util.List;

/**
 * The fonts of a layout in a document: the regular and the bold style of {@link Layout#fontName}.<br>
 * The name is one of the bundled families (Arimo, Tinos, Cousine) or an imported font. An imported font is one file,
 * so its bold style is the same font. The fonts are embedded as subsets; they are loaded when they are first used, so
 * a document never gets a font without glyphs.
 */
final class LayoutFonts {

    private final PDDocument document;
    private final FontSource regularSource;
    private final FontSource boldSource;
    /**
     * Why the font of the layout can't be used, null if it can.
     */
    final String problem;
    private PDFont regular;
    private PDFont bold;

    /**
     * @param imported The imported fonts the name may refer to.
     */
    LayoutFonts(PDDocument document, String name, List<ImportedFont> imported) {
        this.document = document;
        FontSource r = null, b = null;
        for (PdfFontEmbedder.Family f : PdfFontEmbedder.Family.values()) {
            var v = new PdfFontEmbedder.Variant(f, false, false);
            if (v.displayName().equalsIgnoreCase(name)) {
                r = v;
                b = new PdfFontEmbedder.Variant(f, true, false);
            }
        }
        if (r == null)
            for (ImportedFont f : imported)
                if (f.getName().equalsIgnoreCase(name) && f.exists())
                    r = b = f;
        String why = null;
        if (r == null) {
            why = I18n.t("template.fit.font", name);
            r = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, false, false);
            b = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, true, false);
        }
        regularSource = r;
        boldSource = b;
        problem = why;
    }

    String name() {
        return regularSource.displayName();
    }

    PDFont regular() throws IOException {
        if (regular == null)
            regular = load(regularSource);
        return regular;
    }

    PDFont bold() throws IOException {
        if (boldSource == regularSource)
            return regular();
        if (bold == null)
            bold = load(boldSource);
        return bold;
    }

    PDFont get(boolean isBold) throws IOException {
        return isBold ? bold() : regular();
    }

    private PDFont load(FontSource source) throws IOException {
        TrueTypeFont ttf = source.open();
        // The subset is made when the document is saved, so the font must stay open until then.
        document.registerTrueTypeFontForClosing(ttf);
        return PDType0Font.load(document, ttf, true);
    }
}

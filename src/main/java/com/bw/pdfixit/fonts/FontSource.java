package com.bw.pdfixit.fonts;

import org.apache.fontbox.ttf.TrueTypeFont;

import java.io.IOException;

/**
 * A font which can replace the font of a document: one of the bundled fonts, or a font file imported by the user.
 * Implementations are compared by what they stand for (a record, or the path of the file), because they are
 * used as keys.
 */
public interface FontSource {

    /**
     * The name shown to the user.
     */
    String displayName();

    /**
     * Reads the font. The caller closes it. A font file is read here and not before, so unused fonts cost nothing.
     *
     * @throws FontEmbedException If the font can't be used (missing, damaged, embedding not allowed, ...).
     */
    TrueTypeFont open() throws IOException;

    /**
     * Family, weight and slant of the opened font. They go into the flags of the font descriptor.
     */
    PdfFontEmbedder.Variant style(TrueTypeFont ttf);
}

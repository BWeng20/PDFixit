package com.bw.pdfixit.fonts;

import java.io.IOException;

/**
 * Fonts of a document can't be embedded, as PDF/A requires.
 */
public class FontEmbedException extends IOException {
    public FontEmbedException(String message) {
        super(message);
    }
}

package com.bw.pdfixit.template;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Measuring and wrapping text with the metrics of a font.
 */
final class TextLayout {

    /**
     * The distance of two lines, relative to the font size.
     */
    static final float LINE_SPACING = 1.15f;

    private TextLayout() {
    }

    /**
     * Removes what can't be shown: tabs become spaces, other control characters (but line breaks) are dropped.
     */
    static String clean(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.replace("\r\n", "\n").replace('\r', '\n').codePoints().forEach(c -> {
            if (c == '\t')
                sb.append(' ');
            else if (c == '\n' || !Character.isISOControl(c))
                sb.appendCodePoint(c);
        });
        return sb.toString();
    }

    /**
     * The first character the font has no glyph for, null if it has all.
     */
    static String missingCharacter(PDFont font, String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            String s = new String(Character.toChars(c));
            i += s.length();
            if (c == '\n')
                continue;
            try {
                font.encode(s);
            } catch (IllegalArgumentException | IOException e) {
                return s;
            }
        }
        return null;
    }

    /**
     * Drops the characters the font has no glyph for.
     */
    static String withoutMissing(PDFont font, String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            String s = new String(Character.toChars(c));
            try {
                if (c != '\n')
                    font.encode(s);
                sb.append(s);
            } catch (IllegalArgumentException | IOException e) {
                // Reported by missingCharacter.
            }
        });
        return sb.toString();
    }

    /**
     * The width of a text in points. The font must have all characters.
     */
    static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    /**
     * The height of the font above the baseline in points.
     */
    static float ascent(PDFont font, float size) throws IOException {
        float ascent = font.getFontDescriptor() == null ? 0 : font.getFontDescriptor().getAscent();
        if (ascent <= 0)
            ascent = font.getBoundingBox().getUpperRightY();
        return ascent / 1000 * size;
    }

    /**
     * The depth of the font below the baseline in points (positive).
     */
    static float descent(PDFont font, float size) throws IOException {
        float descent = font.getFontDescriptor() == null ? 0 : font.getFontDescriptor().getDescent();
        if (descent == 0)
            descent = font.getBoundingBox().getLowerLeftY();
        return Math.abs(descent) / 1000 * size;
    }

    /**
     * Wraps a text into lines of at most the width. Line breaks of the text are kept; words are broken between
     * characters only if a word alone is wider than the width. The font must have all characters.
     *
     * @param width The width in points.
     */
    static List<String> wrap(PDFont font, float size, String text, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split(" +")) {
                if (word.isEmpty())
                    continue;
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                // The word alone: break it if it is too wide.
                while (width(font, size, word) > width) {
                    int end = 1;
                    while (end < word.length() && width(font, size, word.substring(0, end + 1)) <= width)
                        ++end;
                    if (Character.isHighSurrogate(word.charAt(end - 1)) && end < word.length())
                        ++end;
                    lines.add(word.substring(0, end));
                    word = word.substring(end);
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}

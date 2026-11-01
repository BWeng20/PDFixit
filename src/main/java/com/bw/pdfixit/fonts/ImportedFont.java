package com.bw.pdfixit.fonts;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A font file imported by the user. Only the name and the path are known until the font is used:
 * the file is read in {@link #open()}.<br>
 * Only TrueType outlines (a /glyf table) can be embedded as the font program of a PDF font here,
 * so OpenType fonts with PostScript outlines and font collections (.ttc) are refused.
 * Fonts which don't allow embedding (OS/2 fsType) are refused as well.
 */
public final class ImportedFont implements FontSource {

    private final String name;
    private final Path path;

    public ImportedFont(String name, Path path) {
        this.name = name == null || name.isBlank() ? path.getFileName().toString() : name.trim();
        this.path = path.toAbsolutePath().normalize();
    }

    public String getName() {
        return name;
    }

    public Path getPath() {
        return path;
    }

    /**
     * Checks only if the file is there, it doesn't read it.
     */
    public boolean exists() {
        return Files.isRegularFile(path);
    }

    @Override
    public String displayName() {
        return name;
    }

    /**
     * Checks the fsType flags of the OS/2 table: restricted license embedding (bit 1) and no subsetting (bit 8)
     * forbid what is done here.
     */
    static boolean embeddingAllowed(int fsType) {
        return (fsType & 0x0002) == 0 && (fsType & 0x0100) == 0;
    }

    @Override
    public TrueTypeFont open() throws IOException {
        if (!exists())
            throw new FontEmbedException(I18n.t("fonts.fileGone", path));
        byte[] data = Files.readAllBytes(path);
        if (data.length > 4 && "ttcf".equals(new String(data, 0, 4, StandardCharsets.ISO_8859_1)))
            throw new FontEmbedException(I18n.t("font.collection", path.getFileName()));
        TrueTypeFont ttf;
        try {
            ttf = new TTFParser().parse(new RandomAccessReadBuffer(data));
        } catch (IOException | RuntimeException e) {
            throw new FontEmbedException(I18n.t("font.unusable", path.getFileName(), e.getMessage()));
        }
        try {
            if (!ttf.getTableMap().containsKey("glyf"))
                throw new FontEmbedException(I18n.t("font.postscript", path.getFileName()));
            OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
            if (os2 != null && !embeddingAllowed(os2.getFsType()))
                throw new FontEmbedException(I18n.t("font.noEmbedding", path.getFileName()));
            // The characters are found through this table.
            ttf.getUnicodeCmapLookup();
        } catch (FontEmbedException e) {
            ttf.close();
            throw e;
        } catch (IOException | RuntimeException e) {
            ttf.close();
            throw new FontEmbedException(I18n.t("font.unusable", path.getFileName(), e.getMessage()));
        }
        return ttf;
    }

    @Override
    public PdfFontEmbedder.Variant style(TrueTypeFont ttf) {
        boolean bold = false;
        boolean italic = false;
        PdfFontEmbedder.Family family = PdfFontEmbedder.Family.SANS;
        try {
            OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
            if (os2 != null) {
                bold = os2.getWeightClass() >= 600 || (os2.getFsSelection() & 0x20) != 0;
                italic = (os2.getFsSelection() & 0x01) != 0;
                int familyClass = os2.getFamilyClass() >> 8;
                if (familyClass >= 1 && familyClass <= 5 || familyClass == 7)
                    family = PdfFontEmbedder.Family.SERIF;
            }
            var head = ttf.getHeader();
            if (head != null) {
                bold |= (head.getMacStyle() & 1) != 0;
                italic |= (head.getMacStyle() & 2) != 0;
            }
            var post = ttf.getPostScript();
            if (post != null) {
                italic |= post.getItalicAngle() != 0;
                if (post.getIsFixedPitch() != 0)
                    family = PdfFontEmbedder.Family.MONO;
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("Style of %s unknown: %s", path, e.getMessage());
        }
        return new PdfFontEmbedder.Variant(family, bold, italic);
    }

    /**
     * Reads a font file once to check it and to get its name. This is done when the user imports the file.
     *
     * @throws FontEmbedException If the file can't be used.
     */
    public static ImportedFont fromFile(Path file) throws IOException {
        ImportedFont probe = new ImportedFont(null, file);
        try (TrueTypeFont ttf = probe.open()) {
            String full = null;
            try {
                full = ttf.getNaming() == null ? null : ttf.getNaming().getName(4, 3, 1, 0x409);
                if (full == null && ttf.getNaming() != null)
                    full = ttf.getNaming().getName(4, 1, 0, 0);
            } catch (IOException | RuntimeException e) {
                Log.debug("No name in %s: %s", file, e.getMessage());
            }
            String name = full != null && !full.isBlank() ? full : ttf.getName();
            return new ImportedFont(name != null && !name.isBlank() ? name : file.getFileName().toString(), file);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ImportedFont f && f.path.equals(path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return name + " (" + path + ")";
    }
}

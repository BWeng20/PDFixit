package com.bw.pdfixit.fonts;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Merges fonts which exist more than once with exactly the same content (e.g. one copy per page, or after merging
 * documents): all references then point to one font, the other copies are not written any more.<br>
 * Fonts are only merged if everything is the same: the font dictionary, the descriptor, the widths, the encoding, the
 * ToUnicode table and the bytes of the font program (only entries which don't change the text, like the resource name
 * /Name, are ignored).<br>
 * Composite fonts (as written by Word) with different subsets of the same font are merged too, if the codes of the pages
 * don't have to change: when the codes of one font are all in the other one with the same character and width,
 * or when both are subsets of the same bundled font and don't contradict each other (then the font is written
 * again with the codes of both). A composite and a simple font of the same name are no duplicates.
 */
public final class PdfFontMerger {

    /**
     * What was found or merged.
     *
     * @param groups     Number of fonts which have copies.
     * @param duplicates Number of copies which are (or can be) removed.
     * @param savedBytes The size of the streams (font programs, tables) of the removed copies.
     */
    public record Result(int groups, int duplicates, long savedBytes) {
        public static final Result NONE = new Result(0, 0, 0);
    }

    /**
     * Entries which don't change the appearance of the text and are ignored when comparing fonts:
     * the obsolete name of the font in the resources (Word writes /F1, /F2, ... so every font differs), and the
     * hints of the font descriptor, which only describe the font (a replaced font may still have the old ones).
     */
    private static final Set<String> IGNORED_FONT_ENTRIES = Set.of("Name");
    private static final Set<String> IGNORED_DESCRIPTOR_ENTRIES = Set.of("AvgWidth", "MaxWidth", "XHeight", "Leading");

    private PdfFontMerger() {
    }

    /**
     * Counts the duplicate fonts, the document is not changed.
     */
    public static Result find(PDDocument document) throws IOException {
        return run(document, false);
    }

    /**
     * Merges the duplicate fonts.
     */
    public static Result merge(PDDocument document) throws IOException {
        return run(document, true);
    }

    private static Result run(PDDocument document, boolean apply) throws IOException {
        // All fonts which are used in a /Font resource dictionary, in the order of the first use.
        List<COSDictionary> fonts = new ArrayList<>();
        collectFonts(document.getDocument().getTrailer(), Collections.newSetFromMap(new IdentityHashMap<>()), fonts);

        Map<String, List<COSDictionary>> bySignature = new LinkedHashMap<>();
        for (COSDictionary font : fonts)
            bySignature.computeIfAbsent(signature(font), k -> new ArrayList<>()).add(font);

        // duplicate -> the font which stays
        Map<COSDictionary, COSDictionary> canonical = new IdentityHashMap<>();
        int groups = 0;
        long saved = 0;
        for (List<COSDictionary> same : bySignature.values()) {
            if (same.size() < 2)
                continue;
            ++groups;
            COSDictionary keep = same.get(0);
            Set<COSBase> keepStreams = Collections.newSetFromMap(new IdentityHashMap<>());
            streams(keep, keepStreams);
            for (int i = 1; i < same.size(); ++i) {
                canonical.put(same.get(i), keep);
                Set<COSBase> own = Collections.newSetFromMap(new IdentityHashMap<>());
                streams(same.get(i), own);
                for (COSBase s : own)
                    if (!keepStreams.contains(s))
                        saved += ((COSStream) s).getLength();
            }
        }

        // Composite fonts with different subsets of the same font, which can be merged without changing the pages.
        List<COSDictionary> others = new ArrayList<>(fonts);
        others.removeAll(canonical.keySet());
        CidMerge cid = planCidMerge(others);
        groups += cid.groups;
        saved += cid.savedBytes;
        canonical.putAll(cid.redirects);

        if (apply && !canonical.isEmpty()) {
            for (Union u : cid.unions)
                PdfFontEmbedder.rewriteCid(document, u.keep, u.variant, u.text);
            // The writer writes a font used at several places once, if it is not a direct object.
            canonical.values().forEach(k -> k.setDirect(false));
            replaceReferences(document.getDocument().getTrailer(),
                    Collections.newSetFromMap(new IdentityHashMap<>()), canonical);
        }
        return groups == 0 ? Result.NONE : new Result(groups, canonical.size(), saved);
    }

    // ---------------------------------------------------------------- composite fonts with different subsets

    /**
     * A composite font (Identity-H) of which all codes have a character and a width.
     */
    private static final class Cid {
        final COSDictionary font;
        /**
         * Name, flags, weight etc.: only fonts with the same key are the same font.
         */
        final String key;
        final Map<Integer, String> text;
        final Map<Integer, Float> width;
        private PdfFontEmbedder.Variant origin;
        private boolean originChecked;

        Cid(COSDictionary font, String key, Map<Integer, String> text, Map<Integer, Float> width) {
            this.font = font;
            this.key = key;
            this.text = text;
            this.width = width;
        }

        /**
         * The bundled font this font is a subset of, null if it is not (or not known to be) one.
         */
        PdfFontEmbedder.Variant origin() throws IOException {
            if (!originChecked) {
                origin = PdfFontEmbedder.bundledOrigin(font, text);
                originChecked = true;
            }
            return origin;
        }
    }

    private record Union(COSDictionary keep, PdfFontEmbedder.Variant variant, Map<Integer, String> text) {
    }

    private record CidMerge(Map<COSDictionary, COSDictionary> redirects, List<Union> unions, int groups, long savedBytes) {
    }

    private static final float WIDTH_TOLERANCE = 0.5f;

    /**
     * @return The font, null if it isn't a composite TrueType font with all data needed to compare it.
     */
    private static Cid cid(COSDictionary font) throws IOException {
        if (!COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))
                || !(font.getDictionaryObject(COSName.ENCODING) instanceof COSName enc) || !"Identity-H".equals(enc.getName())
                || !(font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray a)
                || a.size() == 0 || !(a.getObject(0) instanceof COSDictionary cid)
                || !"CIDFontType2".equals(cid.getNameAsString(COSName.SUBTYPE)))
            return null;
        COSDictionary desc = cid.getCOSDictionary(COSName.FONT_DESC);
        if (desc == null || !(desc.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream))
            return null;
        Map<Integer, Float> width = PdfFontEmbedder.cidWidths(cid);
        // Code 0 is the glyph for missing characters: it has no character and is the same in every font.
        width.remove(0);
        if (width.isEmpty())
            return null;
        Map<Integer, String> text = PdfFontEmbedder.cidTexts(font, width.keySet());
        // Every code needs a character, otherwise it is unknown what it means.
        if (text.size() != width.size())
            return null;
        String name = desc.getNameAsString(COSName.FONT_NAME);
        String key = (name == null ? "" : name.replaceFirst("^[A-Z]{6}\\+", "")) + "|" + desc.getInt(COSName.FLAGS, 0)
                + "|" + desc.getInt(COSName.FONT_WEIGHT, 0) + "|" + desc.getFloat(COSName.ITALIC_ANGLE, 0)
                + "|" + cid.getInt(COSName.getPDFName("DW"), 1000);
        return new Cid(font, key, text, width);
    }

    /**
     * Checks that the codes both fonts have mean the same: same character and same width.
     */
    private static boolean consistent(Map<Integer, String> text, Map<Integer, Float> width, Cid other) {
        for (var e : other.text.entrySet()) {
            String mine = text.get(e.getKey());
            if (mine == null)
                continue;
            if (!mine.equals(e.getValue()) || Math.abs(width.get(e.getKey()) - other.width.get(e.getKey())) > WIDTH_TOLERANCE)
                return false;
        }
        return true;
    }

    private static long streamBytes(COSDictionary font) {
        Set<COSBase> streams = Collections.newSetFromMap(new IdentityHashMap<>());
        streams(font, streams);
        long sum = 0;
        for (COSBase s : streams)
            sum += ((COSStream) s).getLength();
        return sum;
    }

    /**
     * Plans which composite fonts are replaced by another one of the same font:
     * <ul>
     * <li>if all codes of a font are in the other one (with the same meaning), the font is replaced by the other one,</li>
     * <li>if both have codes the other doesn't have, but nothing contradicts, and both are subsets of the same bundled
     * font, the other font is written again with the codes of both (the font programs can be made again).</li>
     * </ul>
     * The codes in the pages stay as they are.
     */
    private static CidMerge planCidMerge(List<COSDictionary> fonts) throws IOException {
        Map<String, List<Cid>> byKey = new LinkedHashMap<>();
        for (COSDictionary font : fonts) {
            Cid c = cid(font);
            if (c != null)
                byKey.computeIfAbsent(c.key, k -> new ArrayList<>()).add(c);
        }
        Map<COSDictionary, COSDictionary> redirects = new IdentityHashMap<>();
        List<Union> unions = new ArrayList<>();
        int groups = 0;
        long saved = 0;
        for (List<Cid> same : byKey.values()) {
            if (same.size() < 2)
                continue;
            // The font with the most codes first: the others have the best chance to be contained in it.
            List<Cid> remaining = new ArrayList<>(same);
            remaining.sort((x, y) -> Integer.compare(y.text.size(), x.text.size()));
            while (remaining.size() >= 2) {
                Cid keep = remaining.remove(0);
                Map<Integer, String> text = new TreeMap<>(keep.text);
                Map<Integer, Float> width = new TreeMap<>(keep.width);
                boolean unioned = false;
                int absorbed = 0;
                boolean progress = true;
                while (progress) {
                    progress = false;
                    for (var it = remaining.iterator(); it.hasNext(); ) {
                        Cid f = it.next();
                        if (!consistent(text, width, f))
                            continue;
                        if (!text.keySet().containsAll(f.text.keySet())) {
                            // Needs a new font program: possible for subsets of the same bundled font.
                            PdfFontEmbedder.Variant origin = keep.origin();
                            if (origin == null || !origin.equals(f.origin()))
                                continue;
                            text.putAll(f.text);
                            width.putAll(f.width);
                            unioned = true;
                        }
                        redirects.put(f.font, keep.font);
                        saved += streamBytes(f.font);
                        ++absorbed;
                        it.remove();
                        progress = true;
                    }
                }
                if (absorbed > 0) {
                    ++groups;
                    if (unioned)
                        unions.add(new Union(keep.font, keep.origin(), text));
                }
            }
        }
        return new CidMerge(redirects, unions, groups, saved);
    }

    /**
     * Finds the font dictionaries (each only once) which are entries of a /Font resource dictionary.
     */
    private static void collectFonts(COSBase base, Set<COSBase> visited, List<COSDictionary> result) {
        base = PdfFonts.resolve(base);
        if (base == null || !visited.add(base))
            return;
        if (base instanceof COSDictionary d) {
            if (PdfFonts.resolve(d.getDictionaryObject(COSName.FONT)) instanceof COSDictionary fonts)
                for (COSName name : fonts.keySet())
                    if (PdfFonts.resolve(fonts.getDictionaryObject(name)) instanceof COSDictionary f
                            && PdfFonts.isFont(f) && result.stream().noneMatch(r -> r == f))
                        result.add(f);
            for (COSBase v : d.getValues())
                collectFonts(v, visited, result);
        } else if (base instanceof COSArray a) {
            for (int i = 0; i < a.size(); ++i)
                collectFonts(a.get(i), visited, result);
        }
    }

    private static void replaceReferences(COSBase base, Set<COSBase> visited, Map<COSDictionary, COSDictionary> canonical) {
        base = PdfFonts.resolve(base);
        if (base == null || !visited.add(base))
            return;
        if (base instanceof COSDictionary d) {
            if (PdfFonts.resolve(d.getDictionaryObject(COSName.FONT)) instanceof COSDictionary fonts)
                for (COSName name : new ArrayList<>(fonts.keySet())) {
                    COSBase current = PdfFonts.resolve(fonts.getDictionaryObject(name));
                    COSDictionary keep = current instanceof COSDictionary c ? canonical.get(c) : null;
                    if (keep != null)
                        fonts.setItem(name, keep);
                }
            for (COSBase v : new ArrayList<>(d.getValues()))
                replaceReferences(v, visited, canonical);
        } else if (base instanceof COSArray a) {
            for (int i = 0; i < a.size(); ++i)
                replaceReferences(a.get(i), visited, canonical);
        }
    }

    /**
     * Collects the streams reachable from the font.
     */
    private static void streams(COSBase base, Set<COSBase> result) {
        streams(base, result, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void streams(COSBase base, Set<COSBase> result, Set<COSBase> visited) {
        base = PdfFonts.resolve(base);
        if (base == null || !visited.add(base))
            return;
        if (base instanceof COSStream s)
            result.add(s);
        if (base instanceof COSDictionary d) {
            for (COSBase v : d.getValues())
                streams(v, result, visited);
        } else if (base instanceof COSArray a) {
            for (int i = 0; i < a.size(); ++i)
                streams(a.get(i), result, visited);
        }
    }

    /**
     * A hash of everything of the font, including the bytes of its streams.
     */
    static String signature(COSDictionary font) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digest(font, md, Collections.newSetFromMap(new IdentityHashMap<>()));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void put(MessageDigest md, String s) {
        md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void digest(COSBase base, MessageDigest md, Set<COSBase> path) throws IOException {
        base = PdfFonts.resolve(base);
        if (base == null) {
            put(md, "null;");
        } else if (base instanceof COSDictionary d) {
            if (!path.add(d)) {
                put(md, "cycle;");
                return;
            }
            put(md, d instanceof COSStream ? "S{" : "D{");
            // The same order for every dictionary.
            Map<String, COSName> keys = new TreeMap<>();
            for (COSName k : d.keySet())
                keys.put(k.getName(), k);
            for (var e : keys.entrySet()) {
                // The length is part of the stream bytes.
                if (d instanceof COSStream && COSName.LENGTH.equals(e.getValue()))
                    continue;
                if (PdfFonts.isFont(d) && IGNORED_FONT_ENTRIES.contains(e.getKey()))
                    continue;
                if (COSName.FONT_DESC.equals(d.getCOSName(COSName.TYPE)) && IGNORED_DESCRIPTOR_ENTRIES.contains(e.getKey()))
                    continue;
                put(md, "/" + e.getKey() + ":");
                digest(d.getItem(e.getValue()), md, path);
            }
            if (d instanceof COSStream s) {
                try (InputStream is = s.createRawInputStream()) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = is.read(buffer)) > 0)
                        md.update(buffer, 0, n);
                }
            }
            put(md, "}");
            path.remove(d);
        } else if (base instanceof COSArray a) {
            put(md, "[");
            for (int i = 0; i < a.size(); ++i)
                digest(a.get(i), md, path);
            put(md, "]");
        } else if (base instanceof COSName n) {
            put(md, "/" + n.getName() + ";");
        } else if (base instanceof COSString s) {
            put(md, "(" + HexFormat.of().formatHex(s.getBytes()) + ");");
        } else {
            put(md, base.getClass().getSimpleName() + ":" + base + ";");
        }
    }
}

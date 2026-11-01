package com.bw.pdfixit.fonts;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lists the fonts of a document.
 */
public final class PdfFonts {

    private static final Set<String> SIMPLE_AND_COMPOSITE = Set.of("Type0", "Type1", "MMType1", "TrueType");

    /**
     * A font of the document.
     *
     * @param dictionary The font dictionary, to change the font later.
     * @param name       The name without subset tag.
     * @param type       E.g. "TrueType", "Type 1", "Type 0 (CID)", "Type 3".
     * @param embedded   True if the document contains the font program.
     * @param pages      1-based numbers of the pages which use the font, empty if no page uses it directly
     *                   (e.g. fonts of form fields).
     */
    public record FontInfo(COSDictionary dictionary, String name, String type, boolean embedded, List<Integer> pages) {

        /**
         * The pages as text, e.g. "1-3, 5".
         */
        public String pageText() {
            return pageRanges(pages);
        }

        /**
         * Tells why the font can't be replaced by a bundled one, null if it can.
         */
        public String cannotReplaceReason() {
            return PdfFontEmbedder.cannotReplaceReason(dictionary);
        }
    }

    private PdfFonts() {
    }

    /**
     * Checks if the dictionary describes a font (not a descendant CID font, which belongs to its Type0 font).
     */
    static boolean isFont(COSBase base) {
        if (!(base instanceof COSDictionary d) || d instanceof COSStream)
            return false;
        COSName subtype = d.getCOSName(COSName.SUBTYPE);
        if (subtype == null)
            return false;
        if (COSName.TYPE3.equals(subtype))
            return d.containsKey(COSName.CHAR_PROCS);
        return d.containsKey(COSName.BASE_FONT) && SIMPLE_AND_COMPOSITE.contains(subtype.getName());
    }

    static String typeName(COSDictionary font) {
        String s = font.getNameAsString(COSName.SUBTYPE);
        if (s == null)
            return "?";
        return switch (s) {
            case "Type0" -> "Type 0 (CID)";
            case "Type1" -> "Type 1";
            case "MMType1" -> "Multiple Master";
            case "Type3" -> "Type 3";
            default -> s;
        };
    }

    /**
     * Lists all fonts of the document, sorted by the first page which uses it, then by name.
     */
    public static List<FontInfo> list(PDDocument document) {
        Map<COSDictionary, TreeSet<Integer>> pagesOfFont = new LinkedHashMap<>();
        // Page by page, to know the pages. Fonts used in forms are found through their resources.
        int pageNb = 0;
        for (PDPage page : document.getPages()) {
            ++pageNb;
            collect(page.getCOSObject().getDictionaryObject(COSName.RESOURCES), pageNb, pagesOfFont,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        // Then all others (annotations, form fields, ...).
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(document.getDocument().getTrailer(), visited, pagesOfFont);

        List<FontInfo> result = new ArrayList<>();
        for (var e : pagesOfFont.entrySet()) {
            COSDictionary font = e.getKey();
            result.add(new FontInfo(font, PdfFontEmbedder.baseName(font), typeName(font),
                    COSName.TYPE3.equals(font.getCOSName(COSName.SUBTYPE)) || PdfFontEmbedder.isEmbedded(font),
                    List.copyOf(e.getValue())));
        }
        result.sort(Comparator
                .comparingInt((FontInfo f) -> f.pages().isEmpty() ? Integer.MAX_VALUE : f.pages().get(0))
                .thenComparing(FontInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static COSBase resolve(COSBase b) {
        return b instanceof COSObject o ? o.getObject() : b;
    }

    /**
     * Collects the fonts of a resource dictionary, and of the forms it uses.
     */
    private static void collect(COSBase resources, int pageNb, Map<COSDictionary, TreeSet<Integer>> result,
                                Set<COSDictionary> seen) {
        if (!(resolve(resources) instanceof COSDictionary res) || !seen.add(res))
            return;
        if (resolve(res.getDictionaryObject(COSName.FONT)) instanceof COSDictionary fonts)
            for (COSName name : fonts.keySet())
                if (isFont(resolve(fonts.getDictionaryObject(name))))
                    result.computeIfAbsent((COSDictionary) resolve(fonts.getDictionaryObject(name)),
                            k -> new TreeSet<>()).add(pageNb);
        if (resolve(res.getDictionaryObject(COSName.XOBJECT)) instanceof COSDictionary xobjects)
            for (COSName name : xobjects.keySet())
                if (resolve(xobjects.getDictionaryObject(name)) instanceof COSStream form
                        && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE)))
                    collect(form.getDictionaryObject(COSName.RESOURCES), pageNb, result, seen);
    }

    /**
     * Finds all other fonts in the object graph, they get no page.
     */
    private static void walk(COSBase base, Set<COSBase> visited, Map<COSDictionary, TreeSet<Integer>> result) {
        base = resolve(base);
        if (base == null || !visited.add(base))
            return;
        if (base instanceof COSDictionary d) {
            if (isFont(d))
                result.computeIfAbsent(d, k -> new TreeSet<>());
            for (COSBase v : d.getValues())
                walk(v, visited, result);
        } else if (base instanceof COSArray a) {
            for (int i = 0; i < a.size(); ++i)
                walk(a.get(i), visited, result);
        }
    }

    /**
     * Writes page numbers as ranges, e.g. "1-3, 5".
     */
    public static String pageRanges(Collection<Integer> pages) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(pages));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); ) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1)
                ++j;
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(sorted.get(i));
            if (j > i)
                sb.append(j == i + 1 ? ", " : "-").append(sorted.get(j));
            i = j + 1;
        }
        return sb.toString();
    }
}

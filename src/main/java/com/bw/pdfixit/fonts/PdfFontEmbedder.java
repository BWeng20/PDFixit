package com.bw.pdfixit.fonts;

import com.bw.pdfixit.I18n;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.encoding.DictionaryEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Embeds fonts, which a document uses without embedding them, as PDF/A requires.<br>
 * PDFBox can't take over the original font, so a metric compatible free substitute is used: the
 * Chrome OS core fonts (Apache License 2.0, see <i>fonts/LICENSE-Apache-2.0.txt</i>) replace sans serif (Arial, Helvetica)
 * by Arimo, serif (Times) by Tinos and monospaced (Courier) by Cousine. The substitute is subsetted to the characters of Latin text,
 * the font dictionary is turned into a simple TrueType font with WinAnsi encoding and the widths of the substitute.<br>
 * The visible text keeps its characters, but fonts which are not metric compatible (e.g. Calibri) get the look and the
 * spacing of the substitute.
 * <p>
 * Composite (CID) fonts with the encoding Identity-H get a new font program and a /CIDToGIDMap, the characters
 * come from their /ToUnicode table.
 * <p>
 * Not possible: composite fonts without ToUnicode table or with another encoding, symbol fonts, and characters the
 * bundled fonts don't have. Then nothing is changed and an {@link FontEmbedException} lists the fonts.
 */
public final class PdfFontEmbedder {

    private static final COSName FIRST_CHAR = COSName.FIRST_CHAR;
    private static final COSName LAST_CHAR = COSName.LAST_CHAR;
    private static final int FLAG_FIXED = 1, FLAG_SERIF = 2, FLAG_SYMBOLIC = 4, FLAG_NONSYMBOLIC = 32,
            FLAG_ITALIC = 64, FLAG_FORCE_BOLD = 1 << 18;
    private static final List<String> SYMBOL_FONTS = List.of("zapfdingbats", "dingbats", "wingdings", "webdings");
    private static final List<String> MONO_NAMES = List.of("courier", "mono", "consolas", "typewriter", "lucidaconsole");
    private static final List<String> SANS_NAMES = List.of("sans", "arial", "helvetica", "verdana", "calibri", "tahoma",
            "segoe", "trebuchet", "univers", "frutiger", "myriad", "gothic");
    private static final List<String> SERIF_NAMES = List.of("times", "serif", "georgia", "garamond", "palatino", "bookman",
            "cambria", "minion", "century", "roman", "baskerville", "caslon");

    /**
     * The families of the bundled fonts.
     */
    public enum Family {
        SANS("Arimo", "sans serif, like Arial and Helvetica"),
        SERIF("Tinos", "serif, like Times New Roman"),
        MONO("Cousine", "monospaced, like Courier New");

        final String prefix;
        public final String description;

        Family(String prefix, String description) {
            this.prefix = prefix;
            this.description = description;
        }
    }

    /**
     * One of the bundled fonts.
     */
    public record Variant(Family family, boolean bold, boolean italic) implements FontSource {
        /**
         * Resource name of the font file.
         */
        public String resource() {
            String style = bold ? (italic ? "BoldItalic" : "Bold") : (italic ? "Italic" : "Regular");
            return "/fonts/" + family.prefix + "-" + style + ".ttf";
        }

        /**
         * E.g. "Arimo Bold Italic".
         */
        @Override
        public String displayName() {
            return family.prefix + (bold ? " Bold" : "") + (italic ? " Italic" : "");
        }

        @Override
        public TrueTypeFont open() throws IOException {
            return load(this);
        }

        @Override
        public Variant style(TrueTypeFont ttf) {
            return this;
        }
    }

    /**
     * All bundled fonts, sans serif first.
     */
    public static List<Variant> bundled() {
        List<Variant> list = new ArrayList<>();
        for (Family f : Family.values())
            for (boolean bold : new boolean[]{false, true})
                for (boolean italic : new boolean[]{false, true})
                    list.add(new Variant(f, bold, italic));
        return list;
    }

    /**
     * The bundled font which fits the font best: by name and by the flags of the font descriptor.
     */
    public static Variant bestMatch(COSDictionary font) {
        return chooseVariant(baseName(font), descriptor(font));
    }

    /**
     * Tells why a font can't be replaced.
     *
     * @return The reason, null if the font can be replaced by a bundled one.
     */
    public static String cannotReplaceReason(COSDictionary font) {
        COSName subtype = font.getCOSName(COSName.SUBTYPE);
        String lower = baseName(font).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (COSName.TYPE0.equals(subtype))
            return cidReason(font);
        if (COSName.TYPE3.equals(subtype))
            return I18n.t("fontemb.type3");
        if (isAdobeSymbol(font))
            return null;
        if (containsAny(lower, SYMBOL_FONTS))
            return I18n.t("fontemb.symbol");
        if (isEmbedded(font) && !font.containsKey(COSName.ENCODING))
            return I18n.t("fontemb.noEncoding");
        return null;
    }

    /**
     * Replaces the font program of a font by a bundled font, see the class description.
     * The text keeps its characters, the widths are those of the new font.
     *
     * @param document The document.
     * @param font     The font dictionary.
     * @param source   The font to use: one of the bundled fonts or an imported font file.
     * @throws FontEmbedException If the font can't be replaced, see {@link #cannotReplaceReason}, or the new font
     *                            can't be used (file missing, embedding not allowed, characters missing).
     *                            Nothing was changed then.
     */
    public static void replace(PDDocument document, COSDictionary font, FontSource source) throws IOException {
        String reason = cannotReplaceReason(font);
        if (reason != null)
            throw new FontEmbedException(reason);
        if (isAdobeSymbol(font)) {
            // Only Symbol Neu Euro can replace Symbol, the chosen font is ignored.
            applySymbol(document, planSymbol(font));
            return;
        }
        if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
            try (TrueTypeFont ttf = source.open()) {
                CidPlan plan = planCid(font, baseName(font), source, ttf);
                applyCid(document, plan);
            }
            return;
        }
        run(document, List.of(new Job(font, baseName(font), source)));
    }

    /**
     * What to do for one font dictionary.
     */
    private static final class Job {
        final COSDictionary font;
        final String name;
        final FontSource source;

        Job(COSDictionary font, String name, FontSource source) {
            this.font = font;
            this.name = name;
            this.source = source;
        }

        boolean trueType;
        int first, last;
        /**
         * Unicode for each code from first to last, -1 if unused.
         */
        int[] unicode;
        /**
         * Glyph names which differ from WinAnsi: code -> name.
         */
        final Map<Integer, String> differences = new LinkedHashMap<>();
    }

    private PdfFontEmbedder() {
    }

    private static COSDictionary descriptor(COSDictionary font) {
        COSDictionary d = font.getCOSDictionary(COSName.FONT_DESC);
        if (d != null)
            return d;
        if (COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))
                && font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray a
                && a.size() > 0 && a.getObject(0) instanceof COSDictionary cid)
            return cid.getCOSDictionary(COSName.FONT_DESC);
        return null;
    }

    /**
     * Checks if the font has a font program.
     */
    public static boolean isEmbedded(COSDictionary font) {
        COSDictionary d = descriptor(font);
        return d != null && (d.containsKey(COSName.FONT_FILE) || d.containsKey(COSName.FONT_FILE2)
                || d.containsKey(COSName.FONT_FILE3));
    }

    /**
     * The name of the font without subset tag.
     */
    public static String baseName(COSDictionary font) {
        String n = font.getNameAsString(COSName.BASE_FONT);
        if (n == null)
            return "unnamed";
        // Without subset tag.
        return n.matches("[A-Z]{6}\\+.*") ? n.substring(7) : n;
    }

    private static boolean containsAny(String s, List<String> parts) {
        for (String p : parts)
            if (s.contains(p))
                return true;
        return false;
    }

    private static Variant chooseVariant(String name, COSDictionary desc) {
        final String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        final int flags = desc == null ? 0 : desc.getInt(COSName.FLAGS, 0);
        Family family;
        if (containsAny(n, MONO_NAMES) || (flags & FLAG_FIXED) != 0)
            family = Family.MONO;
        else if (containsAny(n, SANS_NAMES))
            family = Family.SANS;
        else if (containsAny(n, SERIF_NAMES) || (flags & FLAG_SERIF) != 0)
            family = Family.SERIF;
        else
            family = Family.SANS;
        boolean bold = containsAny(n, List.of("bold", "black", "heavy")) || (flags & FLAG_FORCE_BOLD) != 0
                || (desc != null && desc.getInt(COSName.FONT_WEIGHT, 0) >= 700);
        boolean italic = containsAny(n, List.of("italic", "oblique")) || (flags & FLAG_ITALIC) != 0
                || (desc != null && desc.getFloat(COSName.ITALIC_ANGLE, 0) != 0);
        return new Variant(family, bold, italic);
    }

    private static Encoding originalEncoding(COSDictionary font, boolean trueType) {
        Encoding builtIn = trueType ? WinAnsiEncoding.INSTANCE : StandardEncoding.INSTANCE;
        COSBase e = font.getDictionaryObject(COSName.ENCODING);
        if (e instanceof COSName n) {
            Encoding x = Encoding.getInstance(n);
            return x != null ? x : builtIn;
        }
        if (e instanceof COSDictionary d)
            return new DictionaryEncoding(d, true, builtIn);
        return builtIn;
    }

    private static TrueTypeFont load(Variant v) throws IOException {
        try (InputStream is = PdfFontEmbedder.class.getResourceAsStream(v.resource())) {
            if (is == null)
                throw new IOException("Substitute font missing: " + v.resource());
            return new TTFParser().parse(new RandomAccessReadBuffer(is));
        }
    }

    private static int glyph(TrueTypeFont ttf, int unicode) throws IOException {
        return ttf.getUnicodeCmapLookup().getGlyphId(unicode);
    }

    /**
     * Plans the encoding of a font: which character each code has, using the glyphs of the substitute.
     */
    private static void plan(Job job, TrueTypeFont ttf) throws IOException {
        COSDictionary font = job.font;
        job.trueType = COSName.TRUE_TYPE.equals(font.getCOSName(COSName.SUBTYPE));
        boolean hasRange = font.getInt(FIRST_CHAR, -1) >= 0 && font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray;
        job.first = hasRange ? Math.min(255, font.getInt(FIRST_CHAR)) : 32;
        job.last = hasRange ? Math.min(255, Math.max(job.first, font.getInt(LAST_CHAR, 255))) : 255;
        Encoding original = originalEncoding(font, job.trueType);
        GlyphList glyphs = GlyphList.getAdobeGlyphList();
        job.unicode = new int[job.last - job.first + 1];
        COSArray widths = hasRange && font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray w ? w : null;
        Set<String> missing = new TreeSet<>();
        for (int code = job.first; code <= job.last; ++code) {
            // A code counts if the font says it has a width, without widths if it is a plain ASCII character.
            boolean needed = widths != null
                    ? code - job.first < widths.size() && widths.getObject(code - job.first) instanceof COSNumber n && n.floatValue() > 0
                    : code >= 32 && code < 127;
            String name = original.contains(code) ? original.getName(code) : ".notdef";
            String win = WinAnsiEncoding.INSTANCE.contains(code) ? WinAnsiEncoding.INSTANCE.getName(code) : ".notdef";
            int u = -1;
            String lost = null;
            if (!".notdef".equals(name) && !name.equals(win)) {
                String s = glyphs.toUnicode(name);
                if (s != null && !s.isEmpty()) {
                    if (glyph(ttf, s.codePointAt(0)) != 0) {
                        u = s.codePointAt(0);
                        job.differences.put(code, name);
                    } else {
                        lost = s;
                    }
                }
            }
            if (u < 0 && !".notdef".equals(win)) {
                String s = glyphs.toUnicode(win);
                if (s != null && !s.isEmpty()) {
                    if (glyph(ttf, s.codePointAt(0)) != 0)
                        u = s.codePointAt(0);
                    else if (lost == null)
                        lost = s;
                }
            }
            if (u < 0 && lost != null && needed && !Character.isWhitespace(lost.codePointAt(0)) && !Character.isISOControl(lost.codePointAt(0)))
                missing.add(lost);
            job.unicode[code - job.first] = u;
        }
        if (!missing.isEmpty())
            throw new FontEmbedException(I18n.t("fontemb.missing", job.name, job.source.displayName(),
                    String.join(" ", missing.stream().limit(12).toList()) + (missing.size() > 12 ? " ..." : "")));
    }

    private static COSArray names(Map<Integer, String> differences) {
        COSArray a = new COSArray();
        differences.forEach((code, name) -> {
            a.add(COSInteger.get(code));
            a.add(COSName.getPDFName(name));
        });
        return a;
    }

    private static COSFloat f(double v) {
        return new COSFloat((float) v);
    }

    /**
     * Embeds a substitute font for each font which has no font program.
     *
     * @param document The document.
     * @param fonts    All font dictionaries of the document.
     * @return What was changed ("Embedded font X for Y"), empty if all fonts are embedded.
     * @throws FontEmbedException If some font can't be substituted. Then nothing was changed.
     */
    public static Map<String, Integer> embed(PDDocument document, Collection<COSDictionary> fonts) throws IOException {
        List<Job> jobs = new ArrayList<>();
        Set<String> impossible = new TreeSet<>();
        List<COSDictionary> cidFonts = new ArrayList<>();
        List<COSDictionary> symbolFonts = new ArrayList<>();
        for (COSDictionary font : fonts) {
            if (isEmbedded(font))
                continue;
            COSName subtype = font.getCOSName(COSName.SUBTYPE);
            String name = baseName(font);
            String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (COSName.TYPE0.equals(subtype)) {
                String reason = cidReason(font);
                if (reason == null)
                    cidFonts.add(font);
                else
                    impossible.add(I18n.t("fontemb.composite", name, reason));
            } else if (isAdobeSymbol(font)) {
                symbolFonts.add(font);
            } else if (containsAny(lower, SYMBOL_FONTS)) {
                impossible.add(I18n.t("fontemb.symbolFont", name));
            } else {
                jobs.add(new Job(font, name, chooseVariant(name, descriptor(font))));
            }
        }
        if (!impossible.isEmpty())
            throw new FontEmbedException(I18n.t("fontemb.noSubstitute", String.join(", ", impossible)));
        // Plan the font Symbol too, before anything is changed.
        List<SymbolPlan> symbolPlans = new ArrayList<>();
        for (COSDictionary font : symbolFonts)
            symbolPlans.add(planSymbol(font));
        Map<String, Integer> changes = embedBundled(document, jobs, cidFonts);
        for (SymbolPlan plan : symbolPlans) {
            applySymbol(document, plan);
            changes.merge("Embedded font Symbol-NeuEuro for " + plan.name, 1, Integer::sum);
        }
        return changes;
    }

    private static Map<String, Integer> embedBundled(PDDocument document, List<Job> jobs, List<COSDictionary> cidFonts)
            throws IOException {
        if (cidFonts.isEmpty())
            return run(document, jobs);

        // Plan the composite fonts too, before anything is changed.
        List<CidPlan> plans = new ArrayList<>();
        try {
            for (COSDictionary font : cidFonts) {
                String name = baseName(font);
                Variant variant = chooseVariant(name, descriptor(font));
                plans.add(planCid(font, name, variant, load(variant)));
            }
            Map<String, Integer> changes = run(document, jobs);
            for (CidPlan plan : plans) {
                applyCid(document, plan);
                changes.merge("Embedded font " + plan.ttf.getName() + " for " + plan.name, 1, Integer::sum);
            }
            return changes;
        } finally {
            for (CidPlan plan : plans)
                plan.ttf.close();
        }
    }


    // ---------------------------------------------------------------- the font Symbol

    /**
     * The font that replaces Symbol: Symbol Neu of the Chrome OS Core Fonts (Apache License 2.0, like the other
     * bundled fonts), extended by the Euro (code 0xA0, glyph from Arimo) and renamed. It is a metric compatible clone of Symbol, a symbolic TrueType font whose (3,0) cmap has the
     * codes of the Symbol encoding at U+F000 + code.
     * The codes of the document stay as they are, so the pages don't change.
     */
    private static final String SYMBOL_RESOURCE = "/fonts/SymbolNeuEuro.ttf";
    private static final java.util.regex.Pattern ADOBE_SYMBOL =
            java.util.regex.Pattern.compile("symbol(mt)?(bold)?(italic|oblique)?");

    /**
     * Checks if the font is the font Symbol (a simple font), which Symbol Neu Euro can replace.
     */
    static boolean isAdobeSymbol(COSDictionary font) {
        COSName subtype = font.getCOSName(COSName.SUBTYPE);
        if (COSName.TYPE0.equals(subtype) || COSName.TYPE3.equals(subtype))
            return false;
        return ADOBE_SYMBOL.matcher(baseName(font).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")).matches();
    }

    /**
     * Name of the replacement of Symbol, as shown to the user.
     */
    public static String symbolFontName() {
        return "Symbol Neu Euro";
    }

    private static byte[] symbolProgram() throws IOException {
        try (InputStream is = PdfFontEmbedder.class.getResourceAsStream(SYMBOL_RESOURCE)) {
            if (is == null)
                throw new IOException("Substitute font missing: " + SYMBOL_RESOURCE);
            return is.readAllBytes();
        }
    }

    /**
     * What to write for a font Symbol.
     */
    private record SymbolPlan(COSDictionary font, String name, int first, int last, int[] widths,
                              Map<Integer, Integer> unicodes) {
    }

    private static SymbolPlan planSymbol(COSDictionary font) throws IOException {
        final String name = baseName(font);
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(symbolProgram()))) {
            var cmap = ttf.getCmap().getSubtable(3, 0);
            if (cmap == null)
                throw new IOException("Unexpected cmap of " + SYMBOL_RESOURCE);
            final double scale = 1000.0 / ttf.getUnitsPerEm();
            boolean hasRange = font.getInt(FIRST_CHAR, -1) >= 0 && font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray;
            COSArray oldWidths = hasRange && font.getDictionaryObject(COSName.WIDTHS) instanceof COSArray w ? w : null;
            int first = hasRange ? Math.min(255, font.getInt(FIRST_CHAR)) : 32;
            int last = hasRange ? Math.min(255, Math.max(first, font.getInt(LAST_CHAR, 255))) : 255;
            Encoding builtIn = org.apache.pdfbox.pdmodel.font.encoding.SymbolEncoding.INSTANCE;
            Encoding original = font.getDictionaryObject(COSName.ENCODING) instanceof COSDictionary d
                    ? new DictionaryEncoding(d, false, builtIn) : builtIn;
            GlyphList glyphList = GlyphList.getAdobeGlyphList();
            int[] widths = new int[last - first + 1];
            Map<Integer, Integer> unicodes = new TreeMap<>();
            Set<String> missing = new TreeSet<>();
            for (int code = first; code <= last; ++code) {
                int gid = cmap.getGlyphId(0xF000 + code);
                String glyphName = original.contains(code) ? original.getName(code) : ".notdef";
                boolean needed = oldWidths != null && code - first < oldWidths.size()
                        && oldWidths.getObject(code - first) instanceof COSNumber n && n.floatValue() > 0;
                if (gid == 0) {
                    if (needed)
                        missing.add(".notdef".equals(glyphName) ? "#" + code : glyphName);
                    continue;
                }
                int byName = ".notdef".equals(glyphName) ? 0 : ttf.nameToGID(glyphName);
                if (byName != 0 && byName != gid) {
                    // The document gives the code another meaning: a symbolic font can't express that.
                    if (needed)
                        missing.add(glyphName);
                    continue;
                }
                widths[code - first] = (int) Math.round(ttf.getAdvanceWidth(gid) * scale);
                String text = glyphList.toUnicode(".notdef".equals(glyphName) ? ttf.getPostScript().getName(gid) : glyphName);
                if (text != null && !text.isEmpty())
                    unicodes.put(code, text.codePointAt(0));
            }
            if (!missing.isEmpty())
                throw new FontEmbedException(I18n.t("fontemb.missing", name, symbolFontName(),
                        String.join(" ", missing.stream().limit(12).toList()) + (missing.size() > 12 ? " ..." : "")));
            return new SymbolPlan(font, name, first, last, widths, unicodes);
        }
    }

    private static void applySymbol(PDDocument document, SymbolPlan plan) throws IOException {
        byte[] data = symbolProgram();
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(data))) {
            PDStream program = new PDStream(document, new ByteArrayInputStream(data), COSName.FLATE_DECODE);
            program.getCOSObject().setInt(COSName.getPDFName("Length1"), data.length);
            COSDictionary font = plan.font;
            fillDescriptor(font, new Variant(Family.SANS, false, false), ttf, program.getCOSObject());
            COSDictionary desc = font.getCOSDictionary(COSName.FONT_DESC);
            desc.setInt(COSName.FLAGS, FLAG_SYMBOLIC);
            font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
            font.setName(COSName.BASE_FONT, ttf.getName());
            font.setInt(FIRST_CHAR, plan.first);
            font.setInt(LAST_CHAR, plan.last);
            COSArray widths = new COSArray();
            for (int w : plan.widths)
                widths.add(COSInteger.get(w));
            font.setItem(COSName.WIDTHS, widths);
            // A symbolic TrueType font has no encoding: the code selects the glyph through the (3,0) cmap.
            font.removeItem(COSName.ENCODING);
            font.removeItem(COSName.TO_UNICODE);
            if (plan.unicodes.isEmpty())
                return;
            StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                    + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                    + "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(plan.unicodes.entrySet());
            for (int i = 0; i < entries.size(); i += 100) {
                List<Map.Entry<Integer, Integer>> chunk = entries.subList(i, Math.min(entries.size(), i + 100));
                cmap.append(chunk.size()).append(" beginbfchar\n");
                for (var e : chunk) {
                    cmap.append(String.format("<%02X> <", e.getKey()));
                    for (byte b : new String(Character.toChars(e.getValue())).getBytes(java.nio.charset.StandardCharsets.UTF_16BE))
                        cmap.append(String.format("%02X", b & 0xFF));
                    cmap.append(">\n");
                }
                cmap.append("endbfchar\n");
            }
            cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            PDStream toUnicode = new PDStream(document, new ByteArrayInputStream(
                    cmap.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), COSName.FLATE_DECODE);
            font.setItem(COSName.TO_UNICODE, toUnicode.getCOSObject());
        }
    }

    // ---------------------------------------------------------------- composite (CID) fonts

    /**
     * The characters of a composite font and the glyphs of the bundled font for them.
     * Composite fonts with the encoding Identity-H write 2 byte codes, which are glyph numbers of the font program.
     * The bundled font has other glyph numbers, so a /CIDToGIDMap is written. The characters of the codes come from the
     * /ToUnicode table of the font.
     */
    private static final class CidPlan {
        final COSDictionary font;
        final String name;
        final FontSource variant;
        final TrueTypeFont ttf;
        /**
         * Unicode (of the glyph to use) for each code.
         */
        final Map<Integer, Integer> unicodeOfCid = new TreeMap<>();

        CidPlan(COSDictionary font, String name, FontSource variant, TrueTypeFont ttf) {
            this.font = font;
            this.name = name;
            this.variant = variant;
            this.ttf = ttf;
        }
    }

    private static COSDictionary descendant(COSDictionary font) {
        return font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray a
                && a.size() > 0 && a.getObject(0) instanceof COSDictionary cid ? cid : null;
    }

    /**
     * Tells why a composite font can't be replaced.
     *
     * @return The reason, null if the font can be replaced.
     */
    private static String cidReason(COSDictionary font) {
        if (!(font.getDictionaryObject(COSName.ENCODING) instanceof COSName n) || !"Identity-H".equals(n.getName()))
            return I18n.t("fontemb.onlyIdentityH");
        if (!(font.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream))
            return I18n.t("fontemb.noToUnicode");
        if (descendant(font) == null)
            return "the font has no descendant font";
        return null;
    }

    /**
     * The codes the font describes widths for, empty if it has no /W array.
     */
    private static Set<Integer> usedCids(COSDictionary cid) {
        Set<Integer> used = new TreeSet<>();
        if (!(cid.getDictionaryObject(COSName.getPDFName("W")) instanceof COSArray w))
            return used;
        for (int i = 0; i < w.size(); ) {
            if (!(w.getObject(i) instanceof COSNumber first)) {
                ++i;
                continue;
            }
            COSBase next = i + 1 < w.size() ? w.getObject(i + 1) : null;
            if (next instanceof COSArray widths) {
                for (int j = 0; j < widths.size(); ++j)
                    used.add(first.intValue() + j);
                i += 2;
            } else if (next instanceof COSNumber last) {
                for (int c = first.intValue(); c <= Math.min(last.intValue(), 0xFFFF); ++c)
                    used.add(c);
                i += 3;
            } else {
                ++i;
            }
        }
        return used;
    }

    private static final Map<String, Integer> LIGATURES = Map.of(
            "ff", 0xFB00, "fi", 0xFB01, "fl", 0xFB02, "ffi", 0xFB03, "ffl", 0xFB04);

    private static int unicodeFor(String s) {
        if (s.codePointCount(0, s.length()) == 1)
            return s.codePointAt(0);
        return LIGATURES.getOrDefault(s, -1);
    }

    private static CidPlan planCid(COSDictionary font, String name, FontSource variant, TrueTypeFont ttf) throws IOException {
        CidPlan plan = new CidPlan(font, name, variant, ttf);
        CMap toUnicode;
        try (InputStream is = ((COSStream) font.getDictionaryObject(COSName.TO_UNICODE)).createInputStream()) {
            toUnicode = new CMapParser().parse(new RandomAccessReadBuffer(is));
        }
        Set<Integer> used = usedCids(descendant(font));
        Set<String> missing = new TreeSet<>();
        int unknown = 0;
        for (int code = 1; code <= 0xFFFF; ++code) {
            // Without a /W array all codes of the table count.
            if (!used.isEmpty() && !used.contains(code))
                continue;
            String s = toUnicode.toUnicode(code);
            int u = s == null || s.isEmpty() ? -1 : unicodeFor(s);
            if (u < 0) {
                if (!used.isEmpty())
                    ++unknown;
                continue;
            }
            if (glyph(ttf, u) == 0) {
                if (Character.isISOControl(u) || Character.isWhitespace(u) || Character.isSpaceChar(u))
                    u = ' ';
                else {
                    missing.add(new String(Character.toChars(u)));
                    continue;
                }
            }
            plan.unicodeOfCid.put(code, u);
        }
        if (!missing.isEmpty())
            throw new FontEmbedException(I18n.t("fontemb.missing", name, variant.displayName(),
                    String.join(" ", missing.stream().limit(12).toList()) + (missing.size() > 12 ? " ..." : "")));
        if (unknown > 0)
            throw new FontEmbedException(I18n.t("fontemb.noUnicode", name, unknown));
        if (plan.unicodeOfCid.isEmpty())
            throw new FontEmbedException(I18n.t("fontemb.noChars", name));
        return plan;
    }

    /**
     * Writes a subset of the font with the glyphs of the characters.
     *
     * @param oldToNew Receives the new glyph number for each glyph number of the font.
     */
    static byte[] subset(TrueTypeFont ttf, Set<Integer> unicodes, Map<Integer, Integer> oldToNew) throws IOException {
        TTFSubsetter subsetter = new TTFSubsetter(ttf);
        subsetter.addAll(unicodes);
        subsetter.getGIDMap().forEach((newGid, oldGid) -> oldToNew.put(oldGid, newGid));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        subsetter.writeToStream(bos);
        return bos.toByteArray();
    }

    private static Map<String, Variant> variantsByName;

    /**
     * The bundled font with this PostScript name (e.g. "Arimo-Bold"), null if there is none.
     */
    static synchronized Variant variantByName(String name) throws IOException {
        if (variantsByName == null) {
            Map<String, Variant> names = new HashMap<>();
            for (Variant v : bundled())
                try (TrueTypeFont ttf = load(v)) {
                    names.put(ttf.getName(), v);
                }
            variantsByName = names;
        }
        return variantsByName.get(name);
    }

    /**
     * The widths of the /W array of a CID font, by code. Empty if there is no such array.
     */
    static Map<Integer, Float> cidWidths(COSDictionary cid) {
        Map<Integer, Float> result = new TreeMap<>();
        if (!(cid.getDictionaryObject(COSName.getPDFName("W")) instanceof COSArray w))
            return result;
        for (int i = 0; i < w.size(); ) {
            if (!(w.getObject(i) instanceof COSNumber first)) {
                ++i;
                continue;
            }
            COSBase next = i + 1 < w.size() ? w.getObject(i + 1) : null;
            if (next instanceof COSArray widths) {
                for (int j = 0; j < widths.size(); ++j)
                    if (widths.getObject(j) instanceof COSNumber n)
                        result.put(first.intValue() + j, n.floatValue());
                i += 2;
            } else if (next instanceof COSNumber last && i + 2 < w.size() && w.getObject(i + 2) instanceof COSNumber n) {
                for (int c = first.intValue(); c <= Math.min(last.intValue(), 0xFFFF); ++c)
                    result.put(c, n.floatValue());
                i += 3;
            } else {
                ++i;
            }
        }
        return result;
    }

    /**
     * The characters of the codes, from the ToUnicode table of a composite font. Codes without a character are missing.
     */
    static Map<Integer, String> cidTexts(COSDictionary font, Collection<Integer> codes) throws IOException {
        Map<Integer, String> result = new TreeMap<>();
        if (!(font.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream))
            return result;
        CMap toUnicode;
        try (InputStream is = stream.createInputStream()) {
            toUnicode = new CMapParser().parse(new RandomAccessReadBuffer(is));
        }
        for (int code : codes) {
            String s = toUnicode.toUnicode(code);
            if (s != null && !s.isEmpty())
                result.put(code, s);
        }
        return result;
    }

    /**
     * Checks if the font program of a composite font is a subset of a bundled font, made by {@link #applyCid}:
     * the subset for the characters of the font is made again and must be the same bytes.
     *
     * @param text The characters of the codes of the font.
     * @return The bundled font, null if the font is another one.
     */
    static Variant bundledOrigin(COSDictionary font, Map<Integer, String> text) throws IOException {
        COSDictionary cid = descendant(font);
        COSDictionary desc = cid == null ? null : cid.getCOSDictionary(COSName.FONT_DESC);
        if (desc == null || !(desc.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream program))
            return null;
        String name = desc.getNameAsString(COSName.FONT_NAME);
        Variant variant = name == null ? null : variantByName(name.matches("[A-Z]{6}\\+.*") ? name.substring(7) : name);
        if (variant == null)
            return null;
        Set<Integer> unicodes = new TreeSet<>();
        unicodes.add((int) ' ');
        for (String s : text.values()) {
            int u = unicodeFor(s);
            if (u < 0)
                return null;
            unicodes.add(u);
        }
        byte[] embedded;
        try (InputStream is = program.createInputStream()) {
            embedded = is.readAllBytes();
        }
        try (TrueTypeFont ttf = load(variant)) {
            for (int u : unicodes)
                if (glyph(ttf, u) == 0)
                    return null;
            return java.util.Arrays.equals(embedded, subset(ttf, unicodes, new HashMap<>())) ? variant : null;
        }
    }

    /**
     * Writes the font program, the glyph map, the widths and the ToUnicode table of a composite font again for the
     * given characters. The codes stay as they are, so the pages don't change.
     *
     * @param cidToText The characters of all codes of the font.
     * @throws FontEmbedException If the bundled font has no glyph for a character.
     */
    static void rewriteCid(PDDocument document, COSDictionary font, FontSource variant, Map<Integer, String> cidToText)
            throws IOException {
        try (TrueTypeFont ttf = variant.open()) {
            CidPlan plan = new CidPlan(font, baseName(font), variant, ttf);
            for (var e : cidToText.entrySet()) {
                int u = unicodeFor(e.getValue());
                if (u < 0 || glyph(ttf, u) == 0) {
                    if (u >= 0 && (Character.isISOControl(u) || Character.isWhitespace(u) || Character.isSpaceChar(u)))
                        u = ' ';
                    else
                        throw new FontEmbedException(I18n.t("fontemb.noGlyph", plan.name, variant.displayName(), String.valueOf(e.getValue())));
                }
                plan.unicodeOfCid.put(e.getKey(), u);
            }
            applyCid(document, plan);
        }
        StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(cidToText.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            List<Map.Entry<Integer, String>> chunk = entries.subList(i, Math.min(entries.size(), i + 100));
            cmap.append(chunk.size()).append(" beginbfchar\n");
            for (var e : chunk) {
                cmap.append(String.format("<%04X> <", e.getKey()));
                for (byte b : e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_16BE))
                    cmap.append(String.format("%02X", b & 0xFF));
                cmap.append(">\n");
            }
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        PDStream toUnicode = new PDStream(document, new ByteArrayInputStream(
                cmap.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), COSName.FLATE_DECODE);
        font.setItem(COSName.TO_UNICODE, toUnicode.getCOSObject());
    }

    private static void applyCid(PDDocument document, CidPlan plan) throws IOException {
        TrueTypeFont ttf = plan.ttf;
        final double scale = 1000.0 / ttf.getUnitsPerEm();
        final String psName = ttf.getName();

        Set<Integer> unicodes = new TreeSet<>(plan.unicodeOfCid.values());
        unicodes.add((int) ' ');
        Map<Integer, Integer> oldToNew = new HashMap<>();
        byte[] fontData = subset(ttf, unicodes, oldToNew);
        PDStream program = new PDStream(document, new ByteArrayInputStream(fontData), COSName.FLATE_DECODE);
        program.getCOSObject().setInt(COSName.getPDFName("Length1"), fontData.length);

        int maxCid = ((TreeMap<Integer, Integer>) plan.unicodeOfCid).lastKey();
        byte[] cidToGid = new byte[2 * (maxCid + 1)];
        COSArray w = new COSArray();
        COSArray run = null;
        int previous = -2;
        for (var e : plan.unicodeOfCid.entrySet()) {
            int cid = e.getKey();
            int oldGid = glyph(ttf, e.getValue());
            Integer newGid = oldToNew.get(oldGid);
            if (newGid == null)
                throw new IOException("Glyph missing in the subset for U+" + Integer.toHexString(e.getValue()));
            cidToGid[2 * cid] = (byte) (newGid >> 8);
            cidToGid[2 * cid + 1] = (byte) newGid.intValue();
            if (cid != previous + 1) {
                w.add(COSInteger.get(cid));
                run = new COSArray();
                w.add(run);
            }
            run.add(COSInteger.get(Math.round(ttf.getAdvanceWidth(oldGid) * scale)));
            previous = cid;
        }
        PDStream map = new PDStream(document, new ByteArrayInputStream(cidToGid), COSName.FLATE_DECODE);

        COSDictionary cid = descendant(plan.font);
        fillDescriptor(cid, plan.variant, ttf, program.getCOSObject());
        cid.setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType2"));
        cid.setName(COSName.BASE_FONT, psName);
        COSDictionary info = new COSDictionary();
        info.setItem(COSName.getPDFName("Registry"), new COSString("Adobe"));
        info.setItem(COSName.getPDFName("Ordering"), new COSString("Identity"));
        info.setInt(COSName.getPDFName("Supplement"), 0);
        cid.setItem(COSName.getPDFName("CIDSystemInfo"), info);
        cid.setItem(COSName.getPDFName("CIDToGIDMap"), map.getCOSObject());
        cid.setItem(COSName.getPDFName("W"), w);
        cid.setInt(COSName.getPDFName("DW"), 1000);
        plan.font.setName(COSName.BASE_FONT, psName);
    }

    /**
     * Embeds the planned substitutes. Plans everything first, so a failure doesn't leave a half changed document.
     */
    private static Map<String, Integer> run(PDDocument document, List<Job> jobs) throws IOException {
        Map<String, Integer> changes = new LinkedHashMap<>();
        if (jobs.isEmpty())
            return changes;

        // Plan everything first, so a failure doesn't leave a half changed document.
        Map<FontSource, TrueTypeFont> ttfs = new LinkedHashMap<>();
        Map<FontSource, Set<Integer>> used = new LinkedHashMap<>();
        try {
            for (Job job : jobs) {
                TrueTypeFont ttf = ttfs.get(job.source);
                if (ttf == null) {
                    ttf = job.source.open();
                    ttfs.put(job.source, ttf);
                    used.put(job.source, new TreeSet<>(Set.of((int) ' ')));
                }
                plan(job, ttf);
                for (int u : job.unicode)
                    if (u >= 0)
                        used.get(job.source).add(u);
            }
            Map<FontSource, COSStream> programs = new LinkedHashMap<>();
            for (var e : ttfs.entrySet()) {
                TTFSubsetter subsetter = new TTFSubsetter(e.getValue());
                subsetter.addAll(used.get(e.getKey()));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                subsetter.writeToStream(bos);
                byte[] data = bos.toByteArray();
                PDStream stream = new PDStream(document, new ByteArrayInputStream(data), COSName.FLATE_DECODE);
                stream.getCOSObject().setInt(COSName.getPDFName("Length1"), data.length);
                programs.put(e.getKey(), stream.getCOSObject());
            }
            for (Job job : jobs) {
                apply(job, ttfs.get(job.source), programs.get(job.source));
                changes.merge("Embedded font " + ttfs.get(job.source).getName() + " for " + job.name, 1, Integer::sum);
            }
        } finally {
            for (TrueTypeFont t : ttfs.values())
                t.close();
        }
        return changes;
    }

    /**
     * Writes the font descriptor of the owner (a simple font or a CID font) for the bundled font.
     */
    private static void fillDescriptor(COSDictionary owner, FontSource source, TrueTypeFont ttf, COSStream program)
            throws IOException {
        final Variant variant = source.style(ttf);
        final double scale = 1000.0 / ttf.getUnitsPerEm();
        final String psName = ttf.getName();
        COSDictionary desc = owner.getCOSDictionary(COSName.FONT_DESC);
        if (desc == null) {
            desc = new COSDictionary();
            owner.setItem(COSName.FONT_DESC, desc);
        }
        desc.setItem(COSName.TYPE, COSName.FONT_DESC);
        desc.setName(COSName.FONT_NAME, psName);
        int flags = FLAG_NONSYMBOLIC;
        if (variant.family == Family.MONO)
            flags |= FLAG_FIXED;
        if (variant.family == Family.SERIF)
            flags |= FLAG_SERIF;
        if (variant.italic)
            flags |= FLAG_ITALIC;
        if (variant.bold)
            flags |= FLAG_FORCE_BOLD;
        desc.setInt(COSName.FLAGS, flags & ~FLAG_SYMBOLIC);
        var head = ttf.getHeader();
        COSArray bbox = new COSArray();
        bbox.add(COSInteger.get(Math.round(head.getXMin() * scale)));
        bbox.add(COSInteger.get(Math.round(head.getYMin() * scale)));
        bbox.add(COSInteger.get(Math.round(head.getXMax() * scale)));
        bbox.add(COSInteger.get(Math.round(head.getYMax() * scale)));
        desc.setItem(COSName.FONT_BBOX, bbox);
        desc.setItem(COSName.ITALIC_ANGLE, f(ttf.getPostScript().getItalicAngle()));
        int ascent = Math.round(ttf.getHorizontalHeader().getAscender() * (float) scale);
        desc.setInt(COSName.ASCENT, ascent);
        desc.setInt(COSName.DESCENT, Math.round(ttf.getHorizontalHeader().getDescender() * (float) scale));
        desc.setInt(COSName.CAP_HEIGHT, ascent);
        desc.setInt(COSName.STEM_V, variant.bold ? 140 : 80);
        desc.setInt(COSName.FONT_WEIGHT, variant.bold ? 700 : 400);
        // Values of the old font, which don't fit to the new one.
        for (String stale : new String[]{"XHeight", "AvgWidth", "MaxWidth", "Leading", "FontFamily", "FontStretch", "Style"})
            desc.removeItem(COSName.getPDFName(stale));
        desc.removeItem(COSName.MISSING_WIDTH);
        desc.removeItem(COSName.FONT_FILE);
        desc.removeItem(COSName.FONT_FILE3);
        // Only for composite fonts.
        desc.removeItem(COSName.getPDFName("CIDSet"));
        // Only for Type 1 subsets.
        desc.removeItem(COSName.getPDFName("CharSet"));
        desc.setItem(COSName.FONT_FILE2, program);
    }

    private static void apply(Job job, TrueTypeFont ttf, COSStream program) throws IOException {
        COSDictionary font = job.font;
        final double scale = 1000.0 / ttf.getUnitsPerEm();
        final String psName = ttf.getName();

        COSArray widths = new COSArray();
        for (int u : job.unicode) {
            int gid = u < 0 ? 0 : glyph(ttf, u);
            widths.add(COSInteger.get(u < 0 ? 0 : Math.round(ttf.getAdvanceWidth(gid) * scale)));
        }

        fillDescriptor(font, job.source, ttf, program);

        font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        font.setName(COSName.BASE_FONT, psName);
        font.setInt(FIRST_CHAR, job.first);
        font.setInt(LAST_CHAR, job.last);
        font.setItem(COSName.WIDTHS, widths);
        if (job.differences.isEmpty()) {
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
        } else {
            COSDictionary enc = new COSDictionary();
            enc.setItem(COSName.TYPE, COSName.ENCODING);
            enc.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
            enc.setItem(COSName.DIFFERENCES, names(job.differences));
            font.setItem(COSName.ENCODING, enc);
        }
    }
}

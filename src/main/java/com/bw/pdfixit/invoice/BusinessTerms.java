package com.bw.pdfixit.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The formal names of the values of an invoice XML: the business terms of EN 16931 (e.g. "BT-1 Invoice number")
 * for the paths of {@link InvoiceData.XmlField}, and their help texts: what to enter, if the value is mandatory,
 * its format, the allowed codes and the business rules.<br>
 * The texts are in the language of the user interface: short hints reworded from the standard (German version),
 * not its wording. The header of the files names the sources. Elements without business term (e.g. of
 * the ZUGFeRD/Factur-X profile EXTENDED) have no help.
 */
public final class BusinessTerms {

    private static final String BUNDLE = "business-terms";
    private static final String HELP = "business-terms-help";
    private static final String CODE_LISTS = "/invoice-code-lists.properties";
    /**
     * The ids at the start of a business term, e.g. "BT-136 / BT-141" of "BT-136 / BT-141 Invoice line ...".
     */
    private static final Pattern IDS = Pattern.compile("^((?:B[TG]-(?:X-)?\\d+(?:-\\d+)*)(?: / B[TG]-(?:X-)?\\d+(?:-\\d+)*)*) ");

    private static volatile ResourceBundle bundle;
    private static volatile Locale bundleLocale;

    /**
     * Help texts: English (base) and those of the language, which may be missing.
     */
    private static volatile Properties helpBase;
    private static volatile Properties helpLanguage;
    private static volatile Locale helpLocale;
    private static volatile Properties codeLists;
    private static volatile java.util.Map<String, Optional<Help>> helpCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A code list.
     *
     * @param name  Name of the list, e.g. "UNTDID 4461 - Payment means".
     * @param count Number of codes.
     * @param codes Code and name of the codes (names in English, as published). Empty for long lists.
     */
    public record CodeList(String name, int count, List<String[]> codes) {
    }

    /**
     * The help of a business term. Missing items are null.
     *
     * @param id          E.g. "BT-1".
     * @param name        Name in the language of the user interface.
     * @param use         What the value is.
     * @param useEnglish  True if the use is only available in English.
     * @param note        Remarks on the content.
     * @param noteEnglish True if the note is only available in English.
     * @param rules       Business rules: id and text, or only the ids if there is no text in the language.
     * @param cardinality E.g. "1..1" or "0..n".
     * @param type        The data type: amount, unitPrice, quantity, percentage, rate, date, code, identifier,
     *                    text, reference, binary or indicator.
     * @param codeLists   The lists of the allowed codes, empty if there are none.
     */
    public record Help(String id, String name, String use, boolean useEnglish, String note, boolean noteEnglish,
                       List<String> rules, String cardinality, String type, List<CodeList> codeLists) {

        /**
         * Checks if the value must be given (within its group).
         */
        public boolean isMandatory() {
            return cardinality != null && cardinality.startsWith("1");
        }

        /**
         * Checks if the value may occur more than once.
         */
        public boolean isRepeatable() {
            return cardinality != null && (cardinality.endsWith("n") || cardinality.endsWith("*"));
        }
    }

    private BusinessTerms() {
    }

    /**
     * The business term of a value.
     *
     * @param path Path of the value below the root, see {@link InvoiceData.XmlField#path()}. Indices are ignored.
     * @return Id and name of the business term, null if the element has none (e.g. structure or code list elements).
     */
    public static String of(String path) {
        if (path == null)
            return null;
        ResourceBundle b = bundle();
        String key = path.replaceAll("\\[\\d+]", "");
        return b != null && b.containsKey(key) ? b.getString(key) : null;
    }

    /**
     * The ids of the business terms of a value. Elements whose meaning depends on other values have more than one.
     *
     * @return The ids, empty if the element has no business term.
     */
    public static List<String> ids(String path) {
        String term = of(path);
        if (term == null)
            return List.of();
        Matcher m = IDS.matcher(term);
        return m.find() ? List.of(m.group(1).split(" / ")) : List.of();
    }

    /**
     * The help of a business term.
     *
     * @param id E.g. "BT-1".
     * @return The help, null if the id is unknown.
     */
    public static Help help(String id) {
        if (id == null)
            return null;
        loadHelp();
        java.util.Map<String, Optional<Help>> cache = helpCache;
        return cache.computeIfAbsent(id, i -> Optional.ofNullable(createHelp(i))).orElse(null);
    }

    private static Help createHelp(String id) {
        Properties base = helpBase;
        Properties lang = helpLanguage;
        if (java.util.stream.Stream.of(".name", ".use", ".type", ".card").noneMatch(s -> base.containsKey(id + s)))
            return null;
        String name = text(lang, base, id + ".name");
        String use = text(lang, null, id + ".use");
        String note = text(lang, null, id + ".note");
        String useEn = base.getProperty(id + ".use");
        String noteEn = base.getProperty(id + ".note");
        List<String> rules = new ArrayList<>();
        String r = text(lang, base, id + ".rules");
        if (r != null)
            rules.addAll(List.of(r.split("\n")));
        List<CodeList> lists = codeListsOf(id);
        return new Help(id, name, use != null ? use : useEn, use == null && useEn != null && lang != null,
                note != null ? note : noteEn, note == null && noteEn != null && lang != null,
                rules, base.getProperty(id + ".card"), base.getProperty(id + ".type"), lists);
    }

    /**
     * The help of all business terms of a value, see {@link #ids(String)}, each followed by the terms of its
     * attributes (e.g. "BT-29-1 Scheme identifier" after "BT-29 Seller identifier").
     */
    public static List<Help> helpOfPath(String path) {
        List<Help> result = new ArrayList<>();
        for (String id : ids(path)) {
            Help h = help(id);
            if (h == null)
                continue;
            result.add(h);
            String attributes = helpBase.getProperty(id + ".attributes");
            if (attributes != null)
                for (String a : attributes.split(",")) {
                    Help ah = help(a);
                    if (ah != null)
                        result.add(ah);
                }
        }
        return result;
    }

    private static String text(Properties lang, Properties base, String key) {
        String v = lang == null ? null : lang.getProperty(key);
        if ((v == null || v.isBlank()) && base != null)
            v = base.getProperty(key);
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * The code lists of a business term: the lists whose "&lt;list&gt;.ids" contain the id.
     */
    private static List<CodeList> codeListsOf(String id) {
        Properties p = codeLists();
        List<CodeList> result = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(p.stringPropertyNames())) {
            if (key.endsWith(".ids") && List.of(p.getProperty(key).split(",")).contains(id)) {
                CodeList list = codeList(key.substring(0, key.length() - 4));
                if (list != null)
                    result.add(list);
            }
        }
        return result;
    }

    private static Properties codeLists() {
        Properties p = codeLists;
        if (p == null) {
            p = load(CODE_LISTS);
            codeLists = p == null ? new Properties() : p;
            p = codeLists;
        }
        return p;
    }

    private static CodeList codeList(String key) {
        Properties p = codeLists();
        String name = p.getProperty(key + ".name");
        if (name == null)
            return null;
        List<String[]> codes = new ArrayList<>();
        String all = p.getProperty(key + ".codes");
        if (all != null)
            for (String line : all.split("\n")) {
                int tab = line.indexOf('\t');
                codes.add(tab < 0 ? new String[]{line, ""} : new String[]{line.substring(0, tab), line.substring(tab + 1)});
            }
        int count;
        try {
            count = Integer.parseInt(p.getProperty(key + ".count", String.valueOf(codes.size())));
        } catch (NumberFormatException e) {
            count = codes.size();
        }
        return new CodeList(name, count, List.copyOf(codes));
    }

    private static synchronized void loadHelp() {
        Locale l = I18n.getLocale();
        if (helpBase != null && l.equals(helpLocale))
            return;
        if (helpBase == null) {
            Properties p = load("/" + HELP + ".properties");
            helpBase = p == null ? new Properties() : p;
        }
        String language = l.getLanguage();
        helpLanguage = language.isEmpty() || language.equals("en") ? null : load("/" + HELP + "_" + language + ".properties");
        helpLocale = l;
        helpCache = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private static Properties load(String resource) {
        try (InputStream in = BusinessTerms.class.getResourceAsStream(resource)) {
            if (in == null)
                return null;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            Log.error("Failed to read %s: %s", resource, e);
            return null;
        }
    }

    private static ResourceBundle bundle() {
        Locale l = I18n.getLocale();
        ResourceBundle b = bundle;
        if (!l.equals(bundleLocale)) {
            try {
                // Fallback to the English base file, not to the language of the system.
                b = ResourceBundle.getBundle(BUNDLE, l, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            } catch (MissingResourceException e) {
                // No business terms available: the values are shown without them.
                Log.debug("Business terms not found: %s", e);
                b = null;
            }
            bundle = b;
            bundleLocale = l;
        }
        return b;
    }
}

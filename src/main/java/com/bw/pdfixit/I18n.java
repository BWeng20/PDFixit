package com.bw.pdfixit;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Translations of the texts shown to the user.<br>
 * The texts are in the resource bundles {@code messages*.properties} (UTF-8). {@code messages.properties} is the
 * English default and the fallback for missing keys, {@code messages_<language>.properties} holds a translation.<br>
 * The language is chosen once at start ({@link #init()}): the user preference, otherwise the system language. A
 * change of the preference takes effect on the next start.<br>
 * <b>Add a language:</b> create {@code messages_<code>.properties} (with the key {@code lang.name}, the name of the
 * language in that language) and add the code to the key {@code lang.available} of {@code messages.properties}.
 * The test {@code I18nTest} checks that the files are complete.
 */
public final class I18n {

    /**
     * Value of the preference for "use the system language".
     */
    public static final String SYSTEM = "";

    static final String BUNDLE = "messages";

    private static volatile ResourceBundle bundle;
    private static volatile Locale locale = Locale.ENGLISH;

    private I18n() {
    }

    /**
     * Chooses the language from the system property {@code pdfixit.lang} (e.g. for tests and the command line),
     * otherwise from the user preference, otherwise from the system. Sets the default locale of the JVM, so
     * the formatting of numbers and dates and the standard dialogs follow the language.
     */
    public static synchronized void init() {
        String lang = System.getProperty("pdfixit.lang");
        if (lang == null || lang.isBlank())
            lang = Preferences.getInstance().getString(Preferences.USER_PREF_LANGUAGE, SYSTEM);
        init(lang.isBlank() ? null : Locale.forLanguageTag(lang.trim().replace('_', '-')));
    }

    /**
     * Sets the language.
     *
     * @param requested The language, or null for the system language.
     */
    public static synchronized void init(Locale requested) {
        Locale l = requested != null ? requested : systemLocale;
        Locale.setDefault(l);
        locale = l;
        bundle = load(l);
    }

    /**
     * Loads the bundle without the fallback to the default locale of the JVM: a language without translation
     * must give the English default file and not the file of the system language.
     */
    private static ResourceBundle load(Locale l) {
        return ResourceBundle.getBundle(BUNDLE, l,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static final Locale systemLocale = Locale.getDefault();

    private static ResourceBundle bundle() {
        ResourceBundle b = bundle;
        if (b == null) {
            init();
            b = bundle;
        }
        return b;
    }

    /**
     * The active locale.
     */
    public static Locale getLocale() {
        bundle();
        return locale;
    }

    /**
     * Translates a text.
     *
     * @param key  The key in the bundle.
     * @param args Optional arguments for the placeholders {0}, {1}, ... ({@link MessageFormat}).
     * @return The text, or the key in square brackets if there is no such key (so the gap is visible).
     */
    public static String t(String key, Object... args) {
        String text;
        try {
            text = bundle().getString(key);
        } catch (MissingResourceException e) {
            return "[" + key + "]";
        }
        if (args.length == 0)
            return text;
        return new MessageFormat(text, locale).format(args);
    }

    /**
     * True if there is a text for the key.
     */
    public static boolean has(String key) {
        return bundle().containsKey(key);
    }

    /**
     * The codes of the languages with a translation, English first.
     */
    public static List<String> availableLanguages() {
        List<String> r = new ArrayList<>();
        for (String c : load(Locale.ROOT).getString("lang.available").split(","))
            if (!c.isBlank())
                r.add(c.trim());
        return r;
    }

    /**
     * The name of a language in that language.
     */
    public static String languageName(String code) {
        return load(Locale.forLanguageTag(code)).getString("lang.name");
    }

    /**
     * The language code to show as selected: the preference, or {@link #SYSTEM}.
     */
    public static String getPreferredLanguage() {
        return Preferences.getInstance().getString(Preferences.USER_PREF_LANGUAGE, SYSTEM);
    }

    public static void setPreferredLanguage(String code) {
        Preferences.getInstance().set(Preferences.USER_PREF_LANGUAGE, code == null || code.isBlank() ? null : code);
    }
}

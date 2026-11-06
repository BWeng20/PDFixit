package com.bw.pdfixit;

import java.awt.Color;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Preferences {

    /**
     * User preferences key for the last PDF directory the user selected.
     */
    public final static String USER_PREF_LAST_SIG_DIR = "last-sig-dir";

    /**
     * User preferences key for the signature password (if option is enabled).
     */
    public final static String USER_PREF_SIG_PASSWORD = "sig-key-password";

    /**
     * User preferences key for the signature key file.
     */
    public final static String USER_PREF_SIG_FILE = "sig-key-file";

    /**
     * User preferences key for the signature flag.
     */
    public final static String USER_PREF_SIG_ENABLED = "sig";

    /**
     * User preferences key for the last PDF directory the user selected.
     */
    public final static String USER_PREF_LAST_PDF_DIR = "last-dir";

    /**
     * User preferences key for the last directory of the file browser for templates (letterheads of the wizards).
     */
    public final static String USER_PREF_LAST_TEMPLATE_DIR = "last-template-dir";

    /**
     * User preferences keys of the recently used templates: this prefix with 1 (the latest) to
     * {@link #RECENT_TEMPLATES}.
     */
    public final static String USER_PREF_RECENT_TEMPLATE = "recent-template-";

    /**
     * The number of recently used templates that are remembered.
     */
    public final static int RECENT_TEMPLATES = 5;

    /**
     * User preferences key for the PDF/A-3 level of generated invoices: "a", "b" or "u" (the default).
     */
    public final static String USER_PREF_INVOICE_PDFA_LEVEL = "invoice-pdfa-level";
    /**
     * The language of the printed invoice ("de" or "en"), see the wizard and the CLI option {@code --language}.
     */
    public final static String USER_PREF_INVOICE_PRINT_LANGUAGE = "invoice-print-language";

    /**
     * User preferences key for the last directory of invoice XML files (import and export in the invoice editor).
     */
    public final static String USER_PREF_LAST_XML_DIR = "last-xml-dir";

    /**
     * User preferences key for the last image directory the user selected.
     */
    public final static String USER_PREF_LAST_IMAGE_DIR = "last-image-dir";

    /**
     * User preferences key for the language of the user interface (language code, absent for the system language).
     */
    public final static String USER_PREF_LANGUAGE = "language";

    /**
     * User preferences key for the last Look And Feel selected.
     */
    public final static String USER_PREF_LAF = "laf";

    /**
     * User preferences key for the password option.
     */
    public final static String USER_PREF_STORE_PASSWORDS = "store-passwords";

    /**
     * User preferences key for asking before exit if the document is modified (default true).
     */
    public final static String USER_PREF_CONFIRM_EXIT = "confirm-exit-if-modified";

    /**
     * User preferences key for the Encryption Key Length.
     */
    public final static String USER_ENCRYPTION_KEY_LENGTH = "encryption-key-length";

    /**
     * User preferences key for the dpi quality option.
     */
    public final static String USER_PREF_DPI = "dpi";

    /**
     * User preferences keys of the main window: the size of the window (if it is not maximized), if it is maximized, and
     * the position of the divider between the controls and the page preview (in pixels from the left edge).
     * Nothing is stored until the user changes the window.
     */
    public final static String USER_PREF_WINDOW_WIDTH = "window-width";
    public final static String USER_PREF_WINDOW_HEIGHT = "window-height";
    public final static String USER_PREF_WINDOW_MAXIMIZED = "window-maximized";
    public final static String USER_PREF_DIVIDER = "divider-location";

    /**
     * User preferences key for the color of redacted areas (RGB).
     */
    public final static String USER_PREF_REDACTION_COLOR = "redaction-color";

    /**
     * User preferences key: antialiasing of the redaction editor and of the edges of rotated redacted areas.
     * Off by default: then every pixel an area touches is fully covered.
     */
    public final static String USER_PREF_REDACTION_AA = "redaction-aa";

    public final static String USER_PREF_VIEWER_ANTIALIASING = "ViewerAA";
    public final static boolean USER_PREF_VIEWER_ANTIALIASING_DEFAULT = true;

    /**
     * User preferences key: if true, there are no animations (e.g. the hover effect of the page buttons).
     */
    public final static String USER_PREF_REDUCED_MOTION = "reduced-motion";
    public final static boolean USER_PREF_REDUCED_MOTION_DEFAULT = false;

    public final static String USER_PREF_VIEWER_RENDER_QUALITY ="viewer-renderquality";

    public final static String USER_PREF_VIEWER_INTERPOLATE_BI_CUBIC = "viewer-render-bicubic";

    /**
     * User preferences key for the owner password (if option is enabled).
     */
    public final static String USER_PREF_OWNER_PASSWORD = "ownerpassword";

    /**
     * User preferences key for the user password (if option is enabled).
     */
    public final static String USER_PREF_USER_PASSWORD = "userpassword";

    public final static String LAF_DARK_CLASSNAME = "com.formdev.flatlaf.FlatDarkLaf";
    public final static String LAF_LIGHT_CLASSNAME = "com.formdev.flatlaf.FlatLightLaf";


    public final static String DEFAULT_LAF = LAF_LIGHT_CLASSNAME;

    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("pdfixit");

    private Preferences() {
        prefs.addPreferenceChangeListener(evt -> {
            Log.debug("Preference changed: %s %s", evt.getKey(), evt.getNewValue());
            support.firePropertyChange(evt.getKey(), null, evt.getNewValue());
        });
    }


    private static final Preferences instance = new Preferences();

    public static Preferences getInstance() {
        return instance;
    }

    private final PropertyChangeSupport support = new PropertyChangeSupport(this);

    public void addPropertyChangeListener(PropertyChangeListener listener, String... keys) {
        for (String k : keys)
            support.addPropertyChangeListener(k, listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener, String... keys) {
        for (String k : keys)
            support.removePropertyChangeListener(k, listener);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }


    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    /**
     * Get setting {@value USER_PREF_DPI} with sanity check to ensure range [150 - 1200].
     *
     * @return the dpi setting.
     */
    public int getDpi() {
        int dpiSetting = this.getInt(Preferences.USER_PREF_DPI, 300);
        if (dpiSetting < 150)
            dpiSetting = 150;
        else if (dpiSetting > 1200)
            dpiSetting = 1200;
        return dpiSetting;
    }

    /**
     * Get setting {@value USER_PREF_REDACTION_COLOR}.
     *
     * @return the color used to paint redacted areas, black by default.
     */
    public Color getRedactionColor() {
        return new Color(this.getInt(USER_PREF_REDACTION_COLOR, 0) & 0xFFFFFF);
    }

    /**
     * @return true if rotated redacted areas have smooth edges (see {@value USER_PREF_REDACTION_AA}), false by default.
     */
    public boolean isRedactionAntialiasing() {
        return getBoolean(USER_PREF_REDACTION_AA, false);
    }

    public void setRedactionAntialiasing(boolean antialiasing) {
        set(USER_PREF_REDACTION_AA, antialiasing);
    }

    public void setRedactionColor(Color color) {
        this.set(USER_PREF_REDACTION_COLOR, color.getRGB() & 0xFFFFFF);
    }

    /**
     * The PDF/A level of generated invoices, see {@value USER_PREF_INVOICE_PDFA_LEVEL}.
     *
     * @return {@link PdfStandardProfile#PDFA_3A}, {@link PdfStandardProfile#PDFA_3B} or {@link PdfStandardProfile#PDFA_3U}.
     */
    public PdfStandardProfile getInvoicePdfaLevel() {
        return invoicePdfaLevel(getString(USER_PREF_INVOICE_PDFA_LEVEL, null));
    }

    /**
     * Stores the PDF/A level of generated invoices.
     *
     * @param level {@link PdfStandardProfile#PDFA_3A}, {@link PdfStandardProfile#PDFA_3B} or {@link PdfStandardProfile#PDFA_3U}.
     */
    public void setInvoicePdfaLevel(PdfStandardProfile level) {
        if (level != PdfStandardProfile.PDFA_3A && level != PdfStandardProfile.PDFA_3B && level != PdfStandardProfile.PDFA_3U)
            throw new IllegalArgumentException("Not a level of an invoice: " + level);
        set(USER_PREF_INVOICE_PDFA_LEVEL, level.conformance().toLowerCase(Locale.ROOT));
    }

    /**
     * The level of a stored letter: "a" and "b" give PDF/A-3a and 3b, everything else (also none) PDF/A-3u.
     */
    static PdfStandardProfile invoicePdfaLevel(String letter) {
        if ("a".equalsIgnoreCase(letter))
            return PdfStandardProfile.PDFA_3A;
        if ("b".equalsIgnoreCase(letter))
            return PdfStandardProfile.PDFA_3B;
        return PdfStandardProfile.PDFA_3U;
    }

    /**
     * The recently used templates, the latest first. The files are not checked for existence.
     */
    public List<String> getRecentTemplates() {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= RECENT_TEMPLATES; ++i) {
            String path = getString(USER_PREF_RECENT_TEMPLATE + i, null);
            if (path != null && !path.isBlank())
                list.add(path);
        }
        return list;
    }

    /**
     * Puts a template at the top of the recently used ones.
     */
    public void addRecentTemplate(String path) {
        setRecentTemplates(withRecent(getRecentTemplates(), path));
    }

    /**
     * Removes a template from the recently used ones, e.g. one that doesn't exist any more.
     */
    public void removeRecentTemplate(String path) {
        List<String> list = getRecentTemplates();
        list.removeIf(p -> samePath(p, path));
        setRecentTemplates(list);
    }

    private void setRecentTemplates(List<String> list) {
        for (int i = 1; i <= RECENT_TEMPLATES; ++i)
            set(USER_PREF_RECENT_TEMPLATE + i, i <= list.size() ? list.get(i - 1) : null);
    }

    /**
     * The list with the path at the top: an equal path is removed, the list is cut to {@link #RECENT_TEMPLATES}.
     */
    static List<String> withRecent(List<String> list, String path) {
        List<String> result = new ArrayList<>();
        result.add(path);
        for (String p : list)
            if (!samePath(p, path) && result.size() < RECENT_TEMPLATES)
                result.add(p);
        return result;
    }

    private static boolean samePath(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    public String getString(String key, String defaultValue) {
        return prefs.get(key, defaultValue);
    }

    public void set(String key, String value) {
        if (value == null) {
            if (prefs.get(key, null) != null)
                prefs.remove(key);
        } else if (!value.equals(prefs.get(key, null)))
            prefs.put(key, value);
    }

    public void set(String key, boolean value) {
        prefs.putBoolean(key, value);
    }

    public void set(String key, int value) {
        prefs.putInt(key, value);
    }

    public void remove(String key) {
        prefs.remove(key);
    }
}

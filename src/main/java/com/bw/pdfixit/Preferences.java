package com.bw.pdfixit;

import java.awt.Color;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

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
     * User preferences key for the color of redacted areas (RGB).
     */
    public final static String USER_PREF_REDACTION_COLOR = "redaction-color";

    public final static String USER_PREF_VIEWER_ANTIALIASING = "ViewerAA";
    public final static boolean USER_PREF_VIEWER_ANTIALIASING_DEFAULT = true;

    public final static String USER_PREF_VIEWER_RENDER_QUALITY = "viewer-renderquality";

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

    public void setRedactionColor(Color color) {
        this.set(USER_PREF_REDACTION_COLOR, color.getRGB() & 0xFFFFFF);
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

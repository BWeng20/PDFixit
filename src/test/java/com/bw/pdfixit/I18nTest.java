package com.bw.pdfixit;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest {

    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/" + name);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static TreeSet<String> placeholders(String text) {
        TreeSet<String> s = new TreeSet<>();
        Matcher m = Pattern.compile("\\{\\d+|%\\d*\\$?[-0-9.]*[sdf]").matcher(text);
        while (m.find())
            s.add(m.group());
        return s;
    }

    @Test
    void everyLanguageIsCompleteAndHasTheSamePlaceholders() throws Exception {
        Properties base = load("messages.properties");
        for (String code : I18n.availableLanguages()) {
            if (code.equals("en"))
                continue;
            Properties p = load("messages_" + code + ".properties");
            Map<String, String> missing = new HashMap<>();
            for (String key : base.stringPropertyNames()) {
                if (key.equals("lang.available"))
                    continue;
                String t = p.getProperty(key);
                if (t == null || t.isBlank())
                    missing.put(key, "missing");
                else if (!placeholders(t).equals(placeholders(base.getProperty(key))))
                    missing.put(key, "placeholders differ");
            }
            assertTrue(missing.isEmpty(), code + ": " + missing);
            for (String key : p.stringPropertyNames())
                assertTrue(base.containsKey(key), code + ": key not in the default file: " + key);
        }
    }

    @Test
    void translatesAndFallsBack() {
        try {
            I18n.init(Locale.GERMAN);
            assertEquals("Schließen", I18n.t("about.close"));
            assertEquals("Über PDFixit", I18n.t("about.title", "PDFixit"));
            // Language without translation: English default.
            I18n.init(Locale.FRENCH);
            assertEquals("Close", I18n.t("about.close"));
            assertEquals("[no.such.key]", I18n.t("no.such.key"));
        } finally {
            I18n.init(Locale.ENGLISH);
        }
    }

    @Test
    void languageNames() {
        assertEquals("English", I18n.languageName("en"));
        assertEquals("Deutsch", I18n.languageName("de"));
    }
}

package com.bw.pdfixit;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The manual of the tool: the README as PDF.<br>
 * The file is not part of the jar. The distribution has it beside the jar, so it is searched in the folders of the
 * class path and the module path.
 */
public final class Manual {

    /**
     * Name of the PDF.
     */
    static final String FILE_NAME = "manual.pdf";


    /**
     * System property with the path of the PDF, used if the program is not started from a distribution
     * (e.g. by the Gradle task "run").
     */
    static final String PROPERTY = "pdfixit.manual";

    private Manual() {
    }

    /**
     * Finds the manual.
     *
     * @return The file, null if there is none.
     */
    public static Path find() {

        final String language = I18n.getLocale().getLanguage();
        final String i18nFileName = language.equals("de") ? "manual_de.pdf" : "manual.pdf";

        String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                Path p = Path.of(configured);
                if (Files.isRegularFile(p)) {
                    Path localized = p.toAbsolutePath().getParent().resolve(i18nFileName);
                    return Files.isRegularFile(localized) ? localized : p;
                }
            } catch (InvalidPathException e) {
                Log.debug("Bad path in %s: %s", PROPERTY, e.getMessage());
            }
        }

        URL manualURL = Main.class.getClassLoader().getResource( i18nFileName );

        try {
            return (manualURL != null) ? Paths.get(manualURL.toURI()) : null;
        } catch (URISyntaxException ue ) {
            Log.debug("Can't access %s: %s", Objects.toString(manualURL), ue.getMessage());
        }
        return null;

    }
}

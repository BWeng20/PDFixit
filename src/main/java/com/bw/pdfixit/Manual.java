package com.bw.pdfixit;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
     * Name of the PDF in another language, e.g. {@code manual_de.pdf}. The language is the one of the user interface.
     */
    static String fileName(String language) {
        return "manual_" + language + ".pdf";
    }

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
        String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                Path p = Path.of(configured);
                if (Files.isRegularFile(p)) {
                    Path localized = localized(p.toAbsolutePath().getParent());
                    return localized != null ? localized : p;
                }
            } catch (InvalidPathException e) {
                Log.debug("Bad path in %s: %s", PROPERTY, e.getMessage());
            }
        }
        List<Path> entries = new ArrayList<>();
        for (String property : new String[]{"jdk.module.path", "java.class.path"}) {
            String value = System.getProperty(property);
            if (value == null)
                continue;
            for (String entry : value.split(File.pathSeparator)) {
                if (entry.isBlank())
                    continue;
                try {
                    entries.add(Path.of(entry));
                } catch (InvalidPathException e) {
                    Log.debug("Bad path in %s: %s", property, e.getMessage());
                }
            }
        }
        return find(entries);
    }

    /**
     * Looks for the file beside each entry (a jar), and in the entry itself if it is a folder.
     *
     * @param entries Jars and folders of the class path or module path.
     * @return The file, null if there is none.
     */
    static Path find(List<Path> entries) {
        for (Path entry : entries) {
            List<Path> folders = new ArrayList<>(2);
            if (Files.isDirectory(entry))
                folders.add(entry);
            Path parent = entry.toAbsolutePath().getParent();
            if (parent != null)
                folders.add(parent);
            for (Path folder : folders) {
                Path localized = localized(folder);
                if (localized != null)
                    return localized;
                Path file = folder.resolve(FILE_NAME);
                if (Files.isRegularFile(file))
                    return file;
            }
        }
        return null;
    }

    /**
     * The manual in the language of the user interface.
     *
     * @return The file, null if the language is English (the default) or the folder has no manual in that language.
     */
    private static Path localized(Path folder) {
        if (folder == null)
            return null;
        String language = I18n.getLocale().getLanguage();
        if (language.isEmpty() || language.equals("en"))
            return null;
        Path file = folder.resolve(fileName(language));
        return Files.isRegularFile(file) ? file : null;
    }
}

package com.bw.pdfixit.fonts;

import com.bw.pdfixit.Log;
import com.bw.pdfixit.SafeFile;
import com.bw.pdfixit.invoice.InvoiceData;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The font files imported by the user. Like the party presets they are stored in a JSON file of the settings directory,
 * only name and path, never the font itself.<br>
 * Reading the list reads only this small file. The fonts are read when they are used, see {@link ImportedFont#open()}.
 */
public final class ImportedFonts {

    /**
     * An entry of the file.
     */
    public record Entry(String name, String path) {
    }

    private static final String FONTS_KEY = "fonts";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_EMPTY));

    private ImportedFonts() {
    }

    /**
     * "fonts.json" in the settings directory, e.g. <code>{"fonts": [{"name": "My Font", "path": "C:/fonts/my.ttf"}]}</code>.
     */
    public static Path file() {
        return InvoiceData.settingsDirectory().resolve("fonts.json");
    }

    /**
     * Reads the entries.
     *
     * @return The entries, empty if the file doesn't exist.
     * @throws IOException If the file exists, but can't be read.
     */
    public static List<Entry> load(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(file))
            return entries;
        JsonNode root = MAPPER.readTree(file.toFile());
        JsonNode list = root == null ? null : root.get(FONTS_KEY);
        if (list != null && list.isArray())
            for (Entry e : MAPPER.convertValue(list, new TypeReference<List<Entry>>() {
            }))
                if (e != null && e.path() != null && !e.path().isBlank())
                    entries.add(e);
        return entries;
    }

    /**
     * Reads the entries of {@link #file()}. Errors are logged.
     */
    public static List<Entry> load() {
        try {
            return load(file());
        } catch (IOException e) {
            Log.error("Failed to read %s: %s", file(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * The imported fonts of {@link #file()}. The font files are not touched, not even checked for existence.
     */
    public static List<ImportedFont> fonts() {
        return fonts(file());
    }

    /**
     * The imported fonts of the file. Errors are logged.
     */
    public static List<ImportedFont> fonts(Path file) {
        List<ImportedFont> fonts = new ArrayList<>();
        try {
            for (Entry e : load(file))
                fonts.add(new ImportedFont(e.name(), Path.of(e.path())));
        } catch (IOException e) {
            Log.error("Failed to read %s: %s", file, e.getMessage());
        }
        return fonts;
    }

    /**
     * Writes the entries. The directory is created if needed. Other content of an existing file is kept.
     */
    public static void save(Path file, List<Entry> entries) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null)
            Files.createDirectories(dir);
        ObjectNode root = null;
        if (Files.exists(file)) {
            try {
                if (MAPPER.readTree(file.toFile()) instanceof ObjectNode o)
                    root = o;
            } catch (IOException e) {
                // Broken file, replaced.
            }
        }
        if (root == null)
            root = MAPPER.createObjectNode();
        root.set(FONTS_KEY, MAPPER.valueToTree(entries));
        byte[] json = MAPPER.writeValueAsBytes(root);
        SafeFile.write(file, os -> os.write(json));
    }

    /**
     * Adds a font to the list in {@link #file()} unless the same file is in it.
     *
     * @return True if it was added.
     */
    public static boolean add(Path file, ImportedFont font) throws IOException {
        List<Entry> entries = load(file);
        for (Entry e : entries)
            if (new ImportedFont(e.name(), Path.of(e.path())).equals(font))
                return false;
        entries.add(new Entry(font.getName(), font.getPath().toString()));
        save(file, entries);
        return true;
    }

    /**
     * The outcome of {@link #importFiles}.
     *
     * @param added  The fonts which were added.
     * @param errors One message for each file which was not added.
     */
    public record ImportResult(List<ImportedFont> added, List<String> errors) {
    }

    /**
     * Checks font files and adds the usable ones to the list in the file. A file which is in the list already is
     * not an error, it is just not added again.
     */
    public static ImportResult importFiles(Path store, List<Path> files) {
        List<ImportedFont> added = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            try {
                ImportedFont font = ImportedFont.fromFile(file);
                if (add(store, font))
                    added.add(font);
            } catch (IOException e) {
                errors.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        return new ImportResult(added, errors);
    }

    /**
     * Removes the font from the list in the file. The font file itself is not touched.
     *
     * @return True if it was in the list.
     */
    public static boolean remove(Path file, ImportedFont font) throws IOException {
        List<Entry> entries = load(file);
        boolean removed = entries.removeIf(e -> new ImportedFont(e.name(), Path.of(e.path())).equals(font));
        if (removed)
            save(file, entries);
        return removed;
    }
}

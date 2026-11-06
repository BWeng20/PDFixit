package com.bw.pdfixit.template;

import com.bw.pdfixit.Log;
import com.bw.pdfixit.SafeFile;
import com.bw.pdfixit.invoice.InvoiceData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Where the layout of a template is stored.
 * <ol>
 * <li>Beside the PDF: {@code <template base name>.json}, if that file carries the marker of a layout. A JSON of that
 * name without the marker belongs to someone else: it is never read as a layout and never overwritten.</li>
 * <li>Otherwise in the layout directory ({@code ~/.pdfixit/layouts}):
 * {@code <template base name>-<8 hex digits of the hash of the absolute path>.json}.</li>
 * </ol>
 * Saving goes beside the PDF if the folder is writable and the target is absent or a layout; otherwise to the layout
 * directory. If both exist, the one beside the PDF wins.
 */
public final class LayoutFiles {

    private LayoutFiles() {
    }

    /**
     * The layout directory in the settings directory.
     */
    public static Path defaultDirectory() {
        return InvoiceData.settingsDirectory().resolve("layouts");
    }

    /**
     * The layout found for a template.
     *
     * @param layout        The layout; the DIN 5008 defaults (form B) if there is no file.
     * @param file          The file it was read from, null for the defaults.
     * @param foreignBeside The JSON beside the template, if it exists but is no layout (it was left alone).
     *                      Null if there is none or it is a layout.
     */
    public record Found(Layout layout, Path file, Path foreignBeside) {
        public boolean isDefault() {
            return file == null;
        }
    }

    /**
     * The file name without the extension, e.g. "Rechnung" for "Rechnung.pdf".
     */
    static String baseName(Path template) {
        String name = template.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * The layout file beside the template.
     */
    public static Path besideTemplate(Path template) {
        return template.toAbsolutePath().resolveSibling(baseName(template) + ".json");
    }

    /**
     * The layout file of the template in a layout directory. Templates of the same name in different folders get
     * different files.
     */
    public static Path inDirectory(Path template, Path directory) {
        return directory.resolve(baseName(template) + "-" + hash(template) + ".json");
    }

    private static String hash(Path template) {
        Path p = template.toAbsolutePath().normalize();
        try {
            // The real path has the case of the file system (Windows), so the same file always gets the same hash.
            p = p.toRealPath();
        } catch (IOException e) {
            // Doesn't exist (yet): the normalized path.
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(p.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks if a file is a layout file (JSON with the marker).
     */
    public static boolean isLayout(Path file) {
        try {
            return Files.isRegularFile(file) && Layout.isLayout(Files.readAllBytes(file));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Finds the layout of a template in the default layout directory, see {@link #find(Path, Path)}.
     */
    public static Found find(Path template) throws IOException {
        return find(template, defaultDirectory());
    }

    /**
     * Finds the layout of a template.
     *
     * @param template  The template PDF.
     * @param directory The layout directory.
     * @return The layout, the DIN 5008 defaults if there is none.
     * @throws Layout.LayoutException If a layout file exists, but is not valid.
     */
    public static Found find(Path template, Path directory) throws IOException {
        Path beside = besideTemplate(template);
        Path foreign = null;
        if (Files.exists(beside)) {
            if (isLayout(beside))
                return new Found(read(beside), beside, null);
            foreign = beside;
        }
        Path inDirectory = inDirectory(template, directory);
        if (isLayout(inDirectory))
            return new Found(read(inDirectory), inDirectory, foreign);
        return new Found(Layout.din5008(Layout.Form.B), null, foreign);
    }

    /**
     * Reads a layout file. Relative paths in it are relative to its folder.
     */
    public static Layout read(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        return Layout.fromJson(Files.readAllBytes(file), dir);
    }

    /**
     * Saves the layout of a template in the default layout directory, see {@link #save(Layout, Path, Path)}.
     */
    public static Path save(Layout layout, Path template) throws IOException {
        return save(layout, template, defaultDirectory());
    }

    /**
     * Saves the layout of a template: beside the PDF if possible, otherwise in the layout directory.
     *
     * @return The file that was written.
     */
    public static Path save(Layout layout, Path template, Path directory) throws IOException {
        Path beside = besideTemplate(template);
        if (!Files.exists(beside) || isLayout(beside)) {
            try {
                write(layout, beside, beside.getParent());
                return beside;
            } catch (IOException e) {
                Log.info("Can't write the layout beside the template (%s), using %s.", e.getMessage(), directory);
            }
        }
        Path target = inDirectory(template, directory);
        Files.createDirectories(directory);
        // Absolute paths: the layout doesn't move with the template.
        write(layout, target, null);
        return target;
    }

    private static void write(Layout layout, Path file, Path baseDir) throws IOException {
        byte[] json = layout.toJson(baseDir);
        SafeFile.write(file, os -> os.write(json));
    }
}

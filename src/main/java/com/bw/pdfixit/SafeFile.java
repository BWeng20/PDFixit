package com.bw.pdfixit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes files so that a failure never leaves a broken or empty file behind.<br>
 * The content is written to a temporary file in the same directory, which replaces the target only if
 * writing was successful. An existing target is untouched on failure.
 */
public final class SafeFile {

    private SafeFile() {
    }

    /**
     * Writes the content.
     */
    @FunctionalInterface
    public interface ContentWriter {
        void write(OutputStream os) throws IOException;
    }

    /**
     * Writes a file.
     *
     * @param target The file to create or replace.
     * @param writer Writes the content. If it throws, the target is not changed.
     */
    public static void write(Path target, ContentWriter writer) throws IOException {
        final Path file = target.toAbsolutePath();
        Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName() + "-", ".tmp");
        try {
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                writer.write(os);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } finally {
            if (tmp != null)
                Files.deleteIfExists(tmp);
        }
    }
}

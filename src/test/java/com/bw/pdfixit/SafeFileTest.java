package com.bw.pdfixit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class SafeFileTest {

    @TempDir
    Path dir;

    @Test
    void write_createsFile() throws IOException {
        Path file = dir.resolve("new.txt");
        SafeFile.write(file, os -> os.write("hello".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals("hello", Files.readString(file));
        assertNoTempFiles();
    }

    @Test
    void write_replacesFile() throws IOException {
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "old");
        SafeFile.write(file, os -> os.write("new".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals("new", Files.readString(file));
        assertNoTempFiles();
    }

    @Test
    void failure_keepsExistingFile() throws IOException {
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "old");
        Assertions.assertThrows(IOException.class, () -> SafeFile.write(file, os -> {
            os.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("failed");
        }));
        Assertions.assertEquals("old", Files.readString(file));
        assertNoTempFiles();
    }

    @Test
    void failure_createsNoFile() {
        Path file = dir.resolve("new.txt");
        Assertions.assertThrows(IOException.class, () -> SafeFile.write(file, os -> {
            throw new IOException("failed");
        }));
        Assertions.assertFalse(Files.exists(file));
        assertNoTempFiles();
    }

    private void assertNoTempFiles() {
        try (var files = Files.list(dir)) {
            Assertions.assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")), "temp file left");
        } catch (IOException e) {
            Assertions.fail(e);
        }
    }
}

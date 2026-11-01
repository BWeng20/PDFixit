package com.bw.pdfixit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ManualTest {

    @TempDir
    Path dir;

    @Test
    void foundBesideTheJar() throws Exception {
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Path jar = Files.createFile(lib.resolve("PDFixit.jar"));
        Path other = Files.createDirectories(dir.resolve("other"));
        Assertions.assertNull(Manual.find(List.of(jar, other)));

        Path manual = Files.createFile(lib.resolve("manual.pdf"));
        Assertions.assertEquals(manual, Manual.find(List.of(other, jar)));
    }

    @Test
    void foundInAFolderOfTheClassPath() throws Exception {
        Path manual = Files.createFile(dir.resolve("manual.pdf"));
        Assertions.assertEquals(manual, Manual.find(List.of(dir)));
    }

    @Test
    void propertyWins() throws Exception {
        Path manual = Files.createFile(dir.resolve("manual.pdf"));
        System.setProperty(Manual.PROPERTY, manual.toString());
        try {
            Assertions.assertEquals(manual, Manual.find());
        } finally {
            System.clearProperty(Manual.PROPERTY);
        }
    }
}

package com.bw.pdfixit.template;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Where layout files are found and saved. Only temporary folders, never the real settings directory.
 */
class LayoutFilesTest {

    @TempDir
    Path tmp;

    private Path template(String folder, String name) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(folder));
        Path file = dir.resolve(name);
        Files.write(file, TestTemplates.letterhead(false));
        return file;
    }

    private Path layouts() {
        return tmp.resolve("config/layouts");
    }

    private static Layout withFont(String font) {
        Layout l = Layout.din5008(Layout.Form.B);
        l.fontName = font;
        return l;
    }

    @Test
    void test_noLayout() throws Exception {
        Path template = template("a", "Rechnung.pdf");
        LayoutFiles.Found found = LayoutFiles.find(template, layouts());
        Assertions.assertTrue(found.isDefault());
        Assertions.assertNull(found.foreignBeside());
        Assertions.assertArrayEquals(Layout.din5008(Layout.Form.B).toJson(null), found.layout().toJson(null));
    }

    @Test
    void test_saveBeside() throws Exception {
        Path template = template("a", "Rechnung.pdf");
        Path written = LayoutFiles.save(withFont("Tinos"), template, layouts());
        Assertions.assertEquals(template.resolveSibling("Rechnung.json"), written);
        Assertions.assertFalse(Files.exists(layouts()));
        LayoutFiles.Found found = LayoutFiles.find(template, layouts());
        Assertions.assertEquals(written, found.file());
        Assertions.assertEquals("Tinos", found.layout().fontName);
        // Saving again replaces our own file.
        LayoutFiles.save(withFont("Cousine"), template, layouts());
        Assertions.assertEquals("Cousine", LayoutFiles.find(template, layouts()).layout().fontName);
    }

    @Test
    void test_foreignJsonBeside() throws Exception {
        Path template = template("a", "Rechnung.pdf");
        for (String foreign : List.of("{\"invoice\": 17}", "not json at all")) {
            Path beside = template.resolveSibling("Rechnung.json");
            Files.writeString(beside, foreign);
            LayoutFiles.Found none = LayoutFiles.find(template, layouts());
            Assertions.assertTrue(none.isDefault());
            Assertions.assertEquals(beside, none.foreignBeside());

            Path written = LayoutFiles.save(withFont("Tinos"), template, layouts());
            Assertions.assertEquals(LayoutFiles.inDirectory(template, layouts()), written);
            Assertions.assertEquals(foreign, Files.readString(beside), "the foreign file is never overwritten");
            LayoutFiles.Found found = LayoutFiles.find(template, layouts());
            Assertions.assertEquals(written, found.file());
            Assertions.assertEquals(beside, found.foreignBeside());
            Assertions.assertEquals("Tinos", found.layout().fontName);
            Files.delete(written);
        }
    }

    @Test
    void test_sameNameInDifferentFolders() throws Exception {
        Path a = template("a", "Rechnung.pdf");
        Path b = template("b", "Rechnung.pdf");
        Path inA = LayoutFiles.inDirectory(a, layouts());
        Path inB = LayoutFiles.inDirectory(b, layouts());
        Assertions.assertNotEquals(inA, inB);
        Assertions.assertTrue(inA.getFileName().toString().matches("Rechnung-[0-9a-f]{8}\\.json"), inA.toString());
        // The same file gives the same name, however the path is written.
        Assertions.assertEquals(inA, LayoutFiles.inDirectory(tmp.resolve("b/../a/Rechnung.pdf"), layouts()));

        // Foreign files beside both: both go to the layout directory.
        Files.writeString(a.resolveSibling("Rechnung.json"), "{}");
        Files.writeString(b.resolveSibling("Rechnung.json"), "{}");
        LayoutFiles.save(withFont("Tinos"), a, layouts());
        LayoutFiles.save(withFont("Cousine"), b, layouts());
        Assertions.assertEquals("Tinos", LayoutFiles.find(a, layouts()).layout().fontName);
        Assertions.assertEquals("Cousine", LayoutFiles.find(b, layouts()).layout().fontName);
    }

    @Test
    void test_besideWins() throws Exception {
        Path template = template("a", "Rechnung.pdf");
        Files.createDirectories(layouts());
        Files.write(LayoutFiles.inDirectory(template, layouts()), withFont("Cousine").toJson(null));
        Assertions.assertEquals("Cousine", LayoutFiles.find(template, layouts()).layout().fontName);
        Files.write(template.resolveSibling("Rechnung.json"), withFont("Tinos").toJson(null));
        LayoutFiles.Found found = LayoutFiles.find(template, layouts());
        Assertions.assertEquals("Tinos", found.layout().fontName);
        Assertions.assertEquals(template.resolveSibling("Rechnung.json"), found.file());
    }

    @Test
    void test_continuationFilePaths() throws Exception {
        Path template = template("a", "Rechnung.pdf");
        Path next = template("a/pages", "Folgeseite.pdf");
        Layout l = Layout.din5008(Layout.Form.B);
        l.continuation = Layout.Continuation.PDF;
        l.continuationFile = next;
        Path beside = LayoutFiles.save(l, template, layouts());
        Assertions.assertTrue(Files.readString(beside, StandardCharsets.UTF_8).contains("\"pages/Folgeseite.pdf\""));
        Assertions.assertEquals(next, LayoutFiles.find(template, layouts()).layout().continuationFile);

        // Moved with its template: the relative path still fits.
        Path moved = tmp.resolve("moved");
        Files.move(tmp.resolve("a"), moved);
        Assertions.assertEquals(moved.resolve("pages/Folgeseite.pdf"),
                LayoutFiles.find(moved.resolve("Rechnung.pdf"), layouts()).layout().continuationFile);

        // In the layout directory the path is absolute.
        Path template2 = template("b", "Brief.pdf");
        Files.writeString(template2.resolveSibling("Brief.json"), "[]");
        l.continuationFile = moved.resolve("pages/Folgeseite.pdf");
        Path inDirectory = LayoutFiles.save(l, template2, layouts());
        Assertions.assertTrue(Files.readString(inDirectory).contains(
                moved.resolve("pages/Folgeseite.pdf").toString().replace('\\', '/')));
        Assertions.assertEquals(l.continuationFile, LayoutFiles.find(template2, layouts()).layout().continuationFile);
    }

    @Test
    void test_brokenOwnLayout() throws Exception {
        Path template = template("a", "Rechnung.pdf");
        Files.writeString(template.resolveSibling("Rechnung.json"),
                "{\"format\": \"pdfixit-layout\", \"fields\": [{\"id\": \"number\", \"rect\": [1, 2]}]}");
        Assertions.assertThrows(Layout.LayoutException.class, () -> LayoutFiles.find(template, layouts()));
    }

    @Test
    void test_readOnlyFolder() throws Exception {
        Path template = template("ro", "Rechnung.pdf");
        Path dir = template.getParent();
        Runnable restore = denyWrite(dir);
        try {
            boolean writable;
            try {
                Files.delete(Files.createFile(dir.resolve("probe")));
                writable = true;
            } catch (IOException e) {
                writable = false;
            }
            Assumptions.assumeFalse(writable, "The folder can't be made read-only on this system.");
            Path written = LayoutFiles.save(withFont("Tinos"), template, layouts());
            Assertions.assertEquals(LayoutFiles.inDirectory(template, layouts()), written);
            LayoutFiles.Found found = LayoutFiles.find(template, layouts());
            Assertions.assertEquals(written, found.file());
            Assertions.assertNull(found.foreignBeside());
        } finally {
            restore.run();
        }
    }

    /**
     * Makes a folder read-only: an ACL entry that denies creating files (Windows), or the POSIX permissions.
     *
     * @return Restores the folder.
     */
    private static Runnable denyWrite(Path dir) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        if (acl != null) {
            List<AclEntry> before = new ArrayList<>(acl.getAcl());
            List<AclEntry> denied = new ArrayList<>();
            denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.of(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY,
                            AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA)).build());
            denied.addAll(before);
            acl.setAcl(denied);
            return () -> {
                try {
                    acl.setAcl(before);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
        if (posix != null) {
            var before = posix.readAttributes().permissions();
            posix.setPermissions(PosixFilePermissions.fromString("r-xr-xr-x"));
            return () -> {
                try {
                    posix.setPermissions(before);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }
        return () -> {
        };
    }
}

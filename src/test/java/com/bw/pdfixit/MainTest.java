package com.bw.pdfixit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class MainTest {

    @Test
    void test_help() {
        Main main = new Main();
        int exitCode = new CommandLine(main).execute("-h");
        Assertions.assertEquals(0, exitCode);
        Assertions.assertTrue(main.isHelpRequested());
    }

    @TempDir
    Path tmp;

    /**
     * Creates a PDF with one page per text.
     */
    private Path textPdf(String name, String... pageTexts) throws IOException {
        Path file = tmp.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * The text of each page, trimmed.
     */
    private static List<String> pageTexts(PDDocument doc) throws IOException {
        List<String> texts = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        for (int i = 1; i <= doc.getNumberOfPages(); ++i) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            texts.add(stripper.getText(doc).trim());
        }
        return texts;
    }

    private static int run(String... args) {
        Main main = new Main();
        Assertions.assertEquals(0, new CommandLine(main).execute(args));
        Assertions.assertTrue(main.isCli());
        return main.executeCommands();
    }

    private static boolean verifies(SignerInformation signer, X509CertificateHolder cert) {
        try {
            return signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(new BouncyCastleProvider()).build(cert));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void test_append() throws Exception {
        Path testPdf = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/Test.pdf")).toURI());
        Path outPdf = tmp.resolve("out.pdf");

        Assertions.assertEquals(0, run("-upw", "test123", "--no-canPrint",
                "-out", outPdf.toString(), testPdf.toString(), testPdf.toString()));

        // Encrypted: not readable without password.
        Assertions.assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(outPdf.toFile()).close());

        String originalText;
        try (PDDocument original = Loader.loadPDF(testPdf.toFile())) {
            originalText = pageTexts(original).get(0);
        }
        try (PDDocument doc = Loader.loadPDF(outPdf.toFile(), "test123")) {
            Assertions.assertEquals(List.of(originalText, originalText), pageTexts(doc));

            Assertions.assertTrue(doc.isEncrypted());
            Assertions.assertEquals(256, doc.getEncryption().getLength());
            // Only the user password was given, the owner password is random: the permissions apply.
            AccessPermission ap = doc.getCurrentAccessPermission();
            Assertions.assertFalse(ap.isOwnerPermission());
            Assertions.assertFalse(ap.canPrint());
            Assertions.assertFalse(ap.canModify());
        }
    }

    @Test
    void test_ownerPassword_grantsFullAccess() throws Exception {
        Path in = textPdf("in.pdf", "A1");
        Path outPdf = tmp.resolve("out.pdf");

        Assertions.assertEquals(0, run("-opw", "owner", "-upw", "user", "--canPrint",
                "-out", outPdf.toString(), in.toString()));

        try (PDDocument doc = Loader.loadPDF(outPdf.toFile(), "user")) {
            AccessPermission ap = doc.getCurrentAccessPermission();
            Assertions.assertFalse(ap.isOwnerPermission());
            Assertions.assertTrue(ap.canPrint());
            Assertions.assertFalse(ap.canModify());
        }
        try (PDDocument doc = Loader.loadPDF(outPdf.toFile(), "owner")) {
            Assertions.assertTrue(doc.getCurrentAccessPermission().isOwnerPermission());
        }
    }

    @Test
    void test_withoutPassword_notEncrypted() throws Exception {
        Path in = textPdf("in.pdf", "A1");
        Path outPdf = tmp.resolve("out.pdf");
        Assertions.assertEquals(0, run("-out", outPdf.toString(), in.toString()));
        try (PDDocument doc = Loader.loadPDF(outPdf.toFile())) {
            Assertions.assertFalse(doc.isEncrypted());
            Assertions.assertEquals(List.of("A1"), pageTexts(doc));
        }
    }

    @Test
    void test_merge() throws Exception {
        Path a = textPdf("a.pdf", "A1", "A2", "A3");
        Path b = textPdf("b.pdf", "B1", "B2");
        Path outPdf = tmp.resolve("out.pdf");

        // Start before page 2, insert 1 page, skip 1 page.
        Assertions.assertEquals(0, run("-m", "2-1-1", "-out", outPdf.toString(), a.toString(), b.toString()));

        try (PDDocument doc = Loader.loadPDF(outPdf.toFile())) {
            Assertions.assertEquals(List.of("A1", "B1", "A2", "B2", "A3"), pageTexts(doc));
        }
    }

    @Test
    void test_merge_illegalArgument() throws Exception {
        Path a = textPdf("a.pdf", "A1");
        Path outPdf = tmp.resolve("out.pdf");
        Assertions.assertEquals(4, run("-m", "2-1", "-out", outPdf.toString(), a.toString()));
        Assertions.assertEquals(5, run("-m", "2-x-1", "-out", outPdf.toString(), a.toString()));
        Assertions.assertFalse(Files.exists(outPdf));
    }

    @Test
    void test_split() throws Exception {
        Path in = textPdf("in.pdf", "P1", "P2", "P3");
        Path outPdf = tmp.resolve("part.pdf");

        Assertions.assertEquals(0, run("-upw", "test123", "--split", "2", "-out", outPdf.toString(), in.toString()));

        Assertions.assertFalse(Files.exists(outPdf), "only the numbered files are written");
        Assertions.assertFalse(Files.exists(tmp.resolve("part003.pdf")));
        try (PDDocument part1 = Loader.loadPDF(tmp.resolve("part001.pdf").toFile(), "test123");
             PDDocument part2 = Loader.loadPDF(tmp.resolve("part002.pdf").toFile(), "test123")) {
            Assertions.assertEquals(List.of("P1", "P2"), pageTexts(part1));
            Assertions.assertEquals(List.of("P3"), pageTexts(part2));
            Assertions.assertTrue(part1.isEncrypted());
            Assertions.assertTrue(part2.isEncrypted());
        }
    }

    @Test
    void test_signing() throws Exception {
        Path in = textPdf("in.pdf", "Signed text");
        Path certificate = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/test_pw_1234.p12")).toURI());
        Path outPdf = tmp.resolve("signed.pdf");

        Assertions.assertEquals(0, run("-upw", "test123", "-cert", certificate.toString(), "-cpw", "1234",
                "-out", outPdf.toString(), in.toString()));

        byte[] bytes = Files.readAllBytes(outPdf);
        try (PDDocument doc = Loader.loadPDF(bytes, "test123")) {
            Assertions.assertEquals(List.of("Signed text"), pageTexts(doc));
            List<PDSignature> signatures = doc.getSignatureDictionaries();
            Assertions.assertEquals(1, signatures.size());
            PDSignature signature = signatures.get(0);

            // The signature covers the complete file (except the signature value itself).
            int[] range = signature.getByteRange();
            Assertions.assertEquals(0, range[0]);
            Assertions.assertEquals(bytes.length, range[2] + range[3]);

            // Verify the CMS signature against the signed bytes with the embedded certificate.
            byte[] signedContent = signature.getSignedContent(bytes);
            byte[] contents = signature.getContents(bytes);
            // /Contents is padded with zero bytes up to the reserved size: read the first object only.
            ContentInfo contentInfo;
            try (ASN1InputStream asn1 = new ASN1InputStream(contents)) {
                contentInfo = ContentInfo.getInstance(asn1.readObject());
            }
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signedContent), contentInfo);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            @SuppressWarnings("unchecked")
            X509CertificateHolder cert = (X509CertificateHolder) cms.getCertificates()
                    .getMatches(signer.getSID()).iterator().next();
            Assertions.assertTrue(verifies(signer, cert), "signature must be valid");

            // Modified content must not verify.
            signedContent[signedContent.length / 2] ^= 1;
            CMSSignedData tampered = new CMSSignedData(new CMSProcessableByteArray(signedContent), contentInfo);
            Assertions.assertFalse(verifies(tampered.getSignerInfos().getSigners().iterator().next(), cert),
                    "a modified document must not verify");
        }
    }

    @Test
    void test_encryptedInputWithoutPassword_fails() throws Exception {
        Path encrypted = Files.createTempFile("PDFixitEncrypted", ".pdf");
        Path outPdf = Files.createTempFile("PDFixitTest", ".pdf");
        Files.delete(outPdf);
        try {
            try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument()) {
                d.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                d.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                        "owner", "user", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
                d.save(encrypted.toFile());
            }
            Main main = new Main();
            Assertions.assertEquals(0, new CommandLine(main).execute("-out", outPdf.toString(), encrypted.toString()));
            Assertions.assertEquals(14, main.executeCommands());
            Assertions.assertFalse(Files.exists(outPdf));
        } finally {
            Files.deleteIfExists(encrypted);
            Files.deleteIfExists(outPdf);
        }
    }

    @Test
    void test_brokenInput_fails() throws Exception {
        Path broken = Files.createTempFile("PDFixitBroken", ".pdf");
        Path outPdf = Files.createTempFile("PDFixitTest", ".pdf");
        Files.delete(outPdf);
        try {
            Files.writeString(broken, "this is no pdf");
            Path testPdf = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/Test.pdf")).toURI());
            Main main = new Main();
            Assertions.assertEquals(0, new CommandLine(main).execute("-out", outPdf.toString(),
                    testPdf.toString(), broken.toString()));
            Assertions.assertEquals(14, main.executeCommands());
            Assertions.assertFalse(Files.exists(outPdf));
        } finally {
            Files.deleteIfExists(broken);
            Files.deleteIfExists(outPdf);
        }
    }

    @Test
    void test_onlyFiles_opensUi() throws Exception {
        Path testPdf = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/Test.pdf")).toURI());
        Main main = new Main();
        Assertions.assertEquals(0, new CommandLine(main).execute(testPdf.toString(), testPdf.toString()));
        Assertions.assertFalse(main.isCli());
    }

    @Test
    void test_optionWithoutOut_isCliAndFails() throws Exception {
        Path testPdf = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/Test.pdf")).toURI());
        Main main = new Main();
        Assertions.assertEquals(0, new CommandLine(main).execute("-upw", "secret", testPdf.toString()));
        Assertions.assertTrue(main.isCli());
        Assertions.assertEquals(3, main.executeCommands());
    }

    @Test
    void test_outWithoutFiles_fails() {
        Main main = new Main();
        Assertions.assertEquals(0, new CommandLine(main).execute("-out", "x.pdf"));
        Assertions.assertTrue(main.isCli());
        Assertions.assertEquals(2, main.executeCommands());
    }

    @Test
    void test_signingFails_existingFileIsKept() throws Exception {
        Path testPdf = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/Test.pdf")).toURI());
        Path certificate = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/test_pw_1234.p12")).toURI());
        Path outPdf = Files.createTempFile("PDFixitTest", ".pdf");
        try {
            Files.writeString(outPdf, "previous content");
            Main main = new Main();
            Assertions.assertEquals(0, new CommandLine(main).execute(
                    "-cert", certificate.toString(), "-cpw", "wrong",
                    "-out", outPdf.toString(), testPdf.toString()));
            Assertions.assertNotEquals(0, main.executeCommands());
            Assertions.assertEquals("previous content", Files.readString(outPdf));
        } finally {
            Files.deleteIfExists(outPdf);
        }
    }

    @Test
    void test_debugWithFiles_opensUi() throws Exception {
        Path testPdf = Paths.get(Objects.requireNonNull(MainTest.class.getResource("/Test.pdf")).toURI());
        boolean before = Log.isDebug();
        try {
            Main main = new Main();
            Assertions.assertEquals(0, new CommandLine(main).execute("--debug", testPdf.toString()));
            Assertions.assertFalse(main.isCli());
            Assertions.assertTrue(Log.isDebug());
        } finally {
            Log.setDebug(before);
        }
    }

    @Test
    void test_passwordOption_bothSpellings() {
        for (String name : new String[]{"--originalpassword", "--orginalpassword", "-pw"}) {
            Main main = new Main();
            Assertions.assertEquals(0, new CommandLine(main).execute(name, "x", "-out", "x.pdf"), name);
        }
    }

    @BeforeEach
    void setUp() {
    }
}
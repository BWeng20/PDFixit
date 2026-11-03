package com.bw.pdfixit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Repairs the files of <i>src/test/resources/verifyable-rules</i> in memory and checks which of the violated rules of
 * veraPDF are gone afterwards. See the README in that folder.<br>
 * The outcome per file and standard is compared with <i>expected-repair.csv</i>: if the repair gets better or worse,
 * this test fails and shows the difference. Run it with the environment variable <code>UPDATE_SNAPSHOT=true</code> to write the new snapshot and the
 * summary of the README into the <i>build</i> folder.
 */
class VerifiableRulesRepairTest {

    private static final String DIR = "/verifyable-rules/";
    /**
     * Standard (as in the file names) -> flavour of veraPDF.
     */
    private static final Map<String, PDFAFlavour> FLAVOURS = new LinkedHashMap<>();
    /**
     * Names of the supplements, see the README.
     */
    private static final List<String> SUPPLEMENTS = List.of("structure", "embedded-file", "encryption", "linearized",
            "no-metadata", "xmp-broken", "xmp-no-identification", "xmp-wrong-identification", "icc-cmyk", "tagging");

    static {
        FLAVOURS.put("1a", PDFAFlavour.PDFA_1_A);
        FLAVOURS.put("1b", PDFAFlavour.PDFA_1_B);
        FLAVOURS.put("2a", PDFAFlavour.PDFA_2_A);
        FLAVOURS.put("2b", PDFAFlavour.PDFA_2_B);
        FLAVOURS.put("2u", PDFAFlavour.PDFA_2_U);
        FLAVOURS.put("3a", PDFAFlavour.PDFA_3_A);
        FLAVOURS.put("3b", PDFAFlavour.PDFA_3_B);
        FLAVOURS.put("3u", PDFAFlavour.PDFA_3_U);
        FLAVOURS.put("4", PDFAFlavour.PDFA_4);
        FLAVOURS.put("4e", PDFAFlavour.PDFA_4_E);
        FLAVOURS.put("4f", PDFAFlavour.PDFA_4_F);
    }

    /**
     * The outcome of the repair of one file, checked against one standard.
     *
     * @param status     KEPT (the repair kept the declared part of PDF/A), SWITCHED (it declared another part),
     *                   NO_STANDARD (the file declares no valid part of PDF/A, so the repair can't know the standard),
     *                   UNCHANGED (nothing to repair), FAILED (the repair threw an exception) or UNREADABLE (veraPDF can't read the result)
     * @param before     The violated rules before the repair.
     * @param fixed      The rules which were violated before and are not any more.
     * @param introduced The rules which were not violated before and are now.
     * @param offered    The repair did not switch the standard, but could: the file has attachments before PDF/A-3.
     */
    private record Outcome(String file, String standard, String status, TreeSet<String> before, TreeSet<String> fixed,
                           TreeSet<String> introduced, boolean offered) {
        String row() {
            return String.join(";", file, standard, status, String.join(" ", fixed), String.join(" ", introduced),
                    offered ? "upgrade-offered" : "");
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream is = VerifiableRulesRepairTest.class.getResourceAsStream(DIR + name)) {
            return is == null ? null : is.readAllBytes();
        }
    }

    /**
     * The violated rules ("clause-test") or null if veraPDF can't read the file.
     */
    private static TreeSet<String> rules(byte[] pdf, PDFAFlavour flavour) {
        VeraGreenfieldFoundryProvider.initialise();
        try (PDFAParser parser = Foundries.defaultInstance().createParser(new ByteArrayInputStream(pdf), flavour)) {
            ValidationResult result = Foundries.defaultInstance().createValidator(flavour, false).validate(parser);
            TreeSet<String> rules = new TreeSet<>();
            for (TestAssertion a : result.getTestAssertions())
                if (a.getStatus() == TestAssertion.Status.FAILED)
                    rules.add(a.getRuleId().getClause() + "-" + a.getRuleId().getTestNumber());
            return rules;
        } catch (Exception e) {
            return null;
        }
    }

    private static int pdfaPart(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PdfStandard.pdfaPart(doc);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * The test files with the standards each is checked against: the main file of a standard against that standard, a
     * supplement against all standards of its part.
     */
    private static Map<String, List<String>> cases() throws IOException {
        Map<String, List<String>> cases = new LinkedHashMap<>();
        for (String standard : FLAVOURS.keySet())
            cases.put("PDFA-" + standard + "_all-errors.pdf", List.of(standard));
        for (char part = '1'; part <= '4'; ++part)
            for (String name : SUPPLEMENTS) {
                String file = "supplements/PDFA-" + part + "_" + name + ".pdf";
                if (resource(file) == null)
                    continue;
                List<String> standards = new ArrayList<>();
                for (String standard : FLAVOURS.keySet())
                    if (standard.charAt(0) == part)
                        standards.add(standard);
                cases.put(file, standards);
            }
        return cases;
    }

    private static List<Outcome> cached;

    /**
     * Repairs all files once for all tests (about a minute).
     */
    private static synchronized List<Outcome> repairAll() throws IOException {
        if (cached == null)
            cached = compute();
        return cached;
    }

    private static List<Outcome> compute() throws IOException {
        List<Outcome> outcomes = new ArrayList<>();
        for (var entry : cases().entrySet()) {
            byte[] original = resource(entry.getKey());
            Assertions.assertNotNull(original, entry.getKey());
            byte[] repaired = original;
            String status;
            boolean offered = false;
            int partBefore = pdfaPart(original);
            try {
                // without the switch of the standard: that needs the consent of the user
                PdfStandard.Repair repair = PdfStandard.repairPdfa(original);
                offered = repair.upgradeOffered();
                if (repair.pdf() == null) {
                    status = "UNCHANGED";
                } else {
                    repaired = repair.pdf();
                    if (partBefore < 1 || partBefore > 4)
                        status = "NO_STANDARD";
                    else
                        status = pdfaPart(repaired) == partBefore ? "KEPT" : "SWITCHED";
                }
            } catch (Exception e) {
                status = "FAILED";
            }
            for (String standard : entry.getValue()) {
                PDFAFlavour flavour = FLAVOURS.get(standard);
                TreeSet<String> before = rules(original, flavour);
                Assertions.assertNotNull(before, "veraPDF can't read " + entry.getKey());
                TreeSet<String> after = rules(repaired, flavour);
                String s = status;
                TreeSet<String> fixed = new TreeSet<>(), introduced = new TreeSet<>();
                if (after == null) {
                    s = "UNREADABLE";
                } else {
                    fixed.addAll(before);
                    fixed.removeAll(after);
                    introduced.addAll(after);
                    introduced.removeAll(before);
                }
                outcomes.add(new Outcome(entry.getKey(), standard, s, before, fixed, introduced, offered));
            }
        }
        return outcomes;
    }

    /**
     * Per standard: the rules the test files violate, and how many of them the repair fixes. A rule counts as fixed if no
     * file violates it after the repair.
     */
    private static String summary(List<Outcome> outcomes) {
        StringBuilder md = new StringBuilder("| Standard | Rules violated by the files | Fixed by the repair | Share |\n|---|---|---|---|\n");
        for (String standard : FLAVOURS.keySet()) {
            TreeSet<String> violated = new TreeSet<>(), remaining = new TreeSet<>();
            for (Outcome o : outcomes)
                if (o.standard().equals(standard)) {
                    violated.addAll(o.before());
                    TreeSet<String> after = new TreeSet<>(o.before());
                    after.removeAll(o.fixed());
                    remaining.addAll(after);
                }
            int fixed = violated.size() - remaining.size();
            md.append(String.format("| PDF/A-%s | %d | %d | %d %% |%n", standard, violated.size(), fixed,
                    Math.round(100.0 * fixed / violated.size())));
        }
        return md.toString();
    }

    private static Map<String, String> snapshot() throws IOException {
        byte[] data = resource("expected-repair.csv");
        Assertions.assertNotNull(data, "expected-repair.csv is missing, run the test with UPDATE_SNAPSHOT=true");
        Map<String, String> rows = new TreeMap<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.startsWith("#"))
                continue;
            String[] p = line.split(";", -1);
            rows.put(p[0] + ";" + p[1], line);
        }
        return rows;
    }

    @Test
    void test_repairOfTheVerifiableRules() throws Exception {
        List<Outcome> outcomes = repairAll();
        String summary = summary(outcomes);
        System.out.println(summary);

        if (System.getenv("UPDATE_SNAPSHOT") != null) {
            Path dir = Path.of("build");
            Files.createDirectories(dir);
            StringBuilder csv = new StringBuilder("# file;standard;status;fixed rules;introduced rules;upgrade offered (see README.adoc)\n");
            outcomes.forEach(o -> csv.append(o.row()).append('\n'));
            Files.writeString(dir.resolve("verifiable-rules-expected-repair.csv"), csv.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("verifiable-rules-summary.md"), summary, StandardCharsets.UTF_8);
            return;
        }

        Map<String, String> expected = snapshot();
        List<String> differences = new ArrayList<>();
        for (Outcome o : outcomes) {
            String key = o.file() + ";" + o.standard();
            String exp = expected.remove(key);
            if (!o.row().equals(exp))
                differences.add("expected: " + exp + "\n  actual:   " + o.row());
        }
        expected.keySet().forEach(k -> differences.add("no longer checked: " + k));
        Assertions.assertTrue(differences.isEmpty(), "The repair changed, update expected-repair.csv (UPDATE_SNAPSHOT=true):\n"
                + String.join("\n", differences));
    }

    /**
     * A repair that keeps the standard of a file must not make the file violate rules it did not violate before.
     */
    @Test
    void test_repairIntroducesNoViolationsWhenTheStandardIsKept() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Outcome o : repairAll())
            if (!o.introduced().isEmpty() && (o.status().equals("KEPT") || o.status().equals("UNCHANGED")))
                problems.add(o.file() + " as " + o.standard() + ": " + o.introduced());
        Assertions.assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * A PDF/A-1 or PDF/A-2 file with attachments: the repair keeps the standard and offers the switch to PDF/A-3. It switches
     * only if it is told to (the app asks the user first).
     */
    @Test
    void test_theStandardIsSwitchedOnlyOnRequest() throws Exception {
        for (String part : List.of("1", "2")) {
            byte[] original = resource("supplements/PDFA-" + part + "_embedded-file.pdf");
            Assertions.assertNotNull(original);
            int declared = Integer.parseInt(part);
            Assertions.assertEquals(declared, pdfaPart(original));

            PdfStandard.Repair kept = PdfStandard.repairPdfa(original);
            Assertions.assertTrue(kept.upgradeOffered(), "part " + part);
            Assertions.assertEquals(declared, pdfaPart(kept.pdf() == null ? original : kept.pdf()), "the standard is kept");

            PdfStandard.Repair switched = PdfStandard.repairPdfa(original, true);
            Assertions.assertFalse(switched.upgradeOffered(), "part " + part);
            Assertions.assertNotNull(switched.pdf());
            Assertions.assertEquals(3, pdfaPart(switched.pdf()), "switched to PDF/A-3");
            Assertions.assertTrue(rules(switched.pdf(), PDFAFlavour.PDFA_3_B).isEmpty(),
                    "part " + part + ": " + rules(switched.pdf(), PDFAFlavour.PDFA_3_B));
        }
        // nothing to switch for PDF/A-3 and for files without attachments
        Assertions.assertFalse(PdfStandard.repairPdfa(resource("PDFA-3b_all-errors.pdf")).upgradeOffered());
        Assertions.assertFalse(PdfStandard.repairPdfa(resource("PDFA-1b_all-errors.pdf")).upgradeOffered());
    }

    @Test
    void test_everyMainFileGetsBetter() throws Exception {
        for (Outcome o : repairAll())
            if (o.file().endsWith("_all-errors.pdf"))
                Assertions.assertFalse(o.fixed().isEmpty(), o.file() + ": the repair fixed nothing (" + o.status() + ")");
    }
}

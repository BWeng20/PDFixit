package com.bw.pdfixit.template;

import com.bw.pdfixit.template.Problem.Severity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class TemplateCheckTest {

    @TempDir
    Path tmp;

    private static List<Problem> of(List<Problem> problems, Severity severity) {
        return problems.stream().filter(p -> p.severity() == severity).toList();
    }

    private static void assertOnly(List<Problem> problems, Severity severity, String element, String text) {
        List<Problem> found = of(problems, severity);
        Assertions.assertEquals(1, found.size(), problems.toString());
        Assertions.assertEquals(element, found.get(0).element(), problems.toString());
        Assertions.assertTrue(found.get(0).message().contains(text), found.get(0).message());
    }

    private static Element fixed(Layout l, String id) {
        return l.fields.stream().filter(e -> id.equals(e.id) && !e.next).findFirst().orElseThrow();
    }

    @Test
    void test_defaultLayoutFits() throws Exception {
        List<Problem> problems = TemplateCheck.check(TestTemplates.letterhead(false), Layout.din5008(Layout.Form.B));
        Assertions.assertTrue(problems.isEmpty(), problems.toString());
        problems = TemplateCheck.check(TestTemplates.letterhead(false), Layout.din5008(Layout.Form.A));
        Assertions.assertFalse(TemplateCheck.hasErrors(problems), problems.toString());
    }

    @Test
    void test_regionWithTemplateText() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        fixed(l, "number").rect = new Rect(140, 33, 50, 5);
        List<Problem> problems = TemplateCheck.check(TestTemplates.letterhead(false), l);
        assertOnly(problems, Severity.WARNING, "number", "contains text");
        Assertions.assertFalse(TemplateCheck.hasErrors(problems));

        // The header of page 2 is at 12 mm from the top: a region of the following pages there.
        l = Layout.din5008(Layout.Form.B);
        l.regions.removeIf(Region::next);
        l.regions.add(new Region(true, new Rect(20, 10, 100, 250)));
        problems = TemplateCheck.check(TestTemplates.letterhead(false), l);
        assertOnly(problems, Severity.WARNING, "Body", "contains text");
        // With page 1 for the following pages that place is free.
        l.continuation = Layout.Continuation.PAGE1;
        Assertions.assertTrue(TemplateCheck.check(TestTemplates.letterhead(false), l).isEmpty());
        // A label that was moved onto the sender block.
        l = Layout.din5008(Layout.Form.B);
        fixed(l, "number").label = new Label();
        fixed(l, "number").label.rect = TestTemplates.SENDER;
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.WARNING, "number", "contains text");
        // A deleted label isn't printed, its rectangle doesn't matter.
        fixed(l, "number").label.shown = false;
        Assertions.assertTrue(TemplateCheck.check(TestTemplates.letterhead(false), l).isEmpty());
    }

    @Test
    void test_outsidePage() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        fixed(l, "number").rect = new Rect(190, 50, 30, 5);
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.ERROR, "number", "leaves the page");
        l = Layout.din5008(Layout.Form.B);
        l.regions.set(0, new Region(false, new Rect(25, 100, 165, 200)));
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.ERROR, "Body", "leaves the page");
    }

    @Test
    void test_otherTemplate() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        l.pageCount = 2;
        l.pageWidth = 210.0;
        l.pageHeight = 297.0;
        Assertions.assertTrue(TemplateCheck.check(TestTemplates.letterhead(false), l).isEmpty());
        l.pageCount = 1;
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.ERROR, null, "another template");
        l.pageCount = 2;
        l.pageWidth = 216.0;
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.ERROR, null, "another template");
    }

    @Test
    void test_encrypted() throws Exception {
        List<Problem> problems = TemplateCheck.check(TestTemplates.encrypted("secret"), Layout.din5008(Layout.Form.B));
        assertOnly(problems, Severity.ERROR, null, "password");
        // Only an owner password: it can be opened, the security is removed when it is prepared.
        problems = TemplateCheck.check(TestTemplates.encrypted(""), Layout.din5008(Layout.Form.B));
        Assertions.assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void test_unreadable() {
        assertOnly(TemplateCheck.check("no pdf".getBytes(), Layout.din5008(Layout.Form.B)), Severity.ERROR, null,
                "can't be read");
    }

    @Test
    void test_fontThatCantBeEmbedded() throws Exception {
        List<Problem> problems = TemplateCheck.check(TestTemplates.withDingbats(), Layout.din5008(Layout.Form.B));
        assertOnly(problems, Severity.ERROR, null, "ZapfDingbats");
    }

    @Test
    void test_tagged() throws Exception {
        List<Problem> problems = TemplateCheck.check(TestTemplates.letterhead(true), Layout.din5008(Layout.Form.B));
        assertOnly(problems, Severity.INFO, null, "tags");
        Assertions.assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void test_elements() throws Exception {
        byte[] template = TestTemplates.letterhead(false);
        Layout l = Layout.din5008(Layout.Form.B);
        l.fields.add(Element.field("bankName", false, new Rect(25, 270, 50, 5)));
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "bankName", "Unknown");

        l = Layout.din5008(Layout.Form.B);
        l.parts.removeIf(e -> "lines".equals(e.id));
        l.fields.add(Element.field("lines", false, new Rect(25, 100, 165, 50)));
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "lines", "only be placed in the body");

        l = Layout.din5008(Layout.Form.B);
        l.fields.removeIf(e -> "page".equals(e.id));
        l.parts.add(Element.field("page"));
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "page", "can't be placed in the body");

        l = Layout.din5008(Layout.Form.B);
        l.parts.add(Element.field("number"));
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "number", "on the page and in the body");

        l = Layout.din5008(Layout.Form.B);
        l.parts.add(Element.field("note"));
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "note", "twice");

        l = Layout.din5008(Layout.Form.B);
        l.fields.add(Element.field("issueDate", false, new Rect(25, 270, 50, 5)));
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "issueDate", "twice");

        l = Layout.din5008(Layout.Form.B);
        Element image = new Element();
        image.type = Element.Type.IMAGE;
        image.image = "signature";
        l.parts.add(image);
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, "image signature", "missing");

        l = Layout.din5008(Layout.Form.B);
        l.regions.removeIf(r -> !r.next());
        assertOnly(TemplateCheck.check(template, l), Severity.ERROR, null, "no body region");
        // No parts, no region needed.
        l.parts.clear();
        Assertions.assertTrue(TemplateCheck.check(template, l).isEmpty());
    }

    @Test
    void test_continuation() throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        // Not chosen and one page: the user is asked later, no problem now.
        Assertions.assertTrue(TemplateCheck.check(TestTemplates.onePageLetterhead(), l).isEmpty());
        l.continuation = Layout.Continuation.TEMPLATE;
        assertOnly(TemplateCheck.check(TestTemplates.onePageLetterhead(), l), Severity.ERROR, null, "only one page");
        l.continuation = Layout.Continuation.BLANK;
        Assertions.assertTrue(TemplateCheck.check(TestTemplates.onePageLetterhead(), l).isEmpty());

        l.continuation = Layout.Continuation.PDF;
        l.continuationFile = tmp.resolve("missing.pdf");
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.ERROR, null, "missing.pdf");
        Files.write(l.continuationFile, TestTemplates.continuationPdf());
        Assertions.assertTrue(TemplateCheck.check(TestTemplates.letterhead(false), l).isEmpty());
        // Its header line at 15 mm from the top is checked against the regions of the following pages.
        fixedNext(l, "number").rect = new Rect(20, 12, 60, 5);
        assertOnly(TemplateCheck.check(TestTemplates.letterhead(false), l), Severity.WARNING, "number", "contains text");
    }

    private static Element fixedNext(Layout l, String id) {
        return l.fields.stream().filter(e -> id.equals(e.id) && e.next).findFirst().orElseThrow();
    }

    @Test
    void test_templateFile() throws Exception {
        Path template = tmp.resolve("Rechnung.pdf");
        Files.write(template, TestTemplates.letterhead(false));
        Path layouts = tmp.resolve("layouts");
        List<Problem> problems = TemplateCheck.check(template, LayoutFiles.find(template, layouts));
        assertOnly(problems, Severity.INFO, null, "DIN 5008");

        Files.writeString(tmp.resolve("Rechnung.json"), "{\"other\": true}");
        LayoutFiles.save(Layout.din5008(Layout.Form.B), template, layouts);
        problems = TemplateCheck.check(template, LayoutFiles.find(template, layouts));
        assertOnly(problems, Severity.INFO, null, "Rechnung.json");

        problems = TemplateCheck.check(tmp.resolve("missing.pdf"), LayoutFiles.find(template, layouts));
        Assertions.assertTrue(TemplateCheck.hasErrors(problems), problems.toString());
    }
}

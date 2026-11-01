package com.bw.jPdfTool.model;

import com.bw.jPdfTool.PdfRisk;
import com.bw.jPdfTool.Redactor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The tags warning is only reported if the operation removes the tags.
 */
public class TagsLostTest {

    private static DocumentProxy proxy(boolean tagged, List<String> reports) {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage());
        if (tagged)
            doc.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        proxy.loaderFinished(doc, new MergeOptions());
        proxy.riskListener = (name, risks) -> risks.forEach(r -> reports.add(name + ": " + r));
        return proxy;
    }

    @Test
    void test_toImage() {
        List<String> reports = new ArrayList<>();
        DocumentProxy proxy = proxy(true, reports);
        try {
            proxy.renderAllPagesToImage();
            Assertions.assertEquals(List.of("To Image: " + PdfRisk.tagsLostWarning()), reports);
            reports.clear();
            proxy.renderPageToImage(1);
            // The page is an image now: the document has a structure tree, but its page was converted again.
            Assertions.assertEquals(List.of("To Image: " + PdfRisk.tagsLostWarning()), reports);
        } finally {
            proxy.close();
        }
    }

    @Test
    void test_toImageUntaggedSaysNothing() {
        List<String> reports = new ArrayList<>();
        DocumentProxy proxy = proxy(false, reports);
        try {
            proxy.renderAllPagesToImage();
            proxy.renderPageToImage(1);
            Assertions.assertEquals(List.of(), reports);
        } finally {
            proxy.close();
        }
    }

    @Test
    void test_redaction() throws Exception {
        List<String> reports = new ArrayList<>();
        DocumentProxy proxy = proxy(true, reports);
        try {
            proxy.redact(List.of());
            Assertions.assertEquals(List.of(), reports, "nothing redacted");
            proxy.redact(List.of(new Redactor.Region(1, new Rectangle2D.Double(10, 10, 50, 20))));
            Assertions.assertEquals(List.of("Redaction: " + PdfRisk.tagsLostWarning()), reports);
        } finally {
            proxy.close();
        }
    }
}

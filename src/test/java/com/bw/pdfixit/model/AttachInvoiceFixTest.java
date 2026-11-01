package com.bw.pdfixit.model;

import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.EInvoiceTest;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AttachInvoiceFixTest {

    @Test
    void test_attachInvoice_fixesFirstAndReports() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        doc.addPage(page);
        var img = LosslessFactory.createFromImage(doc, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB));
        img.getCOSObject().setBoolean(COSName.INTERPOLATE, true);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, 10, 10, 20, 20);
        }

        var info = new org.apache.pdfbox.pdmodel.PDDocumentInformation();
        info.setProducer("My Producer");
        info.setTitle("My Title");
        doc.setDocumentInformation(info);

        DocumentProxy proxy = new DocumentProxy(new RenderQueue());
        try {
            proxy.loaderFinished(doc, new MergeOptions());
            List<Map<String, Integer>> reported = new ArrayList<>();
            proxy.pdfaFixListener = reported::add;

            proxy.attachInvoice(EInvoiceTest.sampleXml());

            Assertions.assertTrue(EInvoice.hasInvoice(proxy.getDocument()));
            Assertions.assertEquals("My Producer", proxy.getDocument().getDocumentInformation().getProducer());
            Assertions.assertEquals("My Title", proxy.getDocument().getDocumentInformation().getTitle());
            Assertions.assertEquals(1, reported.size());
            Assertions.assertEquals(1, reported.get(0).get("Disabled image interpolation"));
        } finally {
            proxy.close();
        }
    }
}

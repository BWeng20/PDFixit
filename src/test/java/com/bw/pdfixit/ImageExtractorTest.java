package com.bw.pdfixit;

import com.bw.pdfixit.model.Page;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageExtractorTest {

    private static PDImageXObject image(PDDocument doc) throws Exception {
        return LosslessFactory.createFromImage(doc, new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB));
    }

    @Test
    void marksFillAndSignImage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image(doc), 10, 10, 100, 50);
                cs.beginMarkedContent(ImageExtractor.ADOBE_FILL_SIGN);
                cs.drawImage(image(doc), 10, 100, 100, 50);
                cs.endMarkedContent();
                cs.drawImage(image(doc), 10, 200, 100, 50);
            }

            List<Page.ImageResource> images = ImageExtractor.getImages(doc, 0);
            assertEquals(3, images.size());
            assertFalse(images.get(0).signature);
            assertTrue(images.get(1).signature);
            assertFalse(images.get(2).signature);
        }
    }

    @Test
    void marksSignatureFieldImage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image(doc), 10, 10, 100, 50);
            }

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDSignatureField field = new PDSignatureField(form);
            form.getFields().add(field);

            PDAnnotationWidget widget = field.getWidgets().get(0);
            PDRectangle rect = new PDRectangle(200, 200, 100, 50);
            widget.setRectangle(rect);
            widget.setPage(page);

            PDAppearanceStream ap = new PDAppearanceStream(doc);
            ap.setBBox(new PDRectangle(100, 50));
            ap.setResources(new PDResources());
            try (PDPageContentStream cs = new PDPageContentStream(doc, ap)) {
                cs.drawImage(image(doc), 0, 0, 100, 50);
            }
            PDAppearanceDictionary apd = new PDAppearanceDictionary();
            apd.setNormalAppearance(ap);
            widget.setAppearance(apd);
            page.getAnnotations().add(widget);

            List<Page.ImageResource> images = ImageExtractor.getImages(doc, 0);
            assertEquals(2, images.size());
            assertFalse(images.get(0).signature);
            assertTrue(images.get(1).signature);
        }
    }

    @Test
    void fillAndSignInsideFormXObject() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDAppearanceStream xo = new PDAppearanceStream(doc);
            xo.setBBox(new PDRectangle(100, 50));
            xo.setResources(new PDResources());
            try (PDPageContentStream cs = new PDPageContentStream(doc, xo)) {
                cs.drawImage(image(doc), 0, 0, 100, 50);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginMarkedContent(ImageExtractor.ADOBE_FILL_SIGN);
                cs.drawForm(xo);
                cs.endMarkedContent();
            }

            List<Page.ImageResource> images = ImageExtractor.getImages(doc, 0);
            assertEquals(1, images.size());
            assertTrue(images.get(0).signature);
        }
    }
}

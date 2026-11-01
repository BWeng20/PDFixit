package com.bw.pdfixit;

import com.bw.pdfixit.model.Page;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A PageDrawer to save images.<br>
 * As we need the positions and scales of the images in the pages, a simple scan
 * will not help, we need to "draw".<br>
 * We could also overload other drawing function to spare CPU... Perhaps later.
 */
public class ImageExtractor extends PageDrawer {

    public final List<Page.ImageResource> images = new ArrayList<>();
    private int imageCounter = 1;

    private static class MyPDFRenderer extends PDFRenderer {
        MyPDFRenderer(PDDocument document) {
            super(document);
        }

        ImageExtractor extractor;

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            extractor = new ImageExtractor(parameters);
            return extractor;
        }
    }

    /**
     * Extract images from the page - no stencils and no inline-images.
     *
     * @param document  The document.
     * @param pageIndex The 0-based index of the page.
     * @return The list of images, possibly empty but never null.
     */
    public static List<Page.ImageResource> getImages(PDDocument document, int pageIndex) {

        try {
            MyPDFRenderer renderer = new MyPDFRenderer(document);
            BufferedImage image = renderer.renderImage(pageIndex);

            for (var i : renderer.extractor.images)
                Log.debug("> %s", i);
            return renderer.extractor.images;
        } catch (IOException e) {
            Log.exception(e);
        }
        return Collections.emptyList();
    }


    private ImageExtractor(PageDrawerParameters parameters) throws IOException {
        super(parameters);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        var graphics = getGraphics();

        if (pdImage.isStencil() || pdImage instanceof PDInlineImage)
            return;

        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        AffineTransform at = ctm.createAffineTransform();

        graphics.setComposite(getGraphicsState().getNonStrokingJavaComposite());
        setClip();
        drawBufferedImage(pdImage.getImage(), at);
    }

    private void drawBufferedImage(BufferedImage image, AffineTransform at) throws IOException {
        var graphics = getGraphics();

        // PDFBox maintains part of the final state in the  graphics transformation.
        // To calculate the effective coordinates we need to do the same as PageDrawer.

        AffineTransform originalTransform = graphics.getTransform();
        AffineTransform imageTransform = new AffineTransform(at);
        int width = image.getWidth();
        int height = image.getHeight();

        imageTransform.scale(1.0 / width, -1.0 / height);
        imageTransform.translate(0, -height);

        PDSoftMask softMask = getGraphicsState().getSoftMask();
        if (softMask != null) {
            // TODO: Do we need this?
        } else {
            Matrix imageTransformMatrix = new Matrix(imageTransform);
            Matrix graphicsTransformMatrix = new Matrix(originalTransform);
            float scaleX = Math.abs(imageTransformMatrix.getScalingFactorX() * graphicsTransformMatrix.getScalingFactorX());
            float scaleY = Math.abs(imageTransformMatrix.getScalingFactorY() * graphicsTransformMatrix.getScalingFactorY());

            Page.ImageResource i = new Page.ImageResource();
            i.name = "image-" + (imageCounter++);
            i.image = image;
            i.scaleX = scaleX;
            i.scaleY = scaleY;
            images.add(i);

            int w = Math.round(image.getWidth() * scaleX);
            int h = Math.round(image.getHeight() * scaleY);
            imageTransform.scale(1f / w * image.getWidth(), 1f / h * image.getHeight());
            imageTransform.preConcatenate(originalTransform);

            // Do not actually "draw", but get the effective coordinates.
            Point2D p = new Point2D.Double(0, 0);
            imageTransform.transform(p, p);
            i.x = p.getX();
            i.y = p.getY();
        }
    }

}
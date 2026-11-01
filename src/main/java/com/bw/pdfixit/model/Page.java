package com.bw.pdfixit.model;

import com.bw.pdfixit.ImageExtractor;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class Page {

    public final DocumentProxy document;
    public int pageNb;
    public int pageCount;

    public int dpi = 0;
    public double scale = 0;
    public BufferedImage image;
    public String error;

    public Page(DocumentProxy document, int pageNb, int pageCount) {
        this.pageNb = pageNb;
        this.document = document;
        this.pageCount = pageCount;
    }

    public void rotatePage(int degree) {
        document.rotatePage(pageNb, degree);
    }

    public int getRotation() {
        return document.getPageRotation(pageNb);
    }

    public void movePage(int i) {
        document.movePage(pageNb, i);
    }

    public static class ImageResource {
        public String name;
        public BufferedImage image;
        public String error;

        public double x;
        public double y;

        public double scaleX;
        public double scaleY;

        public String toString() {
            if (image == null)
                return name + " " + x + "," + y + " scale " + scaleX + " x " + scaleY;
            else
                return name + " " + x + "," + y + " scale " + scaleX + " x " + scaleY + " size " + image.getWidth() + " x " + image.getHeight();
        }
    }

    public List<ImageResource> getImages() {
        return new ArrayList<>(ImageExtractor.getImages(document.getDocument(), pageNb - 1));
    }
}

package com.bw.pdfixit.template;

/**
 * A picture stored in the layout (e.g. a signature), used by image elements.
 *
 * @param format "png" or "jpeg".
 * @param data   The content of the image file.
 */
public record LayoutImage(String format, byte[] data) {

    public static final String PNG = "png";
    public static final String JPEG = "jpeg";
}

package com.bw.pdfixit.template;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An element of the layout: a field of the invoice, or a free text or image (e.g. closing text and signature).<br>
 * An element is either <b>fixed</b> (it has a {@link #rect} on the first or on the following pages) or <b>flowing</b>
 * (it is a part of the body and takes its place behind the part before it).
 */
public class Element {

    /**
     * The kind of element.
     */
    public enum Type {
        /**
         * A field of the invoice, see {@link LayoutField}.
         */
        FIELD,
        /**
         * A static text.
         */
        TEXT,
        /**
         * A picture of {@link Layout#images}.
         */
        IMAGE
    }

    /**
     * Horizontal alignment.
     */
    public enum Align {
        LEFT, CENTER, RIGHT
    }

    public Type type = Type.FIELD;

    /**
     * The id of a field ({@link LayoutField#id}, unknown ids are kept and reported by the check). Null for free
     * elements.
     */
    public String id;

    /**
     * False: on the first page. True: on every following page. Only for fixed elements.
     */
    public boolean next;

    /**
     * The region of a fixed element in mm, null for a flowing one.
     */
    public Rect rect;

    /**
     * Font size in points, null for the size of the layout font.
     */
    public Float size;

    /**
     * Null for the default (left).
     */
    public Align align;

    public boolean bold;

    /**
     * For the address blocks: everything on one line (return address).
     */
    public boolean singleLine;

    /**
     * The label of a field, null for the defaults.
     */
    public Label label;

    /**
     * The text of a text element per printed language ("de", "en"); the key "" holds a text for every language.
     */
    public final Map<String, String> text = new LinkedHashMap<>();

    /**
     * The name of the picture of an image element, see {@link Layout#images}.
     */
    public String image;

    /**
     * The width of a flowing image in mm, the height follows from the aspect ratio. A fixed image fits into its
     * rectangle.
     */
    public Double width;

    public Element() {
    }

    /**
     * A field.
     */
    public static Element field(String id) {
        Element e = new Element();
        e.id = id;
        return e;
    }

    /**
     * A fixed field.
     */
    public static Element field(String id, boolean next, Rect rect) {
        Element e = field(id);
        e.next = next;
        e.rect = rect;
        return e;
    }

    public boolean isFixed() {
        return rect != null;
    }

    /**
     * The field, null for a free element or an unknown id.
     */
    public LayoutField field() {
        return type == Type.FIELD ? LayoutField.of(id) : null;
    }

    /**
     * True if the label is printed: as the layout says, otherwise by the default of the field. Free elements have no
     * label.
     */
    public boolean isLabelShown() {
        if (type != Type.FIELD)
            return false;
        if (label != null)
            return label.shown;
        LayoutField f = field();
        return f == null || f.labelByDefault;
    }

    /**
     * The text of a text element in a language: the one of that language, else the one for every language, else the
     * first one.
     *
     * @return The text, empty if there is none.
     */
    public String text(String language) {
        String t = text.get(language);
        if (t == null)
            t = text.get("");
        if (t == null && !text.isEmpty())
            t = text.values().iterator().next();
        return t == null ? "" : t;
    }

    /**
     * A name for messages: the id of a field, otherwise the kind of the free element.
     */
    public String name() {
        return switch (type) {
            case FIELD -> String.valueOf(id);
            case TEXT -> "text";
            case IMAGE -> "image " + image;
        };
    }
}

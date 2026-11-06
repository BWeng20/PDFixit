package com.bw.pdfixit.template;

import com.bw.pdfixit.I18n;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the fields of an invoice go on the pages of a template: the layout file, a JSON beside the template PDF.<br>
 * All lengths are millimetres from the top left corner of the page, as in DIN 5008. The template itself is a plain
 * letterhead; the engine writes on top of its pages.
 */
public class Layout {

    /**
     * The value of "format", it tells a layout file from other JSON files.
     */
    public static final String FORMAT = "pdfixit-layout";

    /**
     * The version this program writes and reads.
     */
    public static final int VERSION = 1;

    /**
     * The maximum size of a picture (e.g. a signature) stored in the layout.
     */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    /**
     * The source of the following pages (the second and later pages of an invoice).
     */
    public enum Continuation {
        /**
         * Page 2 of the template.
         */
        TEMPLATE("template"),
        /**
         * Page 1 of the template again; only fields on the following pages are drawn.
         */
        PAGE1("page1"),
        /**
         * The first page of another PDF, see {@link #continuationFile}.
         */
        PDF("pdf"),
        /**
         * An empty page of the size of the template.
         */
        BLANK("blank");

        public final String json;

        Continuation(String json) {
            this.json = json;
        }
    }

    /**
     * The columns of the table of items.
     */
    public enum Column {
        POS("pos"), NAME("name"), QUANTITY("quantity"), UNIT("unit"), PRICE("price"), VAT_PERCENT("vatPercent"),
        TOTAL("total");

        public final String json;

        Column(String json) {
            this.json = json;
        }
    }

    /**
     * The two forms of DIN 5008: A with a small letterhead (address field from 27 mm), B with a large one (from 45 mm).
     */
    public enum Form {
        A, B
    }

    /**
     * The number of pages of the template the layout was made for, null if unknown. Used to detect a template that was
     * replaced by another file.
     */
    public Integer pageCount;

    /**
     * The size of the first page of the template in mm, null if unknown.
     */
    public Double pageWidth, pageHeight;

    /**
     * Null if not chosen yet: then page 2 of the template is used if it has one, otherwise the user is asked.
     */
    public Continuation continuation;

    /**
     * The PDF of {@link Continuation#PDF}, an absolute path (relative in the file, see {@link #toJson(Path)}).
     */
    public Path continuationFile;

    public String fontName = "Arimo";
    public float fontSize = 10;

    /**
     * The fixed elements: fields and free elements with a rectangle.
     */
    public final List<Element> fields = new ArrayList<>();

    /**
     * The body regions, at most one for the first page and one for the following pages.
     */
    public final List<Region> regions = new ArrayList<>();

    /**
     * The flowing elements in their order.
     */
    public final List<Element> parts = new ArrayList<>();

    /**
     * The columns of the table. By default all: the unit and the VAT rate belong on an invoice (§ 14 UStG).
     */
    public final List<Column> columns = new ArrayList<>(List.of(Column.values()));

    /**
     * False to switch off the header row of the table.
     */
    public boolean tableHeader = true;

    /**
     * The pictures of the image elements by name.
     */
    public final Map<String, LayoutImage> images = new LinkedHashMap<>();

    /**
     * The default parts of the body.
     */
    public static final List<String> DEFAULT_PARTS = List.of("note", "lines", "totals", "vatExemptionReason",
            "paymentTerms", "seller.accountHolder", "seller.iban", "seller.bic");

    /**
     * Thrown if a layout file can't be read.
     */
    public static class LayoutException extends IOException {
        public LayoutException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // ---------------------------------------------------------------------------------------------- DIN 5008

    /**
     * The layout of DIN 5008 for A4: the return address and the address of the buyer in the address field, number and
     * dates in the information block on the right, the body below. On the following pages number and page at the top
     * right, the body below.
     */
    public static Layout din5008(Form form) {
        Layout l = new Layout();
        // The address field starts 20 mm from the left with 5 mm inner margin; the additional zone (return address)
        // is 17.7 mm high, the address zone 27.3 mm.
        double field = form == Form.A ? 27 : 45;
        Element sender = Element.field("seller.address", false, new Rect(25, field, 80, 5));
        sender.singleLine = true;
        sender.size = 7f;
        l.fields.add(sender);
        l.fields.add(Element.field("buyer.address", false, new Rect(25, field + 17.7, 80, 27.3)));
        // The information block: 125 mm from the left, 75 mm wide.
        double info = form == Form.A ? 32 : 50;
        String[] infoFields = {"number", "issueDate", "deliveryDate", "buyerReference"};
        for (int i = 0; i < infoFields.length; ++i)
            l.fields.add(rightAligned(Element.field(infoFields[i], false, new Rect(125, info + i * 5, 75, 5))));
        l.fields.add(rightAligned(Element.field("number", true, new Rect(125, 20, 75, 5))));
        l.fields.add(rightAligned(Element.field("page", true, new Rect(125, 25, 75, 5))));
        // The text starts two lines below the address field; 25 mm left and 20 mm right margin; above the footer.
        double body = field + 45 + 8.5;
        l.regions.add(new Region(false, new Rect(25, body, 165, 265 - body)));
        l.regions.add(new Region(true, new Rect(25, 35, 165, 230)));
        for (String id : DEFAULT_PARTS)
            l.parts.add(Element.field(id));
        return l;
    }

    private static Element rightAligned(Element e) {
        e.align = Element.Align.RIGHT;
        return e;
    }

    /**
     * The body region of the first or the following pages as stored, null if the layout has none.
     */
    public Region region(boolean next) {
        for (Region r : regions)
            if (r.next() == next)
                return r;
        return null;
    }

    /**
     * Stores a picture for image elements.
     *
     * @throws LayoutException If the picture is larger than {@link #MAX_IMAGE_BYTES} or not PNG or JPEG.
     */
    public void putImage(String name, LayoutImage image) throws LayoutException {
        if (!LayoutImage.PNG.equals(image.format()) && !LayoutImage.JPEG.equals(image.format()))
            throw new LayoutException(I18n.t("layout.imageFormat", name));
        if (image.data().length > MAX_IMAGE_BYTES)
            throw new LayoutException(I18n.t("layout.imageTooLarge", name, MAX_IMAGE_BYTES / (1024 * 1024)));
        images.put(name, image);
    }

    // -------------------------------------------------------------------------------------------------- JSON

    /**
     * Checks if the JSON carries the marker of a layout file.
     */
    public static boolean isLayout(JsonNode root) {
        return root != null && root.isObject() && FORMAT.equals(root.path("format").asText(null));
    }

    /**
     * Checks if the bytes are a layout file (JSON with the marker), without reading it further.
     */
    public static boolean isLayout(byte[] json) {
        try {
            return isLayout(MAPPER.readTree(json));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads a layout file.
     *
     * @param json    The content of the file.
     * @param baseDir The folder of the layout file, relative paths in it are resolved against it; null if the file
     *                has absolute paths only.
     * @throws LayoutException If it is no layout file or not valid.
     */
    public static Layout fromJson(byte[] json, Path baseDir) throws LayoutException {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw invalid(e.getMessage());
        }
        if (!isLayout(root))
            throw invalid("no " + FORMAT);
        int version = root.path("version").asInt(VERSION);
        if (version > VERSION)
            throw new LayoutException(I18n.t("layout.newerVersion", version));
        Layout l = new Layout();
        l.fields.clear();
        l.parts.clear();
        if (root.has("pageCount"))
            l.pageCount = root.get("pageCount").asInt();
        JsonNode size = root.get("pageSize");
        if (size != null) {
            if (!size.isArray() || size.size() != 2 || !size.get(0).isNumber() || !size.get(1).isNumber())
                throw invalid("pageSize");
            l.pageWidth = size.get(0).asDouble();
            l.pageHeight = size.get(1).asDouble();
        }
        if (root.hasNonNull("continuation")) {
            String c = root.get("continuation").asText();
            for (Continuation v : Continuation.values())
                if (v.json.equals(c))
                    l.continuation = v;
            if (l.continuation == null)
                throw invalid("continuation \"" + c + "\"");
        }
        if (root.hasNonNull("continuationFile")) {
            Path p = Path.of(root.get("continuationFile").asText());
            l.continuationFile = (baseDir == null || p.isAbsolute() ? p : baseDir.resolve(p)).normalize();
        }
        JsonNode font = root.get("font");
        if (font != null) {
            l.fontName = font.path("name").asText(l.fontName);
            l.fontSize = (float) font.path("size").asDouble(l.fontSize);
        }
        // The pictures first: the elements refer to them.
        JsonNode images = root.get("images");
        if (images != null) {
            if (!images.isObject())
                throw invalid("images");
            for (var e : images.properties()) {
                byte[] data;
                try {
                    // Line breaks of a hand-edited file are allowed, other characters are not.
                    data = Base64.getDecoder().decode(e.getValue().path("data").asText("").replaceAll("\\s", ""));
                } catch (IllegalArgumentException ex) {
                    throw invalid("images." + e.getKey() + ": " + ex.getMessage());
                }
                l.putImage(e.getKey(), new LayoutImage(e.getValue().path("format").asText(""), data));
            }
        }
        JsonNode fields = root.get("fields");
        if (fields != null) {
            if (!fields.isArray())
                throw invalid("fields");
            for (JsonNode f : fields) {
                Element e = element(f, "fields");
                if (e.rect == null)
                    throw invalid("fields: " + e.name() + " without rect");
                l.fields.add(e);
            }
        }
        JsonNode body = root.get("body");
        if (body != null) {
            for (JsonNode r : body.path("regions"))
                l.regions.add(new Region(next(r.get("page"), "body.regions"), rect(r.get("rect"), "body.regions")));
            if (body.has("parts")) {
                if (!body.get("parts").isArray())
                    throw invalid("body.parts");
                for (JsonNode p : body.get("parts")) {
                    Element e = element(p, "body.parts");
                    if (e.rect != null)
                        throw invalid("body.parts: " + e.name() + " with rect");
                    l.parts.add(e);
                }
            }
        }
        JsonNode table = root.get("table");
        if (table != null) {
            if (table.has("columns")) {
                l.columns.clear();
                for (JsonNode c : table.get("columns")) {
                    Column column = null;
                    for (Column v : Column.values())
                        if (v.json.equals(c.asText()))
                            column = v;
                    if (column == null)
                        throw invalid("table.columns \"" + c.asText() + "\"");
                    l.columns.add(column);
                }
            }
            l.tableHeader = table.path("header").asBoolean(true);
        }
        return l;
    }

    private static LayoutException invalid(String detail) {
        return new LayoutException(I18n.t("layout.invalid", detail));
    }

    private static boolean next(JsonNode page, String where) throws LayoutException {
        if (page == null || page.isNull() || (page.isInt() && page.asInt() == 1))
            return false;
        if ("next".equals(page.asText()))
            return true;
        throw invalid(where + ": page " + page);
    }

    private static Rect rect(JsonNode r, String where) throws LayoutException {
        if (r == null || !r.isArray() || r.size() != 4)
            throw invalid(where + ": rect");
        for (JsonNode n : r)
            if (!n.isNumber())
                throw invalid(where + ": rect " + r);
        Rect rect = new Rect(r.get(0).asDouble(), r.get(1).asDouble(), r.get(2).asDouble(), r.get(3).asDouble());
        if (rect.width() <= 0 || rect.height() <= 0)
            throw invalid(where + ": rect " + r);
        return rect;
    }

    private static Element element(JsonNode n, String where) throws LayoutException {
        Element e = new Element();
        if (n.isTextual()) {
            e.id = n.asText();
            return e;
        }
        if (!n.isObject())
            throw invalid(where + ": " + n);
        String type = n.path("type").asText("field");
        switch (type) {
            case "field" -> {
                e.type = Element.Type.FIELD;
                e.id = n.path("id").asText(null);
                if (e.id == null)
                    throw invalid(where + ": field without id");
            }
            case "text" -> {
                e.type = Element.Type.TEXT;
                JsonNode t = n.get("text");
                if (t != null && t.isTextual())
                    e.text.put("", t.asText());
                else if (t != null && t.isObject())
                    t.properties().forEach(x -> e.text.put(x.getKey(), x.getValue().asText()));
                else
                    throw invalid(where + ": text without text");
            }
            case "image" -> {
                e.type = Element.Type.IMAGE;
                e.image = n.path("image").asText(null);
                if (e.image == null)
                    throw invalid(where + ": image without image");
                if (n.has("width"))
                    e.width = n.get("width").asDouble();
            }
            default -> throw invalid(where + ": type \"" + type + "\"");
        }
        e.next = next(n.get("page"), where);
        if (n.has("rect"))
            e.rect = rect(n.get("rect"), where + ": " + e.name());
        if (n.has("size"))
            e.size = (float) n.get("size").asDouble();
        if (n.hasNonNull("align")) {
            try {
                e.align = Element.Align.valueOf(n.get("align").asText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw invalid(where + ": align " + n.get("align"));
            }
        }
        e.bold = n.path("bold").asBoolean(false);
        e.singleLine = n.path("singleLine").asBoolean(false);
        JsonNode label = n.get("label");
        if (label != null && !label.isNull())
            e.label = label(label, where + ": " + e.name());
        return e;
    }

    private static Label label(JsonNode n, String where) throws LayoutException {
        if (n.isBoolean())
            return new Label(n.asBoolean());
        Label l = new Label();
        if (n.isTextual()) {
            l.text = n.asText();
            return l;
        }
        if (!n.isObject())
            throw invalid(where + ": label");
        l.text = n.path("text").asText(null);
        if (n.has("rect"))
            l.rect = rect(n.get("rect"), where + ": label");
        if (n.hasNonNull("position")) {
            try {
                l.position = Label.Position.valueOf(n.get("position").asText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw invalid(where + ": label position " + n.get("position"));
            }
        }
        l.dx = n.path("dx").asDouble(0);
        return l;
    }

    /**
     * Writes the layout file.
     *
     * @param baseDir The folder the file is written to: {@link #continuationFile} is stored relative to it. Null to
     *                store it as absolute path.
     */
    public byte[] toJson(Path baseDir) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        if (pageCount != null)
            root.put("pageCount", pageCount);
        if (pageWidth != null && pageHeight != null) {
            ArrayNode size = root.putArray("pageSize");
            number(size, pageWidth);
            number(size, pageHeight);
        }
        if (continuation != null)
            root.put("continuation", continuation.json);
        if (continuationFile != null) {
            Path p = continuationFile;
            if (baseDir != null) {
                try {
                    p = baseDir.toAbsolutePath().normalize().relativize(continuationFile.toAbsolutePath().normalize());
                } catch (IllegalArgumentException e) {
                    // Another drive: keep it absolute.
                }
            }
            root.put("continuationFile", p.toString().replace('\\', '/'));
        }
        ObjectNode font = root.putObject("font");
        font.put("name", fontName);
        number(font, "size", fontSize);
        ArrayNode fieldsNode = root.putArray("fields");
        for (Element e : fields)
            fieldsNode.add(element(e));
        ObjectNode body = root.putObject("body");
        ArrayNode regionsNode = body.putArray("regions");
        for (Region r : regions) {
            ObjectNode o = regionsNode.addObject();
            page(o, r.next());
            rect(o, "rect", r.rect());
        }
        ArrayNode partsNode = body.putArray("parts");
        for (Element e : parts) {
            ObjectNode o = element(e);
            // A field with nothing but its id is written as the id alone.
            if (o.size() == 1 && o.has("id"))
                partsNode.add(e.id);
            else
                partsNode.add(o);
        }
        ObjectNode table = root.putObject("table");
        ArrayNode columnsNode = table.putArray("columns");
        for (Column c : columns)
            columnsNode.add(c.json);
        table.put("header", tableHeader);
        if (!images.isEmpty()) {
            ObjectNode imagesNode = root.putObject("images");
            images.forEach((name, image) -> {
                ObjectNode o = imagesNode.putObject(name);
                o.put("format", image.format());
                o.put("data", Base64.getEncoder().encodeToString(image.data()));
            });
        }
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode element(Element e) {
        ObjectNode o = MAPPER.createObjectNode();
        switch (e.type) {
            case FIELD -> o.put("id", e.id);
            case TEXT -> {
                o.put("type", "text");
                if (e.text.size() == 1 && e.text.containsKey(""))
                    o.put("text", e.text.get(""));
                else {
                    ObjectNode t = o.putObject("text");
                    e.text.forEach(t::put);
                }
            }
            case IMAGE -> {
                o.put("type", "image");
                o.put("image", e.image);
                if (e.width != null)
                    number(o, "width", e.width);
            }
        }
        if (e.rect != null) {
            page(o, e.next);
            rect(o, "rect", e.rect);
        }
        if (e.size != null)
            number(o, "size", e.size);
        if (e.align != null)
            o.put("align", e.align.name().toLowerCase(Locale.ROOT));
        if (e.bold)
            o.put("bold", true);
        if (e.singleLine)
            o.put("singleLine", true);
        Label l = e.label;
        if (l != null) {
            if (!l.shown)
                o.put("label", false);
            else if (l.isSimple() && l.text == null)
                o.put("label", true);
            else if (l.isSimple())
                o.put("label", l.text);
            else {
                ObjectNode lo = o.putObject("label");
                if (l.text != null)
                    lo.put("text", l.text);
                if (l.rect != null)
                    rect(lo, "rect", l.rect);
                if (l.position != null)
                    lo.put("position", l.position.name().toLowerCase(Locale.ROOT));
                if (l.dx != 0)
                    number(lo, "dx", l.dx);
            }
        }
        return o;
    }

    private static void page(ObjectNode o, boolean next) {
        if (next)
            o.put("page", "next");
        else
            o.put("page", 1);
    }

    private static void rect(ObjectNode o, String key, Rect r) {
        ArrayNode a = o.putArray(key);
        number(a, r.x());
        number(a, r.y());
        number(a, r.width());
        number(a, r.height());
    }

    /**
     * Whole numbers without decimals, others rounded to a hundredth of a millimetre.
     */
    private static void number(ArrayNode a, double v) {
        double rounded = Math.round(v * 100) / 100.0;
        if (rounded == Math.rint(rounded))
            a.add((long) rounded);
        else
            a.add(rounded);
    }

    private static void number(ObjectNode o, String key, double v) {
        double rounded = Math.round(v * 100) / 100.0;
        if (rounded == Math.rint(rounded))
            o.put(key, (long) rounded);
        else
            o.put(key, rounded);
    }
}

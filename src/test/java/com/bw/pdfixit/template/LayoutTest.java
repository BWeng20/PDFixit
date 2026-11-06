package com.bw.pdfixit.template;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

class LayoutTest {

    private static Element fixed(Layout l, String id, boolean next) {
        for (Element e : l.fields)
            if (id.equals(e.id) && e.next == next)
                return e;
        Assertions.fail("No field " + id + (next ? " on the following pages" : ""));
        return null;
    }

    @Test
    void test_din5008FormB() {
        Layout l = Layout.din5008(Layout.Form.B);
        Element sender = fixed(l, "seller.address", false);
        Assertions.assertEquals(new Rect(25, 45, 80, 5), sender.rect);
        Assertions.assertTrue(sender.singleLine);
        Assertions.assertFalse(sender.isLabelShown());
        Assertions.assertEquals(new Rect(25, 62.7, 80, 27.3), fixed(l, "buyer.address", false).rect);
        Element number = fixed(l, "number", false);
        Assertions.assertEquals(125, number.rect.x());
        Assertions.assertEquals(50, number.rect.y());
        Assertions.assertEquals(Element.Align.RIGHT, number.align);
        Assertions.assertTrue(number.isLabelShown());
        fixed(l, "number", true);
        Assertions.assertFalse(fixed(l, "page", true).isLabelShown());
        Assertions.assertEquals(98.5, l.region(false).rect().y(), 1e-9);
        Assertions.assertEquals(265, l.region(false).rect().bottom(), 1e-9);
        Assertions.assertNotNull(l.region(true));
        Assertions.assertEquals(Layout.DEFAULT_PARTS, l.parts.stream().map(e -> e.id).toList());
        Assertions.assertNull(l.continuation);
        for (Element e : l.fields)
            Assertions.assertTrue(e.rect.isInside(210, 297), e.name());
        for (Region r : l.regions)
            Assertions.assertTrue(r.rect().isInside(210, 297));
    }

    @Test
    void test_din5008FormA() {
        Layout l = Layout.din5008(Layout.Form.A);
        Assertions.assertEquals(27, fixed(l, "seller.address", false).rect.y());
        Assertions.assertEquals(44.7, fixed(l, "buyer.address", false).rect.y(), 1e-9);
        Assertions.assertEquals(32, fixed(l, "number", false).rect.y());
        Assertions.assertEquals(80.5, l.region(false).rect().y(), 1e-9);
    }

    @Test
    void test_mmToPoints() {
        Assertions.assertEquals(72, Rect.toPoints(25.4), 1e-9);
        Assertions.assertEquals(25.4, Rect.toMm(72), 1e-9);
        var r = new Rect(25.4, 0, 50.8, 25.4).toPoints();
        Assertions.assertEquals(72, r.getX(), 1e-9);
        Assertions.assertEquals(144, r.getWidth(), 1e-9);
        // PDF coordinates: origin at the bottom left.
        PDRectangle pdf = new Rect(25.4, 25.4, 25.4, 25.4).toPdf(PDRectangle.A4);
        Assertions.assertEquals(72, pdf.getLowerLeftX(), 1e-3);
        Assertions.assertEquals(PDRectangle.A4.getHeight() - 144, pdf.getLowerLeftY(), 1e-3);
        Assertions.assertEquals(72, pdf.getHeight(), 1e-3);
        // A shifted crop box.
        PDRectangle box = new PDRectangle(10, 20, 100, 200);
        PDRectangle shifted = new Rect(0, 0, 25.4, 25.4).toPdf(box);
        Assertions.assertEquals(10, shifted.getLowerLeftX(), 1e-3);
        Assertions.assertEquals(220 - 72, shifted.getLowerLeftY(), 1e-3);
    }

    /**
     * A layout with every kind of element and option.
     */
    static Layout full(Path continuationFile) throws Exception {
        Layout l = Layout.din5008(Layout.Form.B);
        l.pageCount = 2;
        l.pageWidth = 210.0;
        l.pageHeight = 297.0;
        l.continuation = Layout.Continuation.PDF;
        l.continuationFile = continuationFile;
        l.fontName = "Tinos";
        l.fontSize = 9.5f;
        fixed(l, "issueDate", false).label = new Label(false);
        Element number = fixed(l, "number", false);
        number.label = new Label();
        number.label.text = "Rechnung Nr.";
        number.label.rect = new Rect(100, 50, 25, 5);
        number.bold = true;
        number.size = 11f;
        fixed(l, "buyer.address", false).label = new Label(true);
        Element iban = l.parts.get(Layout.DEFAULT_PARTS.indexOf("seller.iban"));
        iban.label = new Label();
        iban.label.text = "IBAN";
        iban.label.position = Label.Position.ABOVE;
        iban.label.dx = 3.25;
        l.parts.get(0).label = new Label();
        l.parts.get(0).label.text = "Hinweis";
        Element closing = new Element();
        closing.type = Element.Type.TEXT;
        closing.text.put("de", "Mit freundlichen Grüßen");
        closing.text.put("en", "Kind regards");
        l.parts.add(closing);
        Element signature = new Element();
        signature.type = Element.Type.IMAGE;
        signature.image = "signature";
        signature.width = 40.0;
        signature.align = Element.Align.CENTER;
        l.parts.add(signature);
        Element stamp = new Element();
        stamp.type = Element.Type.TEXT;
        stamp.text.put("", "Vielen Dank");
        stamp.next = true;
        stamp.rect = new Rect(25, 270, 60, 5);
        l.fields.add(stamp);
        l.putImage("signature", new LayoutImage(LayoutImage.PNG, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}));
        l.columns.clear();
        l.columns.addAll(List.of(Layout.Column.POS, Layout.Column.NAME, Layout.Column.UNIT, Layout.Column.VAT_PERCENT,
                Layout.Column.TOTAL));
        l.tableHeader = false;
        return l;
    }

    @Test
    void test_roundTrip() throws Exception {
        Path base = Path.of("layouts").toAbsolutePath();
        Layout l = full(base.resolve("sub/next.pdf"));
        byte[] json = l.toJson(base);
        String text = new String(json, StandardCharsets.UTF_8);
        Assertions.assertTrue(text.contains("\"continuationFile\" : \"sub/next.pdf\""), text);
        Assertions.assertTrue(text.contains("\"format\" : \"pdfixit-layout\""), text);
        // Whole numbers without decimals.
        Assertions.assertTrue(text.contains("[ 25, 45, 80, 5 ]"), text);
        // A field without options is written as its id.
        Assertions.assertTrue(text.contains("\"lines\""), text);

        Layout read = Layout.fromJson(json, base);
        Assertions.assertArrayEquals(json, read.toJson(base));
        Assertions.assertEquals(base.resolve("sub/next.pdf"), read.continuationFile);
        Assertions.assertEquals(Layout.Continuation.PDF, read.continuation);
        Assertions.assertEquals("Tinos", read.fontName);
        Assertions.assertEquals(9.5f, read.fontSize);
        Assertions.assertEquals(2, read.pageCount);
        Assertions.assertFalse(fixed(read, "issueDate", false).isLabelShown());
        Assertions.assertTrue(fixed(read, "buyer.address", false).isLabelShown());
        Label moved = fixed(read, "number", false).label;
        Assertions.assertEquals("Rechnung Nr.", moved.text);
        Assertions.assertEquals(new Rect(100, 50, 25, 5), moved.rect);
        Element iban = read.parts.get(Layout.DEFAULT_PARTS.indexOf("seller.iban"));
        Assertions.assertEquals(Label.Position.ABOVE, iban.label.position);
        Assertions.assertEquals(3.25, iban.label.dx);
        Assertions.assertEquals("Hinweis", read.parts.get(0).label.text);
        Element closing = read.parts.get(read.parts.size() - 2);
        Assertions.assertEquals(Element.Type.TEXT, closing.type);
        Assertions.assertEquals("Mit freundlichen Grüßen", closing.text("de"));
        Assertions.assertEquals("Kind regards", closing.text("en"));
        // Unknown language: the first one.
        Assertions.assertEquals("Mit freundlichen Grüßen", closing.text("fr"));
        Element signature = read.parts.get(read.parts.size() - 1);
        Assertions.assertEquals(Element.Type.IMAGE, signature.type);
        Assertions.assertEquals(40.0, signature.width);
        Assertions.assertArrayEquals(l.images.get("signature").data(), read.images.get("signature").data());
        Element stamp = read.fields.get(read.fields.size() - 1);
        Assertions.assertTrue(stamp.next);
        Assertions.assertEquals("Vielen Dank", stamp.text("en"));
        Assertions.assertFalse(read.tableHeader);
        Assertions.assertEquals(l.columns, read.columns);

        // Without a base directory the path is absolute.
        String absolute = new String(l.toJson(null), StandardCharsets.UTF_8);
        Assertions.assertTrue(absolute.contains(base.resolve("sub/next.pdf").toString().replace('\\', '/')), absolute);
    }

    /**
     * The sketch of the plan (section 4.1).
     */
    @Test
    void test_readsSketch() throws Exception {
        String json = """
                {
                  "format": "pdfixit-layout",
                  "version": 1,
                  "pageCount": 1,
                  "pageSize": [210, 297],
                  "continuation": "page1",
                  "font": { "name": "Arimo", "size": 10 },
                  "fields": [
                    { "id": "seller.address", "page": 1, "rect": [20, 45, 85, 5], "singleLine": true, "size": 7, "label": false },
                    { "id": "buyer.address",  "page": 1, "rect": [20, 50, 85, 40] },
                    { "id": "number",         "page": 1, "rect": [140, 90, 50, 5], "align": "right", "label": { "text": "Rechnung Nr.", "rect": [120, 90, 20, 5] } },
                    { "id": "issueDate",      "page": 1, "rect": [140, 96, 50, 5], "align": "right" },
                    { "id": "number",         "page": "next", "rect": [140, 20, 50, 5], "align": "right" },
                    { "id": "page",           "page": "next", "rect": [140, 26, 50, 5], "align": "right" }
                  ],
                  "body": {
                    "regions": [
                      { "page": 1,      "rect": [20, 110, 170, 150] },
                      { "page": "next", "rect": [20, 40, 170, 220] }
                    ],
                    "parts": ["note", "lines", "totals", "vatExemptionReason", "paymentTerms",
                              "seller.accountHolder",
                              { "id": "seller.iban", "label": { "text": "IBAN", "position": "left", "dx": 0 } },
                              "seller.bic",
                              { "type": "text", "text": { "de": "Mit freundlichen Grüßen", "en": "Kind regards" } },
                              { "type": "image", "image": "signature", "width": 40 }]
                  },
                  "images": { "signature": { "format": "png", "data": "%s" } },
                  "table": { "columns": ["pos", "name", "quantity", "price", "total"], "header": true }
                }
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        Layout l = Layout.fromJson(json.getBytes(StandardCharsets.UTF_8), null);
        Assertions.assertEquals(Layout.Continuation.PAGE1, l.continuation);
        Assertions.assertEquals(6, l.fields.size());
        Assertions.assertEquals(10, l.parts.size());
        Assertions.assertEquals(new Rect(20, 110, 170, 150), l.region(false).rect());
        Assertions.assertEquals(new Rect(20, 40, 170, 220), l.region(true).rect());
        Assertions.assertEquals(7f, l.fields.get(0).size);
        Assertions.assertFalse(l.fields.get(0).isLabelShown());
        Assertions.assertEquals(new Rect(120, 90, 20, 5), l.fields.get(2).label.rect);
        Assertions.assertTrue(l.fields.get(4).next);
        Assertions.assertEquals(Label.Position.LEFT, l.parts.get(6).label.position);
        Assertions.assertArrayEquals(new byte[]{1, 2, 3}, l.images.get("signature").data());
    }

    private static void assertInvalid(String json) {
        Assertions.assertThrows(Layout.LayoutException.class,
                () -> Layout.fromJson(json.getBytes(StandardCharsets.UTF_8), null), json);
    }

    @Test
    void test_invalid() {
        assertInvalid("not json");
        assertInvalid("{}");
        assertInvalid("{\"format\": \"other\"}");
        assertInvalid("[1, 2]");
        String head = "{\"format\": \"pdfixit-layout\", ";
        assertInvalid(head + "\"continuation\": \"page3\"}");
        assertInvalid(head + "\"fields\": [{\"id\": \"number\", \"rect\": [1, 2, 3]}]}");
        assertInvalid(head + "\"fields\": [{\"id\": \"number\", \"rect\": [1, 2, -3, 4]}]}");
        assertInvalid(head + "\"fields\": [{\"id\": \"number\", \"rect\": [1, 2, \"3\", 4]}]}");
        assertInvalid(head + "\"fields\": [{\"id\": \"number\"}]}");
        assertInvalid(head + "\"fields\": [{\"id\": \"number\", \"page\": 2, \"rect\": [1, 2, 3, 4]}]}");
        assertInvalid(head + "\"fields\": [{\"id\": \"number\", \"align\": \"middle\", \"rect\": [1, 2, 3, 4]}]}");
        assertInvalid(head + "\"fields\": [{\"type\": \"circle\", \"rect\": [1, 2, 3, 4]}]}");
        assertInvalid(head + "\"body\": {\"parts\": [{\"id\": \"note\", \"rect\": [1, 2, 3, 4]}]}}");
        assertInvalid(head + "\"body\": {\"parts\": [{\"type\": \"text\"}]}}");
        assertInvalid(head + "\"table\": {\"columns\": [\"color\"]}}");
        assertInvalid(head + "\"images\": {\"x\": {\"format\": \"gif\", \"data\": \"AQID\"}}}");
        assertInvalid(head + "\"images\": {\"x\": {\"format\": \"png\", \"data\": \"!!!\"}}}");
        assertInvalid(head + "\"images\": {\"x\": {\"format\": \"png\", \"data\": \""
                + Base64.getEncoder().encodeToString(new byte[Layout.MAX_IMAGE_BYTES + 1]) + "\"}}}");
        Layout.LayoutException newer = Assertions.assertThrows(Layout.LayoutException.class,
                () -> Layout.fromJson((head + "\"version\": 2}").getBytes(StandardCharsets.UTF_8), null));
        Assertions.assertTrue(newer.getMessage().contains("newer"), newer.getMessage());
    }

    @Test
    void test_minimalAndMarker() throws Exception {
        byte[] json = "{\"format\": \"pdfixit-layout\"}".getBytes(StandardCharsets.UTF_8);
        Assertions.assertTrue(Layout.isLayout(json));
        Assertions.assertFalse(Layout.isLayout("{\"a\": 1}".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertFalse(Layout.isLayout("no json".getBytes(StandardCharsets.UTF_8)));
        Layout l = Layout.fromJson(json, null);
        Assertions.assertTrue(l.fields.isEmpty());
        Assertions.assertTrue(l.parts.isEmpty());
        Assertions.assertTrue(l.regions.isEmpty());
        Assertions.assertEquals("Arimo", l.fontName);
        Assertions.assertTrue(l.tableHeader);
    }

    @Test
    void test_putImageLimits() {
        Layout l = new Layout();
        Assertions.assertThrows(Layout.LayoutException.class,
                () -> l.putImage("big", new LayoutImage(LayoutImage.JPEG, new byte[Layout.MAX_IMAGE_BYTES + 1])));
        Assertions.assertThrows(Layout.LayoutException.class,
                () -> l.putImage("gif", new LayoutImage("gif", new byte[10])));
        Assertions.assertDoesNotThrow(() -> l.putImage("ok", new LayoutImage(LayoutImage.JPEG, new byte[Layout.MAX_IMAGE_BYTES])));
    }

    @Test
    void test_labelDefaults() {
        Assertions.assertTrue(Element.field("number").isLabelShown());
        Assertions.assertTrue(Element.field("lines").isLabelShown());
        Assertions.assertFalse(Element.field("seller.address").isLabelShown());
        Assertions.assertFalse(Element.field("page").isLabelShown());
        Element deleted = Element.field("number");
        deleted.label = new Label(false);
        Assertions.assertFalse(deleted.isLabelShown());
        Element text = new Element();
        text.type = Element.Type.TEXT;
        Assertions.assertFalse(text.isLabelShown());
        Assertions.assertEquals("", text.text("de"));
        Assertions.assertNull(Element.field("bankName").field());
    }

    @Test
    void test_fieldIds() {
        Assertions.assertEquals(LayoutField.SELLER_IBAN, LayoutField.of("seller.iban"));
        Assertions.assertNull(LayoutField.of("seller.bankName"));
        Assertions.assertEquals(LayoutField.Placement.FLOWING_ONLY, LayoutField.LINES.placement);
        Assertions.assertEquals(LayoutField.Placement.FIXED_ONLY, LayoutField.PAGE.placement);
        for (String id : Layout.DEFAULT_PARTS)
            Assertions.assertNotNull(LayoutField.of(id), id);
    }
}

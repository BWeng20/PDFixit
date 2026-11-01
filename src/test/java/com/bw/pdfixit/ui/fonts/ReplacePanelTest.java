package com.bw.pdfixit.ui.fonts;

import com.bw.pdfixit.fonts.PdfFontEmbedder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * The content of the "Replace Font" dialog when another font is chosen. Nothing is shown or drawn, the layout is
 * calculated.
 */
class ReplacePanelTest {

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents())
            if (child instanceof Container sub)
                layout(sub);
    }

    private static List<Rectangle> boundsOfChildren(Container panel) {
        List<Rectangle> result = new ArrayList<>();
        for (Component child : panel.getComponents())
            result.add(child.getBounds());
        return result;
    }

    @Test
    void test_theSizeDoesNotDependOnTheFontOfThePreview() {
        PdfFontEmbedder.Variant best = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.MONO, false, false);
        FontsDialog.ReplacePanel panel = new FontsDialog.ReplacePanel("Helvetica", best);
        Assertions.assertEquals(best, panel.getSelected());

        // The dialog is packed for the first font: the panel gets exactly its preferred size.
        Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        layout(panel);
        List<Rectangle> first = boundsOfChildren(panel);

        for (PdfFontEmbedder.Variant v : PdfFontEmbedder.bundled()) {
            panel.setSelected(v);
            Assertions.assertEquals(v, panel.getSelected());
            Assertions.assertEquals(size, panel.getPreferredSize(), "preferred size with " + v.displayName());
            // The font of the preview is the chosen one.
            Assertions.assertTrue(panel.getPreview().getFont().getFamily().startsWith(v.family().name().equals("SANS") ? "Arimo"
                    : v.family().name().equals("SERIF") ? "Tinos" : "Cousine"), panel.getPreview().getFont().getFamily());
            panel.setSize(size);
            layout(panel);
            List<Rectangle> now = boundsOfChildren(panel);
            Assertions.assertEquals(first, now, "layout with " + v.displayName());
            for (Rectangle r : now) {
                Assertions.assertTrue(r.width > 0 && r.height > 0, "nothing is squeezed away with " + v.displayName() + ": " + r);
                Assertions.assertTrue(new Rectangle(size).contains(r), r + " is inside " + size);
            }
        }
    }

    @Test
    void test_everyEntryCanBeChosenInAnyOrder() {
        FontsDialog.ReplacePanel panel = new FontsDialog.ReplacePanel("Times-Bold",
                new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SERIF, true, false));
        List<PdfFontEmbedder.Variant> all = new ArrayList<>(PdfFontEmbedder.bundled());
        java.util.Collections.reverse(all);
        for (int round = 0; round < 3; ++round)
            for (PdfFontEmbedder.Variant v : all) {
                panel.setSelected(v);
                Assertions.assertEquals(v, panel.getSelected());
            }
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    @Test
    void test_withoutImportedFontsThereIsNoHeadingForThem() {
        FontsDialog.ReplacePanel panel = new FontsDialog.ReplacePanel("Helvetica",
                new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, false, false), dir.resolve("none.json"));
        List<Object> items = panel.getItems();
        Assertions.assertEquals(1 + 12, items.size());
        Assertions.assertEquals(new FontsDialog.Header("Bundled fonts"), items.get(0));
    }

    @Test
    void test_importedFontsAreListedBelowTheBundledOnes() throws Exception {
        java.nio.file.Path font = dir.resolve("Corporate.ttf");
        try (var is = ReplacePanelTest.class.getResourceAsStream("/fonts/Tinos-Bold.ttf")) {
            java.nio.file.Files.write(font, is.readAllBytes());
        }
        java.nio.file.Path store = dir.resolve("fonts.json");
        com.bw.pdfixit.fonts.ImportedFonts.save(store, List.of(
                new com.bw.pdfixit.fonts.ImportedFonts.Entry("Corporate", font.toString()),
                new com.bw.pdfixit.fonts.ImportedFonts.Entry("Gone", dir.resolve("gone.ttf").toString())));

        PdfFontEmbedder.Variant best = new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.SANS, true, false);
        FontsDialog.ReplacePanel panel = new FontsDialog.ReplacePanel("Arial-BoldMT", best, store);
        List<Object> items = panel.getItems();
        Assertions.assertEquals(1 + 12 + 1 + 2, items.size());
        Assertions.assertEquals(new FontsDialog.Header("Bundled fonts"), items.get(0));
        Assertions.assertEquals(new FontsDialog.Header("Imported fonts"), items.get(13));
        Assertions.assertEquals("Corporate", ((com.bw.pdfixit.fonts.ImportedFont) items.get(14)).getName());
        Assertions.assertEquals(best, panel.getSelected(), "the best match of the bundled fonts is selected");

        // A heading can't be chosen.
        panel.selectItem(items.get(0));
        panel.selectItem(items.get(13));
        Assertions.assertEquals(best, panel.getSelected());

        Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        layout(panel);
        List<Rectangle> first = boundsOfChildren(panel);

        // The imported font is shown in its own font.
        com.bw.pdfixit.fonts.ImportedFont corporate = (com.bw.pdfixit.fonts.ImportedFont) items.get(14);
        panel.setSelected(corporate);
        Assertions.assertEquals(corporate, panel.getSelected());
        Assertions.assertTrue(panel.getPreview().getText().startsWith("Lorem ipsum"));
        Assertions.assertTrue(panel.getPreview().getFont().getFamily().startsWith("Tinos"), panel.getPreview().getFont().getFamily());
        Assertions.assertEquals(size, panel.getPreferredSize());

        // A file which is gone can be chosen (it may come back), the preview says why it is not shown.
        com.bw.pdfixit.fonts.ImportedFont gone = (com.bw.pdfixit.fonts.ImportedFont) items.get(15);
        panel.setSelected(gone);
        Assertions.assertEquals(gone, panel.getSelected());
        Assertions.assertTrue(panel.getPreview().getText().startsWith("The preview is not possible"), panel.getPreview().getText());
        Assertions.assertEquals(size, panel.getPreferredSize());
        panel.setSize(size);
        layout(panel);
        Assertions.assertEquals(first, boundsOfChildren(panel));

        // Back to a bundled font: the text is shown again.
        panel.setSelected(best);
        Assertions.assertTrue(panel.getPreview().getText().startsWith("Lorem ipsum"));
    }
}

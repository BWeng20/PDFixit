package com.bw.pdfixit.ui.fonts;

import com.bw.pdfixit.fonts.PdfFontEmbedder;
import com.bw.pdfixit.fonts.PdfFontMerger;
import com.bw.pdfixit.fonts.PdfFonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The selection of the font list after fonts were changed.
 */
class FontsDialogTest {

    /**
     * Three pages with Helvetica, Times-Bold and Courier (the order of the list is by page).
     */
    private static PDDocument document() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            Standard14Fonts.FontName[] names = {Standard14Fonts.FontName.HELVETICA, Standard14Fonts.FontName.TIMES_BOLD,
                    Standard14Fonts.FontName.COURIER};
            for (Standard14Fonts.FontName name : names) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(name), 12);
                    cs.showText("text");
                    cs.endText();
                }
            }
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os);
            return Loader.loadPDF(os.toByteArray());
        }
    }

    @Test
    void test_selectionStaysOnTheReplacedFont() throws Exception {
        try (PDDocument doc = document()) {
            var before = PdfFonts.list(doc);
            Assertions.assertEquals("Times-Bold", before.get(1).name());
            var selected = before.get(1);

            PdfFontEmbedder.replace(doc, selected.dictionary(),
                    new PdfFontEmbedder.Variant(PdfFontEmbedder.Family.MONO, false, false));

            var after = PdfFonts.list(doc);
            // The name is another one now ...
            Assertions.assertEquals("Cousine", after.get(1).name());
            // ... and the same entry is found, not by name.
            Assertions.assertEquals(1, FontsDialog.findRow(after, selected.dictionary(), selected.name()));
        }
    }

    @Test
    void test_selectionFollowsTheFontIfTheOrderChanges() throws Exception {
        try (PDDocument doc = document()) {
            var selected = PdfFonts.list(doc).get(0);
            Assertions.assertEquals("Helvetica", selected.name());
            // A font with a name that sorts before, used on the same page.
            var list = new java.util.ArrayList<>(PdfFonts.list(doc));
            java.util.Collections.reverse(list);
            Assertions.assertEquals(2, FontsDialog.findRow(list, selected.dictionary(), selected.name()));
        }
    }

    @Test
    void test_selectionFallsBackToTheNameWhenAFontWasMerged() throws Exception {
        try (PDDocument doc = document()) {
            var fonts = PdfFonts.list(doc);
            var removed = fonts.get(2);
            // The font object is gone from the list (e.g. merged), a font with the same name stays.
            List<PdfFonts.FontInfo> others = List.of(fonts.get(0), fonts.get(1));
            Assertions.assertEquals(-1, FontsDialog.findRow(others, removed.dictionary(), removed.name()));
            Assertions.assertEquals(0, FontsDialog.findRow(others, removed.dictionary(), "Helvetica"));
            Assertions.assertEquals(-1, FontsDialog.findRow(others, null, null));
        }
    }

    @Test
    void test_selectionAfterMergeStaysOnTheNameOfTheRemovedFont() throws Exception {
        // Three copies of one font: after the merge the selected copy is gone, its twin is selected.
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; ++i) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.showText("text");
                    cs.endText();
                }
            }
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            doc.save(os);
            try (PDDocument loaded = Loader.loadPDF(os.toByteArray())) {
                var before = PdfFonts.list(loaded);
                Assertions.assertEquals(3, before.size());
                var selected = before.get(2);
                PdfFontMerger.merge(loaded);
                var after = PdfFonts.list(loaded);
                Assertions.assertEquals(1, after.size());
                Assertions.assertEquals(0, FontsDialog.findRow(after, selected.dictionary(), selected.name()));
            }
        }
    }

    /**
     * The list gets shorter while the last row is selected (e.g. after "Merge Duplicates"): the listeners of the model,
     * which are called before the table clears its selection, must not read a row which doesn't exist any more.
     */
    @Test
    void test_shorterListWhileTheLastRowIsSelected() throws Exception {
        try (PDDocument doc = document()) {
            var fonts = PdfFonts.list(doc);
            FontsDialog.FontTableModel model = new FontsDialog.FontTableModel();
            model.setFonts(fonts);
            javax.swing.JTable table = new javax.swing.JTable(model);
            table.setRowSelectionInterval(2, 2);

            List<String> seen = new ArrayList<>();
            List<Integer> rowSeenByListener = new ArrayList<>();
            // As in the dialog: a listener added after the table was created is called before the table's own one.
            model.addTableModelListener(e -> {
                rowSeenByListener.add(table.getSelectedRow());
                PdfFonts.FontInfo selected = model.getOrNull(table.getSelectedRow());
                seen.add(selected == null ? "none" : selected.name());
            });
            Assertions.assertNull(model.getOrNull(5));
            Assertions.assertNull(model.getOrNull(-1));

            // Without the fix of the dialog the old selection is still there when the listener is called.
            model.setFonts(fonts.subList(0, 1));
            Assertions.assertEquals(1, seen.size());
            Assertions.assertEquals("none", seen.get(0));
            // The reason of the error: the listener still saw row 2 of a list with one row.
            Assertions.assertEquals(List.of(2), rowSeenByListener);
            Assertions.assertEquals(-1, table.getSelectedRow());
        }
    }
}

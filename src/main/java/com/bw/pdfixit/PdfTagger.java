package com.bw.pdfixit;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Gives a document a <b>formal</b> logical structure, which the conformance level A of PDF/A demands.<br>
 * The structure is: a <i>Document</i> element with one <i>Sect</i> element per page, named by the template
 * {@code tag.page} ("Page 1", "Seite 1", ...), which contains one <i>P</i> element. The whole content of the page is
 * one marked-content sequence of that paragraph.<br>
 * That satisfies the rules of the standard, but it is no real accessibility: a screen reader gets each page as one
 * paragraph, without headings, lists, tables or descriptions of images. The tool can't know these; they have to come
 * from the program that created the document. A document which has a structure already is never changed.
 */
public final class PdfTagger {

    private PdfTagger() {
    }

    /**
     * Checks if the document has a logical structure. A structure tree root without content (Mustang adds one when it
     * declares PDF/A-3) doesn't count.
     */
    public static boolean isTagged(PDDocument document) {
        PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
        if (root == null)
            return false;
        var kids = root.getCOSObject().getDictionaryObject(COSName.K);
        return kids != null && !(kids instanceof COSArray a && a.size() == 0);
    }

    /**
     * Adds the formal structure, see the class description, and marks the document as tagged.
     *
     * @return The number of pages (sections) that were added, 0 if the document is tagged already.
     */
    public static int tagPages(PDDocument document) throws IOException {
        var catalog = document.getDocumentCatalog();
        if (isTagged(document))
            return 0;
        COSDictionary root = indirect(new COSDictionary());
        root.setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot"));
        COSDictionary documentElement = element("Document", root, null);
        COSArray sections = new COSArray();
        COSArray parentTree = new COSArray();
        int key = 0;
        int number = 0;
        for (PDPage page : document.getPages()) {
            ++number;
            COSDictionary section = element("Sect", documentElement, page);
            section.setItem(COSName.T, new COSString(I18n.t("tag.page", number)));
            sections.add(section);
            COSArray kids = new COSArray();
            section.setItem(COSName.K, kids);
            byte[] content = content(page);
            if (content.length == 0)
                continue;
            COSDictionary paragraph = element("P", section, page);
            paragraph.setInt(COSName.K, 0);
            kids.add(paragraph);
            ByteArrayOutputStream wrapped = new ByteArrayOutputStream(content.length + 64);
            wrapped.write("/P <</MCID 0>> BDC\n".getBytes(StandardCharsets.ISO_8859_1));
            wrapped.write(content);
            wrapped.write("\nEMC\n".getBytes(StandardCharsets.ISO_8859_1));
            page.setContents(new PDStream(document, new ByteArrayInputStream(wrapped.toByteArray()), COSName.FLATE_DECODE));
            page.getCOSObject().setInt(COSName.STRUCT_PARENTS, key);
            COSArray parents = new COSArray();
            parents.add(paragraph);
            parentTree.add(COSInteger.get(key));
            parentTree.add(parents);
            ++key;
        }
        documentElement.setItem(COSName.K, sections);
        root.setItem(COSName.K, documentElement);
        COSDictionary tree = new COSDictionary();
        tree.setItem(COSName.NUMS, parentTree);
        root.setItem(COSName.PARENT_TREE, tree);
        root.setInt(COSName.PARENT_TREE_NEXT_KEY, key);
        catalog.setStructureTreeRoot(new PDStructureTreeRoot(root));
        PDMarkInfo mark = new PDMarkInfo();
        mark.setMarked(true);
        catalog.setMarkInfo(mark);
        return number;
    }

    private static byte[] content(PDPage page) throws IOException {
        if (!page.hasContents())
            return new byte[0];
        try (InputStream is = page.getContents()) {
            return is.readAllBytes();
        }
    }

    /**
     * Creates a structure element. They refer to each other, so each one is an indirect object.
     */
    private static COSDictionary element(String type, COSDictionary parent, PDPage page) {
        COSDictionary e = indirect(new COSDictionary());
        e.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
        e.setName(COSName.S, type);
        e.setItem(COSName.P, parent);
        if (page != null)
            e.setItem(COSName.PG, page.getCOSObject());
        return e;
    }

    private static COSDictionary indirect(COSDictionary d) {
        d.setDirect(false);
        return d;
    }
}

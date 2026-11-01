package com.bw.jPdfTool;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds features of a loaded document, which PDFBox (or this tool) doesn't support or which get lost by editing.
 * The result is a list of short warnings for the user.
 */
public final class PdfRisk {

    private PdfRisk() {
    }

    /**
     * Inspects a document. Call it right after loading, before the security is removed.
     *
     * @param document The document.
     * @param data     The bytes of the file, null to skip the check of the file structure.
     * @return The warnings, never null.
     */
    public static List<String> inspect(PDDocument document, byte[] data) {
        List<String> result = new ArrayList<>();

        if (data != null && hasBrokenXref(data))
            result.add(I18n.t("risk.damaged"));

        if (document.isEncrypted()) {
            AccessPermission ap = document.getCurrentAccessPermission();
            StringBuilder s = new StringBuilder(I18n.t("risk.encrypted"));
            if (ap != null && !ap.isOwnerPermission()) {
                List<String> denied = new ArrayList<>();
                if (!ap.canPrint())
                    denied.add(I18n.t("risk.denied.print"));
                if (!ap.canModify())
                    denied.add(I18n.t("risk.denied.modify"));
                if (!ap.canExtractContent())
                    denied.add(I18n.t("risk.denied.copy"));
                if (!denied.isEmpty())
                    s.append(" (").append(I18n.t("risk.denied", String.join(", ", denied))).append(")");
            }
            result.add(s.append(".").toString());
        }

        try {
            if (!document.getSignatureDictionaries().isEmpty())
                result.add(I18n.t("risk.signed"));
        } catch (RuntimeException e) {
            Log.debug("Signature check failed: %s", e.getMessage());
        }

        try {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            if (form != null && form.hasXFA())
                result.add(I18n.t("risk.xfa"));
        } catch (RuntimeException e) {
            Log.debug("Form check failed: %s", e.getMessage());
        }

        try {
            if (hasUnsupportedImages(document))
                result.add(I18n.t("risk.images"));
        } catch (RuntimeException e) {
            Log.debug("Image check failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * The warning for operations which remove the tags of a tagged PDF.
     */
    public static String tagsLostWarning() {
        return I18n.t("risk.tagsLost");
    }

    /**
     * Checks if the document has a structure tree (is a tagged PDF).
     */
    public static boolean isTagged(PDDocument document) {
        try {
            return document != null && document.getDocumentCatalog().getStructureTreeRoot() != null;
        } catch (RuntimeException e) {
            Log.debug("Structure check failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Gets the warning if an operation removed the tags.
     *
     * @param tagged True if a document, which went into the operation, was tagged.
     * @param result The result of the operation, null if it has no tags (e.g. redacted pages).
     * @return A list with {@link #tagsLostWarning()} if the input was tagged and the result is not, otherwise an empty list.
     */
    public static List<String> tagsLost(boolean tagged, PDDocument result) {
        return tagged && !isTagged(result) ? List.of(tagsLostWarning()) : List.of();
    }

    /**
     * Checks if some page uses images with filters, which PDFBox can't decode without extra plugins.
     */
    static boolean hasUnsupportedImages(PDDocument document) {
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : document.getPages()) {
            if (hasUnsupportedImages(page.getResources(), visited))
                return true;
        }
        return false;
    }

    private static boolean hasUnsupportedImages(PDResources resources, Set<COSDictionary> visited) {
        if (resources == null)
            return false;
        COSDictionary xobjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (xobjects == null || !visited.add(xobjects))
            return false;
        for (COSName name : xobjects.keySet()) {
            if (!(xobjects.getDictionaryObject(name) instanceof COSDictionary xo))
                continue;
            COSName subtype = xo.getCOSName(COSName.SUBTYPE);
            if (COSName.IMAGE.equals(subtype)) {
                if (hasFilter(xo.getDictionaryObject(COSName.FILTER)))
                    return true;
            } else if (COSName.FORM.equals(subtype) && visited.add(xo)) {
                COSDictionary res = xo.getCOSDictionary(COSName.RESOURCES);
                if (res != null && hasUnsupportedImages(new PDResources(res), visited))
                    return true;
            }
        }
        return false;
    }

    private static boolean hasFilter(COSBase filter) {
        if (filter instanceof COSName n)
            return COSName.JBIG2_DECODE.equals(n) || COSName.JPX_DECODE.equals(n);
        if (filter instanceof COSArray a) {
            for (COSBase b : a) {
                if (hasFilter(b))
                    return true;
            }
        }
        return false;
    }

    // ---- Damaged files --------------------------------------------------------------------------------------

    private static final Pattern STARTXREF = Pattern.compile("startxref\\s*(\\d+)");
    private static final Pattern XREF_STREAM = Pattern.compile("^\\d+\\s+\\d+\\s+obj");

    /**
     * Checks if the last "startxref" points to a cross reference table or stream. If not, PDFBox has to rebuild
     * the table by scanning the whole file (and silently does so).
     */
    static boolean hasBrokenXref(byte[] data) {
        final String tail = new String(data, Math.max(0, data.length - 2048), Math.min(data.length, 2048),
                StandardCharsets.ISO_8859_1);
        int k = tail.lastIndexOf("startxref");
        if (k < 0)
            return true;
        Matcher m = STARTXREF.matcher(tail.substring(k));
        if (!m.lookingAt())
            return true;
        long offset;
        try {
            offset = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return true;
        }
        // Garbage before the header shifts all offsets, PDFBox accepts that.
        final String head = new String(data, 0, Math.min(data.length, 1024), StandardCharsets.ISO_8859_1);
        int shift = Math.max(0, head.indexOf("%PDF-"));
        long pos = offset + shift;
        if (pos < 0 || pos >= data.length)
            return true;
        final String at = new String(data, (int) pos, (int) Math.min(64, data.length - pos), StandardCharsets.ISO_8859_1);
        return !(at.startsWith("xref") || XREF_STREAM.matcher(at).find());
    }
}

package com.bw.jPdfTool;

import com.bw.jPdfTool.fonts.FontEmbedException;
import com.bw.jPdfTool.fonts.PdfFontEmbedder;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks all objects of a document once, removes entries PDF/A forbids and collects the content streams,
 * see {@link PdfStandard#fixPdfaIssues}.
 */
final class PdfaFixer {
    private static final Set<String> FORBIDDEN_ACTIONS = Set.of("Launch", "Sound", "Movie", "ResetForm",
            "ImportData", "JavaScript", "Hide", "SetOCGState", "Rendition", "Trans", "GoTo3DView");
    private static final Set<String> RENDERING_INTENTS = Set.of("AbsoluteColorimetric", "RelativeColorimetric",
            "Saturation", "Perceptual");
    private static final COSName OPI = COSName.getPDFName("OPI");
    private static final COSName ALTERNATES = COSName.getPDFName("Alternates");
    private static final COSName REF = COSName.getPDFName("Ref");
    private static final COSName OC_PROPERTIES = COSName.getPDFName("OCProperties");
    private static final COSName CONFIGS = COSName.getPDFName("Configs");
    private static final COSName OCGS = COSName.getPDFName("OCGs");
    private static final COSName ORDER = COSName.getPDFName("Order");
    private static final COSName TR2 = COSName.getPDFName("TR2");
    private static final COSName HTP = COSName.getPDFName("HTP");
    private static final COSName JAVASCRIPT = COSName.getPDFName("JavaScript");
    private static final COSName OPEN_ACTION = COSName.getPDFName("OpenAction");
    private static final Set<String> FONT_TYPES = Set.of("Type0", "Type1", "MMType1", "TrueType");
    private static final int FLAG_HIDDEN = 2, FLAG_PRINT = 4, FLAG_NOVIEW = 32;

    private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<COSStream> contents = new ArrayList<>();
    private final List<COSDictionary> fonts = new ArrayList<>();
    /**
     * What was changed and how often, in the order of the first change.
     */
    private final Map<String, Integer> changes = new LinkedHashMap<>();

    private void changed(String what, int times) {
        changes.merge(what, times, Integer::sum);
    }

    private boolean remove(COSDictionary d, COSName key) {
        if (!d.containsKey(key))
            return false;
        d.removeItem(key);
        changed(I18n.t("fixer.forbiddenEntry", key.getName()), 1);
        return true;
    }

    private void fixIntent(COSDictionary d, COSName key) {
        COSName ri = d.getCOSName(key);
        if (ri != null && !RENDERING_INTENTS.contains(ri.getName())) {
            d.setItem(key, COSName.getPDFName("RelativeColorimetric"));
            changed(I18n.t("fixer.renderingIntent"), 1);
        }
    }

    private void fixAction(COSDictionary d, COSName key) {
        if (d.getDictionaryObject(key) instanceof COSDictionary action) {
            COSName type = action.getCOSName(COSName.S);
            if (type != null && FORBIDDEN_ACTIONS.contains(type.getName())) {
                d.removeItem(key);
                changed(I18n.t("fixer.forbiddenAction", type.getName()), 1);
            }
        }
    }

    private void fixGraphicsState(COSDictionary state) {
        remove(state, COSName.TR);
        remove(state, HTP);
        // Only /Default is allowed for TR2.
        COSBase tr2 = state.getDictionaryObject(TR2);
        if (tr2 != null && !(tr2 instanceof COSName n && n.getName().equals("Default")))
            remove(state, TR2);
        fixIntent(state, COSName.getPDFName("RI"));
    }

    private static void collectOrdered(COSArray order, Set<COSBase> found) {
        for (int i = 0; i < order.size(); ++i) {
            COSBase item = order.getObject(i);
            if (item instanceof COSArray nested)
                collectOrdered(nested, found);
            else if (item instanceof COSDictionary)
                found.add(item);
        }
    }

    /**
     * PDF/A: every optional content configuration needs a /Name, and an /Order has to list all OCGs.
     */
    private void fixOptionalContent(COSDictionary ocProperties) {
        List<COSDictionary> configs = new ArrayList<>();
        if (ocProperties.getDictionaryObject(COSName.D) instanceof COSDictionary d)
            configs.add(d);
        if (ocProperties.getDictionaryObject(CONFIGS) instanceof COSArray array)
            for (int i = 0; i < array.size(); ++i)
                if (array.getObject(i) instanceof COSDictionary c)
                    configs.add(c);
        COSArray ocgs = ocProperties.getDictionaryObject(OCGS) instanceof COSArray a ? a : new COSArray();
        for (COSDictionary config : configs) {
            if (config.getString(COSName.NAME) == null) {
                config.setString(COSName.NAME, config == ocProperties.getDictionaryObject(COSName.D) ? "Default"
                        : "Config " + configs.indexOf(config));
                changed(I18n.t("fixer.ocName"), 1);
            }
            if (config.getDictionaryObject(ORDER) instanceof COSArray order) {
                Set<COSBase> found = Collections.newSetFromMap(new IdentityHashMap<>());
                collectOrdered(order, found);
                int added = 0;
                for (int i = 0; i < ocgs.size(); ++i)
                    if (ocgs.getObject(i) instanceof COSDictionary ocg && found.add(ocg)) {
                        order.add(ocgs.get(i));
                        ++added;
                    }
                if (added > 0)
                    changed(I18n.t("fixer.ocOrder"), added);
            }
        }
    }

    /**
     * Streams: external file data is forbidden.
     */
    private void fixStream(COSDictionary stream) {
        remove(stream, COSName.F);
        remove(stream, COSName.F_FILTER);
        remove(stream, COSName.F_DECODE_PARMS);
    }

    /**
     * Image XObjects: no interpolation, alternates, OPI, unknown rendering intents.
     */
    private void fixImage(COSDictionary image) {
        if (image.getBoolean(COSName.INTERPOLATE, false)) {
            image.setBoolean(COSName.INTERPOLATE, false);
            changed(I18n.t("fixer.interpolation"), 1);
        }
        remove(image, ALTERNATES);
        remove(image, OPI);
        fixIntent(image, COSName.INTENT);
    }

    /**
     * Form XObjects: no OPI and no reference XObjects.
     */
    private void fixForm(COSDictionary form) {
        remove(form, OPI);
        remove(form, REF);
    }

    /**
     * Resources: no transfer functions, halftones and unknown rendering intents in the graphics states.
     */
    private void fixGraphicsStates(COSDictionary resources) {
        if (resources.getDictionaryObject(COSName.EXT_G_STATE) instanceof COSDictionary states)
            for (COSName name : states.keySet())
                if (states.getDictionaryObject(name) instanceof COSDictionary state)
                    fixGraphicsState(state);
    }

    /**
     * Catalog: no additional actions, no forbidden open action, no document level JavaScript, valid optional content.
     */
    private void fixCatalog(COSDictionary catalog) {
        remove(catalog, COSName.AA);
        fixAction(catalog, OPEN_ACTION);
        if (catalog.getDictionaryObject(COSName.NAMES) instanceof COSDictionary names)
            remove(names, JAVASCRIPT);
        if (catalog.getDictionaryObject(OC_PROPERTIES) instanceof COSDictionary ocProperties)
            fixOptionalContent(ocProperties);
    }

    /**
     * Annotations of a page: printable and not hidden (popups are exempt).
     */
    private void fixAnnotationFlags(COSDictionary page) {
        if (page.getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots)
            for (int i = 0; i < annots.size(); ++i)
                if (annots.getObject(i) instanceof COSDictionary annot
                        && !COSName.POPUP.equals(annot.getCOSName(COSName.SUBTYPE))) {
                    int f = annot.getInt(COSName.F, 0);
                    int fixedFlags = (f | FLAG_PRINT) & ~(FLAG_HIDDEN | FLAG_NOVIEW);
                    if (fixedFlags != f) {
                        annot.setInt(COSName.F, fixedFlags);
                        changed(I18n.t("fixer.annotFlags"), 1);
                    }
                }
    }

    private void fixDictionary(COSDictionary d) {
        COSName type = d.getCOSName(COSName.TYPE);
        COSName subtype = d.getCOSName(COSName.SUBTYPE);
        boolean stream = d instanceof COSStream;

        if (!stream && d.containsKey(COSName.BASE_FONT) && subtype != null
                && FONT_TYPES.contains(subtype.getName()))
            fonts.add(d);

        if (stream)
            fixStream(d);
        if (COSName.IMAGE.equals(subtype))
            fixImage(d);
        else if (COSName.FORM.equals(subtype))
            fixForm(d);
        fixGraphicsStates(d);
        if (COSName.CATALOG.equals(type))
            fixCatalog(d);
        if (COSName.WIDGET.equals(subtype) || d.containsKey(COSName.FT))
            remove(d, COSName.AA);
        fixAction(d, COSName.A);
        if (COSName.PAGE.equals(type))
            fixAnnotationFlags(d);
    }

    /**
     * Walks the object graph depth-first with an explicit stack: chains like outline /Next links can be
     * thousands of levels deep, which would overflow the call stack with recursion.
     */
    void visit(COSBase root) {
        Deque<COSBase> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty())
            visitOne(stack.pop(), stack);
    }

    private void visitOne(COSBase base, Deque<COSBase> stack) {
        if (base instanceof COSObject o)
            base = o.getObject();
        if (base == null || !visited.add(base))
            return;
        if (base instanceof COSDictionary d) {
            fixDictionary(d);
            if (d instanceof COSStream s) {
                if (COSName.FORM.equals(d.getCOSName(COSName.SUBTYPE)) || d.getInt(COSName.PATTERN_TYPE) == 1)
                    contents.add(s);
            }
            if (COSName.PAGE.equals(d.getCOSName(COSName.TYPE))) {
                COSBase c = d.getDictionaryObject(COSName.CONTENTS);
                if (c instanceof COSStream s)
                    contents.add(s);
                else if (c instanceof COSArray a)
                    for (int i = 0; i < a.size(); ++i)
                        if (a.getObject(i) instanceof COSStream s)
                            contents.add(s);
            }
            if (COSName.TYPE3.equals(d.getCOSName(COSName.SUBTYPE)) && d.getDictionaryObject(COSName.CHAR_PROCS) instanceof COSDictionary procs)
                for (COSBase p : procs.getValues())
                    if (p instanceof COSObject o && o.getObject() instanceof COSStream s)
                        contents.add(s);
                    else if (p instanceof COSStream s)
                        contents.add(s);
            // Pushed in reverse to visit the values in their order.
            List<COSBase> values = new ArrayList<>(d.getValues());
            for (int i = values.size() - 1; i >= 0; --i)
                stack.push(values.get(i));
        } else if (base instanceof COSArray a) {
            for (int i = a.size() - 1; i >= 0; --i)
                stack.push(a.get(i));
        }
    }

    /**
     * Fixes the inline images of the collected content streams.
     */
    void fixContentStreams() throws IOException {
        for (COSStream s : new LinkedHashSet<>(contents)) {
            byte[] data;
            try (InputStream is = s.createInputStream()) {
                data = is.readAllBytes();
            }
            // Cheap check before parsing: inline images start with the BI operator.
            if (!new String(data, StandardCharsets.ISO_8859_1).contains("BI"))
                continue;
            List<Object> tokens = new PDFStreamParser(data).parse();
            int n = 0;
            for (Object t : tokens) {
                if (t instanceof Operator op && "BI".equals(op.getName()) && op.getImageParameters() != null) {
                    COSDictionary p = op.getImageParameters();
                    if (p.getBoolean(COSName.I, false) || p.getBoolean(COSName.INTERPOLATE, false)) {
                        p.removeItem(COSName.INTERPOLATE);
                        p.setBoolean(COSName.I, false);
                        ++n;
                    }
                }
            }
            if (n == 0)
                continue;
            try (OutputStream os = s.createOutputStream(COSName.FLATE_DECODE)) {
                new ContentStreamWriter(os).writeTokens(tokens);
            }
            changed(I18n.t("fixer.inlineInterpolation"), n);
        }
    }

    /**
     * @return What was changed and how often, empty if nothing.
     * @throws FontEmbedException If fonts can't be embedded.
     */
    static Map<String, Integer> fix(PDDocument document) throws IOException {
        PdfaFixer fixer = new PdfaFixer();
        fixer.visit(document.getDocument().getTrailer());
        fixer.fixContentStreams();
        fixer.changes.putAll(PdfFontEmbedder.embed(document, fixer.fonts));
        return fixer.changes;
    }
}

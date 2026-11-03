package com.bw.pdfixit;

import com.bw.pdfixit.fonts.FontEmbedException;
import com.bw.pdfixit.fonts.PdfFontEmbedder;
import com.bw.pdfixit.invoice.EInvoice;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes the standards a document claims to follow: PDF version, PDF/A, PDF/X, PDF/UA and e-invoice.<br>
 * This only reads what the document declares in its metadata, it doesn't validate anything.
 */
public final class PdfStandard {

    private static final String NS_PDFA = "http://www.aiim.org/pdfa/ns/id/";
    private static final String NS_PDFX = "http://www.npes.org/pdfx/ns/id/";
    private static final String NS_PDFUA = "http://www.aiim.org/pdfua/ns/id/";
    private static final String NS_PDFE = "http://www.aiim.org/pdfe/ns/id/";
    private static final String NS_PDFVT = "http://www.npes.org/pdfvt/ns/id/";
    /**
     * The usual prefix of the namespaces of the identification schemas.
     */
    private static final Map<String, String> NAMESPACES = Map.of("pdfaid", NS_PDFA,
            "pdfxid", NS_PDFX, "pdfuaid", NS_PDFUA, "pdfe", NS_PDFE, "pdfvtid", NS_PDFVT);

    /**
     * A property of an identification schema in the XMP, written as element or attribute. The prefix is the one the
     * XMP declares for the namespace, not necessarily the usual one.
     */
    private record Property(String namespace, String defaultPrefix, String name, String value) {
        Matcher matcher(String xmp) {
            return Pattern.compile(Pattern.quote(prefixOf(xmp, namespace, defaultPrefix) + ":" + name)
                    + "(?:>|=[\"'])\\s*(" + value + ")").matcher(xmp);
        }
    }

    private static final Property PDFA_PART = new Property(NS_PDFA, "pdfaid", "part", "\\d");
    private static final Property PDFA_CONFORMANCE = new Property(NS_PDFA, "pdfaid", "conformance", "[A-Za-z]");
    private static final Property PDFX = new Property(NS_PDFX, "pdfxid", "GTS_PDFXVersion", "[^<\"']+");
    private static final Property PDFUA = new Property(NS_PDFUA, "pdfuaid", "part", "\\d");
    private static final Property PDFE = new Property(NS_PDFE, "pdfe", "ISO_PDFEVersion", "[^<\"']+");
    private static final Property PDFVT = new Property(NS_PDFVT, "pdfvtid", "GTS_PDFVTVersion", "[^<\"']+");

    /**
     * Gets the prefix the XMP uses for a namespace.
     *
     * @return The prefix, the default if the XMP doesn't declare the namespace.
     */
    private static String prefixOf(String xmp, String namespace, String defaultPrefix) {
        Matcher m = Pattern.compile("xmlns:([A-Za-z_][\\w.-]*)\\s*=\\s*[\"']" + Pattern.quote(namespace) + "[\"']")
                .matcher(xmp);
        return m.find() ? m.group(1) : defaultPrefix;
    }

    private static String prefixOf(String xmp, String usualPrefix) {
        String namespace = NAMESPACES.get(usualPrefix);
        return namespace == null ? usualPrefix : prefixOf(xmp, namespace, usualPrefix);
    }

    private PdfStandard() {
    }

    /**
     * Gets a short description, e.g. "PDF 1.7 · PDF/A-3u · Factur-X".
     *
     * @param document The document.
     * @return The description, never null.
     */
    public static String describe(PDDocument document) {
        List<String> parts = new ArrayList<>();
        parts.add(String.format(Locale.ROOT, "PDF %.1f", document.getVersion()));

        String xmp = xmp(document);
        String pdfa = pdfaLabel(document);
        if (pdfa != null)
            parts.add(pdfa);
        Matcher m = PDFX.matcher(xmp);
        if (m.find()) {
            String x = m.group(1).trim();
            parts.add(x.startsWith("PDF/X") ? x : "PDF/X-" + x);
        }
        m = PDFUA.matcher(xmp);
        if (m.find())
            parts.add("PDF/UA-" + m.group(1));

        if (EInvoice.hasInvoice(document))
            parts.add("Factur-X");
        return String.join(" · ", parts);
    }

    /**
     * Gets the PDF/A level the document declares, with the conformance level, e.g. "PDF/A-3u".
     *
     * @return The level, null if the document declares no PDF/A.
     */
    public static String pdfaLabel(PDDocument document) {
        String xmp = xmp(document);
        Matcher m = PDFA_PART.matcher(xmp);
        if (!m.find())
            return null;
        Matcher c = PDFA_CONFORMANCE.matcher(xmp);
        return "PDF/A-" + m.group(1) + (c.find() ? c.group(1).toLowerCase(Locale.ROOT) : "");
    }

    /**
     * Gets the PDF/A part the document declares in its metadata (1, 2, 3 or 4).
     *
     * @return The part or 0 if the document declares no PDF/A conformance.
     */
    public static int pdfaPart(PDDocument document) {
        Matcher m = PDFA_PART.matcher(xmp(document));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Checks if the document has an output intent with an RGB ICC profile.<br>
     * PDF/A allows device RGB colors (e.g. images rendered by this tool) only with such an output intent.
     */
    public static boolean hasRgbOutputIntent(PDDocument document) {
        try {
            for (var intent : document.getDocumentCatalog().getOutputIntents()) {
                var profile = intent.getDestOutputIntent();
                if (profile == null)
                    continue;
                try (var is = profile.createInputStream()) {
                    byte[] header = is.readNBytes(20);
                    // ICC header: bytes 16-19 are the colour space of the data.
                    if (header.length == 20 && "RGB ".equals(new String(header, 16, 4, StandardCharsets.US_ASCII)))
                        return true;
                }
            }
        } catch (Exception e) {
            Log.debug("Failed to read output intents: %s", e.getMessage());
        }
        return false;
    }

    /**
     * Name of the sRGB profile in the Mustang library. It is the profile Mustang adds to e-invoices,
     * using it here keeps all documents written by this tool consistent.
     */
    private static final String MUSTANG_SRGB_PROFILE = "sRGB.icc";

    /**
     * Fixes what PDF/A forbids and can be fixed without changing the visible page, in place:
     * <ul>
     * <li>no output intent: adds an sRGB one, see {@link #addSrgbOutputIntent}</li>
     * <li>image interpolation (/Interpolate true, inline /I true), image /Alternates and /OPI</li>
     * <li>external stream data (/F, /FFilter, /FDecodeParms) and PostScript/reference XObject entries</li>
     * <li>transfer functions and halftones in graphics states, unknown rendering intents</li>
     * <li>JavaScript, launch/sound/movie/... actions and additional actions (/AA) of the catalog and form fields</li>
     * <li>annotation flags: printable, not hidden</li>
     * <li>fonts without font program: a metric compatible free substitute is embedded, see {@link PdfFontEmbedder}</li>
     * </ul>
     * Other problems that need real conversion (encryption, ...) are left to the validation.
     *
     * @param document The document.
     * @return What was changed and how often, empty if the document was not changed.
     * @throws FontEmbedException If a font can't be embedded.
     */
    public static Map<String, Integer> fixPdfaIssues(PDDocument document) throws IOException {
        Map<String, Integer> changes = new LinkedHashMap<>();
        if (addSrgbOutputIntent(document))
            changes.put(I18n.t("fix.srgb"), 1);
        if (addPdfa3Identification(document))
            changes.put(I18n.t("fix.declaredPdfa3b"), 1);
        changes.putAll(PdfaFixer.fix(document));
        changes.forEach((what, times) -> Log.info("PDF/A: %s (%d)", what, times));
        return changes;
    }

    /**
     * The result of {@link #repairPdfa}.
     *
     * @param pdf            The repaired PDF, null if nothing was changed.
     * @param changes        What was changed and how often, empty if nothing.
     * @param upgradeOffered True if the document declares PDF/A-1 or PDF/A-2 and has attachments, which these standards
     *                       forbid. The repair did not change the standard; with the option {@code upgradeToPdfa3} it would
     *                       declare PDF/A-3 to keep the attachments.
     */
    public record Repair(byte[] pdf, Map<String, Integer> changes, boolean upgradeOffered) {
    }

    /**
     * Checks if the document declares PDF/A-1 or PDF/A-2 and has attachments. Only PDF/A-3 allows them, so a repair
     * could switch the standard to keep them (see {@link #repairPdfa(byte[], boolean)}).
     */
    public static boolean hasAttachmentsBeforePdfa3(PDDocument document) {
        int part = pdfaPart(document);
        if (part != 1 && part != 2)
            return false;
        var names = document.getDocumentCatalog().getNames();
        return names != null && names.getEmbeddedFiles() != null;
    }

    private static final String BROKEN_SCHEMA_RULE = ".* (6[.]7[.][89]|6[.]6[.]2[.]3[.][0-9])-[0-9]+";
    private static final String DIFFERING_INFO_RULE = ".* (6[.]7[.]3|6[.]6[.]2[.]3[.]1)-[0-9]+";

    /**
     * The one PDF/A repair, used when a loaded document is repaired, for its preview, and when an invoice is
     * attached. It repairs what veraPDF reports and can be repaired without losing data:
     * <ul>
     * <li>{@link #fixPdfaIssues}: content, output intent, declaration, fonts</li>
     * <li>a document that declares level a without a structure gets the formal one of {@link PdfTagger}</li>
     * <li>embedded files in PDF/A-1 and -2: only with the option {@code upgradeToPdfa3} the document is declared
     * PDF/A-3 and the files become associated files; without it the standard is not changed</li>
     * <li>an invalid XMP extension schema is removed</li>
     * <li>the document information and the XMP metadata are made equal (only if they differ)</li>
     * <li>finally the metadata fixer of veraPDF</li>
     * </ul>
     * The input is not changed, so a failure leaves nothing half repaired.
     *
     * @param pdf The complete PDF.
     * @throws FontEmbedException If a font can't be embedded.
     */
    public static Repair repairPdfa(byte[] pdf) throws IOException {
        return repairPdfa(pdf, false);
    }

    /**
     * Like {@link #repairPdfa(byte[])}, and can switch the standard.
     *
     * @param upgradeToPdfa3 True to declare PDF/A-3 for a PDF/A-1 or PDF/A-2 document with attachments. That changes the
     *                       standard of the document, so the caller asks the user first. False keeps the standard, the result then
     *                       tells in {@link Repair#upgradeOffered()} that the switch would be possible.
     */
    public static Repair repairPdfa(byte[] pdf, boolean upgradeToPdfa3) throws IOException {
        boolean upgradeOffered = false;
        Map<String, Integer> changes;
        byte[] result = pdf;
        boolean pdfa;
        boolean brokenSchema = false;
        boolean differingInfo = false;
        try (PDDocument probe = Loader.loadPDF(pdf)) {
            if (pdfaPart(probe) > 0) {
                var v = PdfaValidator.validate(pdf);
                boolean notConform = v.getState() == ValidationResult.State.NOT_CONFORM;
                brokenSchema = notConform && v.getFindings().stream()
                        .anyMatch(f -> f.rule().matches(BROKEN_SCHEMA_RULE));
                differingInfo = notConform && v.getFindings().stream()
                        .anyMatch(f -> f.rule().matches(DIFFERING_INFO_RULE));
            }
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            changes = fixPdfaIssues(document);
            if (needsFormalTags(document, profile(document)))
                // The document declares level a: add the formal structure, see PdfTagger.
                changes.put(I18n.t("fix.formalTags"), PdfTagger.tagPages(document));
            int part = pdfaPart(document);
            if (hasAttachmentsBeforePdfa3(document)) {
                if (upgradeToPdfa3) {
                    // Embedded files are forbidden before PDF/A-3: declare PDF/A-3 instead of removing them.
                    changes.putAll(convertTo(document, PdfStandardProfile.PDFA_3B));
                    part = 3;
                } else {
                    upgradeOffered = true;
                }
            }
            if (part == 3) {
                int associated = associateEmbeddedFiles(document);
                if (associated > 0)
                    changes.put(I18n.t("fix.associatedFiles"), associated);
            }
            if (part >= 1 && part <= 3) {
                if (brokenSchema) {
                    String xmp = xmp(document);
                    String stripped = xmp.replaceAll("(?s)<pdfaExtension:schemas>.*?</pdfaExtension:schemas>", "");
                    if (!stripped.equals(xmp)) {
                        writeXmp(document, stripped);
                        changes.put(I18n.t("fix.extensionSchema"), 1);
                    }
                }
                // The document information and the metadata must be equal.
                if (differingInfo && DocumentInformation.copy(document, document))
                    changes.put(I18n.t("fix.syncInfo"), 1);
            }
            pdfa = part > 0;
            if (!changes.isEmpty()) {
                var out = new java.io.ByteArrayOutputStream(pdf.length + 1024);
                // PDF/A-1 forbids xref and object streams.
                if (part == 1)
                    document.save(out, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
                else
                    document.save(out);
                result = out.toByteArray();
            }
        }
        if (pdfa) {
            PdfaValidator.MetadataFix fix = PdfaValidator.fixMetadata(result);
            Log.info("veraPDF metadata fixer: %s %s", fix.status(), fix.fixes());
            if (fix.pdf() != null) {
                result = fix.pdf();
                fix.fixes().forEach(f -> changes.merge("veraPDF: " + f, 1, Integer::sum));
                if (fix.fixes().isEmpty())
                    changes.merge(I18n.t("ui.fixer.fixed"), 1, Integer::sum);
            }
        }
        return new Repair(changes.isEmpty() ? null : result, changes, upgradeOffered);
    }

    /**
     * PDF/A-3 demands that each embedded file is an associated file: listed in /AF of the catalog and with an
     * /AFRelationship. Files without a relationship get "Unspecified".
     *
     * @return The number of files that were changed.
     */
    private static int associateEmbeddedFiles(PDDocument document) throws IOException {
        var names = document.getDocumentCatalog().getNames();
        if (names == null || names.getEmbeddedFiles() == null)
            return 0;
        var tree = names.getEmbeddedFiles();
        Map<String, org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification> files =
                new LinkedHashMap<>();
        collectFiles(tree, files, 0);
        var catalog = document.getDocumentCatalog().getCOSObject();
        COSArray af = catalog.getCOSArray(COSName.getPDFName("AF"));
        if (af == null)
            af = new COSArray();
        int changed = 0;
        for (var spec : files.values()) {
            COSDictionary dict = spec.getCOSObject();
            boolean fixed = false;
            if (dict.getNameAsString("AFRelationship") == null) {
                dict.setName("AFRelationship", "Unspecified");
                fixed = true;
            }
            // PDF/A-3 demands the keys F and UF and the MIME type of the embedded file.
            String name = dict.getString(COSName.F);
            if (dict.getString(COSName.UF) == null && name != null) {
                dict.setString(COSName.UF, name);
                fixed = true;
            }
            if (dict.getDictionaryObject(COSName.EF) instanceof COSDictionary ef
                    && ef.getDictionaryObject(COSName.F) instanceof org.apache.pdfbox.cos.COSStream stream
                    && stream.getNameAsString(COSName.SUBTYPE) == null) {
                stream.setName(COSName.SUBTYPE, "application/octet-stream");
                fixed = true;
            }
            boolean listed = false;
            for (int i = 0; i < af.size(); ++i)
                if (af.getObject(i) == dict)
                    listed = true;
            if (!listed) {
                af.add(dict);
                fixed = true;
            }
            if (fixed)
                ++changed;
        }
        if (changed > 0)
            catalog.setItem(COSName.getPDFName("AF"), af);
        return changed;
    }

    private static void collectFiles(org.apache.pdfbox.pdmodel.common.PDNameTreeNode<
            org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification> node,
                                     Map<String, org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification> out,
                                     int depth) throws IOException {
        if (node == null || depth > 20)
            return;
        var names = node.getNames();
        if (names != null)
            out.putAll(names);
        var kids = node.getKids();
        if (kids != null)
            for (var kid : kids)
                collectFiles(kid, out, depth + 1);
    }

    /**
     * Gets the standard the document declares in its metadata. If it declares several, the PDF/A level wins.
     *
     * @return The standard, null if the document declares none or one this tool has no profile for.
     */
    public static PdfStandardProfile profile(PDDocument document) {
        String xmp = xmp(document);
        Matcher m = PDFA_PART.matcher(xmp);
        if (m.find()) {
            Matcher c = PDFA_CONFORMANCE.matcher(xmp);
            return PdfStandardProfile.ofPdfa(Integer.parseInt(m.group(1)), c.find() ? c.group(1) : null).orElse(null);
        }
        m = PDFX.matcher(xmp);
        if (m.find()) {
            String v = m.group(1).toLowerCase(Locale.ROOT);
            if (v.contains("x-1"))
                return PdfStandardProfile.PDFX_1A;
            if (v.contains("x-3"))
                return PdfStandardProfile.PDFX_3;
            if (v.contains("x-4"))
                return PdfStandardProfile.PDFX_4;
            return null;
        }
        if (PDFUA.matcher(xmp).find())
            return PdfStandardProfile.PDFUA_1;
        if (PDFE.matcher(xmp).find())
            return PdfStandardProfile.PDFE_1;
        if (PDFVT.matcher(xmp).find())
            return PdfStandardProfile.PDFVT_1;
        return null;
    }

    /**
     * Converts the document to a standard, in place.<br>
     * A PDF/A level is declared (an existing declaration is changed), then everything is fixed that PDF/A forbids,
     * see {@link #fixPdfaIssues}. What the level doesn't allow is removed: embedded files and the invoice for the
     * levels that don't allow them, the page transparency groups for PDF/A-1 (if no transparency is used).
     * Transparency itself can't be removed, PDF/A-1 documents which use it stay not conform. The conformance
     * levels a (tagged) and u (Unicode) are only declared: the tool can't create tags or Unicode tables.<br>
     * The other standards are declared in the metadata and repaired only as far as that is simple, see
     * {@link #convertToOther}. They can't be verified.
     *
     * @param target The standard, null to remove all declarations.
     * @return What was changed and how often, empty if the document was not changed.
     * @throws FontEmbedException If a font can't be embedded.
     */
    public static Map<String, Integer> convertTo(PDDocument document, PdfStandardProfile target)
            throws IOException {
        return convertTo(document, target, null);
    }

    /**
     * Like {@link #convertTo(PDDocument, PdfStandardProfile)}, and sets the PDF version afterwards.
     * PDF/A-4 needs PDF 2.0, a lower version is ignored for it.
     *
     * @param version The PDF version (e.g. 1.7f), null to keep the version.
     */
    public static Map<String, Integer> convertTo(PDDocument document, PdfStandardProfile target, Float version)
            throws IOException {
        Map<String, Integer> changes = convertToStandard(document, target);
        boolean needs20 = target != null && target.isPdfa() && target.part() == 4;
        if (version != null && !(needs20 && version < 2.0f) && setVersion(document, version))
            changes.put(I18n.t("fix.version", String.format(Locale.ROOT, "%.1f", version)), 1);
        return changes;
    }

    /**
     * Sets the PDF version of the file header, also if it is lowered. A version in the catalog is removed.
     *
     * @return True if the version was changed.
     */
    public static boolean setVersion(PDDocument document, float version) {
        if (Math.abs(document.getVersion() - version) < 0.01f)
            return false;
        document.getDocument().setVersion(version);
        document.getDocumentCatalog().getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.VERSION);
        return true;
    }

    private static Map<String, Integer> convertToStandard(PDDocument document, PdfStandardProfile target)
            throws IOException {
        if (target == null)
            return removeDeclarations(document, null);
        if (!target.isPdfa())
            return convertToOther(document, target);
        Map<String, Integer> changes = new LinkedHashMap<>();
        PdfStandardProfile current = profile(document);
        final boolean converting = current != target;
        if (!target.embeddedFilesAllowed() && converting) {
            if (EInvoice.removeXml(document))
                changes.put(I18n.t("fix.removedInvoice"), 1);
            int files = removeEmbeddedFiles(document);
            if (files > 0)
                changes.put(I18n.t("fix.removedFiles"), files);
        }
        if (converting) {
            changes.putAll(removeDeclarations(document, PdfStandardProfile.Family.PDFA));
            if (pdfaPart(document) == 0)
                MustangPdfaConverter.convert(document);
            if (profile(document) != target)
                declare(document, target);
            changes.put(I18n.t("fix.declared", target), 1);
        }
        final boolean part1 = target.part() == 1;
        if (part1 && converting && DocumentInformation.copy(document, document))
            // PDF/A-1 demands that the document information equals the metadata exactly.
            changes.put(I18n.t("fix.syncInfo"), 1);
        if (part1 && !usesTransparency(document)) {
            int groups = removePageGroups(document);
            if (groups > 0)
                changes.put(I18n.t("fix.removedTransparency"), groups);
        }
        if (target.part() == 4 && document.getVersion() < 2.0f) {
            document.setVersion(2.0f);
            // PDFBox writes the version into the catalog, PDF/A-4 demands it in the file header.
            document.getDocument().setVersion(2.0f);
            changes.put(I18n.t("fix.pdf20"), 1);
        }
        if (target.part() == 4 && converting) {
            // The metadata carries the information, the document information dictionary is forbidden.
            DocumentInformation.copy(document, document);
            DocumentInformation.removeInfo(document);
            changes.put(I18n.t("fix.movedInfo"), 1);
        }
        if (needsFormalTags(document, target)) {
            // Level a: a formal structure, see PdfTagger. A document with tags is not touched.
            changes.put(I18n.t("fix.formalTags"), PdfTagger.tagPages(document));
        }
        // The same fixes as the repair of a loaded document.
        changes.putAll(fixPdfaIssues(document));
        return changes;
    }

    /**
     * Checks if the target is a PDF/A level a and the document has no structure, so that the formal structure of
     * {@link PdfTagger} is added.
     */
    public static boolean needsFormalTags(PDDocument document, PdfStandardProfile target) {
        return target != null && target.isPdfa() && "A".equals(target.conformance()) && !PdfTagger.isTagged(document);
    }

    private static final String XMP_EMPTY = "<?xpacket begin=\"?\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

    /**
     * Converts the document to PDF/X, PDF/UA, PDF/E or PDF/VT: the standard is declared and replaces the other
     * declarations. For PDF/X the print preparation is started (information, trim box, output intent, fonts and
     * forbidden entries), for PDF/UA the basics (language, title display, tab order). That is not complete and
     * the result can't be verified.
     */
    static Map<String, Integer> convertToOther(PDDocument document, PdfStandardProfile target)
            throws IOException {
        Map<String, Integer> changes = new LinkedHashMap<>();
        if (profile(document) == target)
            return changes;
        changes.putAll(removeDeclarations(document, target.family()));
        String xmp = xmp(document);
        if (xmp.isEmpty())
            xmp = XMP_EMPTY;
        switch (target.family()) {
            case PDFX -> {
                String version = switch (target) {
                    case PDFX_1A -> "PDF/X-1a:2001";
                    case PDFX_3 -> "PDF/X-3:2002";
                    default -> "PDF/X-4";
                };
                xmp = addXmpProperty(xmp, "pdfxid", "http://www.npes.org/pdfx/ns/id/", "GTS_PDFXVersion", version);
                var info = document.getDocumentInformation();
                info.setCustomMetadataValue("GTS_PDFXVersion", version);
                if (info.getTrapped() == null)
                    info.setTrapped("False");
                changes.put(I18n.t("fix.declared", target), 1);
                int boxes = 0;
                for (var page : document.getPages())
                    if (page.getCOSObject().getDictionaryObject(COSName.getPDFName("TrimBox")) == null
                            && page.getCOSObject().getDictionaryObject(COSName.getPDFName("ArtBox")) == null) {
                        page.setTrimBox(page.getMediaBox());
                        ++boxes;
                    }
                if (boxes > 0)
                    changes.put(I18n.t("fix.trimBox"), boxes);
                writeXmp(document, xmp);
                if (target != PdfStandardProfile.PDFX_1A && addSrgbOutputIntent(document))
                    changes.put(I18n.t("fix.srgb"), 1);
                changes.putAll(PdfaFixer.fix(document));
            }
            case PDFUA -> {
                xmp = addXmpProperty(xmp, "pdfuaid", "http://www.aiim.org/pdfua/ns/id/", "part", "1");
                writeXmp(document, xmp);
                changes.put(I18n.t("fix.declared", target), 1);
                changes.putAll(fixUniversalAccess(document));
            }
            case PDFE -> {
                writeXmp(document, addXmpProperty(xmp, "pdfe", "http://www.aiim.org/pdfe/ns/id/", "ISO_PDFEVersion",
                        "PDF/E-1"));
                changes.put(I18n.t("fix.declared", target), 1);
            }
            default -> {
                writeXmp(document, addXmpProperty(xmp, "pdfvtid", "http://www.npes.org/pdfvt/ns/id/",
                        "GTS_PDFVTVersion", "PDF/VT-1"));
                changes.put(I18n.t("fix.declared", target), 1);
            }
        }
        changes.forEach((what, times) -> Log.info("%s: %s (%d)", target, what, times));
        return changes;
    }

    /**
     * The simple parts of PDF/UA: document language, the title shown in the window title, the tab order along the
     * structure for pages with annotations. The document is marked as tagged only if it has a structure.
     */
    private static Map<String, Integer> fixUniversalAccess(PDDocument document) {
        Map<String, Integer> changes = new LinkedHashMap<>();
        var catalog = document.getDocumentCatalog();
        if (catalog.getLanguage() == null || catalog.getLanguage().isBlank()) {
            catalog.setLanguage(Locale.getDefault().toLanguageTag());
            changes.put(I18n.t("fix.language"), 1);
        }
        var prefs = catalog.getViewerPreferences();
        if (prefs == null || !prefs.displayDocTitle()) {
            if (prefs == null)
                prefs = new PDViewerPreferences(new COSDictionary());
            prefs.setDisplayDocTitle(true);
            catalog.setViewerPreferences(prefs);
            changes.put(I18n.t("fix.titleDisplay"), 1);
        }
        if (catalog.getStructureTreeRoot() != null
                && (catalog.getMarkInfo() == null || !catalog.getMarkInfo().isMarked())) {
            var mark = new PDMarkInfo();
            mark.setMarked(true);
            catalog.setMarkInfo(mark);
            changes.put(I18n.t("fix.markedTagged"), 1);
        }
        int tabs = 0;
        for (var page : document.getPages())
            if (page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray a && a.size() > 0
                    && page.getCOSObject().getDictionaryObject(COSName.getPDFName("Tabs")) == null) {
                page.getCOSObject().setName(COSName.getPDFName("Tabs"), "S");
                ++tabs;
            }
        if (tabs > 0)
            changes.put(I18n.t("fix.tabOrder"), tabs);
        if (DocumentInformation.copy(document, document))
            // PDF/UA needs the title in the metadata.
            changes.put(I18n.t("fix.syncInfo"), 1);
        return changes;
    }

    /**
     * Removes the declarations of the standards from the metadata, except the ones of a family.
     *
     * @param keep The family to keep, null to remove all.
     * @return What was removed.
     */
    static Map<String, Integer> removeDeclarations(PDDocument document, PdfStandardProfile.Family keep)
            throws IOException {
        Map<String, Integer> changes = new LinkedHashMap<>();
        String xmp = xmp(document);
        String result = xmp;
        if (keep != PdfStandardProfile.Family.PDFA && pdfaPart(document) > 0) {
            for (String name : new String[]{"part", "conformance", "rev", "amd"})
                result = removePdfaid(result, name);
            changes.put(I18n.t("fix.removedPdfa"), 1);
        }
        if (keep != PdfStandardProfile.Family.PDFX && PDFX.matcher(xmp).find()) {
            result = removeXmpProperty(result, "pdfxid", "GTS_PDFXVersion");
            changes.put(I18n.t("fix.removedPdfx"), 1);
        }
        if (keep != PdfStandardProfile.Family.PDFUA && PDFUA.matcher(xmp).find()) {
            result = removeXmpProperty(result, "pdfuaid", "part");
            changes.put(I18n.t("fix.removedPdfua"), 1);
        }
        if (keep != PdfStandardProfile.Family.PDFE && PDFE.matcher(xmp).find()) {
            result = removeXmpProperty(result, "pdfe", "ISO_PDFEVersion");
            changes.put(I18n.t("fix.removedPdfe"), 1);
        }
        if (keep != PdfStandardProfile.Family.PDFVT && PDFVT.matcher(xmp).find()) {
            result = removeXmpProperty(result, "pdfvtid", "GTS_PDFVTVersion");
            changes.put(I18n.t("fix.removedPdfvt"), 1);
        }
        if (!changes.isEmpty())
            writeXmp(document, result);
        return changes;
    }

    /**
     * Adds a property in its own description to the XMP, replacing the property if it exists.
     */
    static String addXmpProperty(String xmp, String prefix, String namespace, String name, String value) {
        prefix = prefixOf(xmp, namespace, prefix);
        String cleaned = removeXmpProperty(xmp, prefix, name);
        int end = cleaned.lastIndexOf("</rdf:RDF>");
        if (end < 0)
            return cleaned;
        return cleaned.substring(0, end) + "<rdf:Description rdf:about=\"\" xmlns:" + prefix + "=\"" + namespace
                + "\"><" + prefix + ":" + name + ">" + value + "</" + prefix + ":" + name + "></rdf:Description>"
                + cleaned.substring(end);
    }

    /**
     * Removes the PDF/A declaration from the metadata. The content is not changed.
     *
     * @return What was changed, empty if the document declared no PDF/A.
     */
    public static Map<String, Integer> removePdfa(PDDocument document) throws IOException {
        Map<String, Integer> changes = new LinkedHashMap<>();
        if (pdfaPart(document) == 0)
            return changes;
        String xmp = xmp(document);
        for (String name : new String[]{"part", "conformance", "rev", "amd"})
            xmp = removePdfaid(xmp, name);
        writeXmp(document, xmp);
        changes.put(I18n.t("fix.removedPdfa"), 1);
        return changes;
    }

    /**
     * Shows what converting to a level would change, without touching the document.
     *
     * @param pdf    The complete PDF.
     * @param target The level, null to remove the declaration.
     * @return What would be changed and how often.
     * @throws FontEmbedException If a font can't be embedded.
     */
    public static Map<String, Integer> preview(byte[] pdf, PdfStandardProfile target) throws IOException {
        try (PDDocument copy = Loader.loadPDF(pdf)) {
            return convertTo(copy, target);
        }
    }

    /**
     * What a conversion removes for good and what it can't repair, to show before converting.
     *
     * @param lost        Data that is removed.
     * @param notRepaired Things that make the result not conform.
     * @param info        Other consequences, e.g. settings the program changes.
     */
    public record Impact(List<String> lost, List<String> notRepaired, List<String> info) {
    }

    /**
     * Describes the consequences of a conversion that don't depend on a trial run, see {@link #preview}.
     *
     * @param target The level, null to remove the declaration.
     */
    public static Impact impact(PDDocument document, PdfStandardProfile target) {
        List<String> lost = new ArrayList<>();
        List<String> notRepaired = new ArrayList<>();
        List<String> info = new ArrayList<>();
        PdfStandardProfile current = profile(document);
        if (target == current)
            return new Impact(lost, notRepaired, info);
        boolean invoice = EInvoice.hasInvoice(document);
        if (current != null && current.isPdfa() && (target == null || !target.isPdfa())) {
            if (invoice)
                lost.add(I18n.t("impact.pdfaRemovedInvoice"));
            else
                lost.add(I18n.t("impact.pdfaRemoved"));
        }
        if (target == null)
            return new Impact(lost, notRepaired, info);
        if (current != null && !current.isPdfa() && current.family() != target.family())
            lost.add(I18n.t("impact.declRemoved", current));
        if (!target.embeddedFilesAllowed() && target.isPdfa()) {
            if (invoice)
                lost.add(I18n.t("impact.invoiceRemoved"));
            int files = countEmbeddedFiles(document) - (invoice ? 1 : 0);
            if (files > 0)
                lost.add(I18n.t("impact.filesRemoved", files));
        }
        if (target.isPdfa() && document.isEncrypted())
            lost.add(I18n.t("impact.passwordRemoved"));
        boolean tagged = PdfTagger.isTagged(document);
        if (needsFormalTags(document, target))
            notRepaired.add(I18n.t("impact.levelA"));
        if (target.family() == PdfStandardProfile.Family.PDFUA && !tagged)
            notRepaired.add(I18n.t("impact.ua"));
        if (target == PdfStandardProfile.PDFA_4F && countEmbeddedFiles(document) == 0)
            notRepaired.add(I18n.t("impact.pdfa4f"));
        if (target.isPdfa() && "U".equals(target.conformance()))
            info.add(I18n.t("impact.levelU"));
        if (target.isPdfa() && target.part() == 1 && usesTransparency(document))
            notRepaired.add(I18n.t("impact.transparency"));
        if (!target.allowsCompression())
            info.add(I18n.t("impact.compression", target));
        if (target.family() == PdfStandardProfile.Family.PDFX)
            info.add(I18n.t(target == PdfStandardProfile.PDFX_1A ? "impact.print.1a" : "impact.print"));
        if (target.family() == PdfStandardProfile.Family.PDFE || target.family() == PdfStandardProfile.Family.PDFVT)
            info.add(I18n.t("impact.declOnly"));
        if (target.notVerifiableReason() != null)
            info.add(target.notVerifiableReason());
        return new Impact(lost, notRepaired, info);
    }

    private static final COSName AF = COSName.getPDFName("AF");
    private static final COSName EMBEDDED_FILES = COSName.getPDFName("EmbeddedFiles");
    private static final COSName CA_FILL = COSName.getPDFName("ca");

    private static int countEmbeddedFiles(PDDocument document) {
        COSBase names = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.NAMES);
        if (!(names instanceof COSDictionary d) || !(d.getDictionaryObject(EMBEDDED_FILES) instanceof COSDictionary tree))
            return 0;
        return countTree(tree, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static int countTree(COSDictionary node, Set<COSDictionary> seen) {
        if (!seen.add(node))
            return 0;
        int n = 0;
        if (node.getDictionaryObject(COSName.NAMES) instanceof COSArray a)
            n += a.size() / 2;
        if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids)
            for (int i = 0; i < kids.size(); ++i)
                if (kids.getObject(i) instanceof COSDictionary kid)
                    n += countTree(kid, seen);
        return n;
    }

    /**
     * Removes all embedded files: the name tree, the associated files and file attachment annotations.
     *
     * @return The number of removed embedded files.
     */
    static int removeEmbeddedFiles(PDDocument document) {
        int n = countEmbeddedFiles(document);
        COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
        if (catalog.getDictionaryObject(COSName.NAMES) instanceof COSDictionary names)
            names.removeItem(EMBEDDED_FILES);
        catalog.removeItem(AF);
        for (var page : document.getPages()) {
            COSDictionary pageDict = page.getCOSObject();
            pageDict.removeItem(AF);
            if (pageDict.getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots)
                for (int i = annots.size() - 1; i >= 0; --i)
                    if (annots.getObject(i) instanceof COSDictionary annot
                            && COSName.getPDFName("FileAttachment").equals(annot.getCOSName(COSName.SUBTYPE))) {
                        annots.remove(i);
                        ++n;
                    }
        }
        return n;
    }

    /**
     * Removes the transparency group of the pages. Only allowed (PDF/A-1) when no transparency is used.
     */
    private static int removePageGroups(PDDocument document) {
        int n = 0;
        for (var page : document.getPages())
            if (page.getCOSObject().containsKey(COSName.GROUP)) {
                page.getCOSObject().removeItem(COSName.GROUP);
                ++n;
            }
        return n;
    }

    /**
     * Checks if the document really uses transparency: soft masks, alpha constants below 1 or blend modes.
     */
    static boolean usesTransparency(PDDocument document) {
        Deque<COSBase> stack = new ArrayDeque<>();
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        stack.push(document.getDocument().getTrailer());
        while (!stack.isEmpty()) {
            COSBase base = stack.pop();
            if (base instanceof COSObject o)
                base = o.getObject();
            if (base == null || !visited.add(base))
                continue;
            if (base instanceof COSDictionary d) {
                COSBase mask = d.getDictionaryObject(COSName.SMASK);
                if (mask != null && !(mask instanceof COSName n && n.getName().equals("None")))
                    return true;
                if (d.getDictionaryObject(COSName.CA) instanceof COSNumber n && n.floatValue() < 1)
                    return true;
                if (d.getDictionaryObject(CA_FILL) instanceof COSNumber n && n.floatValue() < 1)
                    return true;
                if (d.getDictionaryObject(COSName.BM) instanceof COSName n
                        && !n.getName().equals("Normal") && !n.getName().equals("Compatible"))
                    return true;
                d.getValues().forEach(stack::push);
            } else if (base instanceof COSArray a) {
                for (int i = 0; i < a.size(); ++i)
                    stack.push(a.get(i));
            }
        }
        return false;
    }

    /**
     * Changes the PDF/A identification of the metadata to the level. The document has to declare a PDF/A part.
     */
    private static void declare(PDDocument document, PdfStandardProfile target) throws IOException {
        String xmp = setPdfaid(xmp(document), "part", Integer.toString(target.part()));
        xmp = target.conformance() == null ? removePdfaid(xmp, "conformance")
                : setPdfaid(xmp, "conformance", target.conformance());
        xmp = target.part() == 4 ? setPdfaid(xmp, "rev", "2020") : removePdfaid(xmp, "rev");
        writeXmp(document, xmp);
    }

    private static void writeXmp(PDDocument document, String xmp) throws IOException {
        PDMetadata metadata = document.getDocumentCatalog().getMetadata();
        if (metadata == null) {
            metadata = new PDMetadata(document);
            document.getDocumentCatalog().setMetadata(metadata);
        }
        metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sets a value of the PDF/A identification, written as element or attribute. Adds it beside the part if missing.
     */
    static String setPdfaid(String xmp, String name, String value) {
        final String p = prefixOf(xmp, "pdfaid");
        Matcher m = Pattern.compile("(<" + p + ":" + name + ">)[^<]*(</" + p + ":" + name + ">)").matcher(xmp);
        if (m.find())
            return xmp.substring(0, m.start()) + m.group(1) + value + m.group(2) + xmp.substring(m.end());
        m = Pattern.compile("(" + p + ":" + name + "=[\"'])[^\"']*([\"'])").matcher(xmp);
        if (m.find())
            return xmp.substring(0, m.start()) + m.group(1) + value + m.group(2) + xmp.substring(m.end());
        m = Pattern.compile("</" + p + ":part>").matcher(xmp);
        if (m.find())
            return xmp.substring(0, m.end()) + "<" + p + ":" + name + ">" + value + "</" + p + ":" + name + ">"
                    + xmp.substring(m.end());
        m = Pattern.compile(p + ":part=[\"'][^\"']*[\"']").matcher(xmp);
        if (m.find())
            return xmp.substring(0, m.end()) + " " + p + ":" + name + "=\"" + value + "\"" + xmp.substring(m.end());
        return xmp;
    }

    /**
     * Removes a value of the PDF/A identification, written as element or attribute.
     */
    static String removePdfaid(String xmp, String name) {
        return removeXmpProperty(xmp, "pdfaid", name);
    }

    /**
     * Removes a property, written as element or attribute.
     */
    static String removeXmpProperty(String xmp, String prefix, String name) {
        String qualified = Pattern.quote(prefixOf(xmp, prefix)) + ":" + name;
        return xmp.replaceAll("\\s*<" + qualified + ">[^<]*</" + qualified + ">", "")
                .replaceAll("\\s+" + qualified + "=([\"'])[^\"']*\\1", "");
    }

    /**
     * Declares PDF/A-3b if the document declares no PDF/A conformance at all, using the preparation of Mustang
     * (see {@link MustangPdfaConverter}).<br>
     * Without it validators treat the document as PDF/A-1b and complain about things that are fine for PDF/A-3.
     * Existing metadata is kept.
     *
     * @return True if the document was converted.
     */
    public static boolean addPdfa3Identification(PDDocument document) throws IOException {
        if (pdfaPart(document) > 0)
            return false;
        MustangPdfaConverter.convert(document);
        return true;
    }

    /**
     * Adds an sRGB output intent if the document has no output intent at all.<br>
     * PDF/A needs an RGB output intent for device RGB colors (e.g. images rendered by this tool). A document with
     * another output intent (e.g. CMYK) is not changed: PDF/A allows only one output profile, and replacing it
     * would change the meaning of device colors.
     *
     * @return True if the output intent was added.
     */
    public static boolean addSrgbOutputIntent(PDDocument document) throws IOException {
        if (!document.getDocumentCatalog().getOutputIntents().isEmpty())
            return false;
        try (InputStream icc = openSrgbProfile()) {
            PDOutputIntent intent = new PDOutputIntent(document, icc);
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputCondition("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            document.getDocumentCatalog().addOutputIntent(intent);
        }
        return true;
    }

    /**
     * The sRGB profile of Mustang, or the one of Java if Mustang doesn't contain it (it is no API of Mustang).
     */
    static InputStream openSrgbProfile() {
        InputStream is = ZUGFeRDExporterFromA3.class.getClassLoader()
                .getResourceAsStream(MUSTANG_SRGB_PROFILE);
        if (is != null)
            return is;
        Log.debug("Mustang sRGB profile not found, using the one of Java.");
        return new ByteArrayInputStream(
                java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
    }

    private static String xmp(PDDocument document) {
        try {
            PDMetadata metadata = document.getDocumentCatalog().getMetadata();
            return metadata == null ? "" : new String(metadata.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Failed to read metadata: %s", e.getMessage());
            return "";
        }
    }
}

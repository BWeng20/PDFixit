package com.bw.pdfixit;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The standards a document can be converted to, see {@link PdfStandard#convertTo}.<br>
 * The PDF/A levels are repaired and verified with veraPDF. The other standards are declared in the metadata, the
 * content is repaired only as far as that is simple, and the result can't be verified by this tool: the
 * conversion is not complete yet.
 */
public enum PdfStandardProfile {
    PDFA_1A(Family.PDFA, 1, "A", "PDF/A-1a", false, "standard.desc.PDFA_1A"),
    PDFA_1B(Family.PDFA, 1, "B", "PDF/A-1b", false, "standard.desc.PDFA_1B"),
    PDFA_2A(Family.PDFA, 2, "A", "PDF/A-2a", false, "standard.desc.PDFA_2A"),
    PDFA_2B(Family.PDFA, 2, "B", "PDF/A-2b", false, "standard.desc.PDFA_2B"),
    PDFA_2U(Family.PDFA, 2, "U", "PDF/A-2u", false, "standard.desc.PDFA_2U"),
    PDFA_3A(Family.PDFA, 3, "A", "PDF/A-3a", true, "standard.desc.PDFA_3A"),
    PDFA_3B(Family.PDFA, 3, "B", "PDF/A-3b", true, "standard.desc.PDFA_3B"),
    PDFA_3U(Family.PDFA, 3, "U", "PDF/A-3u", true, "standard.desc.PDFA_3U"),
    PDFA_4(Family.PDFA, 4, null, "PDF/A-4", false, "standard.desc.PDFA_4"),
    PDFA_4E(Family.PDFA, 4, "E", "PDF/A-4e", false, "standard.desc.PDFA_4E"),
    PDFA_4F(Family.PDFA, 4, "F", "PDF/A-4f", true, "standard.desc.PDFA_4F"),

    PDFX_1A(Family.PDFX, 1, null, "PDF/X-1a", false, "standard.desc.PDFX_1A"),
    PDFX_3(Family.PDFX, 3, null, "PDF/X-3", false, "standard.desc.PDFX_3"),
    PDFX_4(Family.PDFX, 4, null, "PDF/X-4", false, "standard.desc.PDFX_4"),
    PDFUA_1(Family.PDFUA, 1, null, "PDF/UA-1", false, "standard.desc.PDFUA_1"),
    PDFE_1(Family.PDFE, 1, null, "PDF/E-1", false, "standard.desc.PDFE_1"),
    PDFVT_1(Family.PDFVT, 1, null, "PDF/VT-1", false, "standard.desc.PDFVT_1");

    /**
     * The family of standards.
     */
    public enum Family {
        PDFA, PDFX, PDFUA, PDFE, PDFVT
    }

    private final Family family;
    private final int part;
    private final String conformance;
    private final String label;
    private final boolean embeddedFilesAllowed;
    private final String description;

    PdfStandardProfile(Family family, int part, String conformance, String label, boolean embeddedFilesAllowed,
                       String description) {
        this.family = family;
        this.part = part;
        this.conformance = conformance;
        this.label = label;
        this.embeddedFilesAllowed = embeddedFilesAllowed;
        this.description = description;
    }

    public Family family() {
        return family;
    }

    public boolean isPdfa() {
        return family == Family.PDFA;
    }

    /**
     * @return False if the standard is based on a PDF version without compressed object and xref streams
     * (PDF/A-1, PDF/X-1a, PDF/X-3).
     */
    public boolean allowsCompression() {
        return !(isPdfa() && part == 1) && this != PDFX_1A && this != PDFX_3;
    }

    /**
     * @return The part of the standard (for PDF/A the number after "PDF/A-").
     */
    public int part() {
        return part;
    }

    /**
     * @return The conformance level letter (A, B, U, E, F), null if the standard has none.
     */
    public String conformance() {
        return conformance;
    }

    public boolean embeddedFilesAllowed() {
        return embeddedFilesAllowed;
    }

    public String description() {
        return I18n.t(description);
    }

    /**
     * Checks if the result can be verified: only the PDF/A levels can, this tool checks them with veraPDF.
     * veraPDF doesn't know PDF/X, PDF/E and PDF/VT, and this tool doesn't check PDF/UA.
     */
    public boolean verifiable() {
        return family == Family.PDFA;
    }

    /**
     * @return The reason the result can't be verified, null if it can be.
     */
    public String notVerifiableReason() {
        return switch (family) {
            case PDFA -> null;
            case PDFUA -> I18n.t("standard.notVerified.ua");
            default -> I18n.t("standard.notVerified.other", label.substring(0, label.indexOf('-')));
        };
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * @return The PDF/A levels.
     */
    public static List<PdfStandardProfile> pdfaProfiles() {
        return Arrays.stream(values()).filter(PdfStandardProfile::isPdfa).toList();
    }

    /**
     * Finds the PDF/A level.
     *
     * @param part        The part, 1 to 4.
     * @param conformance The conformance level, null or empty for none (PDF/A-4). Missing in the PDF/A-1 to 3 means B.
     * @return The level, empty if there is none.
     */
    public static Optional<PdfStandardProfile> ofPdfa(int part, String conformance) {
        String c = conformance == null || conformance.isBlank() ? null : conformance.toUpperCase(java.util.Locale.ROOT);
        if (c == null && part < 4)
            c = "B";
        final String level = c;
        return pdfaProfiles().stream().filter(p -> p.part == part && java.util.Objects.equals(p.conformance, level))
                .findFirst();
    }
}

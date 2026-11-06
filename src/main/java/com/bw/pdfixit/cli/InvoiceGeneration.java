package com.bw.pdfixit.cli;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.SafeFile;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.template.InvoiceRenderer;
import com.bw.pdfixit.template.Layout;
import com.bw.pdfixit.template.LayoutFiles;
import com.bw.pdfixit.template.Problem;
import com.bw.pdfixit.template.TemplateCheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * The CLI command "generate an invoice": renders an invoice XML on a template PDF with its layout, attaches the XML
 * and writes the result. Never asks, never changes the template or the layout file.
 */
public final class InvoiceGeneration {

    /**
     * Exit code: invalid arguments of the command (missing template, unknown value, combination with other options).
     */
    public static final int EXIT_ARGUMENTS = 17;
    /**
     * Exit code: the invoice XML can't be read, or the invoice data is incomplete.
     */
    public static final int EXIT_DATA = 18;
    /**
     * Exit code: the template, the layout or the engine reported errors. No output is written.
     */
    public static final int EXIT_TEMPLATE = 19;
    /**
     * Exit code: the invoice needs more pages than the template has and the source of the following pages is not
     * known (see {@code --continuation}).
     */
    public static final int EXIT_CONTINUATION = 20;

    private InvoiceGeneration() {
    }

    /**
     * Parses the level letter.
     *
     * @param letter a, b or u (any case); null for the level of the setting.
     * @return {@link PdfStandardProfile#PDFA_3A}, {@link PdfStandardProfile#PDFA_3B} or
     * {@link PdfStandardProfile#PDFA_3U}.
     * @throws IllegalArgumentException If it is something else.
     */
    public static PdfStandardProfile parseLevel(String letter) {
        if (letter == null)
            return Preferences.getInstance().getInvoicePdfaLevel();
        return switch (letter.trim().toLowerCase(Locale.ROOT)) {
            case "a" -> PdfStandardProfile.PDFA_3A;
            case "b" -> PdfStandardProfile.PDFA_3B;
            case "u" -> PdfStandardProfile.PDFA_3U;
            default -> throw new IllegalArgumentException(I18n.t("cli.generate.badLevel", letter));
        };
    }

    /**
     * Parses the printed language.
     *
     * @param code de or en (any case); null for the setting of the wizard, the language of the program if there is none.
     * @throws IllegalArgumentException If it is something else.
     */
    public static Locale parseLanguage(String code) {
        if (code == null)
            code = Preferences.getInstance().getString(Preferences.USER_PREF_INVOICE_PRINT_LANGUAGE,
                    I18n.getLocale().getLanguage());
        return switch (code.trim().toLowerCase(Locale.ROOT)) {
            case "de" -> Locale.GERMAN;
            case "en" -> Locale.ENGLISH;
            default -> throw new IllegalArgumentException(I18n.t("cli.generate.badLanguage", code));
        };
    }

    /**
     * Sets the source of the following pages, if the layout has none.
     *
     * @param value template, page1, blank or the name of a PDF file.
     * @throws IllegalArgumentException If it is no source, or the PDF doesn't exist.
     */
    static void applyContinuation(Layout layout, String value) {
        if (layout.continuation != null) {
            System.err.println(I18n.t("cli.generate.continuationIgnored", layout.continuation.json));
            return;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "template" -> layout.continuation = Layout.Continuation.TEMPLATE;
            case "page1" -> layout.continuation = Layout.Continuation.PAGE1;
            case "blank" -> layout.continuation = Layout.Continuation.BLANK;
            default -> {
                Path pdf = Paths.get(value);
                if (!value.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    throw new IllegalArgumentException(I18n.t("cli.generate.badContinuation", value));
                if (!Files.isRegularFile(pdf))
                    throw new IllegalArgumentException(I18n.t("cli.fileMissing", value));
                layout.continuation = Layout.Continuation.PDF;
                layout.continuationFile = pdf.toAbsolutePath().normalize();
            }
        }
    }

    private static void print(Problem p) {
        StringBuilder sb = new StringBuilder(p.severity() == Problem.Severity.ERROR ? "Error: " : "Warning: ");
        if (p.element() != null || p.page() > 0) {
            sb.append('[');
            if (p.element() != null)
                sb.append(p.element());
            if (p.page() > 0)
                sb.append(p.element() != null ? ", " : "").append(I18n.t("cli.generate.page", p.page()));
            sb.append("] ");
        }
        System.err.println(sb.append(p.message()));
    }

    /**
     * Generates the invoice. Messages go to the standard streams.
     *
     * @param layoutFile   The layout file, null to look for it beside the template and in the layout folder.
     * @param continuation The value of {@code --continuation}, null if not given.
     * @param level        The PDF/A-3 level.
     * @param language     The printed language.
     * @return The exit code, 0 for success.
     */
    public static int generate(Path xml, Path template, Path layoutFile, String continuation,
                               PdfStandardProfile level, Locale language, Path out) throws IOException {
        InvoiceData data;
        try {
            data = InvoiceData.fromXml(Files.readAllBytes(xml));
        } catch (Exception e) {
            System.err.println(I18n.t("cli.generate.xmlFailed", xml, e.getMessage()));
            return EXIT_DATA;
        }
        List<String> missing = data.check();
        if (!missing.isEmpty()) {
            System.err.println(I18n.t("cli.generate.dataIncomplete"));
            missing.forEach(m -> System.err.println("  " + m));
            return EXIT_DATA;
        }

        LayoutFiles.Found found;
        try {
            found = layoutFile != null ? new LayoutFiles.Found(LayoutFiles.read(layoutFile), layoutFile, null)
                    : LayoutFiles.find(template);
        } catch (IOException e) {
            System.err.println(I18n.t("cli.generate.layoutFailed", layoutFile != null ? layoutFile : template,
                    e.getMessage()));
            return EXIT_TEMPLATE;
        }
        Layout layout = found.layout();
        if (continuation != null) {
            try {
                applyContinuation(layout, continuation);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                return EXIT_ARGUMENTS;
            }
        }

        List<Problem> problems = TemplateCheck.check(template, found);
        problems.stream().filter(p -> p.severity() != Problem.Severity.INFO).forEach(InvoiceGeneration::print);
        if (TemplateCheck.hasErrors(problems))
            return EXIT_TEMPLATE;

        InvoiceRenderer.Generated generated = InvoiceRenderer.generate(Files.readAllBytes(template), layout, data,
                language, level);
        generated.render().problems().stream().filter(p -> p.severity() != Problem.Severity.INFO)
                .forEach(InvoiceGeneration::print);
        if (generated.render().choiceNeeded()) {
            System.err.println(I18n.t("cli.generate.choiceNeeded"));
            return EXIT_CONTINUATION;
        }
        if (generated.pdf() == null)
            return EXIT_TEMPLATE;
        if (!generated.isValid()) {
            generated.validation().messages().forEach(System.err::println);
            System.err.println(I18n.t("cli.invoiceInvalid"));
            return 12;
        }
        byte[] pdf = generated.pdf();
        SafeFile.write(out, os -> os.write(pdf));
        System.out.println(I18n.t("cli.generate.done", out, generated.render().pageCount(), level));
        return 0;
    }
}

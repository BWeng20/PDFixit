package com.bw.pdfixit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.verapdf.features.FeatureFactory;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.MetadataFixerResult;
import org.verapdf.metadata.fixer.FixerFactory;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.validation.validators.ValidatorFactory;
import org.verapdf.processor.ItemProcessor;
import org.verapdf.processor.ProcessorFactory;
import org.verapdf.processor.ProcessorResult;
import org.verapdf.processor.TaskType;
import org.verapdf.processor.plugins.PluginsCollectionConfig;
import org.verapdf.processor.reports.ItemDetails;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a PDF with veraPDF (the validator that comes with Mustang) against the PDF/A flavour it declares.
 */
public final class PdfaValidator {

    private PdfaValidator() {
    }

    /**
     * The result of the metadata fixer of veraPDF.
     *
     * @param pdf    The fixed PDF, null if the result is not used: veraPDF changed nothing, or it could not repair
     *               the document and removed the PDF/A identification instead (status "ID Removed").
     * @param status The repair status of veraPDF.
     * @param fixes  What veraPDF did, also if the result is not used.
     */
    public record MetadataFix(byte[] pdf, String status, List<String> fixes) {

        /**
         * @return True if the fixer repaired the metadata and the result is used.
         */
        public boolean applied() {
            return pdf != null;
        }
    }

    /**
     * Runs the metadata fixer of veraPDF. It repairs the XMP metadata only (identification, differences to the
     * document information, ...), nothing of the content. The PDF has to declare PDF/A.
     *
     * @param pdf The complete PDF.
     * @return The fixed PDF and what was fixed, the PDF is null if nothing was changed.
     * @throws IOException If veraPDF failed.
     */
    public static MetadataFix fixMetadata(byte[] pdf) throws IOException {
        if (declaredPart(pdf) == 0)
            return new MetadataFix(null, "The document declares no PDF/A", List.of());
        VeraGreenfieldFoundryProvider.initialise();
        var foundry = Foundries.defaultInstance();
        var config = ValidatorFactory.defaultConfig();
        try (var parser = foundry.createParser(new ByteArrayInputStream(pdf),
                PDFAFlavour.NO_FLAVOUR, config.getDefaultFlavour(), config.getPassword())) {
            List<PDFAFlavour> flavours = parser.getFlavours();
            if (flavours.get(0) == PDFAFlavour.NO_FLAVOUR)
                flavours = List.of(foundry.defaultFlavour());
            List<org.verapdf.pdfa.results.ValidationResult> results;
            try (var validator = foundry.createValidator(config, flavours)) {
                results = validator.validateAll(parser);
            }
            if (results.size() != 1)
                return new MetadataFix(null, "Not supported for several flavours", List.of());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var fixed = foundry.createMetadataFixer().fixMetadata(parser, out, results.get(0));
            var status = fixed.getRepairStatus();
            // ID_REMOVED: the document is still not conform after the fixes, veraPDF removes the PDF/A
            // identification. That makes the document no PDF/A, it is not used.
            boolean repaired = status == MetadataFixerResult.RepairStatus.SUCCESS;
            return new MetadataFix(repaired ? out.toByteArray() : null, status.toString(),
                    List.copyOf(fixed.getAppliedFixes()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("veraPDF failed: " + e.getMessage(), e);
        }
    }

    /**
     * @return The PDF/A part the PDF declares, 0 if none. A PDF that can't be read here (e.g. encrypted) counts
     * as declared, veraPDF reports its problems.
     */
    private static int declaredPart(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return PdfStandard.pdfaPart(document);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Validates a PDF.
     *
     * @param pdf The complete PDF.
     * @return The result: the findings are the violated rules (with the number of occurrences). A PDF that declares
     * no PDF/A is not checked (veraPDF would check it as PDF/A-1b), the state is
     * {@link ValidationResult.State#NOT_DECLARED}.
     * @throws IOException If veraPDF failed.
     */
    public static ValidationResult validate(byte[] pdf) throws IOException {
        if (declaredPart(pdf) == 0)
            return ValidationResult.notDeclared();
        VeraGreenfieldFoundryProvider.initialise();
        var config = ProcessorFactory.fromValues(ValidatorFactory.defaultConfig(), FeatureFactory.defaultConfig(),
                PluginsCollectionConfig.defaultConfig(), FixerFactory.defaultConfig(), EnumSet.of(TaskType.VALIDATE));
        ProcessorResult result;
        try (ItemProcessor processor = ProcessorFactory.createProcessor(config)) {
            result = processor.process(ItemDetails.fromValues("document.pdf"), new ByteArrayInputStream(pdf));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("veraPDF failed: " + e.getMessage(), e);
        }
        if (result.getValidationResults().isEmpty())
            throw new IOException("veraPDF returned no result");
        org.verapdf.pdfa.results.ValidationResult validation = result.getValidationResults().get(0);
        if (validation.isCompliant())
            return ValidationResult.conform();

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (TestAssertion a : validation.getTestAssertions()) {
            if (a.getStatus() != TestAssertion.Status.FAILED)
                continue;
            String key = a.getRuleId().getSpecification() + " " + a.getRuleId().getClause() + "-"
                    + a.getRuleId().getTestNumber();
            counts.merge(key, 1, Integer::sum);
            texts.putIfAbsent(key, a.getMessage());
        }
        List<ValidationResult.Finding> findings = new ArrayList<>();
        counts.forEach((key, n) -> findings.add(new ValidationResult.Finding(key, texts.get(key), n)));
        if (findings.isEmpty())
            throw new IOException("veraPDF reports the document as not compliant without a failed rule");
        return ValidationResult.notConform(findings);
    }
}

package com.bw.jPdfTool.ui;

import com.bw.jPdfTool.ValidationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class VerificationResultPanelTest {

    @Test
    void test_text() {
        VerificationResultPanel panel = new VerificationResultPanel();
        panel.setResult(ValidationResult.conform());
        Assertions.assertEquals(VerificationResultPanel.heading() + "\nNo violations, the document is conform.", panel.toText());

        panel.setResult(ValidationResult.notConform(List.of(
                new ValidationResult.Finding("A 1.2-3", "one", 2), new ValidationResult.Finding("B 4.5-6", "two", 1))));
        Assertions.assertEquals(VerificationResultPanel.heading() + "\nA 1.2-3: one (2x)\nB 4.5-6: two", panel.toText());

        panel.setResult(ValidationResult.failed("boom"));
        Assertions.assertEquals(VerificationResultPanel.heading() + "\nThe check failed: boom", panel.toText());

        panel.setResult(ValidationResult.notDeclared());
        Assertions.assertEquals(VerificationResultPanel.heading() + "\n" + ValidationResult.notDeclaredText(), panel.toText());
    }
}

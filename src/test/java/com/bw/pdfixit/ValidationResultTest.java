package com.bw.pdfixit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ValidationResultTest {

    @Test
    void test_states() {
        Assertions.assertTrue(ValidationResult.conform().isConform());
        Assertions.assertEquals(List.of(), ValidationResult.conform().toLines());

        ValidationResult notDeclared = ValidationResult.notDeclared();
        Assertions.assertFalse(notDeclared.isConform());
        Assertions.assertEquals(ValidationResult.State.NOT_DECLARED, notDeclared.getState());
        Assertions.assertEquals(List.of(ValidationResult.notDeclaredText()), notDeclared.toLines());

        ValidationResult failed = ValidationResult.failed("boom");
        Assertions.assertEquals(ValidationResult.State.FAILED, failed.getState());
        Assertions.assertEquals("boom", failed.getError());
        Assertions.assertFalse(failed.isConform());
    }

    @Test
    void test_findings() {
        var a = new ValidationResult.Finding("ISO 19005-1:2005 6.7.3-8", "ModDate differs", 2);
        var b = new ValidationResult.Finding("ISO 19005-1:2005 6.7.3-7", "Producer differs", 1);
        ValidationResult result = ValidationResult.notConform(List.of(a, b));
        Assertions.assertEquals(ValidationResult.State.NOT_CONFORM, result.getState());
        Assertions.assertEquals(List.of(a, b), result.getFindings());
        Assertions.assertEquals(List.of("ISO 19005-1:2005 6.7.3-8: ModDate differs (2x)",
                "ISO 19005-1:2005 6.7.3-7: Producer differs"), result.toLines());
        Assertions.assertEquals(result, ValidationResult.notConform(List.of(a, b)));
        Assertions.assertNotEquals(result, ValidationResult.notConform(List.of(a)));
    }

    @Test
    void test_notConformNeedsFindings() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ValidationResult.notConform(List.of()));
    }
}

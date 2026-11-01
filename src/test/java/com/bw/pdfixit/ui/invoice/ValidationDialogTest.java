package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.invoice.EInvoice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ValidationDialogTest {

    private static EInvoice.Validation validation(boolean valid, String... messages) {
        return new EInvoice.Validation(valid, List.of(messages), "<report/>");
    }

    @Test
    void summary_countsErrorsAndWarnings() {
        Assertions.assertEquals("2 errors, 1 warning", ValidationDialog.summary(validation(false,
                "error: missing seller", "warning: odd date", "PDF/A: font not embedded")));
        Assertions.assertEquals("1 error, 0 warnings", ValidationDialog.summary(validation(false, "ERROR: broken")));
        Assertions.assertEquals("0 errors, 2 warnings", ValidationDialog.summary(validation(true,
                "warning: a", "Warning: b")));
    }

    @Test
    void summary_withoutMessages() {
        Assertions.assertEquals("No errors or warnings.", ValidationDialog.summary(validation(true)));
        Assertions.assertEquals("See the details for the reason.", ValidationDialog.summary(validation(false)));
    }
}

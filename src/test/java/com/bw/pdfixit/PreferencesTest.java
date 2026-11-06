package com.bw.pdfixit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The logic of the settings, without touching the stored preferences of the user.
 */
class PreferencesTest {

    @Test
    void test_invoicePdfaLevel() {
        Assertions.assertEquals(PdfStandardProfile.PDFA_3U, Preferences.invoicePdfaLevel(null));
        Assertions.assertEquals(PdfStandardProfile.PDFA_3U, Preferences.invoicePdfaLevel("u"));
        Assertions.assertEquals(PdfStandardProfile.PDFA_3U, Preferences.invoicePdfaLevel("x"));
        Assertions.assertEquals(PdfStandardProfile.PDFA_3A, Preferences.invoicePdfaLevel("a"));
        Assertions.assertEquals(PdfStandardProfile.PDFA_3B, Preferences.invoicePdfaLevel("B"));
    }

    @Test
    void test_recentTemplates() {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= 7; ++i)
            list = Preferences.withRecent(list, "t" + i + ".pdf");
        Assertions.assertEquals(List.of("t7.pdf", "t6.pdf", "t5.pdf", "t4.pdf", "t3.pdf"), list);
        // An entry used again moves to the top, it isn't added twice.
        list = Preferences.withRecent(list, "t4.pdf");
        Assertions.assertEquals(List.of("t4.pdf", "t7.pdf", "t6.pdf", "t5.pdf", "t3.pdf"), list);
        list = Preferences.withRecent(list, "./t5.pdf");
        Assertions.assertEquals(List.of("./t5.pdf", "t4.pdf", "t7.pdf", "t6.pdf", "t3.pdf"), list);
    }
}

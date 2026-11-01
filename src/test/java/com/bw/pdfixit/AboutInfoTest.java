package com.bw.pdfixit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AboutInfoTest {

    @Test
    void text() {
        String t = AboutInfo.asText();
        Assertions.assertTrue(t.startsWith("PDFixit " + BuildVersion.VERSION), t);
        Assertions.assertTrue(t.contains("Build date: " + BuildVersion.BUILD_DATE), t);
        Assertions.assertTrue(t.contains("org.apache.pdfbox:pdfbox "), t);
    }

    @Test
    void licenseIsPartOfTheBuild() {
        Assertions.assertTrue(AboutInfo.licenseText().contains("unencumbered software"), "LICENSE resource");
    }

    @Test
    void everyComponentHasALicense() {
        var components = AboutInfo.components();
        Assertions.assertTrue(components.stream().anyMatch(c -> c.name().equals("org.apache.pdfbox:pdfbox")));
        Assertions.assertTrue(components.stream().anyMatch(c -> c.name().startsWith("Arimo")));
        for (var c : components) {
            Assertions.assertNotEquals("unknown", c.license(), "License missing in AboutInfo for " + c.name());
            Assertions.assertTrue(c.licenseUrl() != null && c.licenseUrl().startsWith("http"), c.name());
        }
        Assertions.assertTrue(AboutInfo.asText().contains("(https://www.apache.org/licenses/LICENSE-2.0)"));
    }

    @Test
    void fontLicenseIsPartOfTheBuild() throws Exception {
        try (var is = AboutInfo.class.getResourceAsStream("/fonts/LICENSE-Apache-2.0.txt")) {
            Assertions.assertNotNull(is);
            Assertions.assertTrue(new String(is.readAllBytes()).contains("Apache License"));
        }
    }
}

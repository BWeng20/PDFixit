package com.bw.pdfixit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class BuildVersionTest {

    @Test
    void versionComesFromTheBuild() {
        Assertions.assertTrue(BuildVersion.VERSION.matches("\\d+(\\.\\d+)*"), BuildVersion.VERSION);
    }

    @Test
    void buildDateIsADay() {
        LocalDate built = LocalDate.parse(BuildVersion.BUILD_DATE);
        Assertions.assertFalse(built.isAfter(LocalDate.now()), BuildVersion.BUILD_DATE);
    }

    @Test
    void librariesAreListed() {
        Assertions.assertTrue(BuildVersion.LIBRARIES.length > 0);
        Assertions.assertEquals(BuildVersion.LIBRARIES.length, BuildVersion.LICENSES.length);
        for (String l : BuildVersion.LIBRARIES)
            Assertions.assertEquals(3, l.split(":").length, l);
    }
}

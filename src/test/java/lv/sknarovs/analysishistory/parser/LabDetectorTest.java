package lv.sknarovs.analysishistory.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import lv.sknarovs.analysishistory.model.Lab;
import lv.sknarovs.analysishistory.pdf.Fixtures;
import org.junit.jupiter.api.Test;

class LabDetectorTest {
    @Test void detectsBothLabsAndNothingElse() {
        assertEquals(Optional.of(Lab.EGL), LabDetector.detect(Fixtures.load("egl-sample")));
        assertEquals(Optional.of(Lab.CLAB), LabDetector.detect(Fixtures.load("clab-sample")));
        assertEquals(Optional.empty(), LabDetector.detect(Fixtures.parse("100:Some other lab")));
    }

    @Test void needsBothMarksOfALab() {
        // Any accredited laboratory issues a "testēšanas pārskats"; the web address tells whose it is.
        assertEquals(Optional.empty(), LabDetector.detect(Fixtures.parse("""
                28:Testēšanas pārskats Nr. 30000001#1 // pabeigts
                200:www.paraugs.example
                """)));
        assertEquals(Optional.empty(), LabDetector.detect(Fixtures.parse("200:e-pasts : info@egl.lv")));
        assertEquals(Optional.empty(), LabDetector.detect(Fixtures.parse("""
                150:TESTĒŠANAS PĀRSKATS / PABEIGTS
                48:www.paraugs.example
                """)));
        assertEquals(Optional.empty(), LabDetector.detect(Fixtures.parse("48:www.laboratorija.lv")));
    }
}

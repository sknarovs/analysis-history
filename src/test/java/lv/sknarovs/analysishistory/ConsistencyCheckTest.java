package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsistencyCheckTest {
    private static final TestCatalog CATALOG = TestCatalog.parse("""
            [Hematoloģija]
            Hemoglobīns | g/L | EGL: Hemoglobīns | CLab: Hemoglobīns [g/dL ×10]
            """);

    @Test void matchingPreviousResultsAreSilent() {
        Report earlier = report(Lab.CLAB, "old.pdf", LocalDateTime.of(2025, 11, 3, 9, 12), "14.6", null);
        Report later = report(Lab.CLAB, "new.pdf", LocalDateTime.of(2026, 1, 5, 8, 0), "15.1",
                previous("14.6", LocalDate.of(2025, 11, 3), LocalTime.of(9, 12)));

        assertEquals(List.of(), ConsistencyCheck.check(List.of(later, earlier), CATALOG));
    }

    @Test void mismatchIsReported() {
        Report earlier = report(Lab.CLAB, "old.pdf", LocalDateTime.of(2025, 11, 3, 9, 12, 41), "15.0", null);
        Report later = report(Lab.CLAB, "new.pdf", LocalDateTime.of(2026, 1, 5, 8, 0), "15.1",
                previous("14.6", LocalDate.of(2025, 11, 3), LocalTime.of(9, 12)));

        assertEquals(List.of("new.pdf: Hemoglobīns previous result 14.6 (03.11.2025) differs from 15.0 in old.pdf"),
                ConsistencyCheck.check(List.of(later, earlier), CATALOG));
    }

    @Test void clabTimeMustMatch() {
        Report earlier = report(Lab.CLAB, "old.pdf", LocalDateTime.of(2025, 11, 3, 10, 0), "15.0", null);
        Report later = report(Lab.CLAB, "new.pdf", LocalDateTime.of(2026, 1, 5, 8, 0), "15.1",
                previous("14.6", LocalDate.of(2025, 11, 3), LocalTime.of(9, 12)));

        assertEquals(List.of(), ConsistencyCheck.check(List.of(later, earlier), CATALOG));
    }

    @Test void eglMatchesByDateOnly() {
        Report earlier = report(Lab.EGL, "old.pdf", LocalDateTime.of(2019, 2, 4, 7, 55), "5.2", null);
        Report later = report(Lab.EGL, "new.pdf", LocalDateTime.of(2020, 1, 5, 8, 0), "5.0",
                previous("4.9", LocalDate.of(2019, 2, 4), null));

        List<String> warnings = ConsistencyCheck.check(List.of(later, earlier), CATALOG);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("04.02.2019"));
    }

    @Test void missingEarlierReportIsIgnored() {
        Report later = report(Lab.EGL, "new.pdf", LocalDateTime.of(2020, 1, 5, 8, 0), "5.0",
                previous("4.9", LocalDate.of(2019, 2, 4), null));

        assertEquals(List.of(), ConsistencyCheck.check(List.of(later), CATALOG));
    }

    @Test void comparesNumbersByValue() {
        Report earlier = report(Lab.EGL, "old.pdf", LocalDateTime.of(2019, 2, 4, 7, 55), "4,80", null);
        Report later = report(Lab.EGL, "new.pdf", LocalDateTime.of(2020, 1, 5, 8, 0), "5.0",
                previous("4.8", LocalDate.of(2019, 2, 4), null));

        assertEquals(List.of(), ConsistencyCheck.check(List.of(later, earlier), CATALOG));
    }

    @Test void anyCandidateWithAMatchingResultIsEnough() {
        Report a = report(Lab.EGL, "a.pdf", LocalDateTime.of(2019, 2, 4, 7, 55), "5.2", null);
        Report b = report(Lab.EGL, "b.pdf", LocalDateTime.of(2019, 2, 4, 9, 0), "4.9", null);
        Report later = report(Lab.EGL, "new.pdf", LocalDateTime.of(2020, 1, 5, 8, 0), "5.0",
                previous("4.9", LocalDate.of(2019, 2, 4), null));

        assertEquals(List.of(), ConsistencyCheck.check(List.of(later, a, b), CATALOG));
    }

    @Test void textPreviousResultMayBeTheLastWordsOfTheEarlierValue() {
        Report earlier = report(Lab.CLAB, "old.pdf", LocalDateTime.of(2025, 11, 3, 9, 12), "Rh(D) pozitīvs", null);
        Report later = report(Lab.CLAB, "new.pdf", LocalDateTime.of(2026, 1, 5, 8, 0), "Rh(D) pozitīvs",
                previous("Pozitīvs", LocalDate.of(2025, 11, 3), LocalTime.of(9, 12)));
        Report partWord = report(Lab.CLAB, "part.pdf", LocalDateTime.of(2026, 1, 5, 8, 0), "Rh(D) pozitīvs",
                previous("tīvs", LocalDate.of(2025, 11, 3), LocalTime.of(9, 12)));

        assertEquals(List.of(), ConsistencyCheck.check(List.of(later, earlier), CATALOG));
        assertEquals(List.of("part.pdf: Hemoglobīns previous result tīvs (03.11.2025) differs from Rh(D) pozitīvs"
                + " in old.pdf"), ConsistencyCheck.check(List.of(partWord, earlier), CATALOG));
    }

    private static Previous previous(String value, LocalDate date, LocalTime time) {
        return new Previous(ResultValue.parse(value), date, time);
    }

    private static Report report(Lab lab, String file, LocalDateTime registered, String value, Previous previous) {
        Result result = new Result("Hematoloģija", null, Specimen.BLOOD, "Hemoglobīns", ResultValue.parse(value),
                "g/L", new ReferenceRange.None(), Flag.NONE, previous);
        return new Report(lab, "1", registered, "010101-10000", Path.of(file), List.of("Hematoloģija"),
                List.of(result), List.of());
    }
}

package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lv.sknarovs.analysishistory.ReferenceRange.Below;
import lv.sknarovs.analysishistory.ReferenceRange.Between;
import lv.sknarovs.analysishistory.ResultValue.Compared;
import lv.sknarovs.analysishistory.ResultValue.Numeric;
import lv.sknarovs.analysishistory.ResultValue.Text;
import org.junit.jupiter.api.Test;

class EglParserTest {
    private static final Path FILE = Path.of("testesanas_parskats_20000001.pdf");
    /** What an inline report needs above its results: the number, the registration time and the column header. */
    private static final String TOP = """
            28:Testēšanas pārskats Nr. 20000001#1000 // pabeigts
            28:Reģistrēts laboratorijā | 160:05.03.26,08:15
            179:Rezultāts | 269:References | 340:Vienības | 403:Iepr.rezult./datums
            """;

    @Test void readsHeader() throws ReportFormatException {
        Report report = parse("egl-sample");

        assertEquals(Lab.EGL, report.lab());
        assertEquals("20000001", report.number());
        assertEquals(LocalDateTime.of(2026, 3, 5, 8, 15), report.registered());
        assertEquals("010101-10000", report.personalCode());
        assertEquals(FILE, report.file());
    }

    @Test void readsResultRows() throws ReportFormatException {
        Report report = parse("egl-basic");

        assertEquals(new Result("Hematoloģija", null, Specimen.BLOOD, "Hemoglobīns",
                        new Numeric(new BigDecimal("150"), 0), "g/L",
                        new Between(new BigDecimal("132"), new BigDecimal("175")), Flag.NONE,
                        new Previous(new Numeric(new BigDecimal("139"), 0), LocalDate.of(2025, 10, 14), null)),
                result(report, "Hemoglobīns"));
        assertEquals("132–175", result(report, "Hemoglobīns").range().display());
        assertEquals(new Previous(new Numeric(new BigDecimal("19.5"), 1), LocalDate.of(2025, 10, 14), null),
                result(report, "Brīvais T4").previous());
        assertNull(result(report, "Neitrofīlie %").previous());
        assertEquals(List.of("Hemoglobīns", "Neitrofīlie %", "Ferritīns", "Brīvais T4",
                "Antivielas pret tireoperoksidāzi"), names(report));
        assertEquals(List.of(), report.warnings());
    }

    @Test void readsFlags() throws ReportFormatException {
        Report report = parse("egl-basic");

        assertEquals(Flag.LOW, result(report, "Neitrofīlie %").labFlag());
        assertEquals(new Numeric(new BigDecimal("40.1"), 1), result(report, "Neitrofīlie %").value());
        assertEquals(Flag.LOW, result(report, "Ferritīns").labFlag());
        assertEquals(Flag.HIGH, result(report, "Brīvais T4").labFlag());
        Result antibodies = result(report, "Antivielas pret tireoperoksidāzi");
        assertEquals(Flag.HIGH, antibodies.labFlag());
        assertEquals(new Compared(">", new BigDecimal("9000"), 0), antibodies.value());
        assertEquals(new Below(new BigDecimal("60"), false), antibodies.range());
    }

    @Test void joinsWrappedLines() throws ReportFormatException {
        Report report = parse("egl-multiline");

        assertEquals(List.of("Glomerulu filtrācijas ātrums", "Kreatinīns", "TSH - Tireotropais hormons",
                "Antieritrocitārās av.", "Ērču encefalīta vīrusa IgG"), names(report));
        assertEquals(new Text("Pozitīvs 150.2 U/mL"), result(report, "Ērču encefalīta vīrusa IgG").value());
        assertEquals(new Text("Nav atrastas"), result(report, "Antieritrocitārās av.").value());
        assertEquals("ml/min/1.73 m²", result(report, "Glomerulu filtrācijas ātrums").unit());
        assertEquals("µmol/L", result(report, "Kreatinīns").unit());
        assertEquals(new Numeric(new BigDecimal("2.10"), 2), result(report, "TSH - Tireotropais hormons").value());
        assertEquals(List.of(), report.warnings());
    }

    @Test void separatesUrineStripFromMicroscopy() throws ReportFormatException {
        List<Result> leukocytes = results(parse("egl-urine"), "Leikocīti");

        assertEquals(2, leukocytes.size());
        assertEquals(new Compared("<", new BigDecimal("25"), 0), leukocytes.get(0).value());
        assertEquals(Specimen.URINE, leukocytes.get(0).specimen());
        assertNull(leukocytes.get(0).group());
        assertEquals(new Numeric(new BigDecimal("6"), 0), leukocytes.get(1).value());
        assertEquals(Specimen.URINE_SEDIMENT, leukocytes.get(1).specimen());
        assertEquals("Mikroskopija", leukocytes.get(1).group());
    }

    @Test void rowsWithoutSampleCodeTakeTheSectionsSpecimen() throws ReportFormatException {
        assertEquals(Specimen.URINE, result(parse("egl-urine"), "Nitrītu tests").specimen());
        assertEquals(Specimen.BLOOD, result(parse("egl-notes"), "Dzelzs saistīšanas spēja").specimen());
    }

    @Test void nextSectionEndsTheMicroscopyGroup() throws ReportFormatException {
        Report report = parse("egl-urine");

        Result glucose = result(report, "Glikoze plazmā");
        assertEquals("Klīniskā ķīmija", glucose.section());
        assertNull(glucose.group());
        assertEquals(Specimen.BLOOD, glucose.specimen());
        assertEquals(List.of("Urīna analīze", "Klīniskā ķīmija"), report.sections());
    }

    @Test void ignoresNotesPageFurnitureAndSummaryRows() throws ReportFormatException {
        Report report = parse("egl-notes");

        assertEquals(List.of("Dzelzs", "Dzelzs saistīšanas spēja", "25-OH-vitamīns D (D3+D2)", "Ferritīns"),
                names(report));
        Result capacity = result(report, "Dzelzs saistīšanas spēja");
        assertEquals(Specimen.BLOOD, capacity.specimen());
        assertEquals(new Numeric(new BigDecimal("88"), 0), capacity.value());
        assertEquals(Flag.HIGH, capacity.labFlag());
        // Neither the interpretation table nor the page break below the vitamin D row changes it.
        Result vitaminD = result(report, "25-OH-vitamīns D (D3+D2)");
        assertEquals(new Numeric(new BigDecimal("32.5"), 1), vitaminD.value());
        assertEquals(new Between(new BigDecimal("30"), new BigDecimal("100")), vitaminD.range());
        assertEquals("ng/mL", vitaminD.unit());
        assertEquals(List.of(), report.warnings());
    }

    @Test void warnsAboutUnattachedRows() throws ReportFormatException {
        Report report = parse("egl-stray");

        assertEquals(List.of(new RowWarning(1, "12.3")), report.warnings());
        assertEquals(new Numeric(new BigDecimal("150"), 0), result(report, "Hemoglobīns").value());
        assertEquals(List.of("Hemoglobīns", "Brīvais T4"), names(report));
    }

    @Test void headingsEndTheResultAboveThem() throws ReportFormatException {
        for (String heading : List.of("88:*Hormoni", "91:Mikroskopija")) {
            Report report = EglParser.parse(FILE, Fixtures.parse(TOP + """
                    28:1/2 A | 62:Hemoglobīns | 179:150 | 269:132 - 175 | 340:g/L
                    %s
                    179:12.3 | 340:g/L
                    """.formatted(heading)));

            assertEquals(List.of(new RowWarning(1, "12.3 | g/L")), report.warnings(), heading);
            assertEquals(new Numeric(new BigDecimal("150"), 0), result(report, "Hemoglobīns").value(), heading);
        }
    }

    @Test void notesBelowNoResultAreSkipped() throws ReportFormatException {
        Report report = EglParser.parse(FILE, Fixtures.parse(TOP + """
                28:1/1 | 62:Rezultātu novērtējums
                95:Deficīts | 179:<10
                """));

        assertEquals(List.of(), report.results());
        assertEquals(List.of(), report.warnings());
    }

    @Test void contentEndsForTheWholeReportAtTheFirstFooterRow() throws ReportFormatException {
        for (String footer : List.of("85:Pārskata beigas", "28:Materiāls ņemts | 160:05.03.26,07:50",
                "28:# - testēšanas metode nav akreditēta")) {
            Report report = EglParser.parse(FILE, Fixtures.parse(TOP + """
                    28:1/2 A | 62:Hemoglobīns | 179:150 | 269:132 - 175 | 340:g/L
                    %s
                    179:12.3
                    === page
                    179:Rezultāts | 269:References | 340:Vienības | 403:Iepr.rezult./datums
                    28:2/2 A | 62:Leikocīti | 179:5.20 | 269:4.0 - 9.8 | 340:10x9/L
                    """.formatted(footer)));

            assertEquals(List.of("Hemoglobīns"), names(report), footer);
            assertEquals(List.of(), report.warnings(), footer);
        }
    }

    @Test void rejectsReportWithoutNumber() {
        List<Page> pages = Fixtures.parse("""
                200:e-pasts : info@egl.lv
                28:Personas kods | 160:010101-10000
                28:Reģistrēts laboratorijā | 160:05.03.26,08:15
                179:Rezultāts | 269:References | 340:Vienības | 403:Iepr.rezult./datums
                28:1/1 A | 62:Hemoglobīns | 179:150 | 269:132 - 175 | 340:g/L
                """);

        assertThrows(ReportFormatException.class, () -> EglParser.parse(FILE, pages));
    }

    @Test void rejectsReportWithoutReadableRegistrationDate() {
        for (String registered : List.of("28:Reģistrēts laboratorijā",
                "28:Reģistrēts laboratorijā | 160:05.03.2026 08:15")) {
            List<Page> pages = Fixtures.parse("""
                    28:Testēšanas pārskats Nr. 20000001#1000 // pabeigts
                    28:Personas kods | 160:010101-10000
                    %s
                    179:Rezultāts | 269:References | 340:Vienības | 403:Iepr.rezult./datums
                    28:1/1 A | 62:Hemoglobīns | 179:150 | 269:132 - 175 | 340:g/L
                    """.formatted(registered));

            assertThrows(ReportFormatException.class, () -> EglParser.parse(FILE, pages), registered);
        }
    }

    @Test void personalCodeIsOptional() throws ReportFormatException {
        Report report = EglParser.parse(FILE, Fixtures.parse(TOP));

        assertNull(report.personalCode());
    }

    private static Report parse(String fixture) throws ReportFormatException {
        return EglParser.parse(FILE, Fixtures.load(fixture));
    }

    /** The only result named {@code name}. */
    private static Result result(Report report, String name) {
        List<Result> found = results(report, name);
        assertEquals(1, found.size(), "results named " + name);
        return found.get(0);
    }

    private static List<Result> results(Report report, String name) {
        return report.results().stream().filter(result -> result.name().equals(name)).toList();
    }

    private static List<String> names(Report report) {
        return report.results().stream().map(Result::name).toList();
    }
}

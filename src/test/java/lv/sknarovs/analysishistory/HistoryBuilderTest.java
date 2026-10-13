package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class HistoryBuilderTest {
    /** A few tests in the shipped catalog's format; "GFR" shows that a row is named as the catalog names it. */
    private static final TestCatalog CATALOG = TestCatalog.parse("""
            [Hematoloģija]
            Hemoglobīns | g/L | EGL: Hemoglobīns | CLab: Hemoglobīns [g/dL ×10]
            Leikocīti | 10⁹/L | EGL: Leikocīti | CLab: Leikocīti
            [Leikocītu formula]
            Neitrofīli % | % | EGL: Neitrofīlie % | CLab: Neitrofīli
            [Klīniskā ķīmija]
            Kreatinīns | µmol/L | EGL: Kreatinīns | CLab: Kreatinīns
            GFR | ml/min/1.73 m² | EGL: Glomerulu filtrācijas ātrums | CLab: Glomerulārās filtrācijas ātrums
            [Hormoni]
            TSH - tireotropais hormons | mU/L | EGL: TSH - Tireotropais hormons
            [Infekcijas]
            SARS-CoV-2 RNS (PCR) |  | CLab: Koronavīrusa 2019-nCoV RNS noteikšana
            [Urīna analīze]
            pH |  | EGL: Reakcija pH | CLab: Reakcija
            """);
    private static final String CBC = "Hematoloģija";
    private static final String DIFFERENTIAL = "Leikocītu formula";
    private static final String CHEMISTRY = "Klīniskā ķīmija";
    private static final String HORMONES = "Hormoni";
    private static final String INFECTIONS = "Infekcijas";
    private static final String URINE = "Urīna analīze";
    private static final String ONLY_EGL =
            "Only in EGL reports. Add an alias to catalog.txt to merge it with other labs.";
    private static final DateTimeFormatter REGISTERED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Test void newestVisitFirstAndSameDayReportsShareColumn() {
        Report eglBlood = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CBC, "Hemoglobīns", "150", "g/L", ""));
        Report eglUrine = report(Lab.EGL, "20000011", "04.02.2019 08:30",
                result(URINE, Specimen.URINE, "Reakcija pH", "6.0", "", "5.0 - 8.0", Flag.NONE));
        Report clab = report(Lab.CLAB, "100000010", "20.04.2026 10:05",
                blood(CBC, "Hemoglobīns", "14.1", "g/dL", ""));

        History history = HistoryBuilder.build(List.of(eglUrine, clab, eglBlood), CATALOG);

        LocalDate visit = LocalDate.of(2019, 2, 4);
        assertEquals(List.of(new Visit(Lab.CLAB, LocalDate.of(2026, 4, 20), List.of(clab)),
                new Visit(Lab.EGL, visit, List.of(eglBlood, eglUrine))), history.visits());
        assertEquals("20000010, 20000011", history.visits().get(1).numbers());
        assertEquals("20000010, 20000011", new Visit(Lab.EGL, visit, List.of(eglUrine, eglBlood)).numbers());
        assertEquals(List.of("141", "150"), values(row(history, "Hemoglobīns")));
        assertEquals(Arrays.asList(null, "6.0"), values(row(history, "pH")));
    }

    @Test void sameDayReportsWithSameTestGetSeparateColumns() {
        Report morning = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""));
        Report noon = report(Lab.EGL, "20000011", "04.02.2019 11:40", blood(CBC, "Hemoglobīns", "146", "g/L", ""));

        History history = HistoryBuilder.build(List.of(morning, noon), CATALOG);

        LocalDate visit = LocalDate.of(2019, 2, 4);
        assertEquals(List.of(new Visit(Lab.EGL, visit, List.of(noon)), new Visit(Lab.EGL, visit, List.of(morning))),
                history.visits());
        assertEquals(List.of("146", "150"), values(only(history)));
    }

    @Test void sameDayResultsStayInTimeOrder() {
        Report first = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""));
        Report second = report(Lab.EGL, "20000011", "04.02.2019 08:30", blood(CBC, "Hemoglobīns", "146", "g/L", ""));
        Report third = report(Lab.EGL, "20000012", "04.02.2019 09:00", blood(CBC, "Leikocīti", "5.40", "10x9/L", ""));

        History history = HistoryBuilder.build(List.of(third, second, first), CATALOG);

        // The third report joins the day's latest column, the second's; in the first's, 07:55 would show before 08:30.
        assertEquals(List.of("20000011, 20000012", "20000010"), numbers(history));
        assertEquals(List.of("146", "150"), values(row(history, "Hemoglobīns")));
        assertEquals(Arrays.asList("5.40", null), values(row(history, "Leikocīti")));
    }

    @Test void sameDayReportStartsAColumnWhenTheDaysLatestHasItsTest() {
        Report first = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""));
        Report second = report(Lab.EGL, "20000011", "04.02.2019 08:30", blood(CBC, "Hemoglobīns", "146", "g/L", ""),
                blood(CBC, "Leikocīti", "5.20", "10x9/L", ""));
        Report third = report(Lab.EGL, "20000012", "04.02.2019 09:00", blood(CBC, "Leikocīti", "5.40", "10x9/L", ""));

        History history = HistoryBuilder.build(List.of(first, second, third), CATALOG);

        // The first column lacks Leikocīti, but taking the third report would put it before the second's.
        assertEquals(List.of("20000012", "20000011", "20000010"), numbers(history));
        assertEquals(Arrays.asList(null, "146", "150"), values(row(history, "Hemoglobīns")));
        assertEquals(Arrays.asList("5.40", "5.20", null), values(row(history, "Leikocīti")));
    }

    @Test void reportsRegisteredAtTheSameTimeGoByFileName() {
        Report first = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""),
                blood(CHEMISTRY, "Homocisteīns", "9.8", "µmol/L", ""));
        Report second = report(Lab.EGL, "20000011", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "146", "g/L", ""),
                blood(CHEMISTRY, "HOMOCISTEĪNS", "10.1", "µmol/L", ""));
        Report urine = report(Lab.EGL, "20000012", "04.02.2019 07:55",
                result(URINE, Specimen.URINE, "Reakcija pH", "6.0", "", "", Flag.NONE));

        History history = HistoryBuilder.build(List.of(first, second, urine), CATALOG);

        assertEquals(history, HistoryBuilder.build(List.of(urine, second, first), CATALOG));
        assertEquals(List.of("20000011, 20000012", "20000010"), numbers(history));
        assertEquals(List.of("146", "150"), values(row(history, "Hemoglobīns")));
        assertEquals(List.of("10.1", "9.8"), values(row(history, "HOMOCISTEĪNS")));
        assertEquals("20000011, 20000012", new Visit(Lab.EGL, LocalDate.of(2019, 2, 4), List.of(urine, second))
                .numbers());
    }

    @Test void sameDayReportsOfTwoLabsGetSeparateColumns() {
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""));
        Report clab = report(Lab.CLAB, "100000010", "04.02.2019 09:20", blood(CBC, "Leikocīti", "5.1", "10e9/L", ""));

        History history = HistoryBuilder.build(List.of(egl, clab), CATALOG);

        LocalDate visit = LocalDate.of(2019, 2, 4);
        assertEquals(List.of(new Visit(Lab.CLAB, visit, List.of(clab)), new Visit(Lab.EGL, visit, List.of(egl))),
                history.visits());
    }

    @Test void mergesLabsIntoOneRowWithConversion() {
        Report clab = report(Lab.CLAB, "100000010", "20.04.2026 10:05",
                blood(CBC, "Hemoglobīns", "14.1", "g/dL", "(13.2 - 17.5)"));
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CBC, "Hemoglobīns", "150", "g/L", "132-175"));

        History history = HistoryBuilder.build(List.of(egl, clab), CATALOG);

        assertEquals(List.of(new HistoryRow(CBC, "Hemoglobīns", "g/L", "132–175", null, List.of(
                        new HistoryCell(ResultValue.parse("141"), Flag.NONE, "CLab: 14.1 g/dL"),
                        new HistoryCell(ResultValue.parse("150"), Flag.NONE, null)))),
                history.rows());
        assertEquals(List.of(), history.warnings());
    }

    @Test void referenceComesFromNewestPrintedRange() {
        Report newest = report(Lab.CLAB, "100000011", "20.04.2026 10:05",
                blood(CHEMISTRY, "Kreatinīns", "91.0", "µmol/L", ""));
        Report clab = report(Lab.CLAB, "100000010", "17.03.2026 08:20",
                blood(CHEMISTRY, "Kreatinīns", "88.0", "µmol/L", "(62.0 - 106.0)"));
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CHEMISTRY, "Kreatinīns", "90", "µmol/L", "65-104"));

        HistoryRow row = only(HistoryBuilder.build(List.of(egl, newest, clab), CATALOG));

        assertEquals("62.0–106.0", row.reference());
        assertEquals(Arrays.asList(null, null, "Ref. 65–104 µmol/L"), notes(row));
    }

    @Test void convertedValueWithAnotherRangeGetsBothNoteLines() {
        Report newer = report(Lab.CLAB, "100000011", "20.04.2026 10:05",
                blood(CBC, "Hemoglobīns", "14.1", "g/dL", "(13.2 - 17.5)"),
                result(URINE, Specimen.URINE, "Reakcija", "6.0", "", "(5.0 - 8.0)", Flag.NONE));
        Report older = report(Lab.CLAB, "100000010", "17.03.2026 08:20",
                blood(CBC, "Hemoglobīns", "12.9", "g/dL", "(12.0 - 16.0)"),
                result(URINE, Specimen.URINE, "Reakcija", "5.5", "", "(4.5 - 8.0)", Flag.NONE));

        History history = HistoryBuilder.build(List.of(older, newer), CATALOG);

        // 129 g/L is judged against its own range, 120–160, not the row's: no flag.
        assertEquals(new HistoryCell(ResultValue.parse("129"), Flag.NONE, "CLab: 12.9 g/dL\nRef. 120–160 g/L"),
                row(history, "Hemoglobīns").cells().get(1));
        // pH has no unit: nothing trails the range.
        assertEquals(Arrays.asList(null, "Ref. 4.5–8.0"), notes(row(history, "pH")));
    }

    @Test void textValueInAConvertedRowGetsNoValueNote() {
        Report text = report(Lab.CLAB, "100000012", "20.04.2026 10:05",
                blood(CBC, "Hemoglobīns", "hemolīze", "g/dL", "(13.2 - 17.5)"));
        Report number = report(Lab.CLAB, "100000011", "17.03.2026 08:20",
                blood(CBC, "Hemoglobīns", "14.1", "g/dL", "(13.2 - 17.5)"));
        Report comparator = report(Lab.CLAB, "100000010", "03.02.2026 09:40",
                blood(CBC, "Hemoglobīns", "<5.0", "g/dL", "(13.2 - 17.5)"));

        HistoryRow row = only(HistoryBuilder.build(List.of(comparator, number, text), CATALOG));

        // Text is shown as printed, nothing converted; a number is converted, also one with a comparator.
        assertEquals(List.of("hemolīze", "141", "<50"), values(row));
        assertEquals(Arrays.asList(null, "CLab: 14.1 g/dL", "CLab: <5.0 g/dL"), notes(row));
    }

    @Test void sameRangePrintedWithOtherDecimalsGetsNoNote() {
        Report clab = report(Lab.CLAB, "100000010", "20.04.2026 10:05",
                blood(CBC, "Leikocīti", "5.10", "10e9/L", "(4.00 - 9.80)"));
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CBC, "Leikocīti", "5.2", "10x9/L", "4.0-9.8"));

        HistoryRow row = only(HistoryBuilder.build(List.of(clab, egl), CATALOG));

        assertEquals("4.00–9.80", row.reference());
        assertEquals(Arrays.asList(null, null), notes(row));
    }

    @Test void flagsFollowTheRule() {
        Report clab = report(Lab.CLAB, "100000010", "20.04.2026 10:05",
                blood(CHEMISTRY, "Glomerulārās filtrācijas ātrums", "85", "mL/min/1.73 m2", ""),
                result(INFECTIONS, Specimen.BLOOD, "Koronavīrusa 2019-nCoV RNS noteikšana", "Pozitīvs", "",
                        "Negatīvs", Flag.HIGH),
                result(CHEMISTRY, Specimen.BLOOD, "Kreatinīns", "106.0", "µmol/L", "(62.0 - 106.0)", Flag.HIGH));
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CHEMISTRY, "Glomerulu filtrācijas ātrums", "95", "ml/min/1.73 m²", ">90"),
                blood(DIFFERENTIAL, "Neitrofīlie %", "40.1", "%", "48 - 72"));

        History history = HistoryBuilder.build(List.of(clab, egl), CATALOG);

        // CLab printed no range, so its value is judged against the row's reference, which EGL printed.
        assertEquals(List.of(Flag.LOW, Flag.NONE), flags(row(history, "GFR")));
        // A text value cannot be judged: the laboratory's arrow decides.
        assertEquals(Arrays.asList(Flag.HIGH, null), flags(row(history, "SARS-CoV-2 RNS (PCR)")));
        // Judged against its own range, although the laboratory printed no arrow.
        assertEquals(Arrays.asList(null, Flag.LOW), flags(row(history, "Neitrofīli %")));
        // A value that can be judged is judged, whatever the arrow: bounds belong to the range.
        assertEquals(Arrays.asList(Flag.NONE, null), flags(row(history, "Kreatinīns")));
    }

    @Test void rowsFollowCatalogOrderAndSkipEmptyTests() {
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(HORMONES, "TSH - Tireotropais hormons", "2.10", "mU/L", "0.27 - 4.20"),
                blood(CBC, "Leikocīti", "5.20", "10x9/L", "4.0 - 9.8"),
                blood(CBC, "Hemoglobīns", "150", "g/L", "132-175"));

        History history = HistoryBuilder.build(List.of(egl), CATALOG);

        assertEquals(List.of("Hemoglobīns", "Leikocīti", "TSH - tireotropais hormons"), names(history));
        assertEquals(List.of(CBC, CBC, HORMONES), sections(history));
        assertEquals(List.of("g/L", "10⁹/L", "mU/L"), history.rows().stream().map(HistoryRow::unit).toList());
    }

    @Test void unmappedTestGetsOwnRowAndNote() {
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CHEMISTRY, "Homocisteīns", "9.8", "µmol/L", "5.0 - 15.0"),
                blood(CHEMISTRY, "Kreatinīns", "90", "µmol/L", "65-104"),
                blood(HORMONES, "TSH - Tireotropais hormons", "2.10", "mU/L", "0.27 - 4.20"));

        History history = HistoryBuilder.build(List.of(egl), CATALOG);

        assertEquals(List.of("Kreatinīns", "Homocisteīns", "TSH - tireotropais hormons"), names(history));
        assertEquals(new HistoryRow(CHEMISTRY, "Homocisteīns", "µmol/L", "5.0–15.0", ONLY_EGL, List.of(cell("9.8"))),
                history.rows().get(1));
        assertNull(history.rows().get(0).nameNote());
        assertEquals(List.of(), history.warnings());
    }

    @Test void unmappedResultsShareARowOnlyWithinTheirLab() {
        Report clab = report(Lab.CLAB, "100000010", "20.04.2026 10:05",
                blood(CHEMISTRY, "Homocisteīns", "11.0", "µmol/L", ""));
        Report newer = report(Lab.EGL, "20000011", "05.03.2020 08:15",
                blood(CHEMISTRY, "HOMOCISTEĪNS", "9.8", "umol/L", ""));
        Report older = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CHEMISTRY, "Homocisteīns", "10.1", "µmol/L", ""));

        History history = HistoryBuilder.build(List.of(older, clab, newer), CATALOG);

        // An unmapped row is named as the newest report prints it.
        assertEquals(List.of(
                new HistoryRow(CHEMISTRY, "Homocisteīns", "µmol/L", "",
                        "Only in CLab reports. Add an alias to catalog.txt to merge it with other labs.",
                        Arrays.asList(cell("11.0"), null, null)),
                new HistoryRow(CHEMISTRY, "HOMOCISTEĪNS", "umol/L", "", ONLY_EGL,
                        Arrays.asList(null, cell("9.8"), cell("10.1")))),
                history.rows());
    }

    @Test void otherSectionsFollowTheCatalogsInFirstSeenOrder() {
        Report newer = report(Lab.EGL, "20000011", "05.03.2020 08:15",
                blood("", "Glikozētais hemoglobīns", "5.4", "%", "4.0 - 6.0"),
                blood("Alergoloģija", "Kopējais IgE", "35", "kU/L", "<100"));
        Report older = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood("Alergoloģija", "Specifiskais IgE", "0.10", "kU/L", "<0.35"),
                blood(CBC, "Hemoglobīns", "150", "g/L", "132-175"));

        History history = HistoryBuilder.build(List.of(older, newer), CATALOG);

        // Results are seen newest visit first; a result printed before any section heading is under "Citi".
        assertEquals(List.of("Hemoglobīns", "Glikozētais hemoglobīns", "Kopējais IgE", "Specifiskais IgE"),
                names(history));
        assertEquals(List.of(CBC, "Citi", "Alergoloģija", "Alergoloģija"), sections(history));
    }

    @Test void unitMismatchWarnsAndKeepsRowsApart() {
        Report newer = report(Lab.EGL, "20000011", "05.03.2020 08:15",
                blood(CBC, "Hemoglobīns", "14.8", "g/dL", "13.2 - 17.5"),
                blood(CHEMISTRY, "Glomerulu filtrācijas ātrums", "95", "ml/min", ""));
        Report older = report(Lab.EGL, "20000010", "04.02.2019 07:55",
                blood(CBC, "Hemoglobīns", "150", "g/L", "132-175"));

        History history = HistoryBuilder.build(List.of(older, newer), CATALOG);

        assertEquals(List.of(
                new HistoryRow(CBC, "Hemoglobīns", "g/L", "132–175", null, Arrays.asList(null, cell("150"))),
                new HistoryRow(CBC, "Hemoglobīns", "g/dL", "13.2–17.5",
                        "Printed in g/dL; the catalog expects g/L.", false, Arrays.asList(cell("14.8"), null)),
                new HistoryRow(CHEMISTRY, "Glomerulu filtrācijas ātrums", "ml/min", "",
                        "Printed in ml/min; the catalog expects ml/min/1.73 m².", false, Arrays.asList(cell("95"), null))),
                history.rows());
        // The alias exists, so the run warning is the report and the console does not ask for one.
        assertEquals(List.of(), AnalysisHistory.unmapped(history));
        // The warning names the test as printed, and the unit as the catalog writes it.
        assertEquals(List.of(
                "testesanas_parskats_20000011.pdf: Hemoglobīns is printed in 'g/dL' but the catalog expects 'g/L'; "
                        + "kept in its own row",
                "testesanas_parskats_20000011.pdf: Glomerulu filtrācijas ātrums is printed in 'ml/min' but the "
                        + "catalog expects 'ml/min/1.73 m²'; kept in its own row"),
                history.warnings());
    }

    @Test void testRepeatedInOneReportKeepsTheFirstResultAndWarns() {
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""),
                blood(CBC, "HEMOGLOBĪNS", "151", "g/L", ""));

        History history = HistoryBuilder.build(List.of(egl), CATALOG);

        assertEquals(List.of("150"), values(only(history)));
        assertEquals(List.of("testesanas_parskats_20000010.pdf: Hemoglobīns appears more than once; only the first "
                + "result is shown"), history.warnings());
    }

    @Test void reportsWithoutResultsGetNoColumn() {
        Report microbiology = new Report(Lab.CLAB, "100000011", LocalDateTime.of(2026, 4, 20, 10, 5), "010101-10000",
                Path.of("CLab_100000011.pdf"), List.of("Mikrobioloģija"), List.of(), List.of());
        Report egl = report(Lab.EGL, "20000010", "04.02.2019 07:55", blood(CBC, "Hemoglobīns", "150", "g/L", ""));

        History history = HistoryBuilder.build(List.of(microbiology, egl), CATALOG);

        assertEquals(List.of(new Visit(Lab.EGL, LocalDate.of(2019, 2, 4), List.of(egl))), history.visits());
        assertEquals(List.of("150"), values(only(history)));
    }

    /** An invented report, registered at {@code registered} ({@code dd.MM.yyyy HH:mm}), for the invented patient. */
    private static Report report(Lab lab, String number, String registered, Result... results) {
        Path file = Path.of(lab == Lab.EGL ? "testesanas_parskats_" + number + ".pdf" : "CLab_" + number + ".pdf");
        List<String> sections = Arrays.stream(results).map(Result::section).filter(s -> !s.isEmpty()).distinct()
                .toList();
        return new Report(lab, number, LocalDateTime.parse(registered, REGISTERED), "010101-10000", file, sections,
                List.of(results), List.of());
    }

    /** A blood result without the laboratory's arrow; {@code range} as printed, {@code ""} when none. */
    private static Result blood(String section, String name, String value, String unit, String range) {
        return result(section, Specimen.BLOOD, name, value, unit, range, Flag.NONE);
    }

    private static Result result(String section, Specimen specimen, String name, String value, String unit,
            String range, Flag labFlag) {
        return new Result(section, null, specimen, name, ResultValue.parse(value), unit, ReferenceRange.parse(range),
                labFlag, null);
    }

    /** An unflagged cell without a note. */
    private static HistoryCell cell(String value) {
        return new HistoryCell(ResultValue.parse(value), Flag.NONE, null);
    }

    private static HistoryRow only(History history) {
        assertEquals(1, history.rows().size(), () -> "rows: " + names(history));
        return history.rows().getFirst();
    }

    private static HistoryRow row(History history, String name) {
        return history.rows().stream().filter(row -> row.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no row '" + name + "' in " + names(history)));
    }

    private static List<String> numbers(History history) {
        return history.visits().stream().map(Visit::numbers).toList();
    }

    private static List<String> names(History history) {
        return history.rows().stream().map(HistoryRow::name).toList();
    }

    private static List<String> sections(History history) {
        return history.rows().stream().map(HistoryRow::section).toList();
    }

    private static List<String> values(HistoryRow row) {
        return cells(row, cell -> cell.value().display());
    }

    private static List<Flag> flags(HistoryRow row) {
        return cells(row, HistoryCell::flag);
    }

    private static List<String> notes(HistoryRow row) {
        return cells(row, HistoryCell::note);
    }

    /** {@code field} of each of the row's cells, in visit order, null for an empty cell. */
    private static <T> List<T> cells(HistoryRow row, Function<HistoryCell, T> field) {
        return row.cells().stream().map(cell -> cell == null ? null : field.apply(cell)).toList();
    }
}

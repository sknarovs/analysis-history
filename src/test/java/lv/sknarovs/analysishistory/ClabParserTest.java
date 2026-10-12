package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lv.sknarovs.analysishistory.ReferenceRange.Between;
import lv.sknarovs.analysishistory.ReferenceRange.None;
import lv.sknarovs.analysishistory.ReferenceRange.TextReference;
import lv.sknarovs.analysishistory.ResultValue.Numeric;
import lv.sknarovs.analysishistory.ResultValue.Text;
import org.junit.jupiter.api.Test;

class ClabParserTest {
    private static final Path FILE = Path.of("CLab_100000001.pdf");
    private static final String HEADER = "49:*Tests | 194:*Rezultāts | 257:*Vienības | 318:*References intervāls"
            + " | 417:*Iepriekšējais rezultāts | 522:*Datums";
    /**
     * What an inline report needs above its results: the number, the registration time, the end of the patient block
     * and the column header.
     */
    private static final String TOP = """
            48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
            48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 08:40
            48:Komentāri:
            """ + HEADER + "\n";

    @Test void readsHeader() throws ReportFormatException {
        Report report = parse("clab-sample");

        assertEquals(Lab.CLAB, report.lab());
        assertEquals("100000001", report.number());
        assertEquals(LocalDateTime.of(2026, 3, 17, 8, 40), report.registered());
        assertEquals("010101-10000", report.personalCode());
        assertEquals(FILE, report.file());
    }

    @Test void readsTheSampleWithoutWarnings() throws ReportFormatException {
        Report report = parse("clab-sample");

        assertEquals(List.of("Hemoglobīns", "Leikocīti", "Kreatinīns", "Glomerulārās filtrācijas ātrums"),
                names(report));
        assertEquals(List.of("Hematoloģija", "Klīniskā ķīmija"), report.sections());
        assertEquals("Pilna asins aina ar EGĀ", result(report, "Leikocīti").group());
        // A section ends the group above it.
        assertEquals("Klīniskā ķīmija", result(report, "Kreatinīns").section());
        assertNull(result(report, "Kreatinīns").group());
        assertEquals(List.of(), report.warnings());
    }

    @Test void readsRowsWithPrevious() throws ReportFormatException {
        Report report = parse("clab-cbc");

        assertEquals(new Result("Hematoloģija", "Pilna asins aina ar EGĀ", Specimen.BLOOD, "Hemoglobīns",
                        new Numeric(new BigDecimal("14.1"), 1), "g/dL",
                        new Between(new BigDecimal("13.2"), new BigDecimal("17.5")), Flag.NONE,
                        new Previous(new Numeric(new BigDecimal("14.6"), 1), LocalDate.of(2025, 11, 3),
                                LocalTime.of(9, 12))),
                result(report, "Hemoglobīns"));
        assertEquals("13.2–17.5", result(report, "Hemoglobīns").range().display());
        // The hour may have one digit.
        assertEquals(new Previous(new Numeric(new BigDecimal("238"), 0), LocalDate.of(2025, 7, 14),
                LocalTime.of(9, 8)), result(report, "Trombocīti").previous());
        assertNull(result(report, "EGĀ").previous());
        assertEquals(List.of(), report.warnings());
    }

    @Test void readsNestedFormulaTable() throws ReportFormatException {
        Report report = parse("clab-cbc");

        Result neutrophils = result(report, "Neitrofīli");
        assertEquals(new Result("Hematoloģija", "Leikocitārā formula", Specimen.BLOOD, "Neitrofīli",
                        new Numeric(new BigDecimal("40.1"), 1), "%",
                        new Between(new BigDecimal("48.0"), new BigDecimal("72.0")), Flag.LOW,
                        new Previous(new Numeric(new BigDecimal("55.2"), 1), LocalDate.of(2025, 11, 3),
                                LocalTime.of(9, 12))),
                neutrophils);
        assertEquals("48.0–72.0", neutrophils.range().display());
        assertEquals(Flag.HIGH, result(report, "Limfocīti").labFlag());
        assertEquals("10e9/L", result(report, "Limfocīti abs.").unit());
        // The group title is a row of its own below the blood count: it ends the count's last row, not wraps it.
        assertEquals(List.of("Hemoglobīns", "Leikocīti", "Trombocīti", "EGĀ", "Neitrofīli", "Neitrofīli abs.",
                "Limfocīti", "Limfocīti abs."), names(report));
        assertEquals("Pilna asins aina ar EGĀ", result(report, "EGĀ").group());
        assertEquals(List.of("Hematoloģija"), report.sections());
    }

    @Test void decimalCommaBecomesNumber() throws ReportFormatException {
        assertEquals(new Numeric(new BigDecimal("110.0"), 1), result(parse("clab-sample"), "Kreatinīns").value());
    }

    @Test void joinsWrappedNamesValuesAndUnits() throws ReportFormatException {
        Report report = parse("clab-wrapped");

        assertEquals(List.of("Kreatinīns", "Glomerulārās filtrācijas ātrums", "Rh (D) pazīme (gelkaršu met.)",
                "Koronavīrusa 2019-nCoV RNS noteikšana"), names(report));
        Result filtration = result(report, "Glomerulārās filtrācijas ātrums");
        assertEquals("mL/min/1.73 m2", filtration.unit());
        assertEquals(new Numeric(new BigDecimal("104"), 0), filtration.value());
        assertEquals(new Text("Rh(D) pozitīvs"), result(report, "Rh (D) pazīme (gelkaršu met.)").value());
        Result covid = result(report, "Koronavīrusa 2019-nCoV RNS noteikšana");
        assertEquals(new Text("IR ATRASTA koronavīrusa SARS-CoV-2 RNS"), covid.value());
        assertEquals(new TextReference("nav atrasta"), covid.range());
        assertEquals(List.of(), report.warnings());
    }

    @Test void urineGroupContinuesOnPageWithoutHeader() throws ReportFormatException {
        Report report = parse("clab-urine");

        Result bilirubin = result(report, "Bilirubīns urīnā");
        assertEquals(Specimen.URINE, bilirubin.specimen());
        assertEquals("Urīna analīze ar stripu", bilirubin.group());
        assertEquals("umol/L", bilirubin.unit());
        Result colour = result(report, "Krāsa");
        assertEquals(Specimen.URINE, colour.specimen());
        assertEquals(new Text("Dzeltena"), colour.value());
        List<Result> glucose = results(report, "Glikoze");
        assertEquals(List.of(Specimen.BLOOD, Specimen.URINE), glucose.stream().map(Result::specimen).toList());
        assertEquals(new Text("Norma"), glucose.get(1).value());
        assertEquals(new TextReference("Norma"), glucose.get(1).range());
        assertEquals(new Between(new BigDecimal("1.002"), new BigDecimal("1.035")),
                result(report, "Īpatnējais svars").range());
        // Page 2 repeats the patient block, whose cells right of the name column are no results.
        assertEquals(List.of(), report.warnings());
    }

    @Test void ignoresNotes() throws ReportFormatException {
        Report report = parse("clab-notes");

        assertEquals(List.of("Protrombīns", "INR", "APTL", "Kreatinīns", "Glomerulārās filtrācijas ātrums"),
                names(report));
        Result inr = result(report, "INR");
        assertEquals(new Numeric(new BigDecimal("1.03"), 2), inr.value());
        assertEquals(new Between(new BigDecimal("0.85"), new BigDecimal("1.15")), inr.range());
        assertEquals("", inr.unit());
        // The note under INR starts no group.
        assertNull(result(report, "APTL").group());
        Result filtration = result(report, "Glomerulārās filtrācijas ātrums");
        assertEquals(new Numeric(new BigDecimal("97"), 0), filtration.value());
        assertEquals("mL/min/1.73 m2", filtration.unit());
        assertEquals(new None(), filtration.range());
        assertEquals(List.of(), report.warnings());
    }

    @Test void microbiologyHasNoResults() throws ReportFormatException {
        Report report = parse("clab-micro");

        assertEquals(List.of(), report.results());
        assertEquals(List.of("Mikrobioloģija"), report.sections());
        assertEquals(List.of(), report.warnings());
    }

    @Test void wrappedLinesContinueTheResultEvenWhenBold() throws ReportFormatException {
        Report report = ClabParser.parse(FILE, Fixtures.parse(TOP + """
                46:Koronavīrusa 2019-nCoV | 200:*IR ATRASTA | 318:nav atrasta
                ~45:RNS noteikšana | 200:*koronavīrusa
                ~200:*SARS-CoV-2
                ~200:*RNS
                """));

        assertEquals(new Text("IR ATRASTA koronavīrusa SARS-CoV-2 RNS"),
                result(report, "Koronavīrusa 2019-nCoV RNS noteikšana").value());
        assertEquals(List.of(), report.sections());
    }

    @Test void textInParenthesesIsTheRangeAnywhereRightOfTheNameColumn() throws ReportFormatException {
        Report report = ClabParser.parse(FILE, Fixtures.parse(TOP + """
                63:Neitrofīli | 215:40,1 | 253:↓ | 275:% | 290:(48.0 - 72.0)
                46:Asins grupa | 207:A (II)
                ~46:(gelkaršu met.)
                """));

        Result neutrophils = result(report, "Neitrofīli");
        assertEquals("%", neutrophils.unit());
        assertEquals(new Between(new BigDecimal("48.0"), new BigDecimal("72.0")), neutrophils.range());
        // In the name column it is part of the name.
        assertEquals(List.of("Neitrofīli", "Asins grupa (gelkaršu met.)"), names(report));
        assertEquals(new None(), result(report, "Asins grupa (gelkaršu met.)").range());
        assertEquals(List.of(), report.warnings());
    }

    @Test void warnsAboutUnattachedRows() throws ReportFormatException {
        Report report = ClabParser.parse(FILE, Fixtures.parse(TOP + """
                280:*Hematoloģija
                # A value below no result.
                213:12.3
                46:Hemoglobīns | 213:14.1 | 278:g/dL | 349:(13.2 - 17.5)
                # A wrapped line adds to the name, value, unit or previous result, never to the range.
                ~349:(13.0 - 17.0)
                # An arrow is no value.
                46:Leikocīti | 218:↓ | 278:10e9/L | 349:(4.0 - 9.8)
                46:Eritrocīti | 213:4,61 | 278:10e12/L | 349:(4.20 - 5.70)
                # A row of its own, too far below the result to wrap it; bold, but a section has one cell only.
                213:*4,58 | 278:10e12/L
                """));

        assertEquals(List.of(new RowWarning(1, "12.3"), new RowWarning(1, "(13.0 - 17.0)"),
                new RowWarning(1, "Leikocīti | ↓ | 10e9/L | (4.0 - 9.8)"), new RowWarning(1, "4,58 | 10e12/L")),
                report.warnings());
        assertEquals(List.of("Hemoglobīns", "Eritrocīti"), names(report));
        assertEquals(new Between(new BigDecimal("13.2"), new BigDecimal("17.5")),
                result(report, "Hemoglobīns").range());
        assertEquals(new Numeric(new BigDecimal("4.61"), 2), result(report, "Eritrocīti").value());
        assertEquals("10e12/L", result(report, "Eritrocīti").unit());
    }

    @Test void warnsAboutPreviousResultsItCannotRead() throws ReportFormatException {
        // The cells printed right of the range, and how the row reads with them.
        Map<String, String> unreadable = Map.of(
                "420:14.6", "14.6",
                "505:03.11.2025 09:12", "03.11.2025 09:12",
                "420:14.6 | 505:31.11.2025 09:12", "14.6 | 31.11.2025 09:12",
                "420:14.6 | 505:03.11.2025 24:12", "14.6 | 03.11.2025 24:12",
                "420:14.6 | 505:03.11.25 09:12", "14.6 | 03.11.25 09:12");
        for (Map.Entry<String, String> previous : unreadable.entrySet()) {
            Report report = ClabParser.parse(FILE, Fixtures.parse(TOP
                    + "46:Hemoglobīns | 213:14.1 | 278:g/dL | 349:(13.2 - 17.5) | " + previous.getKey()));

            Result hemoglobin = result(report, "Hemoglobīns");
            assertNull(hemoglobin.previous(), previous.getKey());
            assertEquals(new Numeric(new BigDecimal("14.1"), 1), hemoglobin.value(), previous.getKey());
            assertEquals(List.of(new RowWarning(1, "Hemoglobīns | 14.1 | g/dL | (13.2 - 17.5) | "
                    + previous.getValue())), report.warnings(), previous.getKey());
        }
    }

    @Test void contentEndsOnEachPageAtItsFirstFooterRow() throws ReportFormatException {
        for (String footer : List.of("48:Materiāls paņemts | 130:17.03.2026 08:10",
                "48:Materiāls: | 130:venozās asinis", "48:Rezultātus pārbaudīja laboratorijas ārste ID 1",
                "48:PIN kods | 130:1234", "48:Pārskata sastādīšanas datums | 213:17.03.2026",
                "48:Testēšanas metodes, mērījumu nenoteiktības … www.laboratorija.lv",
                "48:Bez laboratorijas rakstiskas atļaujas pārskatu nedrīkst pavairot daļēji",
                "48:Ar (1) atzīmētie testi nav akreditēti")) {
            Report report = ClabParser.parse(FILE, Fixtures.parse(TOP + """
                    46:Hemoglobīns | 213:14.1 | 278:g/dL | 349:(13.2 - 17.5)
                    %s
                    # Below it nothing is read, but a row that would start a result is reported; footer rows and
                    # other text are not.
                    46:Leikocīti | 213:5,40 | 278:10e9/L | 349:(4.00 - 9.80)
                    48:Pārskata sastādīšanas datums | 213:17.03.2026
                    300:1/2
                    === page
                    48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
                    48:Komentāri:
                    46:Trombocīti | 213:212 | 278:10e9/L | 349:(150 - 410)
                    """.formatted(footer)));

            // The next page's results start below its patient block, in the columns of the last header.
            assertEquals(List.of("Hemoglobīns", "Trombocīti"), names(report), footer);
            assertEquals(List.of(new RowWarning(1, "Leikocīti | 5,40 | 10e9/L | (4.00 - 9.80)")), report.warnings(),
                    footer);
        }
    }

    @Test void rejectsReportWithoutALoneNumber() {
        // Six to ten digits right of x 297 count only above the first page's Komentāri: row, and only once.
        for (String patientBlock : List.of("""
                48:Vecums | 130:100000001 | 470:12345 | 520:12345678901
                48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 08:40
                48:Komentāri:
                46:Hemoglobīns | 213:14.1 | 470:100000001
                === page
                48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
                48:Komentāri:
                """, """
                48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
                48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 08:40 | 520:100000002
                48:Komentāri:
                """)) {
            List<Page> pages = Fixtures.parse(patientBlock);

            assertThrows(ReportFormatException.class, () -> ClabParser.parse(FILE, pages), patientBlock);
        }
    }

    @Test void aNumberPrintedTwiceIsOneNumber() throws ReportFormatException {
        Report report = ClabParser.parse(FILE, Fixtures.parse("""
                48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
                48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 08:40 | 520:100000001
                48:Komentāri:
                """));

        assertEquals("100000001", report.number());
    }

    @Test void rejectsReportWithoutReadableRegistrationDate() {
        for (String registered : List.of("48:Diagnoze", "48:Diagnoze | 340:Reģistrēts",
                "48:Diagnoze | 340:Reģistrēts | 430:17.03.26 08:40",
                "48:Diagnoze | 340:Reģistrēts | 430:31.02.2026 08:40",
                "48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 24:40",
                "48:Diagnoze | 340:Reģistrēts | 430:17.03.20261 08:40")) {
            List<Page> pages = Fixtures.parse("""
                    48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
                    %s
                    48:Komentāri:
                    """.formatted(registered));

            assertThrows(ReportFormatException.class, () -> ClabParser.parse(FILE, pages), registered);
        }
    }

    @Test void registrationHourMayHaveOneDigit() throws ReportFormatException {
        Report report = ClabParser.parse(FILE, Fixtures.parse("""
                48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001
                48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 8:40
                48:Komentāri:
                """));

        assertEquals(LocalDateTime.of(2026, 3, 17, 8, 40), report.registered());
    }

    @Test void personalCodeIsOptional() throws ReportFormatException {
        assertNull(ClabParser.parse(FILE, Fixtures.parse(TOP)).personalCode());
    }

    private static Report parse(String fixture) throws ReportFormatException {
        return ClabParser.parse(FILE, Fixtures.load(fixture));
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

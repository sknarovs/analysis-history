package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lv.sknarovs.analysishistory.CatalogMatch.Matched;
import lv.sknarovs.analysishistory.CatalogMatch.UnitMismatch;
import lv.sknarovs.analysishistory.CatalogMatch.Unknown;
import org.junit.jupiter.api.Test;

class TestCatalogTest {
    /** One per test, so that a broken catalog.txt fails each test with its own message, not the whole class. */
    private final TestCatalog bundled = TestCatalog.bundled();

    @Test void bundledCatalogLoads() {
        assertEquals(List.of("Hematoloģija", "Leikocītu formula", "Dzelzs un vitamīni", "Klīniskā ķīmija"),
                bundled.sections().subList(0, 4));
    }

    @Test void mergesDifferentlyNamedTests() {
        CatalogTest egl = matched(Lab.EGL, Specimen.BLOOD, "MCV- vid. Er tilpums", "fL");

        assertEquals(new Matched(egl, BigDecimal.ONE),
                bundled.match(Lab.CLAB, Specimen.BLOOD, "MCV Eritroc.vid.tilp.", "fL"));
        assertEquals("MCV - vid. eritrocītu tilpums", egl.name());
        assertEquals("fL", egl.unit());
        assertEquals("Hematoloģija", egl.section());
    }

    @Test void convertsUnits() {
        Matched clab = assertInstanceOf(Matched.class,
                bundled.match(Lab.CLAB, Specimen.BLOOD, "Hemoglobīns", "g/dL"));

        assertEquals(new BigDecimal("10"), clab.factor());
        assertEquals("g/L", clab.test().unit());
        assertEquals(new Matched(clab.test(), BigDecimal.ONE),
                bundled.match(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/L"));
    }

    @Test void namesIgnoreCaseAndSpacing() {
        for (String printed : List.of("TSH - Tireotropais hormons", "TSH-Tireotropais  hormons",
                "tsh -tireotropais hormons", "  TSH -  TIREOTROPAIS HORMONS ")) {
            assertMatched(Lab.EGL, Specimen.BLOOD, printed, "mU/L");
        }
        assertMatched(Lab.EGL, Specimen.URINE, "ERITROCĪTI ,HB", "/µL");
    }

    @Test void normalisesNames() {
        assertEquals("tsh-tireotropais hormons", TestCatalog.normaliseName("TSH - Tireotropais hormons"));
        assertEquals("tsh-tireotropais hormons", TestCatalog.normaliseName("TSH-Tireotropais  hormons"));
        assertEquals("mcv-vid. er tilpums", TestCatalog.normaliseName("MCV- vid. Er tilpums"));
        assertEquals("eritrocīti,hb", TestCatalog.normaliseName("Eritrocīti , Hb"));
        assertEquals("eritrocīti,hb", TestCatalog.normaliseName(" Eritrocīti,   Hb "));
        assertEquals("ērču encefalīta vīrusa igg", TestCatalog.normaliseName("ĒRČU ENCEFALĪTA  VĪRUSA IgG"));
        assertEquals("25-oh-vitamīns d (d3+d2)", TestCatalog.normaliseName("25-OH-vitamīns D (D3+D2)"));
    }

    @Test void unitsCompareNormalised() {
        assertMatched(Lab.EGL, Specimen.BLOOD, "Leikocīti", "10x9/L");
        assertMatched(Lab.CLAB, Specimen.BLOOD, "Leikocīti", "10e9/L");
        assertMatched(Lab.EGL, Specimen.BLOOD, "Eritrocīti", "10x12/L");
        assertMatched(Lab.CLAB, Specimen.BLOOD, "Eritrocīti", "10e12/L");
        assertMatched(Lab.CLAB, Specimen.BLOOD, "Kreatinīns", "umol/L");
        assertMatched(Lab.EGL, Specimen.BLOOD, "Kreatinīns", "μmol/L"); // Greek mu, not the micro sign
        assertMatched(Lab.CLAB, Specimen.BLOOD, "Glomerulārās filtrācijas ātrums", "mL/min/1.73 m2");
        assertMatched(Lab.EGL, Specimen.BLOOD, "Glomerulu filtrācijas ātrums", "ml/min/1.73 m²");
        assertMatched(Lab.CLAB, Specimen.URINE, "Leikocīti urīnā", "Leu/uL");
        assertMatched(Lab.CLAB, Specimen.URINE, "Eritrocīti", "Ery/uL");
    }

    @Test void normalisesUnits() {
        assertSameUnit("10^9/l", "10⁹/L", "10x9/L", "10X9/L", "10e9/L", "10E9 /l", "10^9/L", "10 x 9/L");
        assertSameUnit("10^12/l", "10¹²/L", "10x12/L", "10e12/L", "10^12/L");
        assertSameUnit("umol/l", "µmol/L", "μmol/L", "umol/L", "UMOL/L", "µmol /L");
        assertSameUnit("ml/min/1.73m2", "ml/min/1.73 m²", "mL/min/1.73 m2", "mL/min/1.73m²");
        assertSameUnit("/ul", "/µL", "/uL", "Ery/uL", "Leu/uL", "leu/ µL");
        assertSameUnit("", "", "  ");
        assertSameUnit("g/dl", "g/dL", "G/DL");
        // different units stay different
        assertNotEquals(TestCatalog.normaliseUnit("g/L"), TestCatalog.normaliseUnit("g/dL"));
        assertNotEquals(TestCatalog.normaliseUnit("10⁹/L"), TestCatalog.normaliseUnit("10¹²/L"));
        assertNotEquals(TestCatalog.normaliseUnit("/µL"), TestCatalog.normaliseUnit("/L"));
    }

    @Test void specimenSeparatesSameNames() {
        CatalogTest blood = matched(Lab.EGL, Specimen.BLOOD, "Leikocīti", "10⁹/L");
        CatalogTest urine = matched(Lab.EGL, Specimen.URINE, "Leikocīti", "/µL");
        CatalogTest sediment = matched(Lab.EGL, Specimen.URINE_SEDIMENT, "Leikocīti", "/µL");

        assertEquals(List.of("Hematoloģija", "Urīna analīze", "Urīna mikroskopija"),
                List.of(blood.section(), urine.section(), sediment.section()));
        // The unit is checked against the test of the same specimen: urine's unit is not blood's.
        assertEquals(new UnitMismatch(blood, "10⁹/L", "/µL"),
                bundled.match(Lab.EGL, Specimen.BLOOD, "Leikocīti", "/µL"));
    }

    @Test void unitMismatchIsReported() {
        CatalogTest hemoglobin = matched(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/L");

        assertEquals(new UnitMismatch(hemoglobin, "g/L", "g/dL"),
                bundled.match(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/dL"));
        // The alias's own printed unit is the one CLab must print.
        assertEquals(new UnitMismatch(hemoglobin, "g/dL", "g/L"),
                bundled.match(Lab.CLAB, Specimen.BLOOD, "Hemoglobīns", "g/L"));
        // A test without a unit expects none.
        CatalogTest inr = matched(Lab.CLAB, Specimen.BLOOD, "INR", "");
        assertEquals(new UnitMismatch(inr, "", "sek"), bundled.match(Lab.CLAB, Specimen.BLOOD, "INR", "sek"));
    }

    @Test void unknownNameIsUnknown() {
        assertEquals(new Unknown(), bundled.match(Lab.EGL, Specimen.BLOOD, "Homocisteīns", "µmol/L"));
        // Known, but not to this laboratory or not for this specimen.
        assertEquals(new Unknown(), bundled.match(Lab.CLAB, Specimen.BLOOD, "MPV-vid. tr. tilpums", "fL"));
        assertEquals(new Unknown(), bundled.match(Lab.EGL, Specimen.URINE, "Hemoglobīns", "g/L"));
        assertEquals(new Unknown(), bundled.match(Lab.EGL, Specimen.BLOOD, "Hemoglobīns A1c", "g/L"));
    }

    @Test void readsTheCatalogSyntax() {
        TestCatalog catalog = TestCatalog.parse("""
                # display name | unit | lab: name as printed [printed unit ×factor]

                [Hematoloģija]
                Hemoglobīns  |  g/L  |  EGL: Hemoglobīns  | CLab: Hemoglobīns [g/dL ×10] | CLab: Hb (vecais) [g/dL ×10]
                Zeta | U/L | EGL: Zeta | CLab: Zeta [U/dL ×100] | CLab: Zeta kopā (aprēķ.) [IU/L ×1] | CLab: Zeta sin. [u/l]
                Kalcijs | mmol/L | EGL: Kalcijs | CLab: Kalcijs []
                # a comment between the tests
                [Urīna analīze]
                Krāsa |  | CLab: Krāsa
                """);

        assertEquals(List.of("Hematoloģija", "Urīna analīze"), catalog.sections());
        CatalogTest hemoglobin = new CatalogTest("Hematoloģija", "Hemoglobīns", "g/L", 0);
        assertEquals(new Matched(hemoglobin, BigDecimal.ONE),
                catalog.match(Lab.EGL, Specimen.BLOOD, "Hemoglobīns", "g/L"));
        assertEquals(new Matched(hemoglobin, BigDecimal.TEN),
                catalog.match(Lab.CLAB, Specimen.BLOOD, "Hemoglobīns", "g/dL"));
        // A name with parentheses can carry a unit override after them, and a test can have several aliases per lab.
        assertEquals(new Matched(hemoglobin, BigDecimal.TEN),
                catalog.match(Lab.CLAB, Specimen.BLOOD, "Hb (vecais)", "g/dL"));
        CatalogTest zeta = new CatalogTest("Hematoloģija", "Zeta", "U/L", 1);
        assertEquals(new Matched(zeta, new BigDecimal("100")),
                catalog.match(Lab.CLAB, Specimen.BLOOD, "Zeta", "U/dL"));
        // A factor of 1 changes the expected unit only; a spelling of the test's unit needs no factor.
        assertEquals(new Matched(zeta, BigDecimal.ONE), catalog.match(Lab.CLAB, Specimen.BLOOD, "Zeta sin.", "U/L"));
        assertEquals(new Matched(zeta, BigDecimal.ONE),
                catalog.match(Lab.CLAB, Specimen.BLOOD, "Zeta kopā (aprēķ.)", "IU/L"));
        assertEquals(new UnitMismatch(zeta, "IU/L", "U/L"),
                catalog.match(Lab.CLAB, Specimen.BLOOD, "Zeta kopā (aprēķ.)", "U/L"));
        // Empty brackets: the laboratory prints this test without a unit.
        CatalogTest calcium = new CatalogTest("Hematoloģija", "Kalcijs", "mmol/L", 2);
        assertEquals(new Matched(calcium, BigDecimal.ONE), catalog.match(Lab.CLAB, Specimen.BLOOD, "Kalcijs", ""));
        assertEquals(new UnitMismatch(calcium, "", "mmol/L"),
                catalog.match(Lab.CLAB, Specimen.BLOOD, "Kalcijs", "mmol/L"));
        assertEquals(new Matched(calcium, BigDecimal.ONE),
                catalog.match(Lab.EGL, Specimen.BLOOD, "Kalcijs", "mmol/L"));
        // A section sets the specimen, and an empty unit is a unit.
        CatalogTest colour = new CatalogTest("Urīna analīze", "Krāsa", "", 3);
        assertEquals(new Matched(colour, BigDecimal.ONE), catalog.match(Lab.CLAB, Specimen.URINE, "Krāsa", ""));
        assertEquals(new Unknown(), catalog.match(Lab.CLAB, Specimen.BLOOD, "Krāsa", ""));
    }

    @Test void specimenFollowsTheSection() {
        assertEquals(Specimen.URINE, TestCatalog.specimenOf("Urīna analīze"));
        assertEquals(Specimen.URINE_SEDIMENT, TestCatalog.specimenOf("Urīna mikroskopija"));
        assertEquals(Specimen.BLOOD, TestCatalog.specimenOf("Hematoloģija"));
        assertEquals(Specimen.BLOOD, TestCatalog.specimenOf("Infekcijas"));
    }

    @Test void rejectsBadCatalogs() {
        assertRejected("duplicate alias", 3, """
                [Hematoloģija]
                Hemoglobīns | g/L | EGL: Hemoglobīns
                Hb | g/L | EGL: hemoglobīns
                """);
        assertRejected("duplicate alias", 2, """
                [Hematoloģija]
                Hb | g/L | EGL: Hb | CLab: Hb | EGL: HB
                """);
        assertRejected("duplicate alias", 5, """
                [Hematoloģija]
                Hb | g/L | EGL: Hb
                [Hormoni]
                [Leikocītu formula]
                Hb-2 | g/L | EGL: Hb
                """);
        assertRejected("expected", 2, """
                [Hematoloģija]
                Hemoglobīns g/L EGL: Hemoglobīns
                """);
        assertRejected("expected", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L
                """);
        assertRejected("conversion factor", 3, """
                [Hematoloģija]
                # CLab prints g/dL
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL ×3]
                """);
        assertRejected("no conversion factor", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL]
                """);
        assertRejected("write the factor as ×10", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL x10]
                """);
        assertRejected("write the factor as ×10", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL X10]
                """);
        assertRejected("whole number", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL ×0.1]
                """);
        assertRejected("whole number", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL ×]
                """);
        assertRejected("closing ']'", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | CLab: Hemoglobīns [g/dL ×10
                """);
        assertRejected("unknown laboratory", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | LVL: Hemoglobīns
                """);
        assertRejected("laboratory", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | Hemoglobīns
                """);
        assertRejected("laboratory", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | EGL: Hemoglobīns |
                """);
        assertRejected("empty printed name", 2, """
                [Hematoloģija]
                Hemoglobīns | g/L | EGL:
                """);
        assertRejected("empty name", 2, """
                [Hematoloģija]
                 | g/L | EGL: Hemoglobīns
                """);
        assertRejected("no section header", 1, "Hemoglobīns | g/L | EGL: Hemoglobīns\n");
        assertRejected("section header", 1, "[Hematoloģija\nHb | g/L | EGL: Hb\n");
        assertRejected("empty section name", 1, "[ ]\n");
        assertRejected("duplicate section", 4, """
                [Hematoloģija]
                Hb | g/L | EGL: Hb
                # again
                [Hematoloģija]
                """);
    }

    @Test void everySampleFixtureResultIsMapped() throws IOException, ReportFormatException {
        List<String> fixtures = sampleFixtures();
        assertTrue(fixtures.containsAll(List.of("egl-sample", "clab-sample")), "fixtures found: " + fixtures);

        List<String> unmapped = new ArrayList<>();
        int results = 0;
        for (String fixture : fixtures) {
            List<Page> pages = Fixtures.load(fixture);
            Lab lab = LabDetector.detect(pages).orElseThrow(() -> new AssertionError(fixture + " is not recognised"));
            Path file = Path.of(fixture + ".pdf");
            Report report = lab == Lab.EGL ? EglParser.parse(file, pages) : ClabParser.parse(file, pages);
            for (Result result : report.results()) {
                results++;
                CatalogMatch match = bundled.match(lab, result.specimen(), result.name(), result.unit());
                if (!(match instanceof Matched)) {
                    unmapped.add(fixture + ": " + lab.label + " " + result.specimen() + " '" + result.name() + "' ["
                            + result.unit() + "] -> " + match);
                }
            }
        }

        assertEquals(List.of(), unmapped);
        assertTrue(results > 0);
    }

    /** Every file in fixtures/ but egl-stray, which only exists to hold a value that belongs to no result. */
    private static List<String> sampleFixtures() throws IOException {
        try (InputStream listing = TestCatalogTest.class.getResourceAsStream("/fixtures")) {
            return new String(listing.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(name -> name.endsWith(".txt"))
                    .map(name -> name.substring(0, name.length() - ".txt".length()))
                    .filter(name -> !name.equals("egl-stray"))
                    .sorted()
                    .toList();
        }
    }

    private CatalogTest matched(Lab lab, Specimen specimen, String name, String unit) {
        return assertInstanceOf(Matched.class, bundled.match(lab, specimen, name, unit)).test();
    }

    private void assertMatched(Lab lab, Specimen specimen, String name, String unit) {
        CatalogMatch match = bundled.match(lab, specimen, name, unit);
        assertInstanceOf(Matched.class, match, () -> lab.label + " " + specimen + " '" + name + "' [" + unit + "]");
    }

    private static void assertSameUnit(String normalised, String... printed) {
        for (String unit : printed) {
            assertEquals(normalised, TestCatalog.normaliseUnit(unit), "'" + unit + "'");
        }
    }

    /** {@code catalog} is refused: the message starts with the number of the bad line and its text, then the reason. */
    private static void assertRejected(String reason, int line, String catalog) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TestCatalog.parse(catalog));
        String text = catalog.lines().toList().get(line - 1).strip();
        assertTrue(e.getMessage().startsWith("catalog line " + line + " '" + text + "': "), e.getMessage());
        assertTrue(e.getMessage().contains(reason), e.getMessage());
    }
}

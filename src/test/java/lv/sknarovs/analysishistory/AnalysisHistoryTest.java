package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import lv.sknarovs.analysishistory.AnalysisHistory.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisHistoryTest {
    private static final TestCatalog CATALOG = TestCatalog.bundled();
    private static final String CLAB = "CLab_100000001.pdf";
    private static final String EGL = "testesanas_parskats_20000001.pdf";

    @Test void samplesBecomeTwoVisits(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        assertEquals(List.of("OK", "OK"), result.statuses().stream().map(ReportStatus::status).toList());
        assertEquals(List.of("CLab 17.03.2026", "EGL 05.03.2026"), visits(result));
        HistoryRow hemoglobin = result.history().rows().stream()
                .filter(row -> row.name().equals("Hemoglobīns")).findFirst().orElseThrow();
        assertEquals(List.of("141", "150"), hemoglobin.cells().stream().map(cell -> cell.value().display()).toList());
        assertEquals(List.of(), result.warnings());
    }

    @Test void unrecognisedAndBrokenFilesAreListedNotFatal(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});
        try (TestPdf pdf = TestPdf.create().page().text(49, 100, 10, "Some other lab", false)) {
            pdf.save(dir.resolve("other.pdf"));
        }
        Files.createFile(dir.resolve("empty.pdf"));

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        assertEquals("OK", status(result, CLAB).status());
        assertEquals("OK", status(result, EGL).status());
        assertEquals("Unrecognised", status(result, "other.pdf").status());
        assertTrue(status(result, "empty.pdf").status().startsWith("Error: "), status(result, "empty.pdf").status());
        assertEquals(2, result.history().visits().size());
    }

    @Test void readReportsCarryTheirHeader(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});

        ReportStatus egl = status(AnalysisHistory.run(dir, CATALOG), EGL);

        assertEquals(Lab.EGL, egl.lab());
        assertEquals("20000001", egl.number());
        assertEquals(LocalDateTime.of(2026, 3, 5, 8, 15), egl.registered());
        assertEquals(3, egl.results());
    }

    @Test void duplicateReportIsListedOnce(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});
        Files.copy(dir.resolve(CLAB), dir.resolve("CLab_100000001 (1).pdf"));

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        assertEquals(List.of("CLab 17.03.2026", "EGL 05.03.2026"), visits(result));
        assertEquals("Duplicate of " + CLAB, status(result, "CLab_100000001 (1).pdf").status());
        assertEquals("OK", status(result, CLAB).status());
        assertEquals(List.of(), result.warnings());
    }

    @Test void duplicateWithMoreResultsIsKept(@TempDir Path dir) throws IOException {
        render(dir, "egl-sample", "a.pdf",
                "28:3/4 A | 62:TSH - Tireotropais | 179:5.2 | 249:ñ | 269:0.55 - 4.78 | 340:mU/L\n~62:hormons\n", "");
        render(dir, "egl-sample", "b.pdf");

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        assertEquals("Duplicate of b.pdf", status(result, "a.pdf").status());
        assertEquals("OK", status(result, "b.pdf").status());
    }

    @Test void warnsWhenReportsBelongToTwoPeople(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});
        render(dir, "egl-sample", "other-person.pdf", "010101-10000", "020202-20000", "20000001#", "20000009#",
                "05.03.26,08:15", "06.03.26,08:15");

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        List<String> people = result.warnings().stream()
                .filter(warning -> warning.startsWith("reports belong to more than one person: ")).toList();
        assertEquals(List.of("reports belong to more than one person: " + CLAB + ", " + EGL + "; other-person.pdf"),
                people);
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("010101") || w.contains("020202")));
    }

    @Test void notTabulatedReportsAreNamed(@TempDir Path dir) throws IOException {
        render(dir, "clab-micro", "micro.pdf");

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        assertEquals("Not tabulated: Mikrobioloģija", status(result, "micro.pdf").status());
        assertEquals(List.of(), result.history().visits());
    }

    @Test void rowWarningsCountTowardsTheirReport(@TempDir Path dir) throws IOException {
        render(dir, "egl-stray", "stray.pdf");

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        ReportStatus stray = status(result, "stray.pdf");
        assertEquals("OK, 1 warning", stray.status());
        assertEquals(List.of("page 1: unattached row: 12.3"), stray.warnings());
        assertEquals(List.of("stray.pdf: page 1: unattached row: 12.3"), result.warnings());
    }

    @Test void historyAndConsistencyWarningsCountTowardsTheirReport(@TempDir Path dir) throws IOException {
        // A result in another unit than the catalog's, and a printed previous result that differs from egl.pdf's.
        render(dir, "egl-sample", "egl.pdf");
        render(dir, "egl-sample", "later.pdf", "20000001#", "20000002#", "05.03.26,08:15", "20.03.26,09:00",
                "28:2/4 A | 62:Neitrofīlie %", "28:2/4 A | 62:Hemoglobīns",
                "179:150 | 269:132 - 175 | 340:g/L | 439:139 / 14.10.25",
                "179:151 | 269:132 - 175 | 340:g/L | 439:149 / 05.03.26");

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        ReportStatus later = status(result, "later.pdf");
        assertEquals("OK, 2 warnings", later.status());
        assertTrue(later.warnings().stream().noneMatch(w -> w.startsWith("later.pdf")), later.warnings()::toString);
        assertEquals(2, result.warnings().size(), result.warnings()::toString);
        assertTrue(result.warnings().stream().allMatch(w -> w.startsWith("later.pdf: ")
                && !w.startsWith("later.pdf: later.pdf")), result.warnings()::toString);
        assertEquals("OK", status(result, "egl.pdf").status());
    }

    @Test void unmappedRowsAreListedWithTheirLaboratories(@TempDir Path dir) throws IOException {
        render(dir, "egl-sample", "egl.pdf", "62:Neitrofīlie %", "62:Izdomāts tests");

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        assertEquals(List.of("unmapped: Izdomāts tests (EGL) — add an alias to catalog.txt to merge it"),
                AnalysisHistory.unmapped(result.history()));
    }

    @Test void readsPdfFilesOfAnyCaseInNameOrderAndReportsBrokenOnes(@TempDir Path dir) throws IOException {
        try (TestPdf pdf = TestPdf.create().page().text(49, 100, 10, "Some other lab", false)) {
            pdf.save(dir.resolve("b.PDF"));
        }
        Files.createFile(dir.resolve("a.pdf"));
        Files.writeString(dir.resolve("notes.txt"), "not a report");
        Files.createDirectory(dir.resolve("folder.pdf"));
        Files.createDirectories(dir.resolve("sub"));
        Files.copy(dir.resolve("b.PDF"), dir.resolve("sub").resolve("c.pdf"));

        RunResult result = AnalysisHistory.run(dir, CATALOG);

        List<ReportStatus> statuses = result.statuses();
        assertEquals(List.of(dir.resolve("a.pdf"), dir.resolve("b.PDF")),
                statuses.stream().map(ReportStatus::file).toList());
        assertTrue(statuses.get(0).status().startsWith("Error: "), statuses.get(0).status());
        assertEquals(new ReportStatus(dir.resolve("b.PDF"), null, null, null, 0, "Unrecognised", List.of()),
                statuses.get(1));
        assertEquals(List.of(), result.warnings());
    }

    @Test void summaryCountsStatusesByKind() {
        List<ReportStatus> statuses = List.of("OK", "OK, 2 warnings", "Not tabulated: Mikrobioloģija",
                "Unrecognised", "Duplicate of CLab_100000001.pdf", "Error: broken").stream()
                .map(status -> new ReportStatus(Path.of("x.pdf"), null, null, null, 0, status, List.of()))
                .toList();

        assertEquals("6 PDFs: 2 tabulated, 1 not tabulated, 1 unrecognised, 1 duplicates, 1 errors",
                AnalysisHistory.summary(statuses));
    }

    private static ReportStatus status(RunResult result, String file) {
        return result.statuses().stream().filter(status -> status.file().getFileName().toString().equals(file))
                .findFirst().orElseThrow(() -> new AssertionError("no status of " + file + ": " + result.statuses()));
    }

    private static List<String> visits(RunResult result) {
        return result.history().visits().stream()
                .map(visit -> visit.lab().label + " " + DateTimeFormatter.ofPattern("dd.MM.yyyy").format(visit.date()))
                .toList();
    }

    /** Renders fixture {@code name} into {@code dir/file} after replacing each pair of texts. */
    static void render(Path dir, String name, String file, String... replacements) throws IOException {
        String text;
        try (InputStream in = AnalysisHistoryTest.class.getResourceAsStream("/fixtures/" + name + ".txt")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int i = 0; i < replacements.length; i += 2) {
            if (!text.contains(replacements[i])) {
                throw new IllegalArgumentException("'" + replacements[i] + "' not in " + name);
            }
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        FixturePdf.write(Fixtures.parse(text), dir.resolve(file));
    }
}

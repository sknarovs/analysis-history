package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import lv.sknarovs.analysishistory.AnalysisHistory.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Checks the program against the user's real reports (spec §8.3). They are never committed, and the tests are opt-in:
 * they run only when the system property {@code analysis.results} names the folder that holds them (never by default,
 * never in CI), as in {@code ./gradlew test -Danalysis.results=results}. Nothing real is
 * printed on success; a failure lists what differs.
 */
@EnabledIf("resultsPresent")
class RealDataTest {
    private static final TestCatalog CATALOG = TestCatalog.bundled();
    /**
     * Rows of the export whose values no PDF prints, so no parsed result can match them: {@code Formulas % kopā} (the
     * sum of the differential count's percentages) and {@code Rezultātu apraksts} appear in the export only, and the
     * PDFs print {@code Rezultātu novērtējums} as an index row with a name but no value (spec §5.3).
     */
    private static final Set<String> EXCLUDED = Set.of("formulas % kopā", "rezultātu apraksts",
            "rezultātu novērtējums");

    static boolean resultsPresent() {
        String folder = System.getProperty("analysis.results");
        return folder != null && !folder.isBlank() && Files.isDirectory(Path.of(folder));
    }

    private static Path results() {
        return Path.of(System.getProperty("analysis.results"));
    }

    @Test void everyReportIsReadWithoutWarnings() throws IOException {
        RunResult run = AnalysisHistory.run(results(), CATALOG);

        List<String> failed = run.statuses().stream()
                .filter(status -> status.status().equals("Unrecognised") || status.status().startsWith("Error:")
                        || status.status().startsWith("Duplicate"))
                .map(status -> status.file().getFileName() + ": " + status.status())
                .toList();
        assertEquals(List.of(), failed);
        assertEquals(List.of(), run.warnings());
    }

    @Test void everyResultIsInTheCatalog() throws IOException {
        RunResult run = AnalysisHistory.run(results(), CATALOG);

        assertEquals(List.of(), AnalysisHistory.unmapped(run.history()));
        assertEquals(List.of(), run.warnings().stream().filter(warning -> warning.contains("is printed in")).toList());
    }

    @Test void eglValuesMatchTheLabsHtmlExport() throws IOException {
        List<Path> exports = exports();
        assumeFalse(exports.isEmpty(), "no rezultatu_vesture_*.html next to or in the results folder");
        Map<String, Report> egl = reports().stream().filter(report -> report.lab() == Lab.EGL)
                .collect(Collectors.toMap(Report::number, report -> report, (first, second) -> first));

        List<String> differences = new ArrayList<>();
        for (Path export : exports) {
            Map<String, Map<String, List<String>>> html = HistoryHtml.read(export);
            for (Map.Entry<String, Map<String, List<String>>> column : html.entrySet()) {
                Report report = egl.get(column.getKey());
                if (report == null) {
                    differences.add(export.getFileName() + ": report " + column.getKey() + " is not in the folder");
                    continue;
                }
                Map<String, List<String>> parsed = new TreeMap<>();
                report.results().forEach(result -> parsed
                        .computeIfAbsent(TestCatalog.normaliseName(result.name()), name -> new ArrayList<>())
                        .add(result.value().display()));
                Set<String> names = new TreeSet<>(column.getValue().keySet());
                names.addAll(parsed.keySet());
                names.removeAll(EXCLUDED);
                for (String name : names) {
                    List<String> expected = sorted(column.getValue().getOrDefault(name, List.of()));
                    List<String> actual = sorted(parsed.getOrDefault(name, List.of()));
                    if (!expected.equals(actual)) {
                        differences.add("report " + column.getKey() + ", " + name + ": html " + expected
                                + ", parsed " + actual);
                    }
                }
            }
        }
        assertTrue(differences.isEmpty(), () -> differences.size() + " differences:\n"
                + String.join("\n", differences));
    }

    @Test void noPreviousResultMismatches() throws IOException {
        assertEquals(List.of(), ConsistencyCheck.check(reports(), CATALOG));
    }

    /** Every report in the folder, one per laboratory and number; PDFs that are not reports are left out. */
    private static List<Report> reports() throws IOException {
        List<Report> reports = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(results())) {
            for (Path pdf : files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".pdf")).sorted().toList()) {
                read(pdf).filter(report -> seen.add(report.lab() + " " + report.number())).ifPresent(reports::add);
            }
        }
        return reports;
    }

    private static Optional<Report> read(Path pdf) throws IOException {
        List<Page> pages = PdfText.read(pdf);
        Optional<Lab> lab = LabDetector.detect(pages);
        if (lab.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(switch (lab.get()) {
                case EGL -> EglParser.parse(pdf, pages);
                case CLAB -> ClabParser.parse(pdf, pages);
            });
        } catch (ReportFormatException e) {
            throw new AssertionError(pdf.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The exports in the results folder and in the folder above it. */
    private static List<Path> exports() throws IOException {
        Set<Path> exports = new TreeSet<>();
        Path folder = results().toAbsolutePath().normalize();
        for (Path dir : Stream.of(folder, folder.getParent()).filter(dir -> dir != null).toList()) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(file -> file.getFileName().toString().matches("rezultatu_vesture_.*\\.html"))
                        .forEach(exports::add);
            }
        }
        return List.copyOf(exports);
    }

    private static List<String> sorted(List<String> texts) {
        return texts.stream().sorted().toList();
    }
}

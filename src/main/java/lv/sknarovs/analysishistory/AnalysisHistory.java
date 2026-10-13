package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads every PDF directly in a folder (spec §7): each is read, its laboratory detected and its report parsed, and
 * one failure never stops the run. Duplicates (same laboratory and report number) are left out of the history, and
 * every warning that concerns one report counts towards that report's status.
 */
final class AnalysisHistory {
    /** What became of one PDF; {@code lab}, {@code number} and {@code registered} are null when unknown. */
    record ReportStatus(Path file, Lab lab, String number, LocalDateTime registered, int results, String status,
            List<String> warnings) {
    }

    /** The History sheet, one status per PDF in file-name order, and the warnings of the whole run. */
    record RunResult(History history, List<ReportStatus> statuses, List<String> warnings) {
    }

    /** A PDF read: its report, or what kept it from being read ({@code report} is null then). */
    private record Read(Path file, Report report, String failure) {
        String fileName() {
            return file.getFileName().toString();
        }
    }

    /**
     * Which of two copies counts as the first: by name without the extension, then by the whole name, so that
     * {@code CLab_1.pdf} comes before the copy a browser saves as {@code CLab_1 (1).pdf}, as file managers list them.
     */
    private static final Comparator<Read> FIRST_NAME = Comparator
            .comparing((Read read) -> read.fileName().substring(0, read.fileName().lastIndexOf('.')))
            .thenComparing(Read::fileName);

    private AnalysisHistory() {
    }

    /**
     * Reads the {@code *.pdf} files (any case) directly in {@code dir} and lays their reports out with
     * {@code catalog}; a PDF that fails becomes an error status.
     */
    static RunResult run(Path dir, TestCatalog catalog) throws IOException {
        List<Read> reads = pdfs(dir).stream().map(AnalysisHistory::read).toList();
        Map<String, String> duplicates = duplicates(reads);
        List<Read> kept = reads.stream()
                .filter(read -> read.report() != null && !duplicates.containsKey(read.fileName()))
                .toList();
        List<Report> reports = kept.stream().map(Read::report).toList();
        History history = HistoryBuilder.build(reports, catalog);

        Map<String, List<String>> perReport = new LinkedHashMap<>();
        kept.forEach(read -> perReport.put(read.fileName(), new ArrayList<>(read.report().warnings().stream()
                .map(warning -> "page " + warning.page() + ": unattached row: " + warning.text())
                .toList())));
        List<String> unattributed = new ArrayList<>();
        Stream.concat(history.warnings().stream(), ConsistencyCheck.check(reports, catalog).stream())
                .forEach(warning -> attribute(warning, perReport, unattributed));

        List<String> warnings = new ArrayList<>();
        people(kept).ifPresent(warnings::add);
        perReport.forEach((file, list) -> list.forEach(warning -> warnings.add(file + ": " + warning)));
        warnings.addAll(unattributed);

        List<ReportStatus> statuses = reads.stream()
                .map(read -> status(read, duplicates.get(read.fileName()), perReport.getOrDefault(read.fileName(), List.of())))
                .toList();
        return new RunResult(history, statuses, List.copyOf(warnings));
    }

    /**
     * Counts the statuses: {@code "<n> PDFs: <a> tabulated, <b> not tabulated, <c> unrecognised, <d> duplicates,
     * <e> errors"}.
     */
    static String summary(List<ReportStatus> statuses) {
        return statuses.size() + " PDFs: "
                + count(statuses, status -> status.startsWith("OK")) + " tabulated, "
                + count(statuses, status -> status.startsWith("Not tabulated")) + " not tabulated, "
                + count(statuses, status -> status.equals("Unrecognised")) + " unrecognised, "
                + count(statuses, status -> status.startsWith("Duplicate of")) + " duplicates, "
                + count(statuses, status -> status.startsWith("Error:")) + " errors";
    }

    /** One console line per unmapped row of {@code history} (spec §5.6), naming the laboratories that print it. */
    static List<String> unmapped(History history) {
        List<String> lines = new ArrayList<>();
        for (HistoryRow row : history.rows()) {
            if (row.nameNote() == null) {
                continue;
            }
            Set<String> labs = new LinkedHashSet<>();
            for (int column = 0; column < row.cells().size(); column++) {
                if (row.cells().get(column) != null) {
                    labs.add(history.visits().get(column).lab().label);
                }
            }
            lines.add("unmapped: " + row.name() + " (" + String.join(", ", labs)
                    + ") — add an alias to catalog.txt to merge it");
        }
        return lines;
    }

    private static List<Path> pdfs(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        }
    }

    private static Read read(Path pdf) {
        try {
            List<Page> pages = PdfText.read(pdf);
            Optional<Lab> lab = LabDetector.detect(pages);
            if (lab.isEmpty()) {
                return new Read(pdf, null, "Unrecognised");
            }
            Report report = switch (lab.get()) {
                case EGL -> EglParser.parse(pdf, pages);
                case CLAB -> ClabParser.parse(pdf, pages);
            };
            return new Read(pdf, report, null);
        } catch (IOException | ReportFormatException | RuntimeException e) {
            return new Read(pdf, null, "Error: " + (e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName()));
        }
    }

    /**
     * The file names of the reads that duplicate another (same laboratory and number), each with the file name of the
     * one kept: the one with more results, on a tie the first by {@link #FIRST_NAME}.
     */
    private static Map<String, String> duplicates(List<Read> reads) {
        Map<String, List<Read>> byNumber = reads.stream()
                .filter(read -> read.report() != null)
                .collect(Collectors.groupingBy(read -> read.report().lab() + " " + read.report().number(),
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, String> duplicates = new LinkedHashMap<>();
        for (List<Read> same : byNumber.values()) {
            Read keep = same.stream()
                    .max(Comparator.comparingInt((Read read) -> read.report().results().size())
                            .thenComparing(FIRST_NAME.reversed()))
                    .orElseThrow();
            same.stream().filter(read -> read != keep).forEach(read -> duplicates.put(read.fileName(), keep.fileName()));
        }
        return duplicates;
    }

    /**
     * Files {@code warning} under the report whose file name it starts with ({@code "<file>: "}, the longest such name
     * when several match), without that prefix, or under {@code unattributed} when it starts with none.
     */
    private static void attribute(String warning, Map<String, List<String>> perReport, List<String> unattributed) {
        String owner = null;
        for (String file : perReport.keySet()) {
            if (warning.startsWith(file + ": ") && (owner == null || file.length() > owner.length())) {
                owner = file;
            }
        }
        if (owner == null) {
            unattributed.add(warning);
        } else {
            perReport.get(owner).add(warning.substring(owner.length() + 2));
        }
    }

    /**
     * The warning that the reports belong to more than one person, with the files grouped per personal code in order
     * of first file; reports without a code are left out, and the codes are never shown.
     */
    private static Optional<String> people(List<Read> kept) {
        Map<String, List<String>> byPerson = kept.stream()
                .filter(read -> read.report().personalCode() != null)
                .collect(Collectors.groupingBy(read -> read.report().personalCode(), LinkedHashMap::new,
                        Collectors.mapping(Read::fileName, Collectors.toList())));
        if (byPerson.size() < 2) {
            return Optional.empty();
        }
        return Optional.of("reports belong to more than one person: " + byPerson.values().stream()
                .map(files -> String.join(", ", files))
                .collect(Collectors.joining("; ")));
    }

    private static ReportStatus status(Read read, String duplicateOf, List<String> warnings) {
        Report report = read.report();
        if (report == null) {
            return new ReportStatus(read.file(), null, null, null, 0, read.failure(), List.of());
        }
        int results = report.results().size();
        String status;
        if (duplicateOf != null) {
            status = "Duplicate of " + duplicateOf;
            warnings = List.of();
        } else if (results == 0) {
            status = report.sections().isEmpty() ? "Not tabulated"
                    : "Not tabulated: " + String.join(", ", report.sections());
        } else if (warnings.isEmpty()) {
            status = "OK";
        } else {
            status = "OK, " + warnings.size() + (warnings.size() == 1 ? " warning" : " warnings");
        }
        return new ReportStatus(read.file(), report.lab(), report.number(), report.registered(), results, status,
                List.copyOf(warnings));
    }

    private static long count(List<ReportStatus> statuses, Predicate<String> matches) {
        return statuses.stream().map(ReportStatus::status).filter(matches).count();
    }
}

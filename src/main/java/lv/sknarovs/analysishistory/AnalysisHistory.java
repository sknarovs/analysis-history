package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Reads every PDF directly in a folder. No laboratory is recognised yet, so every readable PDF is unrecognised. */
final class AnalysisHistory {
    /** What became of one PDF; {@code lab}, {@code number} and {@code registered} are null when unknown. */
    record ReportStatus(Path file, Lab lab, String number, LocalDateTime registered, int results, String status,
            List<String> warnings) {
    }

    /** One status per PDF, in file-name order, and the warnings of the whole run. */
    record RunResult(List<ReportStatus> statuses, List<String> warnings) {
    }

    private AnalysisHistory() {
    }

    /** Reads the {@code *.pdf} files (any case) directly in {@code dir}; a PDF that fails becomes an error status. */
    static RunResult run(Path dir) throws IOException {
        List<ReportStatus> statuses = new ArrayList<>();
        for (Path pdf : pdfs(dir)) {
            statuses.add(read(pdf));
        }
        return new RunResult(statuses, List.of());
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

    private static List<Path> pdfs(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList();
        }
    }

    private static ReportStatus read(Path pdf) {
        String status;
        try {
            PdfText.read(pdf);
            status = "Unrecognised";
        } catch (IOException | RuntimeException e) {
            status = "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return new ReportStatus(pdf, null, null, null, 0, status, List.of());
    }

    private static long count(List<ReportStatus> statuses, Predicate<String> matches) {
        return statuses.stream().map(ReportStatus::status).filter(matches).count();
    }
}

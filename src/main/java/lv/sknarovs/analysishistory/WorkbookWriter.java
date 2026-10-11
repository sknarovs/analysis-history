package lv.sknarovs.analysishistory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import org.dhatim.fastexcel.HyperLink;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/** Writes the workbook; the only class that uses fastexcel. */
final class WorkbookWriter {
    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final String REGISTERED_FORMAT = "dd.mm.yyyy hh:mm";
    private static final String NO_RESULTS = "–";
    private static final List<String> HEADERS = List.of("Registered", "Lab", "Report no.", "File", "Results", "Status");
    private static final List<Integer> WIDTHS = List.of(17, 6, 12, 36, 8, 40);

    private WorkbookWriter() {
    }

    /**
     * Writes the workbook to a temporary file next to {@code target}, then renames it over {@code target}, so a
     * failed run never leaves a half-written workbook. On any failure the temporary file is deleted.
     */
    static void write(Path target, List<ReportStatus> statuses, LocalDateTime generated) throws IOException {
        Path temp = target.resolveSibling("." + target.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp);
                    Workbook workbook = new Workbook(out, "analysis-history", null)) {
                writeReports(workbook.newWorksheet("Reports"), target, statuses, generated);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** The Reports sheet: a summary line, then one row per PDF, newest first and undated files last. */
    private static void writeReports(Worksheet sheet, Path target, List<ReportStatus> statuses,
            LocalDateTime generated) {
        sheet.value(0, 0, "Generated " + GENERATED.format(generated) + " from " + AnalysisHistory.summary(statuses));
        for (int column = 0; column < HEADERS.size(); column++) {
            sheet.value(2, column, HEADERS.get(column));
            sheet.width(column, WIDTHS.get(column));
        }
        Path folder = target.toAbsolutePath().normalize().getParent();
        List<ReportStatus> rows = new ArrayList<>(statuses);
        rows.sort(Comparator.comparing(ReportStatus::registered, Comparator.nullsLast(Comparator.reverseOrder())));
        int row = 3;
        for (ReportStatus status : rows) {
            if (status.registered() != null) {
                sheet.value(row, 0, status.registered());
                sheet.style(row, 0).format(REGISTERED_FORMAT).set();
            }
            if (status.lab() != null) {
                sheet.value(row, 1, status.lab().label);
            }
            if (status.number() != null) {
                sheet.value(row, 2, status.number());
            }
            sheet.hyperlink(row, 3, new HyperLink(link(folder, status.file()), status.file().getFileName().toString()));
            if (status.results() > 0) {
                sheet.value(row, 4, status.results());
            } else {
                sheet.value(row, 4, NO_RESULTS);
            }
            sheet.value(row, 5, status.status());
            row++;
        }
    }

    /** The PDF relative to the workbook's folder, with {@code /} separators; absolute when on another drive. */
    private static String link(Path folder, Path file) {
        Path pdf = file.toAbsolutePath().normalize();
        try {
            return folder.relativize(pdf).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException differentRoot) {
            return pdf.toUri().toString();
        }
    }
}

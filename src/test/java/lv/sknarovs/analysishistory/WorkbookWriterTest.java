package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbookWriterTest {
    private static final LocalDateTime GENERATED = LocalDateTime.of(2026, 10, 11, 17, 40);

    @Test void writesReportsSheet(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, List.of(
                new ReportStatus(dir.resolve("scan.pdf"), null, null, null, 0, "Unrecognised", List.of()),
                new ReportStatus(dir.resolve("CLab_100000001.pdf"), Lab.CLAB, "100000001",
                        LocalDateTime.of(2026, 3, 17, 8, 40), 12, "OK", List.of())),
                GENERATED);

        XlsxReader.Sheet reports = XlsxReader.read(target).get("Reports");
        assertEquals("Generated 11.10.2026 17:40 from 2 PDFs: 1 tabulated, 0 not tabulated, 1 unrecognised, "
                + "0 duplicates, 0 errors", reports.text("A1"));
        assertEquals(List.of("Registered", "Lab", "Report no.", "File", "Results", "Status"), row(reports, 3));
        // The dated report comes first; its registration is an Excel date-time (17.03.2026 is day 46098).
        assertTrue(reports.isNumber("A4"));
        assertEquals(46098 + (8 * 60 + 40) / 1440.0, Double.parseDouble(reports.text("A4")), 1e-6);
        assertEquals("dd.mm.yyyy hh:mm", reports.numberFormat("A4"));
        assertEquals(List.of("CLab", "100000001", "CLab_100000001.pdf", "12", "OK"), row(reports, 4).subList(1, 6));
        assertTrue(reports.isNumber("E4"));
        assertEquals("CLab_100000001.pdf", reports.hyperlink("D4"));
        // Undated files come last, and a report without results shows a dash.
        assertEquals(Arrays.asList(null, null, null, "scan.pdf", "–", "Unrecognised"), row(reports, 5));
        assertEquals("scan.pdf", reports.hyperlink("D5"));
    }

    @Test void listsNewestReportsFirstAndUndatedOnesLastInFileOrder(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, List.of(
                status(dir.resolve("a.pdf"), LocalDateTime.of(2025, 11, 3, 9, 12)),
                status(dir.resolve("b.pdf"), null),
                status(dir.resolve("c.pdf"), LocalDateTime.of(2026, 3, 17, 8, 40)),
                status(dir.resolve("d.pdf"), null)),
                GENERATED);

        XlsxReader.Sheet reports = XlsxReader.read(target).get("Reports");
        assertEquals(List.of("c.pdf", "a.pdf", "b.pdf", "d.pdf"),
                Stream.of("D4", "D5", "D6", "D7").map(reports::text).toList());
    }

    @Test void replacesAnExistingWorkbook(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");
        WorkbookWriter.write(target, List.of(status(dir.resolve("old.pdf"), null)), GENERATED);

        WorkbookWriter.write(target, List.of(status(dir.resolve("new.pdf"), null)), GENERATED);

        assertEquals("new.pdf", XlsxReader.read(target).get("Reports").text("D4"));
        assertEquals(List.of(target), files(dir));
    }

    @Test void failedRenameKeepsOldWorkbookAndRemovesTemp(@TempDir Path dir) throws IOException {
        // A non-empty directory sits where the workbook should go, so the final rename fails.
        Path target = Files.createDirectory(dir.resolve("analysis-history.xlsx"));
        Path old = Files.writeString(target.resolve("old.txt"), "old");

        assertThrows(IOException.class,
                () -> WorkbookWriter.write(target, List.of(status(dir.resolve("a.pdf"), null)), GENERATED));

        assertEquals(List.of(old), files(target));
        assertEquals("old", Files.readString(old));
        assertEquals(List.of(target), files(dir));
    }

    /** An EGL report with results when {@code registered} is given, otherwise an unrecognised file. */
    private static ReportStatus status(Path file, LocalDateTime registered) {
        return registered == null
                ? new ReportStatus(file, null, null, null, 0, "Unrecognised", List.of())
                : new ReportStatus(file, Lab.EGL, "20000001", registered, 3, "OK", List.of());
    }

    /** The texts of columns A to F of a 1-based row; {@code null} for empty cells. */
    private static List<String> row(XlsxReader.Sheet sheet, int row) {
        return Stream.of("A", "B", "C", "D", "E", "F").map(column -> sheet.text(column + row)).toList();
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }
}

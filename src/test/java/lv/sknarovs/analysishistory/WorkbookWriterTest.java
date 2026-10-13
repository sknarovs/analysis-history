package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbookWriterTest {
    private static final History NONE = new History(List.of(), List.of(), List.of());
    private static final LocalDateTime GENERATED = LocalDateTime.of(2026, 10, 11, 17, 40);

    @Test void writesReportsSheet(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, NONE, List.of(
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
        assertEquals(List.of(17.0, 6.0, 12.0, 36.0, 8.0, 40.0),
                Stream.of("A", "B", "C", "D", "E", "F").map(reports::width).toList());
    }

    @Test void notesTheWarningsOnTheStatusCell(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, NONE, List.of(
                new ReportStatus(dir.resolve("a.pdf"), Lab.EGL, "20000001", LocalDateTime.of(2026, 3, 17, 8, 40), 3,
                        "2 warnings", List.of("first", "second")),
                status(dir.resolve("b.pdf"), LocalDateTime.of(2026, 1, 1, 8, 0))), GENERATED);

        XlsxReader.Sheet reports = XlsxReader.read(target).get("Reports");
        assertEquals("first\nsecond", reports.note("F4"));
        assertEquals("2 warnings", reports.text("F4"));
        assertNull(reports.note("F5"));
    }

    @Test void fileLinkIsRelativeToWorkbookFolder(@TempDir Path dir) throws IOException {
        Path target = Files.createDirectory(dir.resolve("out")).resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, NONE, List.of(status(dir.resolve("Rīga analīzes 1.pdf"), null)), GENERATED);

        assertEquals("../R%C4%ABga%20anal%C4%ABzes%201.pdf",
                XlsxReader.read(target).get("Reports").hyperlink("D4"));
    }

    @Test void writesHeaderRowsAndFreezes(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, history(List.of(row("Hematoloģija", "Hemoglobīns", "g/L", "132–175",
                null, new HistoryCell(new ResultValue.Numeric(new BigDecimal("140"), 0), Flag.NONE, null), null))),
                List.of(), GENERATED);

        Map<String, XlsxReader.Sheet> sheets = XlsxReader.read(target);
        assertEquals(List.of("History", "Reports"), List.copyOf(sheets.keySet()));
        XlsxReader.Sheet history = sheets.get("History");
        assertEquals(List.of("Test", "Unit", "Reference"), Stream.of("A1", "B1", "C1").map(history::text).toList());
        assertTrue(history.isNumber("D1"));
        assertEquals(LocalDate.of(2026, 4, 20).toEpochDay() + 25569, Double.parseDouble(history.text("D1")), 1e-9);
        assertEquals("dd.mm.yyyy", history.numberFormat("D1"));
        assertEquals("CLab", history.text("D2"));
        assertEquals("100000003", history.text("D3"));
        assertEquals("EGL", history.text("E2"));
        assertEquals("D4", history.frozenTopLeft());
        assertEquals(List.of(34.0, 14.0, 12.0, 11.0, 11.0),
                Stream.of("A", "B", "C", "D", "E").map(history::width).toList());
    }

    @Test void writesSectionBandsAndRows(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");
        HistoryCell cell = new HistoryCell(new ResultValue.Numeric(new BigDecimal("140"), 0), Flag.NONE, null);

        WorkbookWriter.write(target, history(List.of(
                row("Hematoloģija", "Hemoglobīns", "g/L", "132–175", null, cell, null),
                row("Hematoloģija", "Eritrocīti", "", "", null, null, cell),
                row("Bioķīmija", "Glikoze", "mmol/L", "", null, cell, cell))), List.of(), GENERATED);

        XlsxReader.Sheet history = XlsxReader.read(target).get("History");
        assertEquals("Hematoloģija", history.text("A4"));
        assertTrue(history.bold("A4"));
        assertEquals("D9D9D9", history.fill("A4"));
        assertEquals("D9D9D9", history.fill("C4"));
        assertEquals("D9D9D9", history.fill("E4"));
        assertEquals(List.of("Hemoglobīns", "g/L", "132–175"),
                Stream.of("A5", "B5", "C5").map(history::text).toList());
        assertEquals("Eritrocīti", history.text("A6"));
        assertNull(history.text("D6"));
        assertEquals("140", history.text("D5"));
        assertEquals("Bioķīmija", history.text("A7"));
        assertTrue(history.bold("A7"));
        assertEquals("Glikoze", history.text("A8"));
        assertFalse(history.bold("A8"));
        // Each section's tests are grouped under its band; the bands themselves are not.
        assertEquals(List.of(0, 1, 1, 0, 1), IntStream.rangeClosed(4, 8).map(history::outlineLevel).boxed().toList());
        // The band is above its rows, so Excel's collapse button belongs above them too.
        assertEquals("0", history.summaryBelow());
    }

    @Test void keepsNumbersNumericWithPrintedDecimals(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, history(List.of(row("S", "CRP", "mg/L", "", null,
                new HistoryCell(new ResultValue.Numeric(new BigDecimal("0.290"), 3), Flag.NONE, null),
                new HistoryCell(new ResultValue.Numeric(new BigDecimal("152"), 0), Flag.NONE, null)))),
                List.of(), GENERATED);

        XlsxReader.Sheet history = XlsxReader.read(target).get("History");
        assertTrue(history.isNumber("D5"));
        assertEquals(0.29, Double.parseDouble(history.text("D5")), 1e-9);
        assertEquals("0.000", history.numberFormat("D5"));
        assertTrue(history.isNumber("E5"));
        assertEquals("0", history.numberFormat("E5"));
    }

    @Test void colorsAndArrowsFlags(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, history(List.of(
                row("S", "A", "u", "", null,
                        new HistoryCell(new ResultValue.Numeric(new BigDecimal("9.1"), 1), Flag.HIGH, null),
                        new HistoryCell(new ResultValue.Numeric(new BigDecimal("1.5"), 1), Flag.LOW, null)),
                row("S", "B", "u", "", null,
                        new HistoryCell(new ResultValue.Compared(">", new BigDecimal("9000"), 0), Flag.HIGH, null),
                        new HistoryCell(new ResultValue.Text("trace"), Flag.NONE, null)),
                row("S", "C", "u", "", null,
                        new HistoryCell(new ResultValue.Text("low"), Flag.LOW, null), null))),
                List.of(), GENERATED);

        XlsxReader.Sheet history = XlsxReader.read(target).get("History");
        assertEquals("C00000", history.fontColor("D5"));
        assertEquals("FDE2E2", history.fill("D5"));
        assertEquals("0.0\" ↑\"", history.numberFormat("D5"));
        assertTrue(history.isNumber("D5"));
        assertEquals("1F4E9E", history.fontColor("E5"));
        assertEquals("DDEBF7", history.fill("E5"));
        assertEquals("0.0\" ↓\"", history.numberFormat("E5"));
        assertEquals(">9000 ↑", history.text("D6"));
        assertFalse(history.isNumber("D6"));
        assertEquals("C00000", history.fontColor("D6"));
        assertEquals("trace", history.text("E6"));
        assertNotEquals("C00000", history.fontColor("E6"));
        assertNull(history.fill("E6"));
        assertEquals("low ↓", history.text("D7"));
        assertEquals("DDEBF7", history.fill("D7"));
    }

    @Test void writesNotes(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, history(List.of(row("S", "Hemoglobīns", "g/L", "", "Not in the catalog",
                new HistoryCell(new ResultValue.Numeric(new BigDecimal("141"), 0), Flag.NONE,
                        "CLab: 14.1 g/dL\nsecond line"), null))), List.of(), GENERATED);

        XlsxReader.Sheet history = XlsxReader.read(target).get("History");
        assertEquals("CLab: 14.1 g/dL\nsecond line", history.note("D5"));
        assertEquals("Not in the catalog", history.note("A5"));
        assertNull(history.note("E5"));
    }

    private static History history(List<HistoryRow> rows) {
        return new History(List.of(
                new Visit(Lab.CLAB, LocalDate.of(2026, 4, 20), List.of(report("100000003"))),
                new Visit(Lab.EGL, LocalDate.of(2026, 3, 17), List.of(report("20000001")))), rows, List.of());
    }

    private static Report report(String number) {
        return new Report(Lab.CLAB, number, LocalDateTime.of(2026, 4, 20, 8, 0), null,
                Path.of(number + ".pdf"), List.of(), List.of(), List.of());
    }

    private static HistoryRow row(String section, String name, String unit, String reference, String nameNote,
            HistoryCell first, HistoryCell second) {
        return new HistoryRow(section, name, unit, reference, nameNote, Arrays.asList(first, second));
    }

    @Test void listsNewestReportsFirstAndUndatedOnesLastInFileOrder(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("analysis-history.xlsx");

        WorkbookWriter.write(target, NONE, List.of(
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
        WorkbookWriter.write(target, NONE, List.of(status(dir.resolve("old.pdf"), null)), GENERATED);

        WorkbookWriter.write(target, NONE, List.of(status(dir.resolve("new.pdf"), null)), GENERATED);

        assertEquals("new.pdf", XlsxReader.read(target).get("Reports").text("D4"));
        assertEquals(List.of(target), files(dir));
    }

    @Test void failedRenameKeepsOldWorkbookAndRemovesTemp(@TempDir Path dir) throws IOException {
        // A non-empty directory sits where the workbook should go, so the final rename fails.
        Path target = Files.createDirectory(dir.resolve("analysis-history.xlsx"));
        Path old = Files.writeString(target.resolve("old.txt"), "old");

        assertThrows(IOException.class,
                () -> WorkbookWriter.write(target, NONE, List.of(status(dir.resolve("a.pdf"), null)), GENERATED));

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

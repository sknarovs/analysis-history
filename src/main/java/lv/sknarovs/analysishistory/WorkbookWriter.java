package lv.sknarovs.analysishistory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import org.dhatim.fastexcel.HyperLink;
import org.dhatim.fastexcel.StyleSetter;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/** Writes the workbook; the only class that uses fastexcel. */
final class WorkbookWriter {
    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final String REGISTERED_FORMAT = "dd.mm.yyyy hh:mm";
    private static final String NO_RESULTS = "–";
    private static final List<String> HEADERS = List.of("Registered", "Lab", "Report no.", "File", "Results", "Status");
    private static final List<Integer> WIDTHS = List.of(17, 6, 12, 36, 8, 40);

    private static final List<String> FIXED_HEADERS = List.of("Test", "Unit", "Reference");
    private static final List<Integer> FIXED_WIDTHS = List.of(34, 14, 12);
    private static final int FIXED_COLUMNS = 3;
    private static final int HEADER_ROWS = 3;
    private static final int VISIT_WIDTH = 11;
    private static final String DATE_FORMAT = "dd.mm.yyyy";
    private static final String BAND_FILL = "D9D9D9";
    private static final String HIGH_FONT = "C00000";
    private static final String HIGH_FILL = "FDE2E2";
    private static final String LOW_FONT = "1F4E9E";
    private static final String LOW_FILL = "DDEBF7";

    private WorkbookWriter() {
    }

    /**
     * Writes the workbook to a temporary file next to {@code target}, then renames it over {@code target}, so a
     * failed run never leaves a half-written workbook. On any failure the temporary file is deleted.
     */
    static void write(Path target, History history, List<ReportStatus> statuses, LocalDateTime generated) throws IOException {
        Path temp = target.resolveSibling("." + target.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp);
                    Workbook workbook = new Workbook(out, "analysis-history", null)) {
                writeHistory(workbook.newWorksheet("History"), history);
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

    /** The History sheet: three header rows, then a band for each section followed by its tests. */
    private static void writeHistory(Worksheet sheet, History history) {
        int columns = FIXED_COLUMNS + history.visits().size();
        // A section's band sits above its rows, so the group's collapse button does too.
        sheet.rowSumsBelow(false);
        for (int column = 0; column < columns; column++) {
            sheet.width(column, column < FIXED_COLUMNS ? FIXED_WIDTHS.get(column) : VISIT_WIDTH);
        }
        for (int column = 0; column < FIXED_COLUMNS; column++) {
            sheet.value(0, column, FIXED_HEADERS.get(column));
            sheet.style(0, column).bold().set();
        }
        for (int column = FIXED_COLUMNS; column < columns; column++) {
            Visit visit = history.visits().get(column - FIXED_COLUMNS);
            sheet.value(0, column, visit.date());
            sheet.style(0, column).format(DATE_FORMAT).bold().set();
            sheet.value(1, column, visit.lab().label);
            sheet.value(2, column, visit.numbers());
        }
        sheet.freezePane(FIXED_COLUMNS, HEADER_ROWS);
        int row = HEADER_ROWS;
        String section = null;
        int sectionStart = 0;
        for (HistoryRow test : history.rows()) {
            if (!test.section().equals(section)) {
                group(sheet, sectionStart, row - 1);
                section = test.section();
                writeBand(sheet, row, section, columns);
                row++;
                sectionStart = row;
            }
            sheet.value(row, 0, test.name());
            if (test.nameNote() != null) {
                sheet.comment(row, 0, test.nameNote());
            }
            sheet.value(row, 1, test.unit());
            sheet.value(row, 2, test.reference());
            for (int visit = 0; visit < test.cells().size(); visit++) {
                HistoryCell cell = test.cells().get(visit);
                if (cell != null) {
                    writeCell(sheet, row, FIXED_COLUMNS + visit, cell);
                }
            }
            row++;
        }
        group(sheet, sectionStart, row - 1);
    }

    private static void group(Worksheet sheet, int first, int last) {
        if (last >= first && first > 0) {
            sheet.groupRows(first, last);
        }
    }

    private static void writeBand(Worksheet sheet, int row, String section, int columns) {
        sheet.value(row, 0, section);
        for (int column = 0; column < columns; column++) {
            sheet.style(row, column).bold().fillColor(BAND_FILL).set();
        }
    }

    /** A number stays numeric, its flag arrow in the number format; any other value is text, right-aligned. */
    private static void writeCell(Worksheet sheet, int row, int column, HistoryCell cell) {
        String arrow = switch (cell.flag()) {
            case HIGH -> " ↑";
            case LOW -> " ↓";
            case NONE -> "";
        };
        if (cell.value() instanceof ResultValue.Numeric number) {
            sheet.value(row, column, number.value());
            String format = number.decimals() == 0 ? "0" : "0." + "0".repeat(number.decimals());
            StyleSetter style = sheet.style(row, column);
            style.format(arrow.isEmpty() ? format : format + "\"" + arrow + "\"");
            flagColors(style, cell.flag());
            style.set();
        } else {
            sheet.value(row, column, cell.value().display() + arrow);
            StyleSetter style = sheet.style(row, column);
            style.horizontalAlignment("right");
            flagColors(style, cell.flag());
            style.set();
        }
        if (cell.note() != null) {
            sheet.comment(row, column, cell.note());
        }
    }

    private static void flagColors(StyleSetter style, Flag flag) {
        switch (flag) {
            case HIGH -> style.fontColor(HIGH_FONT).fillColor(HIGH_FILL);
            case LOW -> style.fontColor(LOW_FONT).fillColor(LOW_FILL);
            case NONE -> { }
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
            if (!status.warnings().isEmpty()) {
                sheet.comment(row, 5, String.join("\n", status.warnings()));
            }
            row++;
        }
    }

    /** A relative path with each segment percent-encoded, so names with spaces or Latvian letters open. */
    private static String encode(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> segment.equals("..") ? segment : URLEncoder.encode(segment, StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    /** The PDF relative to the workbook's folder, with {@code /} separators; absolute when on another drive. */
    private static String link(Path folder, Path file) {
        Path pdf = file.toAbsolutePath().normalize();
        try {
            return encode(folder.relativize(pdf).toString().replace(File.separatorChar, '/'));
        } catch (IllegalArgumentException differentRoot) {
            return pdf.toUri().toString();
        }
    }
}

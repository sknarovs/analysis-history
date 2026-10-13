package lv.sknarovs.analysishistory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lv.sknarovs.analysishistory.Zones.Zone;

/**
 * Reads a Centrālā Laboratorija report. The report number, registration time and personal code come from the patient
 * block, the rows above the first page's {@code Komentāri:} row. Every page repeats that block; a page's results are
 * the rows below its {@code Komentāri:} row up to its first footer row, read in the columns of the last column header
 * (a page that continues a table does not repeat it):
 * <ul>
 * <li>a bold line of one cell from x 150 on is a section, and ends the group;
 * <li>a line of one cell that ends before the value column is a group, such as {@code Leikocitārā formula}; it lasts
 * across pages until the next section or group;
 * <li>a result starts at a row with a name in the name column and a value; the lines up to 12.5 pt below its last line
 * wrap its name, value, unit or previous result, bold or not;
 * <li>a line whose first cell starts in the name column and runs on into the value column is a note, and is skipped;
 * <li>text values are right-aligned under {@code Rezultāts}, so a long one may start a few points left of the value
 * column: a cell that starts at most {@link #VALUE_OVERHANG} left of it and runs on into it is a value.
 * </ul>
 * Before the first column header only sections are read, so a report without one (microbiology) has no results. A
 * {@link RowWarning} reports any other row with text right of the name column, a row below a page's first footer row
 * that would start a result, and a result whose previous result cannot be read (the result itself is kept).
 */
final class ClabParser {
    /** The report number: the lone cell of six to ten digits right of {@link #NUMBER_X} in the patient block. */
    private static final Pattern NUMBER = Pattern.compile("\\d{6,10}");
    /** A date and time as CLab prints them; the hour may have one digit ({@code 14.07.2025 9:08}). */
    private static final Pattern DATE_TIME = Pattern.compile("\\d\\d\\.\\d\\d\\.\\d{4} \\d{1,2}:\\d\\d");
    /**
     * Reads a {@link #DATE_TIME}; dates and times that do not exist, such as 31.02.2026, are rejected, not adjusted.
     */
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.uuuu H:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final Map<String, Flag> ARROWS = Map.of("↑", Flag.HIGH, "↓", Flag.LOW);
    /** How the last row of the patient block starts; a page's results start below it. */
    private static final String COMMENTS = "Komentāri:";
    /** How the first cell of a footer row starts; the first one ends its page's results. */
    private static final List<String> FOOTERS = List.of("Materiāls paņemts", "Materiāls:", "Rezultātus pārbaudīja",
            "PIN kods", "Pārskata sastādīšanas", "Testēšanas", "Bez laboratorijas", "Ar (1)");
    /** How the groups of urine results start, as in {@code Urīna analīze ar stripu}. */
    private static final String URINE_GROUP = "Urīna analīze";
    /** The report number is printed right of this x. */
    private static final float NUMBER_X = 297;
    /** Sections start at this x or right of it; groups start further left. */
    private static final float SECTION_X = 150;
    /**
     * A line at most this many points below a result's last line wraps it; CLab prints wrapped lines 12 pt apart and
     * the rows of a table 13 pt apart, so a row of its own that prints no range (eGFR below creatinine) is not taken in.
     */
    private static final float WRAP_GAP = 12.5f;
    /** A text value may start this many points left of the value column (CLab's start up to about 9 pt left of it). */
    private static final float VALUE_OVERHANG = 20;

    private final List<String> sections = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final List<RowWarning> warnings = new ArrayList<>();
    /** The columns of the last header row; null before the first. */
    private Zones zones;
    private String section = "";
    private String group;
    /** The last result read while lines below it may still wrap it; null when none may. */
    private OpenResult open;

    private ClabParser() {
    }

    /**
     * Reads the report in {@code pages}; {@code file} is only recorded in the report.
     *
     * @throws ReportFormatException if the report number or the registration time is missing or cannot be read
     */
    static Report parse(Path file, List<Page> pages) throws ReportFormatException {
        List<Row> patient = patientBlock(pages);
        String number = number(patient);
        LocalDateTime registered = registered(patient);
        String personalCode = cellAfter(patient, "Pers.kods").orElse(null);
        ClabParser parser = new ClabParser();
        parser.readTable(pages);
        return new Report(Lab.CLAB, number, registered, personalCode, file, List.copyOf(parser.sections),
                List.copyOf(parser.results), List.copyOf(parser.warnings));
    }

    private void readTable(List<Page> pages) {
        for (Page page : pages) {
            readPage(page);
            // No result wraps onto the next page.
            close();
        }
    }

    /**
     * Reads a page's results: the rows below its {@code Komentāri:} row, which ends the patient block, up to its first
     * footer row; a page without a {@code Komentāri:} row has none. Below the first footer row nothing is read, but a
     * row that would start a result is reported rather than lost; further footer rows are not.
     */
    private void readPage(Page page) {
        boolean started = false;
        boolean ended = false;
        List<RowWarning> unplaced = new ArrayList<>();
        for (Row row : page.rows()) {
            if (!started) {
                started = startsWith(row, COMMENTS);
                // Without its Komentāri: row a page after the first is not read, but its result rows are reported.
                if (!started && zones != null && page.number() > 1 && startsResult(row)) {
                    unplaced.add(new RowWarning(page.number(), row.text()));
                }
            } else if (isFooter(row)) {
                close();
                ended = true;
            } else if (!ended) {
                readRow(page.number(), row);
            } else if (zones != null && startsResult(row)) {
                warnings.add(new RowWarning(page.number(), row.text()));
            }
        }
        if (!started) {
            warnings.addAll(unplaced);
        }
    }

    /** A row either wraps the open result, or ends it and is read on its own. */
    private void readRow(int page, Row row) {
        Optional<Zones> header = Zones.clab(row);
        if (open != null && header.isEmpty() && wraps(row)) {
            open.add(row, zones);
            return;
        }
        close();
        if (header.isPresent()) {
            zones = header.get();
        } else if (isSection(row)) {
            section = row.first().text();
            group = null;
            sections.add(section);
        } else if (zones != null) {
            readLine(page, row);
        }
    }

    /**
     * Reads a row below the first header that wraps no result: a group title, the start of a result, or a row that
     * belongs to nothing, reported when it has text right of the name column. A note ({@link #isNote}) is skipped.
     */
    private void readLine(int page, Row row) {
        Cell first = row.first();
        if (isOneCellInNameColumn(row) && first.xEnd() < zones.value()) {
            group = first.text();
        } else if (isNote(row)) {
            return;
        } else if (startsResult(row)) {
            open = new OpenResult(page, row, section, group, specimen(), zones);
        } else if (reachesResultColumns(row)) {
            warnings.add(new RowWarning(page, row.text()));
        }
    }

    /**
     * Whether {@code row} wraps the open result: it is at most {@link #WRAP_GAP} below the result's last line, is not a
     * note, and prints only more of the result's name, value, unit or previous result, nothing for its range.
     */
    private boolean wraps(Row row) {
        return row.baseline() - open.baseline <= WRAP_GAP && !isNote(row) && !prints(row, Field.RANGE);
    }

    /** Whether {@code row} starts a result: its first cell is in the name column, and it prints a value. */
    private boolean startsResult(Row row) {
        return inNameColumn(row.first()) && prints(row, Field.VALUE);
    }

    /**
     * A note: its first cell starts in the name column and runs on into the value column, as sentences do; a cell may
     * follow it, such as the end of the INR note in the range column.
     */
    private boolean isNote(Row row) {
        return inNameColumn(row.first()) && row.first().xEnd() >= zones.value();
    }

    private boolean isOneCellInNameColumn(Row row) {
        return row.cells().size() == 1 && inNameColumn(row.first());
    }

    private boolean inNameColumn(Cell cell) {
        return zone(cell, zones) == Zone.NAME;
    }

    /**
     * The zone {@code cell} is in: the one it starts in, except that a cell starting at most {@link #VALUE_OVERHANG}
     * left of the value column and running on into it is a right-aligned value.
     */
    private static Zone zone(Cell cell, Zones zones) {
        Zone zone = zones.of(cell.x());
        if (zone == Zone.NAME && cell.x() >= zones.value() - VALUE_OVERHANG && cell.xEnd() > zones.value()) {
            return Zone.VALUE;
        }
        return zone;
    }

    /** Whether any cell of {@code row} starts right of the name column, where values, units and ranges go. */
    private boolean reachesResultColumns(Row row) {
        return row.cells().stream().anyMatch(cell -> !inNameColumn(cell));
    }

    /** Whether a cell of {@code row} holds {@code field}. */
    private boolean prints(Row row, Field field) {
        return row.cells().stream().anyMatch(cell -> field(cell, zones) == field);
    }

    /** Rows under a group whose title starts with {@code Urīna analīze} are urine; all others blood. */
    private Specimen specimen() {
        return group != null && group.startsWith(URINE_GROUP) ? Specimen.URINE : Specimen.BLOOD;
    }

    /**
     * Adds the open result to the results, once no further line can wrap it. A previous result that is printed but
     * cannot be read is left out, and the result's row is reported.
     */
    private void close() {
        if (open != null) {
            Result result = open.result();
            results.add(result);
            if (result.previous() == null && open.printsPrevious()) {
                warnings.add(new RowWarning(open.page, open.rowText));
            }
            open = null;
        }
    }

    private static boolean isSection(Row row) {
        Cell cell = row.first();
        return row.cells().size() == 1 && cell.bold() && cell.x() >= SECTION_X;
    }

    private static boolean isFooter(Row row) {
        return FOOTERS.stream().anyMatch(footer -> startsWith(row, footer));
    }

    /** The rows above the first page's {@code Komentāri:} row; the whole page when it has none. */
    private static List<Row> patientBlock(List<Page> pages) {
        List<Row> block = new ArrayList<>();
        for (Row row : pages.isEmpty() ? List.<Row>of() : pages.get(0).rows()) {
            if (startsWith(row, COMMENTS)) {
                break;
            }
            block.add(row);
        }
        return block;
    }

    private static boolean startsWith(Row row, String prefix) {
        return row.first().text().startsWith(prefix);
    }

    /**
     * The report number. The block prints it once, or the same number twice; two different numbers leave it unknown.
     * They are not named in the message, as either could be personal (a phone number).
     */
    private static String number(List<Row> patientBlock) throws ReportFormatException {
        List<String> numbers = patientBlock.stream()
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell.x() > NUMBER_X && NUMBER.matcher(cell.text()).matches())
                .map(Cell::text)
                .distinct()
                .toList();
        if (numbers.isEmpty()) {
            throw new ReportFormatException("no report number (6 to 10 digits above Komentāri:)");
        }
        if (numbers.size() > 1) {
            throw new ReportFormatException("more than one report number (6 to 10 digits above Komentāri:)");
        }
        return numbers.get(0);
    }

    private static LocalDateTime registered(List<Row> patientBlock) throws ReportFormatException {
        String text = cellAfter(patientBlock, "Reģistrēts")
                .orElseThrow(() -> new ReportFormatException("no registration date (Reģistrēts)"));
        return dateTime(text).orElseThrow(
                () -> new ReportFormatException("registration date is not a date dd.mm.yyyy hh:mm: " + text));
    }

    /**
     * The date and time that {@code text} prints as dd.mm.yyyy h:mm; empty for any other text, and for a date or time
     * that does not exist.
     */
    private static Optional<LocalDateTime> dateTime(String text) {
        try {
            return Optional.of(LocalDateTime.parse(text, DATE_TIME_FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The text of the cell right after the first cell that reads {@code label}. */
    private static Optional<String> cellAfter(List<Row> rows, String label) {
        for (Row row : rows) {
            List<Cell> cells = row.cells();
            for (int i = 1; i < cells.size(); i++) {
                if (cells.get(i - 1).text().equals(label)) {
                    return Optional.of(cells.get(i).text());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * What {@code cell} holds. In the name column it is part of the name. Right of it, its shape decides first: an
     * arrow is the flag (CLab prints it where the unit column starts), a date and time is the previous result's
     * date, and text in parentheses is the range; any other text belongs to its zone (see {@link #zone}).
     */
    private static Field field(Cell cell, Zones zones) {
        Zone zone = zone(cell, zones);
        String text = cell.text();
        if (zone != Zone.NAME) {
            if (ARROWS.containsKey(text)) {
                return Field.FLAG;
            }
            if (DATE_TIME.matcher(text).matches()) {
                return Field.PREVIOUS_DATE;
            }
            if (ReferenceRange.PARENTHESISED.matcher(text).matches()) {
                return Field.RANGE;
            }
        }
        return switch (zone) {
            case NAME -> Field.NAME;
            case VALUE -> Field.VALUE;
            case UNIT -> Field.UNIT;
            case RANGE -> Field.RANGE;
            case PREVIOUS -> Field.PREVIOUS;
        };
    }

    /** What a cell of a results row holds: a part of one of the result's fields, or its arrow. */
    private enum Field {
        NAME,
        VALUE,
        UNIT,
        RANGE,
        PREVIOUS,
        PREVIOUS_DATE,
        FLAG
    }

    /**
     * A result being read: the texts of its row and of the lines that wrap it, by field, and its arrow. {@code page}
     * and {@code rowText} are those of the row that starts it, {@code baseline} that of its last line.
     */
    private static final class OpenResult {
        private final int page;
        private final String rowText;
        private final String section;
        private final String group;
        private final Specimen specimen;
        private final Map<Field, String> texts = new EnumMap<>(Field.class);
        private Flag flag = Flag.NONE;
        private float baseline;

        OpenResult(int page, Row row, String section, String group, Specimen specimen, Zones zones) {
            this.page = page;
            this.rowText = row.text();
            this.section = section;
            this.group = group;
            this.specimen = specimen;
            add(row, zones);
        }

        /** Adds a line: an arrow sets the flag, and any other cell joins the text of its field. */
        void add(Row row, Zones zones) {
            for (Cell cell : row.cells()) {
                Field field = field(cell, zones);
                if (field == Field.FLAG) {
                    flag = ARROWS.get(cell.text());
                } else {
                    texts.merge(field, cell.text(), (text, more) -> WrappedText.join(text, more, field == Field.UNIT));
                }
            }
            baseline = row.baseline();
        }

        boolean printsPrevious() {
            return texts.containsKey(Field.PREVIOUS) || texts.containsKey(Field.PREVIOUS_DATE);
        }

        Result result() {
            return new Result(section, group, specimen, text(Field.NAME), ResultValue.parse(text(Field.VALUE)),
                    text(Field.UNIT), ReferenceRange.parse(text(Field.RANGE)), flag, previous());
        }

        /**
         * The previous result: its value, and the one date and time beside it; null when either is missing, or when
         * the date cannot be read (two dates, or one that does not exist).
         */
        private Previous previous() {
            String value = text(Field.PREVIOUS);
            Optional<LocalDateTime> date = dateTime(text(Field.PREVIOUS_DATE));
            if (value.isEmpty() || date.isEmpty()) {
                return null;
            }
            return new Previous(ResultValue.parse(value), date.get().toLocalDate(), date.get().toLocalTime());
        }

        private String text(Field field) {
            return texts.getOrDefault(field, "");
        }
    }
}

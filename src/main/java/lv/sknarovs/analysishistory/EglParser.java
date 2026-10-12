package lv.sknarovs.analysishistory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lv.sknarovs.analysishistory.Zones.Zone;

/**
 * Reads an E. Gulbja Laboratorija report. The report number, registration time and personal code come from the patient
 * block. Results come from the rows below each page's column header, up to the report's first footer row:
 * <ul>
 * <li>a result starts at a row whose first cell is an index such as {@code 3/39 A} (the letter is the sample, {@code A}
 * blood or {@code U} urine; calculated tests have none) and has a value;
 * <li>the lines below it that start at its name, or right of the name column, wrap its name, value, unit, range or
 * previous result;
 * <li>bold lines indented in the name column are sections, and the line {@code Mikroskopija} starts the urine sediment
 * rows; both end the result above them;
 * <li>other lines indented past the result's name are notes, sub-headings or interpretation tables, and are skipped;
 * <li>any other line with text where results go becomes a {@link RowWarning}.
 * </ul>
 */
final class EglParser {
    private static final Pattern NUMBER = Pattern.compile("Testēšanas pārskats Nr\\. (\\d+)#");
    /** The two-digit years of both dates are read as 20yy. */
    private static final DateTimeFormatter REGISTERED = DateTimeFormatter.ofPattern("dd.MM.yy,HH:mm");
    private static final DateTimeFormatter PREVIOUS_DATE = DateTimeFormatter.ofPattern("dd.MM.yy");
    /** A result's index, then its sample code (group 1) when it has one. */
    private static final Pattern INDEX = Pattern.compile("\\d+/\\d+(?: ([AU]))?");
    private static final Pattern PAGE_NUMBER = Pattern.compile("\\d+/\\d+");
    /** A previous result: its value (group 1) and date (group 2). */
    private static final Pattern PREVIOUS = Pattern.compile("(.+) / (\\d\\d\\.\\d\\d\\.\\d\\d)");
    /** The arrows, which extract from their Wingdings glyphs as these letters. */
    private static final Map<String, Flag> ARROWS =
            Map.of("ñ", Flag.HIGH, "ññ", Flag.HIGH, "ò", Flag.LOW, "òò", Flag.LOW);
    /** How the first cell of a footer row starts; the first footer row ends the results for the whole report. */
    private static final List<String> FOOTERS = List.of("Pārskata beigas", "Materiāls ņemts", "#");
    /** The second line of the header cell {@code References intervāls}. */
    private static final String HEADER_SECOND_LINE = "intervāls";
    private static final String URINE_SECTION = "Urīna analīze";
    private static final String MICROSCOPY = "Mikroskopija";
    /** Index cells start left of this x. */
    private static final float INDEX_END = 50;
    /** Where the name column starts; notes below no result are measured from here. */
    private static final float NAME_X = 62;
    /** Sections start right of this x. */
    private static final float SECTION_X = 70;
    /** Page numbers start right of this x. */
    private static final float PAGE_NUMBER_X = 200;
    /** Lines in the name column that start more than this many points right of the result's name are notes. */
    private static final float NOTE_INDENT = 10;
    /** A wrapped name starts within this many points of the name it continues. */
    private static final float NAME_TOLERANCE = 3;

    private final List<String> sections = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final List<RowWarning> warnings = new ArrayList<>();
    private String section = "";
    private String group;
    /** The last result read while lines below it may still wrap it; null when none may. */
    private OpenResult open;

    private EglParser() {
    }

    /**
     * Reads the report in {@code pages}; {@code file} is only recorded in the report.
     *
     * @throws ReportFormatException if the report number or the registration date is missing
     */
    static Report parse(Path file, List<Page> pages) throws ReportFormatException {
        String number = number(pages);
        LocalDateTime registered = registered(pages);
        String personalCode = cellAfter(pages, "Personas kods").orElse(null);
        EglParser parser = new EglParser();
        parser.readTable(pages);
        return new Report(Lab.EGL, number, registered, personalCode, file, List.copyOf(parser.sections),
                List.copyOf(parser.results), List.copyOf(parser.warnings));
    }

    private void readTable(List<Page> pages) {
        for (Page page : pages) {
            Zones zones = null;
            for (Row row : page.rows()) {
                Optional<Zones> header = Zones.egl(row);
                if (header.isPresent()) {
                    zones = header.get();
                    continue;
                }
                // Rows above the page's header are its patient block.
                if (zones == null || isFurniture(row)) {
                    continue;
                }
                if (isFooter(row)) {
                    close();
                    return;
                }
                readRow(page.number(), row, zones);
            }
        }
        close();
    }

    private void readRow(int page, Row row, Zones zones) {
        Cell first = row.first();
        Matcher index = INDEX.matcher(first.text());
        if (first.x() < INDEX_END && index.matches()) {
            start(row, index.group(1), zones);
            return;
        }
        boolean oneCell = row.cells().size() == 1;
        boolean inNameColumn = zones.of(first.x()) == Zone.NAME;
        if (oneCell && first.bold() && inNameColumn && first.x() > SECTION_X) {
            close();
            section = first.text();
            group = null;
            sections.add(section);
            return;
        }
        if (oneCell && !first.bold() && first.text().equals(MICROSCOPY)) {
            close();
            group = MICROSCOPY;
            return;
        }
        float nameX = open != null ? open.nameX : NAME_X;
        if (inNameColumn && first.x() > nameX + NOTE_INDENT) {
            return;
        }
        if (open != null && (!inNameColumn || Math.abs(first.x() - nameX) <= NAME_TOLERANCE)) {
            row.cells().forEach(cell -> open.add(cell, zones));
            return;
        }
        if (row.cells().stream().anyMatch(cell -> zones.of(cell.x()) != Zone.NAME)) {
            warnings.add(new RowWarning(page, row.text()));
        }
    }

    /** Opens the result of an index row; a row without a value ({@code Rezultātu novērtējums}) leaves none open. */
    private void start(Row row, String code, Zones zones) {
        close();
        List<Cell> cells = row.cells().subList(1, row.cells().size());
        float nameX = cells.stream().filter(cell -> zones.of(cell.x()) == Zone.NAME).findFirst().map(Cell::x)
                .orElse(NAME_X);
        OpenResult result = new OpenResult(section, group, specimen(code), nameX);
        cells.forEach(cell -> result.add(cell, zones));
        if (result.hasValue()) {
            open = result;
        }
    }

    /** Microscopy rows are urine sediment; otherwise the sample code decides, and without one the section. */
    private Specimen specimen(String code) {
        if (MICROSCOPY.equals(group)) {
            return Specimen.URINE_SEDIMENT;
        }
        if (code == null) {
            return section.equals(URINE_SECTION) ? Specimen.URINE : Specimen.BLOOD;
        }
        return code.equals("U") ? Specimen.URINE : Specimen.BLOOD;
    }

    /** Adds the open result to the results, once no further line can wrap it. */
    private void close() {
        if (open != null) {
            results.add(open.result());
            open = null;
        }
    }

    /** The page number ({@code 1/2}) and the header's second line, wherever they fall between results. */
    private static boolean isFurniture(Row row) {
        Cell cell = row.first();
        return row.cells().size() == 1 && (cell.text().equals(HEADER_SECOND_LINE)
                || cell.x() > PAGE_NUMBER_X && PAGE_NUMBER.matcher(cell.text()).matches());
    }

    private static boolean isFooter(Row row) {
        String text = row.first().text();
        return FOOTERS.stream().anyMatch(text::startsWith);
    }

    private static String number(List<Page> pages) throws ReportFormatException {
        for (Page page : pages) {
            for (Row row : page.rows()) {
                Matcher number = NUMBER.matcher(row.text());
                if (number.find()) {
                    return number.group(1);
                }
            }
        }
        throw new ReportFormatException("no report number (Testēšanas pārskats Nr.)");
    }

    private static LocalDateTime registered(List<Page> pages) throws ReportFormatException {
        String text = cellAfter(pages, "Reģistrēts laboratorijā")
                .orElseThrow(() -> new ReportFormatException("no registration date (Reģistrēts laboratorijā)"));
        try {
            return LocalDateTime.parse(text, REGISTERED);
        } catch (DateTimeParseException e) {
            throw new ReportFormatException("registration date is not dd.mm.yy,hh:mm: " + text);
        }
    }

    /** The text of the cell right after the first cell that reads {@code label}. */
    private static Optional<String> cellAfter(List<Page> pages, String label) {
        for (Page page : pages) {
            for (Row row : page.rows()) {
                List<Cell> cells = row.cells();
                for (int i = 1; i < cells.size(); i++) {
                    if (cells.get(i - 1).text().equals(label)) {
                        return Optional.of(cells.get(i).text());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** A result being read: the texts of its row and of the lines that wrap it, by zone, and its arrow. */
    private static final class OpenResult {
        private final String section;
        private final String group;
        private final Specimen specimen;
        /** Where its name starts. */
        private final float nameX;
        private final Map<Zone, String> texts = new EnumMap<>(Zone.class);
        private Flag flag = Flag.NONE;

        OpenResult(String section, String group, Specimen specimen, float nameX) {
            this.section = section;
            this.group = group;
            this.specimen = specimen;
            this.nameX = nameX;
        }

        /**
         * Adds a cell to the text of its zone after a space, or right after a unit that ends with {@code /}
         * ({@code ml/min/} and {@code 1.73 m²}). An arrow sets the flag wherever it is.
         */
        void add(Cell cell, Zones zones) {
            Flag arrow = ARROWS.get(cell.text());
            if (arrow != null) {
                flag = arrow;
                return;
            }
            Zone zone = zones.of(cell.x());
            texts.merge(zone, cell.text(),
                    (text, more) -> text + (zone == Zone.UNIT && text.endsWith("/") ? "" : " ") + more);
        }

        boolean hasValue() {
            return texts.containsKey(Zone.VALUE);
        }

        Result result() {
            return new Result(section, group, specimen, text(Zone.NAME), ResultValue.parse(text(Zone.VALUE)),
                    text(Zone.UNIT), ReferenceRange.parse(text(Zone.RANGE)), flag, previous());
        }

        /** The previous result, printed {@code <value> / dd.mm.yy}; null when there is none in that form. */
        private Previous previous() {
            Matcher previous = PREVIOUS.matcher(text(Zone.PREVIOUS));
            if (!previous.matches()) {
                return null;
            }
            return new Previous(ResultValue.parse(previous.group(1)), LocalDate.parse(previous.group(2), PREVIOUS_DATE),
                    null);
        }

        private String text(Zone zone) {
            return texts.getOrDefault(zone, "");
        }
    }
}

package lv.sknarovs.analysishistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lv.sknarovs.analysishistory.CatalogMatch.Matched;
import lv.sknarovs.analysishistory.CatalogMatch.UnitMismatch;
import lv.sknarovs.analysishistory.ReferenceRange.None;

/**
 * Lays the reports out as the History sheet (spec §4.1): a column per visit, newest first, and a row per test, by
 * section, with every value converted to its row's unit and flagged (§5.5). Pure logic: it reads and writes nothing.
 * <ul>
 * <li><b>Visits.</b> Reports with results are taken in {@link Report#REGISTRATION_ORDER}. Each joins the latest column
 * of its laboratory and registration date if that column has none of its tests ({@link RowKey}), or starts a new one,
 * so that two reports of one day with the same test keep both results and each row's results stay in time order.
 * Columns are ordered by their last report, newest first.
 * <li><b>Rows.</b> One per test that some report has: the catalog's sections in catalog order, then other sections in
 * the order first seen; within a section the catalog's tests in catalog order, then the unmapped tests (unknown to the
 * catalog, or printed in a unit it does not expect) in the order first seen. Results are seen newest visit first, the
 * reports of a visit in registration order, and each report's results in printed order. An unmapped test takes its
 * name, unit and section from the result first seen.
 * <li><b>Reference and flags.</b> A row's reference is the newest range printed in it, in the row's unit. A value is
 * judged against the range its report printed, or against the row's reference when the report printed none; when that
 * tells nothing, the laboratory's arrow decides.
 * </ul>
 */
final class HistoryBuilder {
    /** The section of an unmapped test that its report printed before any section heading. */
    private static final String OTHER_SECTION = "Citi";
    /** The note on the name of an unmapped test; {@code %s} is the laboratory's label. */
    private static final String UNMAPPED_NOTE =
            "Only in %s reports. Add an alias to catalog.txt to merge it with other labs.";
    /** The note on the name of a test printed in another unit than its alias expects. */
    private static final String MISMATCH_NOTE = "Printed in %s; the catalog expects %s.";
    /** Where an unmapped test sorts among the catalog's tests of its section: after all of them. */
    private static final int AFTER_CATALOG_TESTS = Integer.MAX_VALUE;

    /** A printed result, what the catalog makes of it, and the row it belongs in. */
    private record Entry(Report report, Result result, CatalogMatch match, RowKey key) {
        /** The factor into the row's unit: the catalog's for a mapped test, otherwise 1. */
        BigDecimal factor() {
            return match instanceof Matched matched ? matched.factor() : BigDecimal.ONE;
        }

        /** Whether the value is converted into the row's unit: a number, also with a comparator, and a factor not 1. */
        boolean converted() {
            return !(result.value() instanceof ResultValue.Text) && factor().compareTo(BigDecimal.ONE) != 0;
        }

        /** The value in the row's unit. */
        ResultValue value() {
            return result.value().scaled(factor());
        }

        /** The printed range in the row's unit. */
        ReferenceRange range() {
            return result.range().scaled(factor());
        }

        String fileName() {
            return report.file().getFileName().toString();
        }
    }

    /** A visit being assembled: reports of one laboratory and day in registration order, and the tests they have. */
    private static final class Column {
        private final Lab lab;
        private final LocalDate date;
        private final List<Report> reports = new ArrayList<>();
        private final List<Entry> entries = new ArrayList<>();
        private final Set<RowKey> keys = new HashSet<>();

        Column(Report first) {
            lab = first.lab();
            date = first.registered().toLocalDate();
        }

        /** Whether {@code report} is of this visit's laboratory and day. */
        boolean isOf(Report report) {
            return report.lab() == lab && report.registered().toLocalDate().equals(date);
        }

        /** Whether the visit has none of the tests of {@code results}. */
        boolean admits(List<Entry> results) {
            return results.stream().noneMatch(entry -> keys.contains(entry.key()));
        }

        void add(Report report, List<Entry> results) {
            reports.add(report);
            entries.addAll(results);
            results.forEach(entry -> keys.add(entry.key()));
        }

        /** The report added last, which is the latest in {@link Report#REGISTRATION_ORDER}. */
        Report last() {
            return reports.getLast();
        }

        Visit visit() {
            return new Visit(lab, date, List.copyOf(reports));
        }
    }

    /** A row being filled: its heading, from the result first seen, and per visit the result in that column or null. */
    private static final class RowResults {
        private final String section;
        private final String name;
        private final String unit;
        private final String nameNote;
        private final boolean unmapped;
        private final int order;
        private final Entry[] cells;

        RowResults(Entry first, int visits) {
            CatalogTest test = first.key().test();
            if (test != null) {
                section = test.section();
                name = test.name();
                unit = test.unit();
                nameNote = null;
                unmapped = false;
                order = test.order();
            } else {
                Result result = first.result();
                section = result.section().isBlank() ? OTHER_SECTION : result.section();
                name = result.name();
                unit = result.unit();
                if (first.match() instanceof UnitMismatch mismatch) {
                    nameNote = MISMATCH_NOTE.formatted(mismatch.printedUnit().isBlank() ? "no unit"
                            : mismatch.printedUnit(), mismatch.expectedUnit());
                    unmapped = false;
                } else {
                    nameNote = UNMAPPED_NOTE.formatted(first.report().lab().label);
                    unmapped = true;
                }
                order = AFTER_CATALOG_TESTS;
            }
            cells = new Entry[visits];
        }

        /** Puts {@code entry} in the cell of visit {@code column}; false, changing nothing, when it is taken. */
        boolean place(int column, Entry entry) {
            if (cells[column] != null) {
                return false;
            }
            cells[column] = entry;
            return true;
        }

        HistoryRow row() {
            ReferenceRange reference = Arrays.stream(cells)
                    .filter(Objects::nonNull)
                    .map(Entry::range)
                    .filter(range -> !(range instanceof None))
                    .findFirst()
                    .orElse(new None());
            List<HistoryCell> shown = Arrays.stream(cells)
                    .map(entry -> entry == null ? null : cell(entry, reference, unit))
                    .toList();
            return new HistoryRow(section, name, unit, reference.display(), nameNote, unmapped, shown);
        }
    }

    private HistoryBuilder() {
    }

    /**
     * The History sheet of {@code reports}, in any order, with their tests merged by {@code catalog}. A report without
     * results gets no column. The warnings, each starting with the report's file name, are about results printed in
     * another unit than the catalog expects, and about a test that one report has more than once, of which only the
     * first result is shown.
     */
    static History build(List<Report> reports, TestCatalog catalog) {
        List<Column> columns = columns(reports, catalog);
        Map<RowKey, RowResults> rows = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (int column = 0; column < columns.size(); column++) {
            for (Entry entry : columns.get(column).entries) {
                if (entry.match() instanceof UnitMismatch mismatch) {
                    warnings.add(entry.fileName() + ": " + entry.result().name() + " is printed in '"
                            + mismatch.printedUnit() + "' but the catalog expects '" + mismatch.expectedUnit()
                            + "'; kept in its own row");
                }
                RowResults row = rows.computeIfAbsent(entry.key(), key -> new RowResults(entry, columns.size()));
                if (!row.place(column, entry)) {
                    warnings.add(entry.fileName() + ": " + row.name + " appears more than once; only the first "
                            + "result is shown");
                }
            }
        }
        return new History(columns.stream().map(Column::visit).toList(), ordered(rows.values(), catalog),
                List.copyOf(warnings));
    }

    /** The visits' columns, newest first. */
    private static List<Column> columns(List<Report> reports, TestCatalog catalog) {
        List<Report> registered = reports.stream()
                .filter(report -> !report.results().isEmpty())
                .sorted(Report.REGISTRATION_ORDER)
                .toList();
        List<Column> columns = new ArrayList<>();
        for (Report report : registered) {
            List<Entry> results = entries(report, catalog);
            // Only the day's latest column may take the report: an earlier one would then sort as newer than a later
            // one, and a result could show before a newer result of the same test.
            Column column = columns.reversed().stream()
                    .filter(candidate -> candidate.isOf(report))
                    .findFirst()
                    .filter(latest -> latest.admits(results))
                    .orElse(null);
            if (column == null) {
                column = new Column(report);
                columns.add(column);
            }
            column.add(report, results);
        }
        columns.sort(Comparator.comparing(Column::last, Report.REGISTRATION_ORDER).reversed());
        return columns;
    }

    /** {@code report}'s results in printed order. */
    private static List<Entry> entries(Report report, TestCatalog catalog) {
        return report.results().stream()
                .map(result -> new Entry(report, result,
                        catalog.match(report.lab(), result.specimen(), result.name(), result.unit()),
                        RowKey.of(report.lab(), result, catalog)))
                .toList();
    }

    /**
     * The rows in the sheet's order. {@code rows} are in the order first seen, which the stable sort keeps among the
     * unmapped tests.
     */
    private static List<HistoryRow> ordered(Collection<RowResults> rows, TestCatalog catalog) {
        List<String> sections = new ArrayList<>(catalog.sections());
        for (RowResults row : rows) {
            if (!sections.contains(row.section)) {
                sections.add(row.section);
            }
        }
        return rows.stream()
                .sorted(Comparator.comparingInt((RowResults row) -> sections.indexOf(row.section))
                        .thenComparingInt(row -> row.order))
                .map(RowResults::row)
                .toList();
    }

    /** {@code entry} as the sheet shows it in a row with {@code reference} and {@code unit}. */
    private static HistoryCell cell(Entry entry, ReferenceRange reference, String unit) {
        Result result = entry.result();
        ResultValue value = entry.value();
        ReferenceRange range = entry.range();
        // judge() tells nothing of a text value, of a comparator value that may lie on either side of a bound, and
        // against a text or missing range; then the laboratory's arrow decides.
        Flag flag = (range instanceof None ? reference : range).judge(value).orElse(result.labFlag());
        List<String> notes = new ArrayList<>();
        if (entry.converted()) {
            notes.add((entry.report().lab().label + ": " + result.value().display() + " " + result.unit()).strip());
        }
        if (!(range instanceof None) && !range.sameAs(reference)) {
            notes.add(("Ref. " + range.display() + " " + unit).strip());
        }
        return new HistoryCell(value, flag, notes.isEmpty() ? null : String.join("\n", notes));
    }
}

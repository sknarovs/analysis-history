package lv.sknarovs.analysishistory;

import java.util.List;

/**
 * One test's row of the History sheet. {@code reference} is the reference range shown beside the unit ({@code ""}
 * when no result in the row printed one), {@code nameNote} the note on the test's name (null when none), and
 * {@code unmapped} whether the note says the catalog lacks an alias for the test, and {@code cells} has one entry per visit, in the order of the visits, null where the visit has no result of the test.
 */
record HistoryRow(String section, String name, String unit, String reference, String nameNote,
        boolean unmapped, List<HistoryCell> cells) {
    /** A row whose note, if any, says the catalog lacks an alias for it. */
    HistoryRow(String section, String name, String unit, String reference, String nameNote,
            List<HistoryCell> cells) {
        this(section, name, unit, reference, nameNote, nameNote != null, cells);
    }
}

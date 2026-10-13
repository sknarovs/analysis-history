package lv.sknarovs.analysishistory;

/**
 * A result as the History sheet shows it: the {@code value} converted to its row's unit, its {@code flag} (spec §5.5),
 * and the {@code note} on the cell, one remark per line separated by {@code \n}, or null when there is nothing to
 * remark.
 */
record HistoryCell(ResultValue value, Flag flag, String note) {
}

package lv.sknarovs.analysishistory;

/** A row with text where results go that belongs to no result; {@code page} is 1-based, {@code text} the row's text. */
record RowWarning(int page, String text) {
}

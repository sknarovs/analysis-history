package lv.sknarovs.analysishistory.model;

/** A row with text where results go that belongs to no result; {@code page} is 1-based, {@code text} the row's text. */
public record RowWarning(int page, String text) {
}

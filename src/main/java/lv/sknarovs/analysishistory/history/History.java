package lv.sknarovs.analysishistory.history;

import java.util.List;

/**
 * The History sheet: the {@code visits}, newest first, the {@code rows} in the order shown, each with a cell per
 * visit, and the {@code warnings} raised while placing the results in them.
 */
public record History(List<Visit> visits, List<HistoryRow> rows, List<String> warnings) {
}

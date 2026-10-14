package lv.sknarovs.analysishistory.pdf;

import java.util.List;

/** One page of a PDF; {@code number} is 1-based and {@code rows} run from the top of the page down. */
public record Page(int number, List<Row> rows) {
}

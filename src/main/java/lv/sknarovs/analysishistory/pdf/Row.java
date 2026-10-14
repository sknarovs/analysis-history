package lv.sknarovs.analysishistory.pdf;

import java.util.List;
import java.util.stream.Collectors;

/** The cells of one line of a page, left to right; {@code baseline} is measured down from the top of the page. */
public record Row(float baseline, List<Cell> cells) {
    public Cell first() {
        return cells.get(0);
    }

    /** The cell texts joined with {@code " | "}. */
    public String text() {
        return cells.stream().map(Cell::text).collect(Collectors.joining(" | "));
    }
}

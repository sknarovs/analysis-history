package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads text fixtures that describe PDF pages the way {@link PdfText} reads them. UTF-8; {@code #} lines and blank
 * lines are ignored; {@code === page} starts a new page. Every other line is one row of cells separated by
 * {@code " | "}, each written {@code x:text}, or {@code x:*text} for a bold cell. A row starting with {@code ~} is a
 * wrapped line 12 pt below the previous row, one starting with {@code +} is 13 pt below it (the next row of a CLab
 * table printed at single spacing); other rows are 18 pt below it, and a page's first row is at baseline 60.
 */
final class Fixtures {
    private static final float FIRST_BASELINE = 60;
    private static final float ROW_STEP = 18;
    private static final float WRAPPED_STEP = 12;
    private static final float TIGHT_STEP = 13;
    /** Loaded cells are this many points wide per character. */
    private static final float CHAR_WIDTH = 5;

    private Fixtures() {
    }

    /** Reads {@code fixtures/<name>.txt} from the test resources. */
    static List<Page> load(String name) {
        String resource = "/fixtures/" + name + ".txt";
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses fixture text; rows before the first {@code === page} line are on page 1. */
    static List<Page> parse(String text) {
        List<Page> pages = new ArrayList<>();
        List<Row> rows = null;
        float baseline = 0;
        for (String line : text.lines().map(String::strip).toList()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("=== page")) {
                if (rows != null) {
                    pages.add(new Page(pages.size() + 1, rows));
                }
                rows = new ArrayList<>();
                continue;
            }
            if (rows == null) {
                rows = new ArrayList<>();
            }
            float step = switch (line.charAt(0)) {
                case '~' -> WRAPPED_STEP;
                case '+' -> TIGHT_STEP;
                default -> ROW_STEP;
            };
            baseline = rows.isEmpty() ? FIRST_BASELINE : baseline + step;
            rows.add(new Row(baseline, cells(step == ROW_STEP ? line : line.substring(1))));
        }
        if (rows != null) {
            pages.add(new Page(pages.size() + 1, rows));
        }
        return pages;
    }

    private static List<Cell> cells(String line) {
        List<Cell> cells = new ArrayList<>();
        for (String part : line.split(" \\| ")) {
            String cell = part.strip();
            int colon = cell.indexOf(':');
            if (colon < 1) {
                throw new IllegalArgumentException("cell without x position: '" + cell + "' in '" + line + "'");
            }
            float x = Float.parseFloat(cell.substring(0, colon));
            String text = cell.substring(colon + 1);
            boolean bold = text.startsWith("*");
            if (bold) {
                text = text.substring(1);
            }
            cells.add(new Cell(x, x + CHAR_WIDTH * text.length(), text, bold));
        }
        return cells;
    }
}

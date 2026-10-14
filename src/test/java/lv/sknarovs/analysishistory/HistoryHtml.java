package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lv.sknarovs.analysishistory.catalog.TestCatalog;

/**
 * Reads E. Gulbja Laboratorija's own history export ({@code rezultatu_vesture_*.html}), the oracle the real-data
 * checks compare the parsed reports with. The export is one table: its second {@code Head} row holds the report
 * numbers, one column each, and every {@code <tr class="Test">} row holds a test's name and then one cell per report.
 */
final class HistoryHtml {
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z]+)[^>]*>");
    private static final Pattern HEAD_ROW = Pattern.compile("<tr class=\"Head\">");
    private static final Pattern TEST_ROW = Pattern.compile("<tr class=\"Test\">");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private HistoryHtml() {
    }

    /**
     * Report number → normalised test name ({@link TestCatalog#normaliseName}) → the texts of its non-empty cells, with
     * tags and the arrows {@code ⇧}/{@code ⇩} removed and white space collapsed. A name printed in several rows (blood
     * and urine leukocytes) collects the cells of all of them.
     */
    static Map<String, Map<String, List<String>>> read(Path html) throws IOException {
        String text = Files.readString(html, StandardCharsets.UTF_8);
        Matcher head = HEAD_ROW.matcher(text);
        if (!head.find() || !head.find()) {
            throw new IOException(html.getFileName() + ": no second header row of report numbers");
        }
        List<String> numbers = cells(text, head.end());
        Map<String, Map<String, List<String>>> byReport = new LinkedHashMap<>();
        numbers.forEach(number -> byReport.put(number, new LinkedHashMap<>()));
        Matcher test = TEST_ROW.matcher(text);
        while (test.find()) {
            List<String> cells = cells(text, test.end());
            if (cells.isEmpty()) {
                continue;
            }
            String name = TestCatalog.normaliseName(cells.get(0));
            for (int column = 1; column < cells.size() && column - 1 < numbers.size(); column++) {
                String cell = cells.get(column);
                if (!cell.isEmpty()) {
                    byReport.get(numbers.get(column - 1)).computeIfAbsent(name, key -> new ArrayList<>()).add(cell);
                }
            }
        }
        return byReport;
    }

    /**
     * The texts of the cells ({@code td} or {@code th}) of the row that starts at {@code from}, up to its {@code </tr>};
     * tables nested in a cell are part of that cell's text.
     */
    private static List<String> cells(String text, int from) {
        List<String> cells = new ArrayList<>();
        Matcher tag = TAG.matcher(text);
        int depth = 0;
        int cellStart = -1;
        int position = from;
        while (tag.find(position)) {
            position = tag.end();
            boolean closing = !tag.group(1).isEmpty();
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            if (name.equals("table")) {
                depth += closing ? -1 : 1;
            } else if (depth == 0 && (name.equals("td") || name.equals("th"))) {
                if (!closing) {
                    cellStart = tag.end();
                } else if (cellStart >= 0) {
                    cells.add(clean(text.substring(cellStart, tag.start())));
                    cellStart = -1;
                }
            } else if (depth == 0 && name.equals("tr") && closing) {
                break;
            }
        }
        return cells;
    }

    private static String clean(String html) {
        String text = TAG.matcher(html).replaceAll(" ")
                .replace("⇧", "")
                .replace("⇩", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
        Matcher entity = NUMERIC_ENTITY.matcher(text);
        text = entity.replaceAll(match -> Matcher.quoteReplacement(Character.toString(
                Integer.parseInt(match.group(2), match.group(1).isEmpty() ? 10 : 16))));
        text = text.replace("&amp;", "&");
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
}

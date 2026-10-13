package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryHtmlTest {
    /** The export's layout with invented reports and values. */
    private static final String EXPORT = """
            <table id="T"><thead>
            <tr class="Head"><th class="fz" rowspan="2">Testi</th><th class="Result">05.03.26</th>
            <th class="Result">17.03.26</th></tr>
            <tr class="Head"><td class="Result">20000001</td><td class="Result" style="background:silver">20000002</td></tr>
            </thead><tbody>
            <tr class="Comment"><td class="fz" colspan="3"><DIV><STRONG>Hematoloģija</STRONG></DIV>
            <TABLE><TR><TD>a note</TD></TR></TABLE></td></tr>
            <tr class="Test">
            <td class="fz"><span style="width:6cm;">Hemoglobīns</span></td>
            <td class="Result" style=""><span class="Flag"><span class="High">⇧</span></span>
                  150
                </td>
            <td><br></td></tr>
            <tr class="Test">
            <td class="fz"><span>Eritrocīti , Hb</span></td>
            <td><br></td><td class="Result"><span class="Flag"><span class="Low">⇩⇩</span></span> &lt;&nbsp;5 &amp; &#62;1</td></tr>
            <tr class="Test">
            <td class="fz"><span>Leikocīti</span></td><td class="Result">6.1</td><td><br></td></tr>
            <tr class="Test">
            <td class="fz"><span>Leikocīti</span></td><td class="Result">Neg.</td><td><br></td></tr>
            </tbody></table>
            """;

    @Test void readsCellsByReportNumberAndNormalisedName(@TempDir Path dir) throws IOException {
        Path html = dir.resolve("rezultatu_vesture_1.html");
        Files.writeString(html, EXPORT, StandardCharsets.UTF_8);

        Map<String, Map<String, List<String>>> read = HistoryHtml.read(html);

        assertEquals(Map.of("hemoglobīns", List.of("150"), "leikocīti", List.of("6.1", "Neg.")), read.get("20000001"));
        assertEquals(Map.of("eritrocīti,hb", List.of("< 5 & >1")), read.get("20000002"));
    }
}

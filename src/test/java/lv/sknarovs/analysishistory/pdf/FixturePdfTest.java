package lv.sknarovs.analysishistory.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixturePdfTest {
    private static final float TOLERANCE = 0.5f;

    @Test void renderedFixtureReadsBackIdentically(@TempDir Path dir) throws IOException {
        for (String name : List.of("egl-sample", "clab-sample", "clab-micro")) {
            List<Page> expected = Fixtures.load(name);

            List<Page> actual = PdfText.read(FixturePdf.write(expected, dir.resolve(name + ".pdf")));

            assertEquals(expected.size(), actual.size(), name + ": pages");
            for (int p = 0; p < expected.size(); p++) {
                List<Row> wanted = expected.get(p).rows();
                List<Row> read = actual.get(p).rows();
                assertEquals(rowTexts(wanted), rowTexts(read), name + ": rows of page " + (p + 1));
                for (int r = 0; r < wanted.size(); r++) {
                    assertSameRow(wanted.get(r), read.get(r), name + ": row '" + wanted.get(r).text() + "'");
                }
            }
        }
    }

    private static void assertSameRow(Row wanted, Row read, String where) {
        assertEquals(wanted.baseline(), read.baseline(), TOLERANCE, where + ": baseline");
        assertEquals(wanted.cells().size(), read.cells().size(), where + ": cells");
        for (int c = 0; c < wanted.cells().size(); c++) {
            Cell want = wanted.cells().get(c);
            Cell got = read.cells().get(c);
            assertEquals(want.text(), got.text(), where);
            assertEquals(want.bold(), got.bold(), where + ": bold of '" + want.text() + "'");
            assertEquals(want.x(), got.x(), TOLERANCE, where + ": x of '" + want.text() + "'");
        }
    }

    private static List<String> rowTexts(List<Row> rows) {
        return rows.stream().map(Row::text).toList();
    }
}

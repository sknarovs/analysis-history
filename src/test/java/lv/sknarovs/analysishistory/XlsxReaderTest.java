package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.dhatim.fastexcel.HyperLink;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the test helper against what fastexcel actually writes, so workbook tests cannot pass by misreading. */
class XlsxReaderTest {
    @Test void readsValuesStylesNotesLinksAndPanes(@TempDir Path dir) throws IOException {
        Path xlsx = dir.resolve("test.xlsx");
        try (OutputStream out = Files.newOutputStream(xlsx); Workbook workbook = new Workbook(out, "test", null)) {
            Worksheet first = workbook.newWorksheet("First");
            first.value(0, 0, "shared");
            first.inlineString(0, 1, "inline");
            first.value(1, 0, 14.25);
            first.style(1, 0).format("0.00").bold().fontColor("C00000").fillColor("FDE2E2").set();
            first.comment(1, 0, "CLab: 14.1 g/dL");
            first.hyperlink(2, 0, new HyperLink("../Rīga 1.pdf", "Rīga 1.pdf"));
            first.freezePane(3, 3);
            workbook.newWorksheet("Second").value(0, 0, "other");
        }

        Map<String, XlsxReader.Sheet> sheets = XlsxReader.read(xlsx);

        assertEquals(List.of("First", "Second"), List.copyOf(sheets.keySet()));
        XlsxReader.Sheet first = sheets.get("First");
        assertEquals("shared", first.text("A1"));
        assertEquals("inline", first.text("B1"));
        assertFalse(first.isNumber("A1"));
        assertNull(first.text("C1"));
        assertTrue(first.isNumber("A2"));
        assertEquals("14.25", first.text("A2"));
        assertEquals("0.00", first.numberFormat("A2"));
        assertEquals("General", first.numberFormat("A1"));
        assertTrue(first.bold("A2"));
        assertFalse(first.bold("A1"));
        assertEquals("C00000", first.fontColor("A2"));
        assertEquals("000000", first.fontColor("A1")); // fastexcel's default font is explicitly black
        assertEquals("FDE2E2", first.fill("A2"));
        assertNull(first.fill("A1"));
        assertEquals("CLab: 14.1 g/dL", first.note("A2"));
        assertNull(first.note("A1"));
        assertEquals("Rīga 1.pdf", first.text("A3"));
        assertEquals("../Rīga 1.pdf", first.hyperlink("A3"));
        assertNull(first.hyperlink("A1"));
        assertEquals("D4", first.frozenTopLeft());
        XlsxReader.Sheet second = sheets.get("Second");
        assertEquals("other", second.text("A1"));
        assertNull(second.note("A1"));
        assertNull(second.frozenTopLeft());
    }
}

package lv.sknarovs.analysishistory.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTextTest {
    private static final float TOLERANCE = 0.5f;

    @Test void splitsCellsAtColumnGapsAndKeepsLatvianLetters(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(62, 300, 9, "Dzelzs saistīšanas spēja", false)
                .text(179, 300, 9, "90", false));

        Row row = onlyRow(pages);
        assertEquals(List.of("Dzelzs saistīšanas spēja", "90"), texts(row));
        Cell name = row.cells().get(0);
        Cell value = row.cells().get(1);
        assertEquals(62, name.x(), TOLERANCE);
        assertEquals(179, value.x(), TOLERANCE);
        assertTrue(name.xEnd() > name.x() && name.xEnd() < value.x(), "xEnd " + name.xEnd());
        assertEquals(300, row.baseline(), TOLERANCE);
        assertSame(name, row.first());
        assertEquals("Dzelzs saistīšanas spēja | 90", row.text());
    }

    @Test void joinsTwoBaselinesIntoOneRow(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(46, 300, 11, "Eritrocīti", false)
                .text(278, 297, 8, "10e12/L", false));

        Row row = onlyRow(pages);
        assertEquals(List.of("Eritrocīti", "10e12/L"), texts(row));
        assertEquals(300, row.baseline(), TOLERANCE);
    }

    @Test void keepsWrappedLinesTwelvePointsApartAsSeparateRows(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(62, 300, 9, "TSH - Tireotropais", false)
                .text(62, 312, 9, "hormons", false));

        assertEquals(List.of("TSH - Tireotropais", "hormons"), rowTexts(pages.get(0)));
    }

    @Test void splitsHeaderDrawnAsOneString(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(49, 100, 10, "Tests" + " ".repeat(40) + "Rezultāts", false));

        assertEquals(List.of("Tests", "Rezultāts"), texts(onlyRow(pages)));
    }

    @Test void keepsSingleSpacesInsideCells(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(49, 100, 10, "Bilirubīns kopējais", false));

        assertEquals(List.of("Bilirubīns kopējais"), texts(onlyRow(pages)));
    }

    @Test void treatsNonBreakingSpacesAsSpaces(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(49, 100, 10, "Rh(D)\u00A0pozitīvs", false));

        assertEquals(List.of("Rh(D) pozitīvs"), texts(onlyRow(pages)));
    }

    @Test void marksBoldCells(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .text(49, 100, 10, "Hematoloģija", true)
                .text(49, 120, 10, "Hemoglobīns", false));

        List<Row> rows = pages.get(0).rows();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).first().bold());
        assertFalse(rows.get(1).first().bold());
    }

    @Test void marksBoldFromTheFontName(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .helveticaBold(49, 100, 10, "Hematologija")
                .helvetica(49, 120, 10, "Hemoglobins"));

        List<Row> rows = pages.get(0).rows();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).first().bold());
        assertFalse(rows.get(1).first().bold());
    }

    @Test void readsNonEmbeddedHelveticaWithoutSystemFonts(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create().page()
                .helvetica(49, 100, 10, "Glikoze 4,8"));

        assertEquals("Glikoze 4,8", onlyRow(pages).text());
        assertInstanceOf(BundledFontMapper.class, FontMappers.instance());
    }

    @Test void installsTheBundledFontMapperOnlyOnce() {
        PdfText.useBundledFonts();
        FontMapper installed = FontMappers.instance();
        PdfText.useBundledFonts();

        assertInstanceOf(BundledFontMapper.class, installed);
        assertSame(installed, FontMappers.instance());
    }

    @Test void switchesPdfBoxAndFontBoxLoggingOff() {
        PdfText.useBundledFonts();

        assertEquals(Level.OFF, Logger.getLogger("org.apache.pdfbox").getLevel());
        assertEquals(Level.OFF, Logger.getLogger("org.apache.fontbox").getLevel());
    }

    @Test void ordersRowsTopToBottomAcrossPages(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create()
                .page()
                .text(49, 200, 10, "third", false)
                .text(49, 100, 10, "first", false)
                .text(49, 150, 10, "second", false)
                .page()
                .text(49, 120, 10, "fifth", false)
                .text(49, 80, 10, "fourth", false));

        assertEquals(List.of(1, 2), pages.stream().map(Page::number).toList());
        assertEquals(List.of("first", "second", "third"), rowTexts(pages.get(0)));
        assertEquals(List.of("fourth", "fifth"), rowTexts(pages.get(1)));
        List<Row> rows = pages.get(0).rows();
        assertEquals(100, rows.get(0).baseline(), TOLERANCE);
        assertEquals(150, rows.get(1).baseline(), TOLERANCE);
        assertEquals(200, rows.get(2).baseline(), TOLERANCE);
    }

    @Test void keepsPagesWithoutText(@TempDir Path dir) throws IOException {
        List<Page> pages = roundTrip(dir, TestPdf.create()
                .page()
                .page()
                .text(49, 100, 10, "second", false));

        assertEquals(List.of(1, 2), pages.stream().map(Page::number).toList());
        assertEquals(List.of(), pages.get(0).rows());
        assertEquals(List.of("second"), rowTexts(pages.get(1)));
    }

    /** Saves the document in {@code dir}, closes it and reads the file back. */
    private static List<Page> roundTrip(Path dir, TestPdf pdf) throws IOException {
        try (pdf) {
            return PdfText.read(pdf.save(dir.resolve("test.pdf")));
        }
    }

    private static Row onlyRow(List<Page> pages) {
        assertEquals(1, pages.size());
        assertEquals(1, pages.get(0).number());
        assertEquals(1, pages.get(0).rows().size(), () -> "rows: " + pages.get(0).rows());
        return pages.get(0).rows().get(0);
    }

    private static List<String> texts(Row row) {
        return row.cells().stream().map(Cell::text).toList();
    }

    private static List<String> rowTexts(Page page) {
        return page.rows().stream().map(Row::text).toList();
    }
}

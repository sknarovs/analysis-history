package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FixturesTest {
    @Test void readsCellsBoldCellsWrappedRowsAndPages() {
        List<Page> pages = Fixtures.parse("""
                # a comment, then a blank line

                28:1/4 A | 62:Hemoglobīns
                85:*Hematoloģija
                ~62:hormons
                === page
                179:12.5
                """);

        assertEquals(List.of(
                new Page(1, List.of(
                        new Row(60, List.of(new Cell(28, 53, "1/4 A", false), new Cell(62, 117, "Hemoglobīns", false))),
                        new Row(78, List.of(new Cell(85, 145, "Hematoloģija", true))),
                        new Row(90, List.of(new Cell(62, 97, "hormons", false))))),
                new Page(2, List.of(
                        new Row(60, List.of(new Cell(179, 199, "12.5", false)))))),
                pages);
    }

    @Test void rowStartingWithPlusIsThirteenPointsBelow() {
        List<Page> pages = Fixtures.parse("""
                46:Kreatinīns
                +49:Glomerulārās
                ~49:ātrums
                """);

        assertEquals(List.of(60f, 73f, 85f), pages.get(0).rows().stream().map(Row::baseline).toList());
        assertEquals("Glomerulārās", pages.get(0).rows().get(1).first().text());
    }

    @Test void pageMarkerBeforeTheFirstRowStartsPageOne() {
        List<Page> pages = Fixtures.parse("""
                === page
                10:first
                === page
                10:second
                """);

        assertEquals(List.of(
                new Page(1, List.of(new Row(60, List.of(new Cell(10, 35, "first", false))))),
                new Page(2, List.of(new Row(60, List.of(new Cell(10, 40, "second", false)))))),
                pages);
    }
}

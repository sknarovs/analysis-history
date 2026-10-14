package lv.sknarovs.analysishistory.pdf;

import java.nio.file.Path;
import java.util.List;

/** Renders fixture pages as a PDF: each cell at its x on its row's baseline, 9 pt, bold cells in the bold font. */
public final class FixturePdf {
    private static final float SIZE = 9;

    private FixturePdf() {
    }

    /** Writes {@code pages} to {@code out} and returns {@code out}. */
    public static Path write(List<Page> pages, Path out) {
        // TestPdf installs the bundled font mapper before it creates any font, so no system fonts are scanned.
        try (TestPdf pdf = TestPdf.create()) {
            for (Page page : pages) {
                pdf.page();
                for (Row row : page.rows()) {
                    for (Cell cell : row.cells()) {
                        pdf.text(cell.x(), row.baseline(), SIZE, cell.text(), cell.bold());
                    }
                }
            }
            return pdf.save(out);
        }
    }
}

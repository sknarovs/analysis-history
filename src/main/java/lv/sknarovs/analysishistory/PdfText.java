package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads a PDF into pages of rows of cells: PDFBox glyphs are grouped into rows by vertical overlap, and each row is
 * cut into cells at wide horizontal gaps. All positions are in points, measured from the top-left of the page.
 */
final class PdfText {
    /** Slack, in points, when deciding whether a glyph still belongs to the current row. */
    private static final float ROW_TOLERANCE = 0.5f;
    /** A gap wider than this many font sizes starts a new cell. */
    private static final float CELL_GAP = 0.45f;
    /** A gap wider than this many font sizes inside a cell is written as one space. */
    private static final float SPACE_GAP = 0.15f;
    /** Glyph height, as a fraction of the font size, for fonts that report none. */
    private static final float DEFAULT_HEIGHT = 0.7f;

    // java.util.logging holds loggers weakly; these fields keep the levels set below from being lost.
    private static final Logger PDFBOX_LOG = Logger.getLogger("org.apache.pdfbox");
    private static final Logger FONTBOX_LOG = Logger.getLogger("org.apache.fontbox");

    private static boolean bundledFontsInstalled;

    static {
        PDFBOX_LOG.setLevel(Level.OFF);
        FONTBOX_LOG.setLevel(Level.OFF);
        useBundledFonts();
    }

    private PdfText() {
    }

    /** Reads every page of {@code pdf}; rows run from the top of a page down, cells from left to right. */
    static List<Page> read(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            List<Page> pages = new ArrayList<>();
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                GlyphCollector collector = new GlyphCollector();
                collector.setStartPage(number);
                collector.setEndPage(number);
                collector.writeText(document, Writer.nullWriter());
                pages.add(new Page(number, rows(collector.glyphs)));
            }
            return pages;
        }
    }

    /**
     * Makes PDFBox resolve fonts through {@link BundledFontMapper}; does nothing when that already happened. This must
     * run before any PDFBox font object is created: with PDFBox's own mapper, creating a font scans the system's fonts
     * and writes {@code ~/.pdfbox.cache}.
     */
    static synchronized void useBundledFonts() {
        if (!bundledFontsInstalled) {
            FontMappers.set(new BundledFontMapper());
            bundledFontsInstalled = true;
        }
    }

    /**
     * Groups glyphs into rows. A glyph joins the current row when its vertical extent, {@code baseline - height} to
     * {@code baseline}, overlaps the row's. Glyphs arrive in baseline order, so a glyph never ends above the row;
     * only its top can fall below the row's bottom (the largest baseline so far), and that is what is tested. This
     * joins the name and the unit that CLab prints on two baselines.
     */
    private static List<Row> rows(List<Glyph> glyphs) {
        List<Glyph> byBaseline = new ArrayList<>(glyphs);
        byBaseline.sort(Comparator.comparingDouble(Glyph::baseline));
        List<Row> rows = new ArrayList<>();
        List<Glyph> line = new ArrayList<>();
        for (Glyph glyph : byBaseline) {
            if (!line.isEmpty() && glyph.top() > line.get(line.size() - 1).baseline() + ROW_TOLERANCE) {
                rows.add(row(line));
                line = new ArrayList<>();
            }
            line.add(glyph);
        }
        if (!line.isEmpty()) {
            rows.add(row(line));
        }
        return rows;
    }

    /** The glyphs of {@code line} are sorted by baseline, so the last one has the row's (largest) baseline. */
    private static Row row(List<Glyph> line) {
        return new Row(line.get(line.size() - 1).baseline(), cells(line));
    }

    /**
     * Cuts the glyphs of one row, left to right, into cells. Gaps are measured from the glyph that reaches furthest
     * right so far, in units of its font size. {@code line} must not be empty.
     */
    private static List<Cell> cells(List<Glyph> line) {
        List<Glyph> byX = new ArrayList<>(line);
        byX.sort(Comparator.comparingDouble(Glyph::x));
        List<Cell> cells = new ArrayList<>();
        Glyph first = byX.get(0);
        Glyph edge = first;
        StringBuilder text = new StringBuilder(first.text());
        for (Glyph glyph : byX.subList(1, byX.size())) {
            float gap = glyph.x() - edge.xEnd();
            if (gap > CELL_GAP * edge.size()) {
                cells.add(new Cell(first.x(), edge.xEnd(), text.toString(), first.bold()));
                first = glyph;
                edge = glyph;
                text = new StringBuilder(glyph.text());
                continue;
            }
            if (gap > SPACE_GAP * edge.size()) {
                text.append(' ');
            }
            text.append(glyph.text());
            if (glyph.xEnd() > edge.xEnd()) {
                edge = glyph;
            }
        }
        cells.add(new Cell(first.x(), edge.xEnd(), text.toString(), first.bold()));
        return cells;
    }

    /** Bold by the font's name (Arial-BoldMT, Helvetica-Bold) or by its descriptor (weight 600 or more, ForceBold). */
    private static boolean isBold(PDFont font) {
        String name = font.getName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains("bold")) {
            return true;
        }
        PDFontDescriptor descriptor = font.getFontDescriptor();
        return descriptor != null && (descriptor.getFontWeight() >= 600 || descriptor.isForceBold());
    }

    /**
     * True for spaces of every kind, non-breaking ones included. They matter only as the gaps they leave, so they are
     * not kept as glyphs, and they cannot stretch a row.
     */
    private static boolean isBlank(String text) {
        return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }

    /** A drawn, non-blank character; {@code baseline} is measured down from the top of the page. */
    private record Glyph(String text, float x, float xEnd, float baseline, float height, float size, boolean bold) {
        float top() {
            return baseline - height;
        }
    }

    /** Collects glyphs instead of assembling text, so the writer it is given stays empty. */
    private static final class GlyphCollector extends PDFTextStripper {
        private final List<Glyph> glyphs = new ArrayList<>();

        @Override
        protected void processTextPosition(TextPosition position) {
            String text = position.getUnicode();
            if (isBlank(text)) {
                return;
            }
            float size = position.getFontSizeInPt();
            float height = position.getHeightDir() > 0 ? position.getHeightDir() : DEFAULT_HEIGHT * size;
            glyphs.add(new Glyph(text, position.getXDirAdj(), position.getXDirAdj() + position.getWidthDirAdj(),
                    position.getYDirAdj(), height, size, isBold(position.getFont())));
        }
    }
}

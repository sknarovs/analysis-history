package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Builds small A4 PDFs for tests. Text starts at {@code x} on a baseline measured in points from the top of the
 * page, like {@code TextPosition.getYDirAdj()}.
 */
final class TestPdf implements AutoCloseable {
    private static final String LIBERATION_SANS = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();

    private final PDDocument document = new PDDocument();
    private final PDFont regular;
    private final PDFont bold;
    private final PDFont helvetica;
    private final PDFont helveticaBold;
    private PDPageContentStream content;

    private TestPdf() throws IOException {
        regular = embeddedSans();
        bold = embeddedSans();
        // The same glyphs again, but flagged bold in the font descriptor; the name stays "LiberationSans".
        bold.getFontDescriptor().setFontWeight(700);
        // Not embedded: PDFBox asks the font mapper for these as soon as they are created.
        helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        helveticaBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    /** Starts an empty document. Installs the bundled font mapper first, so PDFBox never scans system fonts. */
    static TestPdf create() {
        PdfText.useBundledFonts();
        try {
            return new TestPdf();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Appends an A4 page; the following text goes onto it. */
    TestPdf page() {
        try {
            closeContent();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Draws text in an embedded font; {@code bold} selects the instance flagged bold. */
    TestPdf text(float x, float baseline, float size, String text, boolean bold) {
        return show(bold ? this.bold : regular, x, baseline, size, text);
    }

    /** Draws text in non-embedded Helvetica. */
    TestPdf helvetica(float x, float baseline, float size, String text) {
        return show(helvetica, x, baseline, size, text);
    }

    /** Draws text in non-embedded Helvetica-Bold, which is bold by its name only. */
    TestPdf helveticaBold(float x, float baseline, float size, String text) {
        return show(helveticaBold, x, baseline, size, text);
    }

    Path save(Path file) {
        try {
            closeContent();
            document.save(file.toFile());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try (document) {
            closeContent();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PDType0Font embeddedSans() throws IOException {
        try (InputStream in = PDFont.class.getResourceAsStream(LIBERATION_SANS)) {
            return PDType0Font.load(document, in);
        }
    }

    private TestPdf show(PDFont font, float x, float baseline, float size, String text) {
        if (content == null) {
            throw new IllegalStateException("call page() before adding text");
        }
        try {
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, PAGE_HEIGHT - baseline);
            content.showText(text);
            content.endText();
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void closeContent() throws IOException {
        if (content != null) {
            content.close();
            content = null;
        }
    }
}

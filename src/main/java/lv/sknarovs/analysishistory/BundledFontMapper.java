package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

/**
 * Maps every font a PDF does not embed to the Liberation Sans that PDFBox bundles. Text extraction only needs the
 * metrics PDFBox ships, so the result is the same on every machine; system fonts are never scanned and nothing is
 * written to the home folder.
 */
final class BundledFontMapper implements FontMapper {
    private static final String LIBERATION_SANS = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private final TrueTypeFont font;

    BundledFontMapper() {
        try (InputStream in = PDFont.class.getResourceAsStream(LIBERATION_SANS)) {
            if (in == null) {
                throw new IOException("missing resource " + LIBERATION_SANS);
            }
            font = new TTFParser().parse(new RandomAccessReadBuffer(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public FontMapping<TrueTypeFont> getTrueTypeFont(String baseFont, PDFontDescriptor fontDescriptor) {
        return new FontMapping<>(font, true);
    }

    @Override
    public FontMapping<FontBoxFont> getFontBoxFont(String baseFont, PDFontDescriptor fontDescriptor) {
        return new FontMapping<>(font, true);
    }

    @Override
    public CIDFontMapping getCIDFont(String baseFont, PDFontDescriptor fontDescriptor,
            PDCIDSystemInfo cidSystemInfo) {
        return new CIDFontMapping(null, font, true);
    }
}

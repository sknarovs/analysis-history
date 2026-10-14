package lv.sknarovs.analysishistory.parser;

import java.util.List;
import java.util.Optional;
import lv.sknarovs.analysishistory.model.Lab;
import lv.sknarovs.analysishistory.pdf.Page;

/**
 * Tells which laboratory issued a report from its text, never from its file name. Each laboratory needs two marks,
 * because any accredited laboratory issues a "testēšanas pārskats" (test report); its web address tells whose it is.
 */
public final class LabDetector {
    private LabDetector() {
    }

    /**
     * EGL when the text has {@code Testēšanas pārskats Nr.} and {@code egl.lv}, CLab when it has
     * {@code TESTĒŠANAS PĀRSKATS} and {@code laboratorija.lv}; empty for anything else.
     */
    public static Optional<Lab> detect(List<Page> pages) {
        if (contains(pages, "Testēšanas pārskats Nr.") && contains(pages, "egl.lv")) {
            return Optional.of(Lab.EGL);
        }
        if (contains(pages, "TESTĒŠANAS PĀRSKATS") && contains(pages, "laboratorija.lv")) {
            return Optional.of(Lab.CLAB);
        }
        return Optional.empty();
    }

    /** Whether the text of some row, on any page, contains {@code text}. */
    private static boolean contains(List<Page> pages, String text) {
        return pages.stream().flatMap(page -> page.rows().stream()).anyMatch(row -> row.text().contains(text));
    }
}

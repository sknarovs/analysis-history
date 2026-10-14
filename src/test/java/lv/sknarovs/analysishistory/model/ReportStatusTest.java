package lv.sknarovs.analysishistory.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportStatusTest {
    @Test void summaryCountsStatusesByKind() {
        List<ReportStatus> statuses = List.of("OK", "OK, 2 warnings", "Not tabulated: Mikrobioloģija",
                "Unrecognised", "Duplicate of CLab_100000001.pdf", "Error: broken").stream()
                .map(status -> new ReportStatus(Path.of("x.pdf"), null, null, null, 0, status, List.of()))
                .toList();

        assertEquals("6 PDFs: 2 tabulated, 1 not tabulated, 1 unrecognised, 1 duplicates, 1 errors",
                ReportStatus.summary(statuses));
    }
}

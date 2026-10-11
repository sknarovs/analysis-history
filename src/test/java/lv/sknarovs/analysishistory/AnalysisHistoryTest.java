package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lv.sknarovs.analysishistory.AnalysisHistory.ReportStatus;
import lv.sknarovs.analysishistory.AnalysisHistory.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisHistoryTest {
    @Test void readsPdfFilesOfAnyCaseInNameOrderAndReportsBrokenOnes(@TempDir Path dir) throws IOException {
        try (TestPdf pdf = TestPdf.create().page().text(49, 100, 10, "Some other lab", false)) {
            pdf.save(dir.resolve("b.PDF"));
        }
        Files.createFile(dir.resolve("a.pdf"));
        Files.writeString(dir.resolve("notes.txt"), "not a report");
        Files.createDirectory(dir.resolve("folder.pdf"));
        Files.createDirectories(dir.resolve("sub"));
        Files.copy(dir.resolve("b.PDF"), dir.resolve("sub").resolve("c.pdf"));

        RunResult result = AnalysisHistory.run(dir);

        List<ReportStatus> statuses = result.statuses();
        assertEquals(List.of(dir.resolve("a.pdf"), dir.resolve("b.PDF")),
                statuses.stream().map(ReportStatus::file).toList());
        assertTrue(statuses.get(0).status().startsWith("Error: "), statuses.get(0).status());
        assertEquals(new ReportStatus(dir.resolve("b.PDF"), null, null, null, 0, "Unrecognised", List.of()),
                statuses.get(1));
        assertEquals(List.of(), result.warnings());
    }

    @Test void summaryCountsStatusesByKind() {
        List<ReportStatus> statuses = List.of("OK", "OK, 2 warnings", "Not tabulated: Mikrobioloģija",
                "Unrecognised", "Duplicate of CLab_100000001.pdf", "Error: broken").stream()
                .map(status -> new ReportStatus(Path.of("x.pdf"), null, null, null, 0, status, List.of()))
                .toList();

        assertEquals("6 PDFs: 2 tabulated, 1 not tabulated, 1 unrecognised, 1 duplicates, 1 errors",
                AnalysisHistory.summary(statuses));
    }
}

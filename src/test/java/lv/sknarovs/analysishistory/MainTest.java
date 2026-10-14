package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import lv.sknarovs.analysishistory.pdf.TestPdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    @Test void writesWorkbookForFolderWithPdfs(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});

        assertEquals(0, run(dir.toString()), this::console);

        Path workbook = dir.resolve("analysis-history.xlsx");
        assertTrue(Files.isRegularFile(workbook));
        List<String> lines = printed().lines().toList();
        assertTrue(lines.get(0).startsWith("Read 2 PDFs:"), this::console);
        assertTrue(lines.get(0).contains(", 0 errors"), this::console);
        assertEquals("Wrote " + workbook, lines.get(1));
        assertEquals("", errors());
    }

    @Test void usageErrorExitsTwo() {
        assertEquals(2, run("--nope"));

        assertTrue(errors().startsWith(CliOptions.USAGE), this::console);
        assertTrue(errors().contains("analysis-history: error: "), this::console);
        assertTrue(errors().contains("--nope"), this::console);
    }

    @Test void missingFolderIsUsageError() {
        assertEquals(2, run("/no/such/dir"));

        assertTrue(errors().startsWith(CliOptions.USAGE), this::console);
        assertTrue(errors().contains("not a directory"), this::console);
    }

    @Test void emptyFolderWritesNothing(@TempDir Path dir) throws IOException {
        assertEquals(1, run(dir.toString()));

        assertTrue(errors().contains("no PDF files"), this::console);
        assertEquals(List.of(), files(dir));
    }

    @Test void folderWithoutReadableReportsExitsOne(@TempDir Path dir) throws IOException {
        try (TestPdf pdf = TestPdf.create().page().text(49, 100, 10, "Some other lab", false)) {
            pdf.save(dir.resolve("other.pdf"));
        }

        assertEquals(1, run(dir.toString()));

        assertTrue(errors().contains("no reports could be read in " + dir), this::console);
        assertEquals(List.of(dir.resolve("other.pdf")), files(dir));
    }

    @Test void listsWarningsAndUnmappedTestsAfterTheSummary(@TempDir Path dir) throws IOException {
        AnalysisHistoryTest.render(dir, "egl-stray", "stray.pdf", "62:Brīvais T4", "62:Izdomāts tests");

        assertEquals(0, run(dir.toString()), this::console);

        List<String> lines = printed().lines().toList();
        assertEquals("Read 1 PDFs: 1 tabulated, 0 not tabulated, 0 unrecognised, 0 duplicates, 0 errors; 1 warnings",
                lines.get(0));
        assertEquals(List.of("warning: stray.pdf: page 1: unattached row: 12.3",
                "unmapped: Izdomāts tests (EGL) — add an alias to catalog.txt to merge it"), lines.subList(2, 4));
    }

    @Test void invalidPathIsUsageError() {
        assertEquals(2, run("-o", "x\0.xlsx"));

        assertTrue(errors().startsWith(CliOptions.USAGE), this::console);
    }

    @Test void helpExitsZero() {
        assertEquals(0, run("-h"));

        assertTrue(printed().startsWith(CliOptions.USAGE), this::console);
        assertEquals("", errors());
    }

    @Test void unwritableWorkbookExitsOneAndSaysToCloseIt(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});
        Path blocked = Files.createDirectory(dir.resolve("analysis-history.xlsx"));
        Files.writeString(blocked.resolve("old.txt"), "old");

        assertEquals(1, run(dir.toString()));

        assertTrue(errors().contains("cannot write " + blocked), this::console);
        assertTrue(errors().contains("close it and run again"), this::console);
        assertEquals("", printed());
        assertEquals("old", Files.readString(blocked.resolve("old.txt")));
    }

    @Test void outputInMissingFolderExitsOneAndSaysWhy(@TempDir Path dir) throws IOException {
        SampleReports.main(new String[] {dir.toString()});
        Path target = dir.resolve("missing").resolve("out.xlsx");

        assertEquals(1, run(dir.toString(), "-o", target.toString()));

        assertTrue(errors().contains("cannot write " + target + ": no such file or directory."), this::console);
        assertEquals(List.of(), files(dir).stream().filter(file -> !file.toString().endsWith(".pdf")).toList());
    }

    private int run(String... args) {
        return Main.run(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    private String printed() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    private String console() {
        return "out:\n" + printed() + "err:\n" + errors();
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }
}

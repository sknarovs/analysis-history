# Analysis History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A self-contained native CLI, `analysis-history`, that reads EGL and CLab PDF lab reports from a folder and writes one Excel workbook with every result on a shared, newest-first timeline.

**Architecture:** PDFBox glyphs → rows/cells (`PdfText`) → one parser per lab → `Report` records → `TestCatalog` merges tests across labs → `HistoryBuilder` builds visits × rows → `WorkbookWriter` (fastexcel). GraalVM native image, with AWT native loading blocked and a font mapper that never touches system fonts.

**Tech Stack:** Java 25, Gradle 9.8.1 (Kotlin DSL), GraalVM CE 25 Native Image (`org.graalvm.buildtools.native` 1.1.14), PDFBox 3.0.8, fastexcel 0.20.2, JUnit 6 (junit-bom 6.1.3), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-11-analysis-history-design.md` — read it first; section numbers below (§n) refer to it.

## Global Constraints

- Never commit real reports, the HTML export or any workbook. `.gitignore` already excludes `/results/`, `*.pdf`, `*.html`, `*.xlsx`. All fixtures use invented values and the invented patient `Jānis Paraugs`, personal code `010101-10000`.
- Package `lv.sknarovs.analysishistory`, one flat package (as in `bksb-calendar`). Classes are package-private unless a test or `main` needs otherwise.
- Runtime dependencies: only `org.apache.pdfbox:pdfbox:3.0.8` and `org.dhatim:fastexcel:0.20.2`. Compile-only: `org.graalvm.sdk:nativeimage:25.4.4.1.1`. Test: JUnit 6 only — workbooks are read back with `java.util.zip` + `javax.xml`, no fastexcel-reader.
- Binary/Gradle project name `analysis-history`; default output `DIR/analysis-history.xlsx`.
- Native build args: `-Djava.awt.headless=true`, `-H:+AddAllCharsets`, `-march=compatibility`. Releases ship only the executable.
- Exit codes: 0 workbook written; 1 nothing written; 2 usage error.
- Console output goes through plain `System.out`/`System.err` (the console's `stdout.encoding`); do not wrap in a UTF-8 `PrintStream`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Native commands run in the toolbox:
  `toolbox run -c fedora-toolbox-44 bash -c 'cd /var/home/andrejs/Work/blood-analysis && export JAVA_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce GRAALVM_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce && ./gradlew <tasks>'`
  (written below as `TOOLBOX ./gradlew <tasks>`). JVM tasks run on the host: `./gradlew test`.

## Review Focus

1. **A file the tool did not write sits in the folder** (a scanned PDF, a PDF from a third lab, a password-protected PDF, a zero-byte `.pdf`). Expected: a Reports row `Unrecognised` or `Error: …`, the run still writes the workbook. Pinned in Task 10 (`unrecognisedAndBrokenFilesAreListedNotFatal`).
2. **The same report downloaded twice** (`CLab_1.pdf` and `CLab_1 (1).pdf`). Expected: one column, the second listed `Duplicate of CLab_1.pdf`. Pinned in Task 10 (`duplicateReportIsListedOnce`).
3. **The output workbook is open in Excel on Windows** (rename fails). Expected: exit 1, message telling the user to close it, the old workbook untouched, no stray temp file. Pinned in Task 2 (`failedRenameKeepsOldWorkbookAndRemovesTemp`).
4. **Two reports of one visit both contain the same test** (e.g. a repeated CBC on the same day). Expected: separate columns, nothing overwritten. Pinned in Task 7 (`sameDayReportsWithSameTestGetSeparateColumns`).
5. **A PDF name with Latvian letters or spaces, and `-o` pointing to another folder.** Expected: hyperlink on the Reports sheet still opens the PDF (relative to the workbook's folder). Pinned in Task 9 (`fileLinkIsRelativeToWorkbookFolder`).

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradlew, gradlew.bat, gradle/wrapper/*   (wrapper copied from ~/Work/bksb-calendar)
.github/workflows/build.yml
README.md, AGENTS.md
src/main/java/lv/sknarovs/analysishistory/
  Main.java                 CLI entry, exit codes, console summary
  CliOptions.java           argument parsing
  AnalysisHistory.java      run(dir, catalog) → RunResult; RunResult, ReportStatus records
  Cell.java Row.java Page.java   text model
  PdfText.java              PDFBox → pages/rows/cells
  BundledFontMapper.java    non-embedded fonts → bundled LiberationSans
  NoAwtSubstitutions.java   native-image substitutions (§6.2)
  Lab.java Specimen.java Flag.java
  ResultValue.java          sealed: Numeric | Compared | Text
  ReferenceRange.java       sealed: Between | Below | Above | TextReference | None
  Result.java Previous.java RowWarning.java Report.java ReportFormatException.java
  LabDetector.java
  Zones.java                column zones from a header row (shared by both parsers)
  EglParser.java ClabParser.java
  TestCatalog.java CatalogTest.java CatalogMatch.java
  HistoryBuilder.java History.java Visit.java HistoryRow.java HistoryCell.java
  ConsistencyCheck.java
  WorkbookWriter.java
src/main/resources/lv/sknarovs/analysishistory/catalog.txt
src/main/resources/META-INF/native-image/lv.sknarovs/analysis-history/reachability-metadata.json
src/test/java/lv/sknarovs/analysishistory/
  TestPdf.java Fixtures.java FixturePdf.java SampleReports.java XlsxReader.java   (test helpers)
  *Test.java, RealDataTest.java, HistoryHtml.java
src/test/resources/fixtures/*.txt
```

---

### Task 1: Gradle project and the text layer

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, wrapper files, `Cell.java`, `Row.java`, `Page.java`, `PdfText.java`, `BundledFontMapper.java`
- Test: `TestPdf.java`, `PdfTextTest.java`

**Interfaces:**
- Produces:
  - `record Cell(float x, float xEnd, String text, boolean bold)`
  - `record Row(float baseline, List<Cell> cells)` with `Cell first()` and `String text()` (cell texts joined with `" | "`)
  - `record Page(int number, List<Row> rows)` (`number` is 1-based)
  - `final class PdfText { static List<Page> read(Path pdf) throws IOException }`
  - `final class BundledFontMapper implements org.apache.pdfbox.pdmodel.font.FontMapper`
  - Test helper `final class TestPdf implements AutoCloseable`: `static TestPdf create()`, `TestPdf page()`, `TestPdf text(float x, float baseline, float size, String text, boolean bold)`, `TestPdf helvetica(float x, float baseline, float size, String text)`, `Path save(Path file)`. `baseline` is measured from the top of an A4 page (842 pt), like PDFBox's `getYDirAdj()`.

- [ ] **Step 1: Scaffold the build**

Copy `gradlew`, `gradlew.bat` and `gradle/` from `~/Work/bksb-calendar` (Gradle 9.8.1). `settings.gradle.kts`: `rootProject.name = "analysis-history"`. `build.gradle.kts`:

```kotlin
plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.dhatim:fastexcel:0.20.2")
    compileOnly("org.graalvm.sdk:nativeimage:25.4.4.1.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass = "lv.sknarovs.analysishistory.Main" }

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
```

- [ ] **Step 2: Write `TestPdf`** — embeds PDFBox's bundled `/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf` twice with `PDType0Font.load`; the bold instance gets `getFontDescriptor().setFontWeight(700)`. `helvetica` uses `new PDType1Font(Standard14Fonts.FontName.HELVETICA)` (not embedded).

- [ ] **Step 3: Write the failing tests in `PdfTextTest`**

```java
@Test void splitsCellsAtColumnGapsAndKeepsLatvianLetters(@TempDir Path dir) // "Dzelzs saistīšanas spēja" at x=62 and "90" at x=179, size 9, baseline 300
    // → one row; cells texts ["Dzelzs saistīšanas spēja", "90"], x ≈ 62 and 179 (±0.5)
@Test void joinsTwoBaselinesIntoOneRow(@TempDir Path dir)   // "Eritrocīti" size 11 baseline 300 x=46; "10e12/L" size 8 baseline 297 x=278
    // → exactly one row, cells ["Eritrocīti", "10e12/L"], row.baseline() ≈ 300
@Test void splitsHeaderDrawnAsOneString(@TempDir Path dir)  // one text call: "Tests" + " ".repeat(40) + "Rezultāts", size 10, x=49
    // → cells ["Tests", "Rezultāts"]
@Test void keepsSingleSpacesInsideCells(@TempDir Path dir)  // "Bilirubīns kopējais" → one cell "Bilirubīns kopējais"
@Test void marksBoldCells(@TempDir Path dir)                // bold "Hematoloģija" → cell.bold() == true; regular → false
@Test void readsNonEmbeddedHelveticaWithoutSystemFonts(@TempDir Path dir) // helvetica "Glikoze 4,8"
    // → text "Glikoze 4,8"; FontMappers.instance() instanceof BundledFontMapper
@Test void ordersRowsTopToBottomAcrossPages(@TempDir Path dir) // two pages → Page numbers 1, 2; rows sorted by baseline
```

- [ ] **Step 4: Run** `./gradlew test --tests '*PdfTextTest'` — expected: compilation fails (`PdfText` missing).

- [ ] **Step 5: Implement `Cell`, `Row`, `Page`, `BundledFontMapper`, `PdfText`**

- `BundledFontMapper` parses the bundled LiberationSans once (`new TTFParser().parse(new RandomAccessReadBuffer(stream))`) and returns it as a fallback for all three `FontMapper` methods (`new FontMapping<>(font, true)`, `new CIDFontMapping(null, font, true)`).
- `PdfText` static initialiser: `FontMappers.set(new BundledFontMapper())`; set `java.util.logging.Logger` levels for `org.apache.pdfbox` and `org.apache.fontbox` to `Level.OFF`, keeping the loggers in static fields.
- Per page, a `PDFTextStripper` subclass overrides `processTextPosition` to collect glyphs (unicode, `getXDirAdj`, `getWidthDirAdj`, `getYDirAdj`, `getHeightDir` (use `0.7 × size` when ≤ 0), `getFontSizeInPt`, bold = font name contains `bold` ignoring case, or descriptor weight ≥ 600, or `isForceBold`).
- Rows: sort glyphs by baseline; a glyph joins the current row when `[baseline − height, baseline]` overlaps the row's extent (0.5 pt tolerance). `Row.baseline` = largest baseline in the row.
- Cells: glyphs by x, whitespace dropped; new cell when the gap > `0.45 × size`; one space inserted when the gap > `0.15 × size`; `bold` if the cell's first glyph is bold.

- [ ] **Step 6: Run** `./gradlew test` — expected: PASS.

- [ ] **Step 7: Commit** `feat: extract rows and cells from PDFs with PDFBox`

---

### Task 2: Walking skeleton — CLI, Reports sheet, native binary, CI on four targets

Proves §6 before any parser work. After this task every PDF is still `Unrecognised`; Task 10 completes `AnalysisHistory.run`.

**Files:**
- Create: `CliOptions.java`, `Main.java`, `AnalysisHistory.java`, `WorkbookWriter.java`, `NoAwtSubstitutions.java`, `Lab.java`, `src/main/resources/META-INF/native-image/lv.sknarovs/analysis-history/reachability-metadata.json`, `.github/workflows/build.yml`, `src/test/resources/fixtures/egl-sample.txt`, `src/test/resources/fixtures/clab-sample.txt`
- Modify: `build.gradle.kts`
- Test: `Fixtures.java`, `FixturePdf.java`, `SampleReports.java`, `XlsxReader.java`, `CliOptionsTest.java`, `FixturePdfTest.java`, `WorkbookWriterTest.java`, `MainTest.java`

**Interfaces:**
- Consumes: `PdfText.read`, `Page`/`Row`/`Cell`.
- Produces:
  - `enum Lab { EGL("EGL"), CLAB("CLab"); final String label; }`
  - `record CliOptions(Path dir, Path output, boolean help)` with `static CliOptions parse(String... args) throws UsageException`; `USAGE = "usage: analysis-history [-h] [-o FILE] [DIR]"`
  - `record ReportStatus(Path file, Lab lab, String number, LocalDateTime registered, int results, String status, List<String> warnings)` (`lab`, `number`, `registered` null when unknown)
  - `record RunResult(List<ReportStatus> statuses, List<String> warnings)` — Task 10 adds `History history` as the first component
  - `final class AnalysisHistory { static RunResult run(Path dir) throws IOException }` — Task 10 changes it to `run(Path dir, TestCatalog catalog)`
  - `final class WorkbookWriter { static void write(Path target, List<ReportStatus> statuses, LocalDateTime generated) throws IOException }` — Task 9 adds `History history` after `target`
  - `static String summary(List<ReportStatus> statuses)` in `AnalysisHistory` → `"<n> PDFs: <a> tabulated, <b> not tabulated, <c> unrecognised, <d> duplicates, <e> errors"` (tabulated = status starts with `OK`)
  - Test helpers: `Fixtures.load(String name) → List<Page>` (reads `fixtures/<name>.txt`); `FixturePdf.write(List<Page> pages, Path out)`; `SampleReports.main(String[] args)` writes `testesanas_parskats_20000001.pdf` (from `egl-sample`) and `CLab_100000001.pdf` (from `clab-sample`) into `args[0]`; `XlsxReader.read(Path xlsx) → Map<String, XlsxReader.Sheet>` (by sheet name), where `Sheet` exposes `String text(String ref)` (cell as text; shared strings resolved; e.g. `"D1"`), `boolean isNumber(String ref)`, `String numberFormat(String ref)`, `boolean bold(String ref)`, `String fontColor(String ref)`, `String fill(String ref)` (RGB hex, e.g. `"FDE2E2"`), `String note(String ref)`, `String hyperlink(String ref)`, `String frozenTopLeft()` (e.g. `"D4"`).
  - `Main.run(String[] args, PrintStream out, PrintStream err) → int` (exit code; `main` only calls it and exits)

**Fixture format** (used by every parser test from here on): UTF-8 text. `#` lines and blank lines are ignored; `=== page` starts a new page. Every other line is one row: cells separated by ` | `, each `x:text`; `x:*text` marks a bold cell. A row starting with `~` is a wrapped line 12 pt below the previous row; other rows are 18 pt below (first row of a page at baseline 60). Loaded cells get `xEnd = x + 5 × text.length()`. `FixturePdf` renders each cell at its x and the row's baseline, size 9 (bold cells with the bold font).

- [ ] **Step 1: Write the two sample fixtures** (invented data, real layout x positions from §5 and the probe):

`egl-sample.txt` — header block rows containing `200:E.Gulbja Laboratorija`, `200:e-pasts : info@egl.lv`, `28:Testēšanas pārskats Nr. 20000001#1000 // pabeigts`, `28:Vārds , Uzvārds | 160:Jānis Paraugs`, `28:Personas kods | 160:010101-10000`, `28:Reģistrēts laboratorijā | 160:05.03.26,08:15`; header `179:Rezultāts | 269:References | 340:Vienības | 403:Iepr.rezult./datums`, `~269:intervāls`; `85:*Hematoloģija`; rows such as `28:1/4 A | 62:Hemoglobīns | 179:150 | 269:132 - 175 | 340:g/L | 439:139 / 14.10.25`, `28:2/4 A | 62:Neitrofīlie % | 179:40.1 | 249:ò | 269:48 - 72 | 340:%`, `88:*Hormoni`, `28:3/4 A | 62:TSH - Tireotropais | 179:5.2 | 249:ñ | 269:0.55 - 4.78 | 340:mU/L`, `~62:hormons`, `28:4/4 | 62:Rezultātu novērtējums`, `85:Pārskata beigas`.

`clab-sample.txt` — header block with `150:TESTĒŠANAS PĀRSKATS / PABEIGTS`, `48:Vārds, Uzvārds | 130:Jānis Paraugs`, `48:Vecums | 130:01.01.2001 (25 g.) | 470:100000001`, `48:Pers.kods | 130:010101-10000`, `48:Diagnoze | 340:Reģistrēts | 430:17.03.2026 08:40`, `48:Komentāri:`; `280:*Hematoloģija`; header `49:*Tests | 194:*Rezultāts | 257:*Vienības | 318:*References intervāls | 417:*Iepriekšējais rezultāts | 522:*Datums`; `50:Pilna asins aina ar EGĀ`; `46:Hemoglobīns | 213:14.1 | 278:g/dL | 349:(13.2 - 17.5) | 420:14.6 | 505:03.11.2025 09:12`; `46:Leikocīti | 218:3.9 | 256:↓ | 278:10e9/L | 349:(4.0 - 9.8)`; `280:*Klīniskā ķīmija`; header again; `46:Kreatinīns | 213:110,0 | 256:↑ | 278:umol/L | 349:(62.0 - 106.0)`; `49:Glomerulārās | 210:85 | 261:mL/min/`, `~49:filtrācijas ātrums | 261:1.73 m2`; footer `48:Rezultātus pārbaudīja laboratorijas ārste ID 1` and `48:Testēšanas metodes, mērījumu nenoteiktības … www.laboratorija.lv`.

- [ ] **Step 2: Write the failing tests**

```java
// CliOptionsTest
@Test void defaultsToCurrentFolderAndOutputInsideIt()   // parse() → dir ".", output "./analysis-history.xlsx"
@Test void outputFollowsDirUnlessGiven()                 // parse("res") → output "res/analysis-history.xlsx"; parse("-o","x.xlsx","res") → "x.xlsx"; parse("--output=x.xlsx") → "x.xlsx"
@Test void rejectsSecondDirAndUnknownOption()            // parse("a","b") and parse("--nope") throw UsageException
// FixturePdfTest
@Test void renderedFixtureReadsBackIdentically(@TempDir Path dir) // for egl-sample and clab-sample: FixturePdf → PdfText.read equals Fixtures.load
    // (same number of pages and rows; per cell: text, bold, x ±0.5; row baseline ±0.5)
// WorkbookWriterTest
@Test void writesReportsSheet(@TempDir Path dir)          // two statuses → sheet "Reports": A1 = "Generated 11.10.2026 17:40 from 2 PDFs: 1 tabulated, 0 not tabulated, 1 unrecognised, 0 duplicates, 0 errors";
    // row 3 headers Registered | Lab | Report no. | File | Results | Status; row 4 = newest dated report; undated rows last; Results "–" when 0
@Test void failedRenameKeepsOldWorkbookAndRemovesTemp(@TempDir Path dir) // target is a non-empty directory → IOException; directory untouched; no "*.tmp" left in dir
// MainTest (calls Main.run(String[] args, PrintStream out, PrintStream err) → int)
@Test void writesWorkbookForFolderWithPdfs(@TempDir Path dir) // SampleReports into dir → exit 0; dir/analysis-history.xlsx exists; out contains "2 PDFs:" and ", 0 errors"
@Test void usageErrorExitsTwo()                          // run(["--nope"]) → 2, err starts with USAGE
@Test void missingFolderIsUsageError()                   // run(["/no/such/dir"]) → 2, err contains "not a directory"
@Test void emptyFolderWritesNothing(@TempDir Path dir)   // no PDFs → exit 1, err contains "no PDF files", no workbook
```

- [ ] **Step 3: Run** `./gradlew test` — expected: FAIL (classes missing).

- [ ] **Step 4: Implement**

- `CliOptions` in the style of `bksb-calendar` (`--opt=value`, `-oFILE` accepted). `DIR` must be an existing directory (else `UsageException("not a directory: " + dir)`).
- `Main.main` sets `java.awt.headless=true`, calls `run(args, System.out, System.err)` and exits with its result. `run`: usage errors → print `USAGE` and `analysis-history: error: <message>` to err, return 2; `-h` → help, return 0.
- `AnalysisHistory.run`: lists `*.pdf` (case-insensitive) directly in `dir`, sorted by name; for each, `PdfText.read` → `ReportStatus(file, null, null, null, 0, "Unrecognised", List.of())`; an exception → `"Error: " + message`. Empty folder → Main prints `no PDF files in <dir>` and returns 1.
- `WorkbookWriter.write`: sheet "Reports" per §4.2 (summary in A1 = `"Generated " + dd.MM.yyyy HH:mm + " from " + summary`), column widths Registered 17, Lab 6, Report no. 12, File 36, Results 8, Status 40; File cell is a hyperlink (`HyperLink(relativePath, fileName)`), relative to `target.getParent()`. Writing goes to `target.resolveSibling("." + target.getFileName() + ".tmp")`, then `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)`; on any failure delete the temp file and rethrow. Main turns that `IOException` into `cannot write <target>: <reason>. If the workbook is open in another program, close it and run again.` and exit 1.
- Console after a successful write: `Read <summary>; <w> warnings` then `Wrote <target>`, then each warning as `warning: <text>`.
- `NoAwtSubstitutions.java` exactly as §6.2: two `@TargetClass` classes (`java.awt.image.ColorModel`, `java.awt.Toolkit`) each with `@Substitute static void loadLibraries()` throwing `UnsatisfiedLinkError("AWT native libraries are not available in this binary")`.

- [ ] **Step 5: Run** `./gradlew test` — expected: PASS.

- [ ] **Step 6: Native configuration** — append to `build.gradle.kts`:

```kotlin
graalvmNative {
    toolchainDetection = false
    metadataRepository { enabled = true }
    binaries {
        all {
            resources.autodetect()
            buildArgs.addAll("-Djava.awt.headless=true", "-H:+AddAllCharsets", "-march=compatibility")
        }
        named("main") {
            imageName = "analysis-history"
            if (providers.gradleProperty("static").isPresent) buildArgs.addAll("--static", "--libc=musl")
        }
    }
}

tasks.register<JavaExec>("generateSamples") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "lv.sknarovs.analysishistory.SampleReports"
    args(layout.buildDirectory.dir("samples").get().asFile.path)
}
```

Generate metadata with the tracing agent: `TOOLBOX ./gradlew -Pagent test` then `TOOLBOX ./gradlew metadataCopy --task test --dir src/main/resources/META-INF/native-image/lv.sknarovs/analysis-history`. Edit the result: delete every entry whose type starts with `java.awt`, `sun.awt`, or is `java.lang.System`/`java.lang.Boolean` with `jniAccessible`; make sure `resources` contains globs `org/apache/pdfbox/resources/afm/*.afm`, `org/apache/pdfbox/resources/glyphlist/*.txt`, `org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf`, `org/apache/pdfbox/resources/text/BidiMirroring.txt`, `lv/sknarovs/analysishistory/*.txt`. Test-only entries (JUnit) belong in `src/test/resources/META-INF/native-image/…` instead.

- [ ] **Step 7: Verify natively**

Run: `TOOLBOX ./gradlew nativeTest nativeCompile` → expected: tests pass, `build/native/nativeCompile/analysis-history` exists.
Then on the host: `./gradlew generateSamples && rm -rf /tmp/claude-1000/ah-smoke && mkdir /tmp/claude-1000/ah-smoke && cp build/native/nativeCompile/analysis-history /tmp/claude-1000/ah-smoke/ && (cd /tmp/claude-1000/ah-smoke && LD_DEBUG=files ./analysis-history "$OLDPWD/build/samples" -o out.xlsx 2> ld.log; echo exit=$?; grep -cE 'file=.*(awt|libjvm)' ld.log)`
Expected: `exit=0`, grep count `0`, `out.xlsx` exists, no `~/.pdfbox.cache`.

- [ ] **Step 8: CI workflow** `.github/workflows/build.yml` — triggers as in `bksb-calendar` (push to main and `v*` tags, `paths-ignore: '**/*.md'`, pull_request, workflow_dispatch). One `build` job with a matrix:

| `target` | `runs-on` | extra |
|---|---|---|
| `linux-x86_64` | `ubuntu-24.04` | `setup-graalvm` with `native-image-musl: 'true'`; Gradle flag `-Pstatic` |
| `linux-aarch64` | `ubuntu-24.04-arm` | `container: debian:bookworm` + apt step from `bksb-calendar` |
| `windows-x86_64` | `windows-2025` | executable suffix `.exe` |
| `macos-aarch64` | `macos-15` | — |

Steps: checkout, `graalvm/setup-graalvm@v1` (`java-version: '25'`, `distribution: graalvm-community`), `gradle/actions/setup-gradle@v6`, `./gradlew test nativeTest nativeCompile generateSamples ${{ matrix.gradle-flags }}`, then a `shell: bash` smoke step:

```bash
mkdir smoke && cp "build/native/nativeCompile/analysis-history${EXE}" smoke/
cd smoke && "./analysis-history${EXE}" ../build/samples -o out.xlsx | tee out.txt
test -s out.xlsx && grep -q ", 0 errors" out.txt
```

then package `dist/analysis-history-${target}${EXE}` plus `.sha256` (`sha256sum`; on macOS `shasum -a 256`) and upload. The `release` job (tags only) downloads all four artifacts and runs `gh release create` as in `bksb-calendar`.

- [ ] **Step 9: Commit** `feat: walking skeleton with native build and four-target CI`. Ask the user to push to a GitHub repository and confirm all four matrix jobs are green before Task 3 (the skeleton's whole point); fix any platform failure first.

---

### Task 3: Values, reference ranges, flags

**Files:** Create `Flag.java`, `ResultValue.java`, `ReferenceRange.java`. Test: `ResultValueTest.java`, `ReferenceRangeTest.java`.

**Interfaces:**
- Produces:
  - `enum Flag { HIGH, LOW, NONE }`
  - `sealed interface ResultValue` with records `Numeric(BigDecimal value, int decimals)`, `Compared(String op, BigDecimal value, int decimals)` (`op` ∈ `<`, `>`, `≤`, `≥`), `Text(String text)`; `static ResultValue parse(String raw)`; `String display()`; `ResultValue scaled(BigDecimal factor)`; `boolean sameAs(ResultValue other)`
  - `sealed interface ReferenceRange` with records `Between(BigDecimal lo, BigDecimal hi)`, `Below(BigDecimal limit, boolean inclusive)`, `Above(BigDecimal limit, boolean inclusive)`, `TextReference(String text)`, `None()`; `static ReferenceRange parse(String raw)`; `Optional<Flag> judge(ResultValue value)`; `boolean isNumeric()`; `ReferenceRange scaled(BigDecimal factor)`; `String display()`
  - Scaling rule (factor is always a power of ten, ≥ 1): multiply, then `setScale(max(0, decimals − log10(factor)))`.

- [ ] **Step 1: Failing tests**

```java
// ResultValueTest
@Test void parsesNumbersKeepingDecimals()   // parse("0.290") → Numeric(0.290, 3), display "0.290"; parse("13,6") → Numeric(13.6,1), display "13.6"; parse("152") decimals 0
@Test void parsesComparators()              // parse("<25") → Compared("<",25,0); parse(">9000"); parse("< 0.25") → display "<0.25"; parse("≤3.9")
@Test void everythingElseIsText()           // parse("Negatīvs"), parse("Pozitīvs 150.2 U/mL"), parse("Rh(D) pozitīvs"), parse("-"), parse("+")
@Test void scalesByPowersOfTen()            // parse("14.1").scaled(10) → display "141"; parse("32.0").scaled(10) → "320"; Text unchanged
@Test void sameAsComparesNumbersByValueAndTextLoosely() // "4.80" sameAs "4.8"; "Negatīvs" sameAs "negatīvs"; "IR  ATRASTA" sameAs "ir atrasta"; "<25" !sameAs "25"
// ReferenceRangeTest
@Test void parsesAllShapes() // "4.5 - 5.9","9.1-12.6","(13.2 - 17.5)","(1.002-1.035)" → Between; "<41" Below exclusive; "≤3.9" Below inclusive;
    // ">90" Above exclusive; "≥1.0" Above inclusive; "-","(-)","" → None; "negatīvs","Norma","(nav atrastas)" → TextReference (parentheses stripped)
@Test void judgesInclusiveBetween()   // Between 62.0–106.0: 106.0 → NONE, 107.0 → HIGH, 61.9 → LOW
@Test void judgesOpenBounds()         // "<5.00": 5.04 → HIGH, 5.00 → HIGH, 4.99 → NONE; ">90": 88 → LOW, 90 → LOW, 91 → NONE; "≥1.0": 1.0 → NONE
@Test void judgesComparatorsOnlyWhenCertain() // ">9000" vs "<60" → HIGH; "<25" vs "<25" → NONE; "<25" vs "<10" → empty; "<0.5" vs "<0.5" → NONE
@Test void cannotJudgeTextOrMissingRanges()   // TextReference or None → empty; Text value → empty
@Test void displaysWithEnDashAndScales()      // "(13.2 - 17.5)".scaled(10).display() → "132–175"; "4.0 - 9.8" → "4.0–9.8"; "≥1.0" → "≥1.0"; ">90" → ">90"
```

- [ ] **Step 2: Run** `./gradlew test --tests '*ResultValueTest' --tests '*ReferenceRangeTest'` → FAIL (missing types).
- [ ] **Step 3: Implement** per §5.4 (decimal comma → dot; numeric regex `-?\d+([.,]\d+)?`; comparator `^([<>≤≥])\s*(-?\d+([.,]\d+)?)$`). `judge` for `Compared` (the true value is beyond `x`): `Below(hi)`: `<x` → NONE when `x ≤ hi`, `>x` → HIGH when `x ≥ hi`; `Above(lo)`: `>x` → NONE when `x ≥ lo`, `<x` → LOW when `x ≤ lo`; `Between(lo, hi)`: `<x` → LOW when `x ≤ lo`, `>x` → HIGH when `x ≥ hi`; every other case → empty.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: parse result values and reference ranges, compute flags`

---

### Task 4: Report model, lab detection, column zones, EGL parser

**Files:** Create `Specimen.java`, `Previous.java`, `Result.java`, `RowWarning.java`, `Report.java`, `ReportFormatException.java`, `LabDetector.java`, `Zones.java`, `EglParser.java`. Test: `LabDetectorTest.java`, `EglParserTest.java`, fixtures `egl-*.txt`.

**Interfaces:**
- Consumes: `Page`/`Row`/`Cell`, `ResultValue`, `ReferenceRange`, `Flag`, `Lab`, `Fixtures`.
- Produces:
  - `enum Specimen { BLOOD, URINE, URINE_SEDIMENT }`
  - `record Previous(ResultValue value, LocalDate date, LocalTime time)` (`time` null for EGL)
  - `record Result(String section, String group, Specimen specimen, String name, ResultValue value, String unit, ReferenceRange range, Flag labFlag, Previous previous)` (`group`, `previous` may be null; `unit` is `""` when none)
  - `record RowWarning(int page, String text)`
  - `record Report(Lab lab, String number, LocalDateTime registered, String personalCode, Path file, List<String> sections, List<Result> results, List<RowWarning> warnings)`
  - `final class ReportFormatException extends Exception`
  - `final class LabDetector { static Optional<Lab> detect(List<Page> pages) }` — EGL when any row text contains `Testēšanas pārskats Nr.` and any contains `egl.lv`; CLab when `TESTĒŠANAS PĀRSKATS` and `laboratorija.lv`.
  - `record Zones(float value, float unit, float range, float previous)` — each is the x where that zone starts (header cell x − 12); `static Optional<Zones> egl(Row header)` (cells `Rezultāts`, `References`, `Vienības`, `Iepr.rezult./datums`), `static Optional<Zones> clab(Row header)` (`Tests`, `Rezultāts`, `Vienības`, `References intervāls`, `Iepriekšējais rezultāts`, `Datums`); `enum Zone { NAME, VALUE, UNIT, RANGE, PREVIOUS }` and `Zone of(float x)`.
  - `final class EglParser { static Report parse(Path file, List<Page> pages) throws ReportFormatException }`

**EGL rules (§5.2–5.3, with the measured x positions: index 28, name 62, sections 85–91, values 179, flags 245–249, ranges 269, units 340, previous 436–445):**
- Metadata from joined row texts: number `Testēšanas pārskats Nr\. (\d+)#`; registered from the cell after `Reģistrēts laboratorijā` (`dd.MM.yy,HH:mm`, year 20yy); personal code from the cell after `Personas kods`. Missing number or date → `ReportFormatException`.
- On every page, content starts after the header row; rows before it are ignored. Content ends for the whole document at a row whose first cell starts with `Pārskata beigas`, `Materiāls ņemts` or `#`.
- Ignored rows: a single cell matching `\d+/\d+` with x > 200 (page number).
- Result start: first cell matches `^\d+/\d+( [AU])?$` and x < 50. Name = next cell in the NAME zone; value = first non-flag cell in the VALUE zone. Flags by shape anywhere: `ñ`/`ññ` → HIGH, `ò`/`òò` → LOW. RANGE, UNIT zones fill range and unit; PREVIOUS cell matching `^(.+) / (\d\d\.\d\d\.\d\d)$` → `Previous`. Index row without a value (`Rezultātu novērtējums`) → no result, and nothing is open.
- Bold single cell in the NAME zone right of x 70 → section (resets group; added to `sections`). Non-bold single cell with text `Mikroskopija` → group `Mikroskopija`. Other rows whose first cell starts in the NAME zone right of `nameX + 10` (nameX = x of the open result's name) → notes, ignored.
- Continuation (no index, open result): a first cell within ±3 of nameX appends to the name; cells in VALUE/UNIT/RANGE/PREVIOUS append to those fields (space-joined; units without space after a trailing `/`).
- A row with a cell in VALUE or right of it that is not a result start, continuation or ignored row → `RowWarning(page, row.text())`.
- Specimen: `A` → BLOOD, `U` → URINE, no code → URINE when the section is `Urīna analīze`, else BLOOD; group `Mikroskopija` → URINE_SEDIMENT.

- [ ] **Step 1: Write fixtures** (invented values, one quirk each): `egl-basic` (index rows with `A`, previous results, flags `ñ`, `ò`, `ññ` with `>9000` against `<60`), `egl-multiline` (`TSH - Tireotropais` + `~hormons`; `Ērču encefalīta vīrusa` value `Pozitīvs` + `~62:IgG | 179:150.2 U/mL`; unit `ml/min/1.73` + `~340:m²`; `Antieritrocitārās av.` value `Nav` + `~179:atrastas`), `egl-urine` (section `Urīna analīze`, non-bold `91:Ķīmiska ar teststripu`, `U` rows incl. `Leikocīti <25`, then `91:Mikroskopija` and `11/18 U | 62:Leikocīti | 179:6`), `egl-notes` (index without code `26/39 | 62:Dzelzs saistīšanas spēja | 179:88 | 249:ñ`, a Vitamin D interpretation table at x 95 with numbers in value positions, a second page repeating the patient block and header, page number `300:1/2`, `Rezultātu novērtējums` followed by `85:Veiktajos testos novirzes no normas nav konstatētas.`), `egl-stray` (a row `179:12.3` with no open result).

- [ ] **Step 2: Failing tests**

```java
// LabDetectorTest
@Test void detectsBothLabsAndNothingElse() // Fixtures egl-sample → EGL; clab-sample → CLAB; a page with "Some other lab" → empty
// EglParserTest
@Test void readsHeader()            // egl-sample: number "20000001", registered 2026-03-05T08:15, personalCode "010101-10000", lab EGL
@Test void readsResultRows()        // egl-basic: Hemoglobīns value "150" unit "g/L" range display "132–175" previous 139 on 2025-10-14, specimen BLOOD, section "Hematoloģija"
@Test void readsFlags()             // ò → LOW, ñ → HIGH, ññ → HIGH; value ">9000" is Compared
@Test void joinsWrappedLines()      // names "TSH - Tireotropais hormons", "Ērču encefalīta vīrusa IgG"; values "Pozitīvs 150.2 U/mL", "Nav atrastas"; unit "ml/min/1.73 m²"
@Test void separatesUrineStripFromMicroscopy() // two "Leikocīti": "<25" URINE, "6" URINE_SEDIMENT
@Test void ignoresNotesPageFurnitureAndSummaryRows() // egl-notes: results only the index rows with values; "Dzelzs saistīšanas spēja" BLOOD 88 HIGH; no warnings
@Test void warnsAboutUnattachedRows() // egl-stray → one RowWarning, page 1, text "12.3"
@Test void rejectsReportWithoutNumber() // fixture without "Testēšanas pārskats Nr." line → ReportFormatException
```

- [ ] **Step 3: Run** `./gradlew test --tests '*LabDetectorTest' --tests '*EglParserTest'` → FAIL.
- [ ] **Step 4: Implement** the records, `LabDetector`, `Zones`, `EglParser`.
- [ ] **Step 5: Run** → PASS.
- [ ] **Step 6: Commit** `feat: read EGL reports`

---

### Task 5: CLab parser

**Files:** Create `ClabParser.java`. Test: `ClabParserTest.java`, fixtures `clab-*.txt`.

**Interfaces:**
- Consumes: Task 4 model, `Zones.clab`, `Zones.Zone`.
- Produces: `final class ClabParser { static Report parse(Path file, List<Page> pages) throws ReportFormatException }`

**CLab rules (measured x: names 46–63, values 198–229, flags 253–256, units 261–282, ranges 318–349, previous 420, previous dates 505; header `Tests` 49, `Rezultāts` 194, `Vienības` 257, `References intervāls` 318, `Iepriekšējais rezultāts` 417, `Datums` 522):**
- Metadata: number = a cell matching `^\d{6,10}$` with x > 297 above the `Komentāri:` row of page 1; registered from the cell after `Reģistrēts` (`dd.MM.yyyy HH:mm`); personal code after `Pers.kods`. Missing → `ReportFormatException`.
- On every page content starts after the row whose first cell starts with `Komentāri:`, and ends at a row whose first cell starts with `Materiāls paņemts`, `Materiāls:`, `Rezultātus pārbaudīja`, `PIN kods`, `Pārskata sastādīšanas`, `Testēšanas`, `Bez laboratorijas` or `Ar (1)`.
- Header row (`Zones.clab` present) sets the zones; zones carry over to later pages. Until the first header row, only sections are recognised; everything else is ignored (this is why microbiology reports yield no results).
- Section: a single bold cell with x ≥ 150 (resets group, added to `sections`).
- Result start: first cell in the NAME zone and a non-flag cell in the VALUE zone. Flags `↑` → HIGH, `↓` → LOW. UNIT, RANGE (`(…)` or text in the RANGE zone), PREVIOUS value + a cell matching `^\d\d\.\d\d\.\d{4} \d{1,2}:\d\d$` → `Previous(value, date, time)`.
- A single cell in the NAME zone whose `xEnd` < value-zone start: continuation of the name when a result is open and `row.baseline − lastRowOfThatResult.baseline ≤ 14`; otherwise a group (`Pilna asins aina ar EGĀ`, `Leikocitārā formula`, `Urīna analīze ar stripu`), which closes the open result. Groups persist across pages until the next section or group.
- Continuation cells in VALUE, UNIT or PREVIOUS zones (row without a result start, open result, baseline within 14 pt of the previous row of that result) append to those fields.
- Notes: a row whose first cell's `xEnd` ≥ value-zone start and has no other cells → ignored.
- Anything else with a cell in VALUE or right of it → `RowWarning`.
- Specimen: group starting with `Urīna analīze` → URINE, else BLOOD.

- [ ] **Step 1: Write fixtures**: `clab-cbc` (section, header, group `Pilna asins aina ar EGĀ`, rows with previous value + date, then group `Leikocitārā formula` 29 pt below the last row — write it as a normal row, not `~` — with nested rows at name x 63, value 215, flag 253, unit 275, range 318), `clab-wrapped` (`Glomerulārās`/`~filtrācijas ātrums` + unit `mL/min/`/`1.73 m2`; `Rh (D) pazīme (gelkaršu | 207:Rh(D)` + `~46:met.) | 198:pozitīvs`; COVID `Koronavīrusa 2019-nCoV | 200:IR ATRASTA | 318:nav atrasta` + `~45:RNS noteikšana | 200:koronavīrusa` + `~200:SARS-CoV-2` + `~200:RNS`), `clab-urine` (group `Urīna analīze ar stripu`, `Glikoze | 211:Norma | 282:mmol/L | 325:Norma`, `Īpatnējais svars | 217:1.009 | 325:(1.002-1.035)`, page 2 without header continuing with `Bilirubīns urīnā` and `Krāsa | 203:Dzeltena`), `clab-notes` (an INR note sentence starting at x 60 reaching x 500, the eGFR interpretation lines), `clab-micro` (section `Mikrobioloģija`, no header row, rows with values in value positions).

- [ ] **Step 2: Failing tests**

```java
@Test void readsHeader()               // clab-sample: number "100000001", registered 2026-03-17T08:40, personalCode "010101-10000"
@Test void readsRowsWithPrevious()     // clab-cbc: Hemoglobīns "14.1" unit "g/dL" range "13.2–17.5" previous 14.6 on 2025-11-03 09:12; group "Pilna asins aina ar EGĀ"
@Test void readsNestedFormulaTable()   // Neitrofīli 40.1 LOW unit "%" range 48.0–72.0, group "Leikocitārā formula"
@Test void decimalCommaBecomesNumber() // "110,0" → Numeric(110.0,1)
@Test void joinsWrappedNamesValuesAndUnits() // "Glomerulārās filtrācijas ātrums" unit "mL/min/1.73 m2"; "Rh (D) pazīme (gelkaršu met.)" value "Rh(D) pozitīvs";
    // "Koronavīrusa 2019-nCoV RNS noteikšana" value "IR ATRASTA koronavīrusa SARS-CoV-2 RNS", range TextReference "nav atrasta"
@Test void urineGroupContinuesOnPageWithoutHeader() // clab-urine: Bilirubīns urīnā and Krāsa are URINE; Glikoze range TextReference "Norma"
@Test void ignoresNotes()              // clab-notes: no warnings, results unaffected
@Test void microbiologyHasNoResults()  // clab-micro: results empty, sections ["Mikrobioloģija"], no warnings
```

- [ ] **Step 3: Run** → FAIL. **Step 4: Implement.** **Step 5: Run** → PASS.
- [ ] **Step 6: Commit** `feat: read CLab reports`

---

### Task 6: Test catalog

**Files:** Create `CatalogTest.java`, `CatalogMatch.java`, `TestCatalog.java`, `src/main/resources/lv/sknarovs/analysishistory/catalog.txt`. Test: `TestCatalogTest.java`.

**Interfaces:**
- Consumes: `Lab`, `Specimen`.
- Produces:
  - `record CatalogTest(String section, String name, String unit, int order)` (`order` = position in the file)
  - `sealed interface CatalogMatch` with `Matched(CatalogTest test, BigDecimal factor)`, `Unknown()`, `UnitMismatch(CatalogTest test, String expectedUnit, String printedUnit)`
  - `final class TestCatalog`: `static TestCatalog bundled()`, `static TestCatalog parse(String text)` (throws `IllegalArgumentException` with line number on malformed lines, duplicate aliases, or a factor that is not a power of ten), `CatalogMatch match(Lab lab, Specimen specimen, String name, String unit)`, `List<String> sections()`, `static Specimen specimenOf(String section)`, `static String normaliseName(String)`, `static String normaliseUnit(String)`

**Rules (§5.6):** line syntax `<name> | <unit> | <Lab label>: <printed name>`, optionally followed by `[<printed unit>]` or `[<printed unit> ×<factor>]` (square brackets, because printed names themselves contain parentheses, e.g. `25-OH-vitamīns D (D3+D2)`, `Asins grupa (gelkaršu met.)`), aliases separated by ` | `; `#` comments; `[Section]` headers. `normaliseName`: lowercase, collapse whitespace, remove spaces around `-` and `,`. `normaliseUnit`: remove spaces; `µ`/`μ` → `u`; lowercase; `10x9`, `10e9`, `10⁹` → `10^9`; `10x12`, `10e12`, `10¹²` → `10^12`; `m²` → `m2`; then equivalences `ery/ul` → `/ul`, `leu/ul` → `/ul`. Expected unit = alias printed unit if given, else the test's unit.

- [ ] **Step 1: Write `catalog.txt`** with exactly these lines (covers every test in the 28 reports; names only):

```
# <display name> | <unit> | <lab>: <name as printed> [<printed unit> ×<factor>] | …
# Sections imply the specimen: Urīna analīze → urine, Urīna mikroskopija → urine sediment, others → blood.
[Hematoloģija]
Eritrocīti | 10¹²/L | EGL: Eritrocīti | CLab: Eritrocīti
Hemoglobīns | g/L | EGL: Hemoglobīns | CLab: Hemoglobīns [g/dL ×10]
Hematokrīts | % | EGL: Hematokrīts | CLab: Hematokrīts
MCV - vid. eritrocītu tilpums | fL | EGL: MCV- vid. Er tilpums | CLab: MCV Eritroc.vid.tilp.
MCH - vid. Hb saturs eritrocītā | pg | EGL: MCH-vidējā Hb konc. | CLab: MCH Vid.Hb saturs er.
MCHC - vid. Hb koncentrācija | g/L | EGL: MCHC-vid. Hb konc. Er | CLab: MCHC Vid.Hb konc.er. [g/dL ×10]
RDW - eritrocītu sadalījums | % | EGL: RDW-Er. sadalījums | CLab: RDW Eritr. anizocit.
Trombocīti | 10⁹/L | EGL: Trombocīti | CLab: Trombocīti
MPV - vid. trombocītu tilpums | fL | EGL: MPV-vid. tr. tilpums
PCT - trombocitokrīts | % | EGL: PCT-trombocitokrīts
PDW - trombocītu sadalījums | fL | EGL: PDW-tr. sadalījums
Leikocīti | 10⁹/L | EGL: Leikocīti | CLab: Leikocīti
EGĀ | mm/h | EGL: EGĀ (Kapilārās fotometrijas metode) | CLab: EGĀ
[Leikocītu formula]
Neitrofīli % | % | EGL: Neitrofīlie % | CLab: Neitrofīli
Neitrofīli abs. | 10⁹/L | EGL: Neitrofīlie abs. | CLab: Neitrofīli abs.
Limfocīti % | % | EGL: Limfocīti % | CLab: Limfocīti
Limfocīti abs. | 10⁹/L | EGL: Limfocīti abs. | CLab: Limfocīti abs.
Monocīti % | % | EGL: Monocīti % | CLab: Monocīti
Monocīti abs. | 10⁹/L | EGL: Monocīti abs. | CLab: Monocīti abs.
Eozinofīli % | % | EGL: Eozinofīlie % | CLab: Eozinofīli
Eozinofīli abs. | 10⁹/L | EGL: Eozinofīlie abs. | CLab: Eozinofīli abs.
Bazofīli % | % | EGL: Bazofīlie % | CLab: Bazofīli
Bazofīli abs. | 10⁹/L | EGL: Bazofīlie abs. | CLab: Bazofīli abs.
[Dzelzs un vitamīni]
Dzelzs | µmol/L | EGL: Dzelzs
Transferīns | g/L | EGL: Transferīns
Dzelzs saistīšanas spēja | µmol/L | EGL: Dzelzs saistīšanas spēja
Transferīna piesātinājums | % | EGL: Transferīna piesātinājums
Ferritīns | ng/mL | EGL: Ferritīns
Vitamīns B12 | pg/mL | EGL: Vitamīns B12
Folskābe | ng/mL | EGL: Folskābe
25-OH-vitamīns D (D3+D2) | ng/mL | EGL: 25-OH-vitamīns D (D3+D2)
[Klīniskā ķīmija]
Glikoze | mmol/L | EGL: Glikoze plazmā | CLab: Glikoze
Urīnviela | mmol/L | EGL: Urīnviela | CLab: Urīnviela
Kreatinīns | µmol/L | EGL: Kreatinīns | CLab: Kreatinīns
Glomerulu filtrācijas ātrums | ml/min/1.73 m² | EGL: Glomerulu filtrācijas ātrums | CLab: Glomerulārās filtrācijas ātrums
Bilirubīns kopējais | µmol/L | EGL: Bilirubīns-kopējais | CLab: Bilirubīns kopējais
Bilirubīns tiešais | µmol/L | CLab: Bilirubīns tiešais
ALAT | U/L | EGL: ALAT | CLab: ALAT
ASAT | U/L | EGL: ASAT | CLab: ASAT
Sārmainā fosfatāze | U/L | EGL: Sārmainā fosfatāze
KFK - kreatinkināze | U/L | EGL: KFK - Kreatinkināze
Nātrijs | mmol/L | EGL: Nātrijs | CLab: Nātrijs
Kālijs | mmol/L | EGL: Kālijs | CLab: Kālijs
Kalcijs | mmol/L | EGL: Kalcijs | CLab: Kalcijs
C reaktīvais olbaltums | mg/L | EGL: CRO | CLab: C reaktīvais olbaltums
[Lipīdi]
Kopējais holesterīns | mmol/L | EGL: Kopējais holesterīns
Augsta blīvuma holesterīns | mmol/L | EGL: Augsta blīvuma holesterīns
Ne-ABL holesterīns | mmol/L | EGL: Ne-ABL holesterīns
Zema blīvuma holesterīns | mmol/L | EGL: Zema blīvuma holesterīns
R-holesterīns | mmol/L | EGL: R-holesterīns
Triglicerīdi | mmol/L | EGL: Triglicerīdi
[Hormoni]
TSH - tireotropais hormons | mU/L | EGL: TSH - Tireotropais hormons
Brīvais T4 | pmol/L | EGL: Brīvais T4
Brīvais T3 | pmol/L | EGL: Brīvais T3
T3 - kopējais trijodtironīns | nmol/L | EGL: T3 - Kopējais trijodtironīns
Antivielas pret tireoglobulīnu | IU/mL | EGL: Antivielas pret tireoglobulīnu
Antivielas pret tireoperoksidāzi | IU/mL | EGL: Antivielas pret tireoperoksidāzi
Prolaktīns | mIU/L | EGL: Prolaktīns
Testosterons | ng/mL | EGL: Testosterons
SHBG | nmol/L | EGL: SHBG
Kortizols | nmol/L | EGL: Kortizols
AKTH | pg/mL | EGL: AKTH
[Hemostāze]
Protrombīns (pēc Kvika) | % | EGL: Protrombīna laiks | CLab: Protrombīns
INR |  | EGL: INR | CLab: INR
APTL | sek | EGL: APTL | CLab: APTL
Fibrinogēns | g/L | CLab: Fibrinogēns
[Imūnhematoloģija]
Asins grupa |  | EGL: Asins grupa | CLab: Asins grupa (gelkaršu met.)
Rh(D) |  | EGL: Rēzus | CLab: Rh (D) pazīme (gelkaršu met.)
Antieritrocitārās antivielas |  | EGL: Antieritrocitārās av.
[Infekcijas]
SARS-CoV-2 RNS (PCR) |  | CLab: Koronavīrusa 2019-nCoV RNS noteikšana
Anti-SARS-CoV-2-Nc | COI | EGL: Anti-SARS-CoV-2-Nc
Ērču encefalīta vīrusa IgG |  | EGL: Ērču encefalīta vīrusa IgG
[Urīna analīze]
Īpatnējais svars |  | EGL: Īpatnējais svars | CLab: Īpatnējais svars
pH |  | EGL: Reakcija pH | CLab: Reakcija
Leikocīti | /µL | EGL: Leikocīti | CLab: Leikocīti urīnā
Nitrīti |  | EGL: Nitrītu tests | CLab: Nitrīti
Olbaltums | g/L | EGL: Olbaltums | CLab: Olbaltums
Glikoze | mmol/L | EGL: Glikoze | CLab: Glikoze
Ketoni | mmol/L | EGL: Ketoni | CLab: Ketonvielas
Urobilinogēns | µmol/L | EGL: Urobilinogēns | CLab: Urobilinogēns
Bilirubīns | µmol/L | EGL: Bilirubīns | CLab: Bilirubīns urīnā
Eritrocīti (Hb) | /µL | EGL: Eritrocīti , Hb | CLab: Eritrocīti
Krāsa |  | CLab: Krāsa
Dzidrums |  | CLab: Dzidrums
[Urīna mikroskopija]
Leikocīti | /µL | EGL: Leikocīti
Eritrocīti | /µL | EGL: Eritrocīti
Plakanais epitēlijs | /µL | EGL: Plakanais epitēlijs
Pārejas epitēlijs | /µL | EGL: Pārejas epitēlijs
Hialīnie cilindri | /r.l. | EGL: Hialīnie cilindri
Gļotas |  | EGL: Gļotas
Sāļi |  | EGL: Sāļi
Baktērijas |  | EGL: Baktērijas
```

(If Task 11's real-data run shows a printed unit that differs from this file, e.g. a CLab test printed without a unit, fix the alias with `[<printed unit>]` — that is what the unit check is for.)

- [ ] **Step 2: Failing tests**

```java
@Test void bundledCatalogLoads()                // bundled().sections() starts with [Hematoloģija, Leikocītu formula, Dzelzs un vitamīni, Klīniskā ķīmija]
@Test void mergesDifferentlyNamedTests()        // match(CLAB, BLOOD, "MCV Eritroc.vid.tilp.", "fL") and match(EGL, BLOOD, "MCV- vid. Er tilpums", "fL") → same CatalogTest, factor 1
@Test void convertsUnits()                      // match(CLAB, BLOOD, "Hemoglobīns", "g/dL") → Matched factor 10, test unit "g/L"
@Test void namesIgnoreCaseAndSpacing()          // match(EGL, BLOOD, "TSH-Tireotropais  hormons", "mU/L") → Matched
@Test void unitsCompareNormalised()             // EGL "10x9/L" and CLab "10e9/L" vs "10⁹/L"; "umol/L" vs "µmol/L"; "mL/min/1.73 m2" vs "ml/min/1.73 m²"; CLab urine "Leu/uL", "Ery/uL" → Matched
@Test void specimenSeparatesSameNames()         // EGL "Leikocīti" BLOOD / URINE / URINE_SEDIMENT → three different CatalogTests
@Test void unitMismatchIsReported()             // match(EGL, BLOOD, "Hemoglobīns", "g/dL") → UnitMismatch(expected "g/L", printed "g/dL")
@Test void unknownNameIsUnknown()               // match(EGL, BLOOD, "Homocisteīns", "µmol/L") → Unknown
@Test void rejectsBadCatalogs()                 // parse with duplicate alias, a line without "|", factor ×3 → IllegalArgumentException mentioning the line number
@Test void everySampleFixtureResultIsMapped()   // all results from EglParser/ClabParser over every fixtures/*.txt (except egl-stray) → Matched
```

- [ ] **Step 3: Run** → FAIL. **Step 4: Implement.** **Step 5: Run** → PASS.
- [ ] **Step 6: Commit** `feat: catalog that merges tests across labs`

---

### Task 7: History builder

**Files:** Create `Visit.java`, `HistoryCell.java`, `HistoryRow.java`, `History.java`, `HistoryBuilder.java`. Test: `HistoryBuilderTest.java`.

**Interfaces:**
- Consumes: `Report`, `Result`, `TestCatalog`, `CatalogMatch`, `ResultValue`, `ReferenceRange`, `Flag`.
- Produces:
  - `record Visit(Lab lab, LocalDate date, List<Report> reports)` with `String numbers()` (report numbers in registration order, joined `", "`)
  - `record HistoryCell(ResultValue value, Flag flag, String note)` (`value` already in the row's unit; `note` null when none)
  - `record HistoryRow(String section, String name, String unit, String reference, String nameNote, List<HistoryCell> cells)` — `cells.size() == visits.size()`, null entries for empty cells
  - `record History(List<Visit> visits, List<HistoryRow> rows, List<String> warnings)`
  - `final class HistoryBuilder { static History build(List<Report> reports, TestCatalog catalog) }`

**Rules (§4.1, §5.5):**
- Row key: `Matched` → the `CatalogTest`; otherwise (lab, specimen, normalised name, normalised printed unit). Unmapped rows: name = first printed name, unit = printed unit, section = the result's section (or `"Citi"` when empty), `nameNote` = `Only in <Lab label> reports. Add an alias to catalog.txt to merge it with other labs.`; `UnitMismatch` also adds warning `<file name>: <name> is printed in '<printed>' but the catalog expects '<expected>'; kept in its own row`.
- Visits: group reports with ≥ 1 result by (lab, registration date). Within a group, take reports by registration time and put each into the first column of the group that has none of its row keys; else start a new column. Visits sorted by the latest registration time in the visit, newest first.
- Rows: catalog sections in catalog order, then other sections in first-seen order; within a section mapped rows by `order`, then unmapped rows in first-seen order. Rows without any value are omitted.
- Cell value = `value.scaled(factor)`. Row reference = the newest cell's `range.scaled(factor)` that is not `None`; `reference` = its `display()`.
- Flag: own range numeric → `judge`; own range `None` and row reference numeric → `judge` against it; when that yields empty (or the range is text) → `labFlag`.
- Note lines, joined with `\n`: converted (factor ≠ 1) → `<Lab label>: <raw value display> <printed unit>`; own range not `None` and its scaled display ≠ row reference → `Ref. <display> <row unit>` (trimmed).

- [ ] **Step 1: Failing tests** (build `Report`s directly in Java with invented values)

```java
@Test void newestVisitFirstAndSameDayReportsShareColumn()   // EGL blood 04.02.2019 07:55 + EGL urine 04.02.2019 08:30 + CLab 2026 → visits [CLab 2026, EGL 2019]; EGL numbers "20000010, 20000011"
@Test void sameDayReportsWithSameTestGetSeparateColumns()   // two EGL reports on one date both with Hemoglobīns → two visits, newer first, both values present
@Test void mergesLabsIntoOneRowWithConversion()             // CLab Hemoglobīns 14.1 g/dL + EGL 150 g/L → one row unit "g/L"; cells "141" (note "CLab: 14.1 g/dL") and "150" (no note)
@Test void referenceComesFromNewestPrintedRange()           // newest CLab Kreatinīns (62.0 - 106.0), older EGL 65-104 → reference "62.0–106.0"; EGL cell note "Ref. 65–104 µmol/L"
@Test void flagsFollowTheRule()                             // CLab GFR 85 without range + EGL GFR ">90" row reference → LOW; text value with lab flag HIGH → HIGH; Neitrofīli % 40.1 against 48 - 72 with no lab flag → LOW
@Test void rowsFollowCatalogOrderAndSkipEmptyTests()        // results for TSH, Hemoglobīns, Leikocīti → order Hemoglobīns, Leikocīti (Hematoloģija), TSH (Hormoni)
@Test void unmappedTestGetsOwnRowAndNote()                  // EGL "Homocisteīns" in section "Klīniskā ķīmija" → row after mapped Klīniskā ķīmija rows, nameNote "Only in EGL reports. …"
@Test void unitMismatchWarnsAndKeepsRowsApart()             // EGL Hemoglobīns printed "g/dL" → separate row + warning text as specified
@Test void reportsWithoutResultsGetNoColumn()               // microbiology report (no results) → no visit
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: build the visit × test history`

---

### Task 8: Consistency check

**Files:** Create `ConsistencyCheck.java`. Test: `ConsistencyCheckTest.java`.

**Interfaces:**
- Consumes: `Report`, `Result`, `Previous`, `TestCatalog`, `ResultValue.sameAs`.
- Produces: `final class ConsistencyCheck { static List<String> check(List<Report> reports, TestCatalog catalog) }`

**Rules (§7):** for each result with `previous`, candidate earlier reports: same lab, `registered.toLocalDate()` == `previous.date()`, and (`previous.time()` null or equal to `registered.toLocalTime()` truncated to minutes). The earlier result is the one with the same row key as in Task 7 (catalog test, or specimen + normalised name). Compare the printed (unscaled) values with `sameAs`. Mismatch → `<file>: <name> previous result <previous display> (<dd.MM.yyyy>) differs from <earlier display> in <earlier file>`. No candidate report or no matching result → nothing.

- [ ] **Step 1: Failing tests**

```java
@Test void matchingPreviousResultsAreSilent()     // CLab report says previous Hemoglobīns 14.6 at 03.11.2025 09:12; earlier report has "14.6" → []
@Test void mismatchIsReported()                   // earlier has "15.0" → one warning with both file names and "03.11.2025"
@Test void clabTimeMustMatch()                    // earlier CLab report same date 10:00 vs previous 09:12 → []
@Test void eglMatchesByDateOnly()                 // EGL previous "4.9 / 04.02.19" → earlier EGL registered 2019-02-04 07:55 checked
@Test void missingEarlierReportIsIgnored()        // → []
@Test void comparesNumbersByValue()               // previous "4.8" vs earlier "4,80" (Numeric 4.80) → []
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: cross-check printed previous results`

---

### Task 9: History sheet

**Files:** Modify `WorkbookWriter.java`, `Main.java` (call site). Test: `WorkbookWriterTest.java` (extend), `XlsxReader.java` (extend if needed).

**Interfaces:**
- Consumes: `History`, `Visit`, `HistoryRow`, `HistoryCell`.
- Produces: `static void write(Path target, History history, List<ReportStatus> statuses, LocalDateTime generated) throws IOException` (replaces the Task 2 signature); sheet order History, Reports.

**Rules (§4.1):** A1..C1 `Test`, `Unit`, `Reference`; visit columns from D: row 1 date (`LocalDate`, format `dd.mm.yyyy`), row 2 lab label, row 3 `numbers()`; `freezePane(3, 3)`. Section rows: bold, fill `D9D9D9` across all used columns, then `groupRows(first, last)` over the section's test rows. Numbers via `value(r, c, BigDecimal)` with format `0` / `0.0…` by decimals; HIGH: font `C00000`, fill `FDE2E2`, format suffix `" ↑"`; LOW: `1F4E9E`, `DDEBF7`, `" ↓"`; `Compared`/`Text` values as strings, right-aligned, flagged ones get `" ↑"`/`" ↓"` appended. Notes via `comment(r, c, note)`; `nameNote` on column A. Widths A 34, B 14, C 12, visits 11. Reports sheet: status cells with warnings get `comment` = warnings joined with `\n`.

- [ ] **Step 1: Failing tests**

```java
@Test void writesHeaderRowsAndFreezes(@TempDir Path dir)    // D1 isNumber, value = LocalDate.of(2026,4,20).toEpochDay() + 25569, numberFormat "dd.mm.yyyy"; D2 "CLab"; D3 "100000003"; frozenTopLeft "D4"
@Test void writesSectionBandsAndRows(@TempDir Path dir)     // A4 "Hematoloģija" bold fill D9D9D9; A5 "Hemoglobīns", B5 "g/L", C5 "132–175"
@Test void keepsNumbersNumericWithPrintedDecimals(@TempDir Path dir) // cell "0.290" stored numeric with format "0.000"
@Test void colorsAndArrowsFlags(@TempDir Path dir)          // HIGH cell font C00000 fill FDE2E2 format ending "\" ↑\""; LOW 1F4E9E / DDEBF7 / " ↓"; text ">9000" HIGH → ">9000 ↑"
@Test void writesNotes(@TempDir Path dir)                   // converted cell note "CLab: 14.1 g/dL"; unmapped name note
@Test void fileLinkIsRelativeToWorkbookFolder(@TempDir Path dir) // workbook in dir/out/, PDF dir/"Rīga analīzes 1.pdf" → hyperlink "../Rīga analīzes 1.pdf" (URI-encoded as fastexcel requires)
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: write the History sheet`

---

### Task 10: Complete the run — detection, statuses, duplicates, warnings

**Files:** Modify `AnalysisHistory.java`, `Main.java`, `.github/workflows/build.yml` (smoke expectation). Test: `AnalysisHistoryTest.java`, `MainTest.java` (extend).

**Interfaces:**
- Consumes: everything above.
- Produces: `record RunResult(History history, List<ReportStatus> statuses, List<String> warnings)`; `static RunResult run(Path dir, TestCatalog catalog) throws IOException`.

**Rules (§7):** per PDF: `PdfText.read` → `LabDetector.detect` → parser. Status: results > 0 → `OK` / `OK, 1 warning` / `OK, <n> warnings` (row warnings of that report, formatted `page <n>: unattached row: <text>`); no results → `Not tabulated: <sections joined ", ">`; no lab → `Unrecognised`; `ReportFormatException`/`IOException` → `Error: <message>`. Duplicates: same lab + number → keep the report with more results (tie: first file name), the other gets `Duplicate of <kept file name>` and is excluded from history. More than one distinct personal code → warning `reports belong to more than one person: <files of person 1>; <files of person 2>` (never print the codes). `RunResult.warnings` = per-report warnings prefixed with the file name + `History.warnings` + `ConsistencyCheck.check`. Main: no tabulated report → print `no reports could be read in <dir>` and return 1 without writing; otherwise write with `TestCatalog.bundled()`.

- [ ] **Step 1: Failing tests**

```java
@Test void samplesBecomeTwoVisits(@TempDir Path dir)                      // SampleReports → statuses both "OK", history visits [CLab 17.03.2026, EGL 05.03.2026], Hemoglobīns row has "141" and "150"
@Test void unrecognisedAndBrokenFilesAreListedNotFatal(@TempDir Path dir) // samples + "other.pdf" (TestPdf "Some other lab") + "empty.pdf" (0 bytes) → "Unrecognised", "Error: …", samples still OK
@Test void duplicateReportIsListedOnce(@TempDir Path dir)                 // copy of CLab sample as "CLab_100000001 (1).pdf" → one CLab visit; status "Duplicate of CLab_100000001.pdf"
@Test void warnsWhenReportsBelongToTwoPeople(@TempDir Path dir)           // second EGL fixture with code 020202-20000 → warning contains both file names, not the codes
@Test void notTabulatedReportsAreNamed(@TempDir Path dir)                  // clab-micro rendered → "Not tabulated: Mikrobioloģija"
// MainTest
@Test void folderWithoutReadableReportsExitsOne(@TempDir Path dir)        // only "other.pdf" → exit 1, err "no reports could be read", no workbook
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./gradlew test` → PASS.
- [ ] **Step 5:** In the CI smoke step change the check to `grep -q "2 PDFs: 2 tabulated" out.txt && grep -q ", 0 errors" out.txt`. Run `TOOLBOX ./gradlew nativeTest nativeCompile` and the Task 2 Step 7 lone-binary check again; re-run the tracing agent (Task 2 Step 6) if `nativeTest` reports missing metadata.
- [ ] **Step 6: Commit** `feat: complete the run with statuses, duplicates and warnings`

---

### Task 11: Real-data checks

**Files:** Create `src/test/java/.../RealDataTest.java`, `HistoryHtml.java`.

**Interfaces:**
- Consumes: `AnalysisHistory.run`, `EglParser`, `PdfText`, `TestCatalog.normaliseName`.
- Produces: `final class HistoryHtml { static Map<String, Map<String, List<String>>> read(Path html) }` — report number → normalised test name → cell texts (rows `<tr class="Test">`; columns from the second header row of report numbers; cell text with tags removed, `⇧`/`⇩` removed, whitespace collapsed; empty cells skipped).

**Rules (§8.3):** the class is `@EnabledIf("resultsPresent")`; folder = system property `analysis.results` or `results/` (pass it through in `tasks.test` with `systemProperty("analysis.results", …)` only when the property is set). Never committed data; CI has no `results/`, so the tests are skipped there.

- [ ] **Step 1: Write the tests**

```java
@Test void everyReportIsReadWithoutWarnings()   // run(results, bundled) → no status "Unrecognised" or "Error: …" or "Duplicate…"; RunResult.warnings empty
@Test void eglValuesMatchTheLabsHtmlExport()    // for each rezultatu_vesture_*.html: per report number and normalised name, the sorted list of HTML texts equals the sorted
    // list of parsed EGL value displays (Text/Compared/Numeric display); names in EXCLUDED = {"formulas % kopā", "rezultātu apraksts", "rezultātu novērtējums"} skipped;
    // assertion message lists every differing (report, test, html, parsed)
@Test void noPreviousResultMismatches()         // ConsistencyCheck over all parsed reports → []
```

- [ ] **Step 2: Run** `./gradlew test --tests '*RealDataTest'` (on the machine with `results/`). Expected at first: failures that point at parser gaps, catalog units, or HTML-only rows.
- [ ] **Step 3: Fix** each failure at its source: a parser rule (add a fixture reproducing it with invented values and a test in Task 4/5's test class first), a catalog alias unit, or — only for rows that exist in the HTML but are never printed in PDFs — an `EXCLUDED` entry with a comment saying why. Re-run until all three pass; also run `./gradlew run --args=results` and open `results/analysis-history.xlsx` to confirm the layout by eye.
- [ ] **Step 4: Commit** `test: check parsed values against the HTML export and previous results` (only test code, fixtures and fixes — `git status` must show nothing under `results/`).

---

### Task 12: Documentation and first release

**Files:** Create `README.md`, `AGENTS.md`.

- [ ] **Step 1: Write `README.md`**: what it does; download per platform from Releases; usage `analysis-history [DIR] [-o FILE]` with exit codes; macOS `xattr -d com.apple.quarantine analysis-history`; Windows "More info → Run anyway"; how to add a catalog alias (edit `catalog.txt`, line syntax, run `./gradlew test`); building locally (host `./gradlew test run --args=results`, toolbox native commands); privacy note (never commit `results/`).
- [ ] **Step 2: Write `AGENTS.md`** in the style of `~/Work/bksb-calendar/AGENTS.md`: developer commands (host and `TOOLBOX`), key architecture facts (classes and responsibilities from the spec §3, the two native build arguments and the AWT block and why, the font mapper and why, the fixture format, the catalog rules), testing (fixtures, generated samples, real-data tests skipped without `results/`), CI/release (four targets, `v*` tags), and the rule that no real report or workbook is ever committed.
- [ ] **Step 3: Verify**: `./gradlew test`, `TOOLBOX ./gradlew nativeTest nativeCompile`, lone-binary check from Task 2 Step 7 on `results/` → exit 0, `0 errors`, no JDK libraries opened.
- [ ] **Step 4: Commit** `docs: README and AGENTS.md`. Ask the user before tagging `v0.1.0` (tag push publishes a public release).

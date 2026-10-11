# Analysis History — Design

- **Date:** 2026-10-11
- **Status:** Draft for review
- **Repository:** this folder (`blood-analysis`), new; nothing implemented yet

## 1. Intent

### What was asked

- A tool that turns the laboratory reports in a folder into one Excel workbook showing how results
  change over time.
- Reports come from two laboratories with different formats: E. Gulbja Laboratorija (EGL,
  `testesanas_parskats_<nr>.pdf`) and Centrālā Laboratorija (CLab, `CLab_<nr>.pdf`).
- EGL can export an HTML history (`rezultatu_vesture_<nr>.html`); CLab cannot. The workbook
  replaces that export and covers both laboratories.
- Newest results first: each column is one visit, and columns go back in time to the right.
- It is run periodically on the results folder.
- It ships as a **self-contained native binary** (GraalVM) that runs on Linux, Windows and macOS
  without depending on anything installed on the machine: no JVM, no system tools (for example
  poppler), no system fonts.

### Answers gathered during design

| Topic | Answer |
|---|---|
| Same test at both labs | One merged row. Units are converted to the row's unit (CLab g/dL → g/L); the original value goes in a cell note. |
| Scope | Every result in the labs' standard results tables: blood, urine, COVID PCR, blood group. Free-form reports (microbiology) are listed but not tabulated. |
| Stack | Java 25, Apache PDFBox, fastexcel, GraalVM Native Image, Gradle (Kotlin DSL); same conventions as `bksb-calendar` and `lff-futsal-calendar`. |
| PDF→Markdown (`anydoc`) | Not used: it separates values from their units and ranges in these tables (§2). |
| Fallback to `pdftotext` | Rejected: the binary must not depend on the host system. |
| Release targets | `linux-x86_64` (static), `linux-aarch64`, `windows-x86_64`, `macos-aarch64`. |
| Name | `analysis-history`: the reports cover more than blood. |
| Workbook layout and flag rule | As in §4 and §5.5. |

### Constraints discovered

- **Personal data.** Reports and the HTML export contain name, personal code, address, e-mail and
  diagnosis codes. Nothing from `results/`, no PDF, HTML or workbook may be committed; test fixtures
  use invented data; the workbook never contains the personal code.
- 28 sample reports (18 EGL, 10 CLab). All have a text layer, so no OCR is needed.
- **EGL** PDFs are produced by Prince and embed Verdana. High/low arrows are Wingdings glyphs that
  extract as `ñ` (⇧ high), `ò` (⇩ low) and `ññ` (⇧⇧). EGL prints no arrow on leukocyte-differential
  percentages when the absolute count is normal, while its HTML export does flag them. Result rows
  start with an index `n/N` and a sample code (`A` blood, `U` urine); calculated rows have no code.
  Two-digit years (`dd.mm.yy`).
- **CLab** PDFs are produced by ReportLab, embed Calibri and use **non-embedded** Helvetica and
  Times-Roman. Arrows are `↑ ↓`. Current results use a decimal comma, previous results a dot.
  Hemoglobin and MCHC are in g/dL (EGL: g/L). Some tables are nested ("Leikocitārā formula") and
  sit at different x positions from the header. Pages can continue a table without repeating its
  header.
- **CLab rows have two baselines:** name and value at 11 pt, unit, range and previous result at
  8 pt, about 3 pt higher.
- **CLab draws its column header as one string with spaces**, so PDFBox reports it as one "word";
  cells must be rebuilt from glyph positions.
- **Same name, different sample:** `Leikocīti`, `Eritrocīti` and `Glikoze` appear for blood and
  urine; EGL urine `Leikocīti` appears twice (test strip and microscopy).
- Multi-line names, values and units occur in both labs (`TSH - Tireotropais` / `hormons`,
  `Rh(D)` / `pozitīvs`, `mL/min/` / `1.73 m2`).
- GraalVM Native Image cannot cross-compile: each OS's binary is built on that OS.
- Local toolchain: GraalVM CE 25.4.4.1.1 (JDK 25) and Temurin 25 via SDKMAN, Gradle 9.8.1, Fedora
  Atomic host; `native-image` runs in `fedora-toolbox-44` (gcc, glibc-devel, zlib headers).

### Success criteria

1. One command on the results folder writes a workbook in which every tabulated value equals the
   value printed in its PDF. For EGL this is checked against the lab's own HTML export; for both
   labs against each report's "previous result" column (§8).
2. Both laboratories share one timeline, newest first; the same test is one row; out-of-range values
   are highlighted.
3. Nothing is dropped silently: every PDF appears on the Reports sheet with a status, and unparsed
   rows, unit mismatches and consistency mismatches are reported.
4. Each release artifact is a single executable that works with nothing else installed. On Linux
   this is shown in a container with no fonts and no network; on all four targets by CI smoke tests.
5. A new report in the folder appears after the next run. A test the catalog does not know appears
   as its own row until an alias is added.

### Non-goals

- OCR or scanned reports.
- Laboratories other than EGL and CLab (the design allows adding a parser per lab later).
- Charts or sparklines (fastexcel cannot write them). Values are numeric, so charts can be made in
  Excel.
- Tabulating free-form reports such as microbiology cultures.
- Reading the EGL HTML export as input (it is a test oracle only).
- Lab notes in the workbook (for example "No 25.03.26 jauna metode…").
- Reading sub-folders.
- Code signing or notarisation of the binaries.

## 2. Feasibility probe (2026-10-11)

A throwaway program using PDFBox 3.0.8 and fastexcel 0.20.2 was built as a native image in the
toolbox and run on all 28 sample reports.

| Check | Result |
|---|---|
| Bare executable copied alone into an empty folder | 28/28 reports read in about 0.45 s; extracted text identical to the JVM run |
| Container with no font files, no network, reports mounted read-only | Same output |
| A full JDK on `LD_LIBRARY_PATH` | Without the AWT block (§6.2) the binary loaded the JDK's `libawt`, `libawt_xawt` and `libjvm`. With it, no JDK library is opened |
| Font cache | The bundled font mapper (§5.1) never writes `~/.pdfbox.cache`; PDFBox's default mapper scans system fonts and writes it |
| Workbook | Opens in an independent reader; number formats, notes and hyperlinks present |
| Size and Linux dependencies | 27 MB with all charsets; a default (dynamic) build needs glibc ≥ 2.34 and `libz` |

Other findings:

- PDFBox's `PDDocument` static initialiser warms up AWT colour management and catches
  `UnsatisfiedLinkError` itself.
- Native Image includes only a few charsets by default; PDFBox then falls back from Windows-1252 to
  ISO-8859-1 and logs a stack trace.
- `anydoc` output for CLab listed nine haematology values, then their nine units and ranges as a
  separate table; for EGL it detached ranges and units from rows. It is not usable for parsing.

## 3. Architecture

```
<DIR>/*.pdf
  │  PdfText: PDFBox + bundled font mapper → glyphs → rows (vertical overlap) → cells (gaps)
  ▼
LabDetector ──► EglParser | ClabParser                  unrecognised → Reports sheet
  │  Report: lab, number, registered, personal code, results, sections, row warnings
  ▼
TestCatalog (catalog.txt): (lab, specimen, name, unit) → catalog test + conversion factor
  ▼
HistoryBuilder → History: visits (newest first) × rows (by section)
ConsistencyCheck → warnings (previous results vs earlier reports)
  ▼
WorkbookWriter (fastexcel) → <DIR>/analysis-history.xlsx (temp file, then rename)
```

Every run reads all PDFs and rewrites the workbook. Nothing is stored between runs.

### Components (package `lv.sknarovs.analysishistory`)

| Unit | Responsibility | Uses |
|---|---|---|
| `Main`, `CliOptions` | Arguments, exit codes, console summary | everything below |
| `PdfText` | The only class using PDFBox. Installs `BundledFontMapper`. Produces pages → rows → cells | PDFBox |
| `NoAwtSubstitutions` | Native image only: AWT native loading fails immediately (§6.2) | `org.graalvm.sdk:nativeimage` (compile only) |
| `LabDetector` | Chooses the parser from the text, never from the file name | `PdfText` model |
| `EglParser`, `ClabParser` | One lab's layout → `Report`. Know nothing about Excel or the other lab | `PdfText` model, value types |
| `ResultValue`, `ReferenceRange`, `Flag` | Parsing values and ranges; computing flags | — |
| `TestCatalog` | Loads `catalog.txt`; maps results to catalog tests; unit equivalence and conversion | — |
| `HistoryBuilder` | Visits, rows, reference column, flags, notes. Pure logic, no I/O | catalog, model |
| `ConsistencyCheck` | Compares printed previous results with earlier reports | model |
| `WorkbookWriter` | The only class using fastexcel | fastexcel |

### Model (records)

- `Report(Lab lab, String number, LocalDateTime registered, String personalCode, Path file,
  List<String> sections, List<Result> results, List<RowWarning> warnings)`
- `Result(String section, String group, Specimen specimen, String name, ResultValue value,
  String unit, ReferenceRange range, Flag labFlag, Previous previous)`
- `ResultValue`: `Numeric(BigDecimal value, int decimals)` | `Compared(Comparator op, BigDecimal value)`
  | `Text(String text)`
- `ReferenceRange`: `Between(lo, hi)` | `Below(x, inclusive)` | `Above(x, inclusive)` |
  `TextReference(String)` | `None`
- `Previous(ResultValue value, LocalDate date, LocalTime time)` (`time` is null for EGL)
- `Specimen`: `BLOOD`, `URINE`, `URINE_SEDIMENT`; `Lab`: `EGL`, `CLAB`; `Flag`: `HIGH`, `LOW`, `NONE`

### Dependencies

- Runtime: `org.apache.pdfbox:pdfbox` 3.0.8 (brings `pdfbox-io`, `fontbox`, `commons-logging` 1.4.0)
  and `org.dhatim:fastexcel` 0.20.2 (brings `opczip` 1.2.0).
- Compile only: `org.graalvm.sdk:nativeimage`, matching the GraalVM release used for builds.
- Test: JUnit 6. Workbooks are read back with the JDK's zip and XML APIs, so the tests run unchanged
  as a native image.

## 4. Workbook

Two sheets, **History** and **Reports**. Headers are in English; section and test names are Latvian,
as printed on the reports.

### 4.1 History

Example with invented values:

```
      A                       B               C           D           E           F
 1    Test                    Unit            Reference   20.04.2026  17.03.2026  05.03.2026  …
 2                                                        CLab        CLab        EGL
 3                                                        100000003   100000002   20000001
 4  ▾ Hematoloģija ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒
 5    Hemoglobīns             g/L             132–175                 141 ◤       150
 6    Leikocīti               10⁹/L           4.0–9.8                 3.9 ↓       5.20
 7  ▾ Klīniskā ķīmija ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒
 8    Kreatinīns              µmol/L          62.0–106.0  88.0        110.0 ↑     90 ◤
 9    Glomerulu filtr. ātrums ml/min/1.73 m²  >90         95          85 ↓        92
◤ cell notes: E5 "CLab: 14.1 g/dL" · F8 "Ref. 65–104 µmol/L"
```

- **Visits.** One column per visit: same laboratory and same registration date. The reports of a
  visit share a column unless two of them contain the same test; those stay in separate columns,
  ordered by registration time. The newest visit is column D. A visit with no tabulated results gets
  no column.
- **Header rows.** Row 1: date (an Excel date, shown `dd.mm.yyyy`). Row 2: `EGL` or `CLab`. Row 3:
  report number(s), comma-separated. Rows 1–3 and columns A–C are frozen.
- **Sections.** Shaded bold band rows, collapsible as row groups, in catalog order. Default order:
  Hematoloģija, Leikocītu formula, Dzelzs un vitamīni, Klīniskā ķīmija, Lipīdi, Hormoni, Hemostāze,
  Imūnhematoloģija, Infekcijas, Urīna analīze, Urīna mikroskopija. Sections found only on unmapped
  tests follow.
- **Rows.** Catalog order within a section. A test gets a row only when some report contains it.
  Unmapped tests follow the mapped ones in the section where they were found.
- **Column C** shows the reference range of the newest result in the row that printed one,
  converted to the row's unit. Text references (for example `negatīvs`) are shown as text.
- **Values.** Numeric results are stored as numbers with the decimals printed in the report.
  Converted values lose `log10(factor)` decimals, down to zero (14.1 g/dL → 141 g/L). Comparator
  values (`<25`, `>9000`) and text results (`Negatīvs`, `Rh(D) pozitīvs`) are stored as text,
  right-aligned.
- **Flags** (§5.5). High: red text `C00000` on `FDE2E2` with ` ↑`. Low: blue text `1F4E9E` on
  `DDEBF7` with ` ↓`. For numbers the arrow is part of the number format (for example `0.0" ↑"`), so
  the cell stays numeric for formulas and charts; for text cells it is appended to the text.
- **Notes**, only when informative:
  - converted value: `CLab: 14.1 g/dL` (as printed);
  - reference range different from column C, compared in the row's unit: `Ref. 65–104 µmol/L`;
  - on the name of an unmapped test: `Only in EGL reports. Add an alias to catalog.txt to merge it
    with other labs.`
- **Widths.** A 34, B 14, C 12, visits 11.

### 4.2 Reports

```
Generated 11.10.2026 17:40 from 30 PDFs: 27 tabulated, 2 not tabulated, 1 unrecognised, 0 duplicates, 0 errors

Registered        Lab   Report no.  File                              Results  Status
20.04.2026 10:05  CLab  100000003   CLab_100000003.pdf                12       OK
02.04.2026 08:50  CLab  100000001   CLab_100000001.pdf                –        Not tabulated: Mikrobioloģija
05.03.2026 08:15  EGL   20000002    testesanas_parskats_20000002.pdf  3        OK, 1 warning ◤
                                    scan-2019.pdf                     –        Unrecognised
```

- One row per PDF, newest first; files without a date (unrecognised, errors) at the end.
- **File** is a hyperlink to the PDF, relative to the workbook's folder.
- **Status:** `OK`; `OK, n warnings` with the warning texts in a cell note; `Not tabulated: <sections>`;
  `Unrecognised`; `Duplicate of <file>`; `Error: <message>`.

## 5. Reading reports

### 5.1 Text layer (`PdfText`)

- A `PDFTextStripper` subclass collects every glyph: character, x, width, baseline, height, font
  size, and whether the font name contains `Bold`.
- **Rows:** glyphs sorted by baseline. A glyph joins the current row when its vertical extent
  `[baseline − height, baseline]` overlaps the row's extent; this joins CLab's two baselines.
- **Cells:** a row's glyphs sorted by x, whitespace glyphs dropped. A gap wider than 0.45 × font size
  starts a new cell; a gap wider than 0.15 × font size inside a cell becomes one space. (Checked on
  real reports: `26/39 | Dzelzs saistīšanas spēja | 88 | ñ | 45 - 63 | µmol/L`.)
- Model: `Page(int number, List<Row> rows)`, `Row(List<Cell> cells)`,
  `Cell(float x, float xEnd, String text, boolean bold)`.
- **`BundledFontMapper`** maps every non-embedded font to PDFBox's bundled LiberationSans. Text
  extraction only needs the font metrics PDFBox ships (AFM files), so results are identical on every
  machine; system fonts are never scanned and nothing is written to the home folder.
- PDFBox's logging is switched off; the tool prints its own messages only.

### 5.2 Laboratory and report header

| | EGL | CLab |
|---|---|---|
| Recognised by | `Testēšanas pārskats Nr.` and `egl.lv` | `TESTĒŠANAS PĀRSKATS` and `laboratorija.lv` |
| Report number | `Nr. <digits>#…` | the lone 6–10-digit cell in the right half of the header block |
| Registered | `Reģistrēts laboratorijā dd.mm.yy,HH:mm` (year 20yy) | `Reģistrēts dd.mm.yyyy HH:mm` |
| Personal code | `Personas kods` | `Pers.kods` |

The personal code is used only for the "more than one person" warning (§7).

### 5.3 Results table

- **Column zones** come from the page's header row: EGL `Rezultāts`, `References intervāls`,
  `Vienības`, `Iepr.rezult./datums`; CLab `Tests`, `Rezultāts`, `Vienības`, `References intervāls`,
  `Iepriekšējais rezultāts`, `Datums`. A CLab page without a header row reuses the previous page's
  zones, and its content starts after the `Komentāri:` line.
- **Result start.** EGL: a cell matching `^\d+/\d+( [AU])?$` at the left margin. CLab: a name cell
  left of the result zone plus a cell inside it.
- **Cells are classified by shape first, then by zone.** Shapes: flags `↑ ↓ ñ ò ññ`; `(…)` is a CLab
  range; `dd.mm.yyyy HH:mm` is a CLab previous-result date; `x / dd.mm.yy` is an EGL previous result.
  Everything else by zone: value, unit, range, previous value. (Shape comes first because CLab's
  nested tables put flags at x ≈ 253, under the `Vienības` header at 257.)
- **Continuation rows** have cells only in the name, value, unit or previous-result zones and no
  result start. They append to the previous result's fields with a space; unit parts are joined
  without one when the first part ends with `/` (`mL/min/` + `1.73 m2`).
- **Sections and groups** (checked on the sample reports):
  - EGL: bold lines right of the name column are sections (`Hematoloģija`, `Urīna analīze`). Sub-headings
    are not bold and sit within a few points of note lines, so only the one that matters is recognised,
    by its text: a line reading `Mikroskopija` starts the urine-sediment block.
  - CLab: bold centred titles are sections. A single cell at the name margin that ends before the result
    zone is a group (`Pilna asins aina ar EGĀ`, `Leikocitārā formula`, `Urīna analīze ar stripu`); bold
    varies, so it is not used. A group lasts until the next section or group, across page breaks.
- **Ignored:** everything above the header row (patient block); footers (`Pārskata beigas`,
  `Materiāls ņemts`/`paņemts`, `Rezultātus pārbaudīja`, page numbers); EGL rows starting right of the
  name column that are neither sections nor `Mikroskopija` (notes, sub-headings, interpretation
  tables); CLab rows whose first cell reaches past the start of the result zone (note sentences); EGL
  `Rezultātu novērtējums` (an index row without a value).
- A row with a cell in the result zone that cannot be attached becomes a `RowWarning` with file, page
  and row text.
- **Specimen.** EGL: code `A` → blood, `U` → urine; rows without a code take the section's specimen
  (`Urīna analīze` → urine); rows under the `Mikroskopija` group → urine sediment. CLab: rows under a
  group starting with `Urīna analīze` → urine; otherwise blood.
- A recognised report with no result rows is reported as `Not tabulated: <section names>`.

### 5.4 Values and ranges

- Decimal comma becomes a dot. `-?\d+([.,]\d+)?` is numeric and keeps its number of decimals.
  `[<>≤≥]` followed by a number is a comparator value. Anything else is text; multi-line text is
  joined with spaces.
- Ranges: `a - b`, `a-b`, `(a - b)` → between, inclusive. `<x` (exclusive) and `≤x` → upper bound.
  `>x` (exclusive) and `≥x` → lower bound. `-`, `(-)` or nothing → none. Any other text → text
  reference: shown, never used for flags.

### 5.5 Flags

1. Numeric value and numeric range from the same report → high or low by the bounds above.
2. The report printed no range → the row's reference (column C), if numeric.
3. Comparator values are flagged only when the outcome is certain: `>9000` against `<60` → high;
   `<25` against `<25` → normal; `<25` against `<10` → no flag.
4. Otherwise the lab's printed arrow decides (EGL `ñ`/`ò`, CLab `↑`/`↓`).

Known differences from the laboratories: an EGL eGFR of `85` against `>90` is flagged low although EGL
prints no arrow; differential percentages outside their range are flagged although EGL's PDFs omit
the arrow (its HTML export shows it).

### 5.6 Catalog

`src/main/resources/lv/sknarovs/analysishistory/catalog.txt`, UTF-8, one line per test:

```
# <display name> | <unit> | <lab>: <name as printed> [(<printed unit> ×<factor>)] | …
[Hematoloģija]
Hemoglobīns | g/L | EGL: Hemoglobīns            | CLab: Hemoglobīns (g/dL ×10)
MCV         | fL  | EGL: MCV- vid. Er tilpums   | CLab: MCV Eritroc.vid.tilp.
[Urīna analīze]
Leikocīti   | /µL | EGL: Leikocīti              | CLab: Leikocīti urīnā
[Urīna mikroskopija]
Leikocīti   | /µL | EGL: Leikocīti
```

- A section implies the specimen: `Urīna analīze` → urine, `Urīna mikroskopija` → urine sediment,
  all others → blood.
- Lookup key: laboratory, specimen and normalised name (case-insensitive, whitespace collapsed,
  spaces around `-` and `,` removed), so `TSH-Tireotropais hormons` = `TSH - Tireotropais hormons`.
- **Units must match.** The printed unit, normalised (`µ`/`u`, `10e9`/`10x9`/`10⁹`, `m2`/`m²`, case,
  spaces, plus listed equivalences such as `Ery/uL` = `/µL`), must equal the alias's printed unit if
  one is given, otherwise the row's unit. On a mismatch the result is not merged: it gets an unmapped
  row and a warning, so units are never mixed silently.
- **Conversion** multiplies value and range by the factor (§4.1 for decimals).
- The shipped catalog covers every test in the current 28 reports (names only, never values).
- **Unmapped results** get their own row (printed name, parsed section, note on the name) and are
  listed in the console summary.
- Catalog mistakes (duplicate aliases, malformed lines) fail the unit tests, never a user's run.

## 6. Native image and distribution

### 6.1 Build

- Gradle native build tools plugin, as in the other projects, with the GraalVM reachability metadata
  repository enabled (it has tested PDFBox 3.0.7/3.0.8 metadata) and `resources.autodetect()`.
- Build arguments: `-Djava.awt.headless=true`, `-H:+AddAllCharsets`, `-march=compatibility` (portable
  across CPUs; GraalVM's defaults target newer x86-64 and ARM levels).
- Resources: PDFBox `resources/afm/*.afm`, `resources/glyphlist/*.txt`,
  `resources/ttf/LiberationSans-Regular.ttf`, `resources/text/BidiMirroring.txt`, and `catalog.txt`.
  Remaining metadata (commons-logging) is generated once with the tracing agent, with AWT entries
  removed, and committed under `src/main/resources/META-INF/native-image/lv.sknarovs/analysis-history/`.
- Local: `./gradlew test` and `run` on the host; `nativeCompile` and `nativeTest` in
  `fedora-toolbox-44` with `JAVA_HOME`/`GRAALVM_HOME` set to the SDKMAN GraalVM.

### 6.2 No AWT native libraries

`NoAwtSubstitutions` replaces `java.awt.image.ColorModel.loadLibraries()` and
`java.awt.Toolkit.loadLibraries()` with methods that throw `UnsatisfiedLinkError`. PDFBox's
`PDDocument` static initialiser already catches that error, and text extraction never needs AWT's
native code. Without the block, a binary on a machine with a JDK on its library path loads that
JDK's AWT and `libjvm` (§2). Native Image still writes `libawt.so`/`awt.dll` and similar files next
to the executable; releases ship **only the executable**.

### 6.3 Releases

GitHub Actions, one job per target. Each job builds, runs `test` and `nativeTest`, copies the bare
executable into an empty folder, runs it on the generated sample PDFs (§8) and checks the exit code,
the summary line and that the workbook exists, then uploads the executable and its `.sha256`.

| Target | Build |
|---|---|
| `linux-x86_64` | Fully static (musl): runs on any x86-64 distribution |
| `linux-aarch64` | glibc, built in a `debian:bookworm` container (glibc 2.36), as in `bksb-calendar` |
| `windows-x86_64` | `analysis-history.exe` |
| `macos-aarch64` | Apple Silicon |

Pushing a `v*` tag publishes a GitHub Release with the four executables and checksums. The binaries
are unsigned: macOS needs `xattr -d com.apple.quarantine analysis-history` once, and Windows
SmartScreen needs "More info → Run anyway". The README explains both.

## 7. Errors and output

- **Usage:** `analysis-history [DIR] [-o FILE] [-h]`. `DIR` defaults to the current folder; output
  defaults to `DIR/analysis-history.xlsx`. Only PDFs directly in `DIR` are read (`*.pdf`, any case).
- **Per file:** each PDF is handled independently, and one failure never stops the run.
  Unrecognised files, errors (encrypted or corrupt PDFs) and duplicates (same laboratory and report
  number; the copy with more results is kept, ties go to the first file name) are reported on the
  Reports sheet.
- **Warnings:** unattached rows (§5.3); unit mismatches (§5.6); reports belonging to more than one
  person (files grouped per person, workbook still written); consistency mismatches.
- **Consistency check:** for each printed previous result, the report it refers to (same laboratory,
  same date, plus time for CLab) is looked up. If that report is in the folder and has the same
  catalog test, the values must be equal (numbers by value, text ignoring case and spacing). A
  mismatch is a warning; a missing report is ignored.
- **Writing:** the workbook is written to a temporary file in the target folder, then renamed over the
  old one. If that fails (on Windows Excel locks open files) the message says to close the workbook
  and run again.
- **Exit codes:** 0 workbook written (with or without warnings); 1 nothing written (no readable
  reports, or the write failed); 2 usage error.
- **Console:** one summary line, then warnings. Output uses the console's encoding
  (`stdout.encoding`), so Latvian letters display correctly in Linux and macOS terminals and in
  Windows consoles with a Baltic or UTF-8 code page.

## 8. Testing

No real report, export or workbook is committed (`.gitignore`); all committed fixtures use invented
values and an invented patient.

1. **Unit tests** (JUnit 6), run on the JVM by `./gradlew test` and as a native image by `nativeTest`:
   - **Text layer:** PDFs generated in the test with PDFBox, covering the cases the labs use: embedded
     TrueType with Latvian letters, non-embedded Helvetica, two baselines in one row, a header drawn
     as a single string, tight gaps.
   - **Parsers:** fixtures in the `x:cell | x:cell` row format the probe printed (easy to write by
     hand), one per quirk in §1 and §5.3: index rows with and without a sample code, flags, nested
     tables, multi-line names, values and units, continuation pages, notes to ignore, urine strip
     versus microscopy, text references, decimal commas.
   - Values, ranges and flags; catalog loading, matching, unit equivalence and conversion; history
     building (visit merging and conflicts, ordering, reference column, notes); the consistency
     check; the workbook (values, styles, notes, links, frozen panes read back from its XML); CLI
     arguments, exit codes and atomic writing.
2. **Generated sample reports:** test code builds EGL-like and CLab-like PDFs from fixture data.
   `./gradlew generateSamples` writes them to `build/samples/` for the CI smoke run; end-to-end tests
   use them too.
3. **Real-data checks** (skipped unless `results/` exists; the system property `analysis.results`
   overrides the path; never run in CI):
   - every PDF in the folder is recognised, none fails, and there are no warnings (this keeps holding
     as new reports are added, so no counts are stored anywhere);
   - **HTML oracle:** for every EGL report and test name, the values in `rezultatu_vesture_*.html`
     and the parsed values are the same multiset (both directions, so nothing is missed or invented);
   - **previous-result check:** zero mismatches across both laboratories.
4. **Native and platforms:** CI runs `nativeTest` and the bare-executable smoke test on all four
   targets (§6.3). The first implementation task is a walking skeleton that proves this before any
   parser work.

## 9. Repository layout

```
.gitignore                       results/, *.pdf, *.html, *.xlsx, build output
README.md                        usage, downloads, unsigned-binary steps, adding a catalog alias
AGENTS.md                        commands and architecture facts, as in the other projects
build.gradle.kts, settings.gradle.kts, gradlew, gradle/
.github/workflows/build.yml
docs/superpowers/specs/2026-10-11-analysis-history-design.md
docs/superpowers/plans/…
src/main/java/lv/sknarovs/analysishistory/…
src/main/resources/lv/sknarovs/analysishistory/catalog.txt
src/main/resources/META-INF/native-image/lv.sknarovs/analysis-history/…
src/test/java/lv/sknarovs/analysishistory/…
src/test/resources/fixtures/…
results/                         your reports and the generated workbook (ignored by git)
```

## 10. Risks

| Risk | Mitigation |
|---|---|
| Windows or macOS native builds behave differently (AWT, charsets, console, paths) | The walking skeleton builds and smoke-tests all four targets before parser work |
| The fully static musl build fails | Build `linux-x86_64` against an old glibc (Debian bookworm), like `linux-aarch64` |
| A laboratory changes its layout | Row warnings, the previous-result check and unmapped rows make it visible; fix with a new fixture and a parser change |
| A laboratory changes a unit | The unit check refuses to merge and warns |
| A PDFBox upgrade changes extraction | Fixtures and the real-data checks catch it |
| The workbook is open in Excel on Windows | Clear message, exit code 1, the previous workbook stays intact |

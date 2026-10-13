# AGENTS.md

Java CLI that reads E. Gulbja Laboratorija (EGL) and Centrālā Laboratorija (CLab) PDF lab reports and writes one Excel
history, shipped as a GraalVM native binary. Design: `docs/superpowers/specs/2026-10-11-analysis-history-design.md`.

## Developer Commands

- **Run tests** (JUnit, no network, works on the host): `./gradlew test`
- **Run on the JVM**: `./gradlew run --args="results -o /tmp/out.xlsx"`
- **Native binary + native tests** need GraalVM CE 25 and gcc, so run them in the Fedora toolbox. On this host a linuxbrew
  `podman` shadows `/usr/bin/podman`, so prefix `PATH=/usr/bin:$PATH`:
  `PATH=/usr/bin:$PATH toolbox run -c fedora-toolbox-44 bash -c 'cd /var/home/andrejs/Work/blood-analysis && export JAVA_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce GRAALVM_HOME=$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce && ./gradlew nativeTest nativeCompile'`
  (a non-login shell does not load SDKMAN, hence the explicit exports). Output: `build/native/nativeCompile/analysis-history`.
  Add `-Pstatic` for the fully static musl build CI uses for linux-x86_64 (needs musl tooling).
- **Generate sample PDFs** for smoke tests: `./gradlew generateSamples` (writes `build/samples/`).
- **Check a binary alone**: copy only the executable into an empty folder outside the repo, run it with
  `LD_DEBUG=files` on a reports folder and confirm exit 0, `0 errors`, and that no `libawt`/`libjvm` is opened.

## Key Architecture Facts

- **Java 25, Gradle (Kotlin DSL), GraalVM Native Build Tools.** Runtime dependencies: only PDFBox 3.0.8 and fastexcel
  0.20.2. Compile-only: `org.graalvm.sdk:nativeimage`. Each dependency must work in a native image.
- Flat package `lv.sknarovs.analysishistory` (`src/main/java/...`), classes package-private unless needed:
  - `Main` / `CliOptions` - `analysis-history [-h] [-o FILE] [DIR]`; exit 0 written, 1 nothing written, 2 usage error.
    Console output uses plain `System.out`/`System.err` (the console's `stdout.encoding`); do not wrap it in a UTF-8 stream.
  - `AnalysisHistory` - the run: reads each PDF independently, statuses, duplicates, warnings, summary line.
  - `PdfText` - the only class using PDFBox: glyphs to rows (vertical overlap, joins CLab's two baselines) to cells (gaps).
    `BundledFontMapper` - maps non-embedded fonts to the bundled LiberationSans so extraction is identical everywhere,
    system fonts are never scanned and no `~/.pdfbox.cache` is written.
  - `LabDetector` (from text, never from file names), `EglParser`, `ClabParser` - one lab's layout to `Report`; they know
    nothing about Excel or each other. Row-level problems become `RowWarning`s.
  - `ResultValue`, `ReferenceRange`, `Flag`, `Decimals` - values, ranges, flag rules (spec 5.5).
  - `TestCatalog`, `CatalogTest`, `CatalogMatch` - load `catalog.txt` and map (lab, specimen, name, unit) to a catalog test
    with a conversion factor.
  - `HistoryBuilder` -> `History` (visits newest first x rows by section), `ConsistencyCheck` (printed previous results
    against earlier reports), `WorkbookWriter` - the only class using fastexcel; writes a temp file, then renames.
  - `NoAwtSubstitutions` - see below.
- **Native build arguments** in `build.gradle.kts`: `-Djava.awt.headless=true`, `-H:+AddAllCharsets` (PDFBox otherwise
  falls back from Windows-1252 and logs a stack trace), `-march=compatibility` (portable across CPUs). `-Pstatic` adds
  `--static --libc=musl`. Keep them.
- **No AWT native libraries**: `NoAwtSubstitutions` makes `ColorModel.loadLibraries()` and `Toolkit.loadLibraries()` throw
  `UnsatisfiedLinkError` (PDFBox's `PDDocument` catches it). Without it a binary on a machine with a JDK loads that JDK's
  `libawt` and `libjvm`. Native Image still writes `libawt.so`/`awt.dll` beside the executable; releases ship only the executable.
- Reachability metadata for what the metadata repository lacks (commons-logging) is in
  `src/main/resources/META-INF/native-image/lv.sknarovs/analysis-history/`, generated with the tracing agent, AWT entries removed.
- **Catalog** (`src/main/resources/lv/sknarovs/analysishistory/catalog.txt`): `[Section]` headers; lines
  `<display name> | <unit> | <Lab>: <printed name> [<printed unit> ×<factor>] | ...` (square brackets, because printed names
  contain parentheses). A section implies the specimen. Names are normalised (case, whitespace, spaces around `-` and `,`);
  the printed unit must equal the alias's unit (or the row's) after normalising, else the result is not merged and a warning
  is raised. Unmapped results get their own row. Catalog mistakes fail the unit tests, never a user's run. The catalog holds
  names only, never values.
- **Fixture format** (`src/test/resources/fixtures/*.txt`, read by `Fixtures`): UTF-8, `#` comments, `=== page` starts a page,
  each line is a row of cells `x:text | x:text`, `x:*text` for bold; `~` prefix = wrapped line 12 pt below, `+` = 13 pt below,
  otherwise 18 pt. `Fixtures.text` normalises CRLF to LF.

## Testing

- JUnit 6 in `src/test/java`; parsers are tested from fixtures, the text layer from PDFs generated with PDFBox
  (`TestPdf`, `FixturePdf`), workbooks are read back with `java.util.zip` + `javax.xml` so tests also run as a native image.
  `SampleReports` generates the PDFs for end-to-end tests and the CI smoke run.
- `RealDataTest` is opt-in: it runs only with `./gradlew test -Danalysis.results=<folder>` (e.g. `results/`) and is skipped
  otherwise, so no real data is read by default: all
  PDFs recognised and no warnings, values equal to the EGL HTML export, zero previous-result mismatches. Never runs in CI.
- `nativeTest` runs the same suite as a native image.
- Tests are independent of locale and line endings (CI runs in a container without a UTF-8 locale and on Windows).

## CI / Release

- `.github/workflows/build.yml` builds four targets: linux-x86_64 (static musl), linux-aarch64 (`debian:bookworm`
  container, `LANG=C.UTF-8`), windows-x86_64, macos-aarch64. Each runs `test nativeTest nativeCompile generateSamples`,
  smoke-tests the bare executable on the generated samples, and uploads the executable plus `.sha256`.
- Pushing a `v*` tag publishes a GitHub release with those files. Ask the user before tagging.
- `.gitattributes` forces LF (except `*.bat`).

## Privacy

`results/` holds real medical reports. **Never commit, publish, quote or send any real report, the HTML export or any
workbook** (`.gitignore` excludes `/results/`, `*.pdf`, `*.html`, `*.xlsx`). Fixtures and docs use invented data and the
invented patient `Jānis Paraugs`.

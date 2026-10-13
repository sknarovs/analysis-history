# analysis-history

Turns the laboratory reports in a folder into one Excel workbook that shows how your results change over time.

It reads the PDF reports of **E. Gulbja Laboratorija** (`testesanas_parskats_<nr>.pdf`) and **Centrālā Laboratorija**
(`CLab_<nr>.pdf`) and writes `analysis-history.xlsx`:

- **History** sheet: one column per visit, newest on the left, one row per test. The same test from both laboratories is
  one row (CLab values in g/dL are converted to g/L, with the printed value in a cell note). Values outside the
  reference range are highlighted red (high) or blue (low) with an arrow, and stay numbers, so Excel can chart them.
- **Reports** sheet: every PDF with a status (`OK`, `Not tabulated`, `Unrecognised`, `Duplicate of …`, `Error: …`), a link
  to the file and any warnings. Nothing is dropped silently.

Only reports with a text layer are read (no OCR). Everything runs on your computer; nothing is sent anywhere.

## Download

Get the executable for your system from the [Releases](https://github.com/sknarovs/analysis-history/releases) page. It is a
single self-contained file: no Java, no other tools.

| System | File |
|---|---|
| Linux, x86-64 (static, any distribution) | `analysis-history-linux-x86_64` |
| Linux, ARM64 (glibc 2.36 or newer) | `analysis-history-linux-aarch64` |
| Windows, x86-64 | `analysis-history-windows-x86_64.exe` |
| macOS, Apple Silicon | `analysis-history-macos-aarch64` |

Each file has a `.sha256` checksum next to it. On Linux and macOS make it executable once: `chmod +x analysis-history-*`.

The binaries are not code-signed:

- **macOS:** remove the quarantine flag once: `xattr -d com.apple.quarantine analysis-history-macos-aarch64`.
- **Windows:** SmartScreen shows "Windows protected your PC"; choose **More info**, then **Run anyway**.

## Usage

```
analysis-history [-h] [-o FILE] [DIR]
```

- `DIR` is the folder with the PDF reports (default: the current folder). Only PDFs directly in it are read.
- `-o FILE` is the workbook to write (default: `DIR/analysis-history.xlsx`).
- Run it again whenever new reports arrive; it re-reads all PDFs and rewrites the workbook.

It prints one summary line, then any warnings and the tests that are not in the catalog yet.

| Exit code | Meaning |
|---|---|
| 0 | Workbook written (possibly with warnings) |
| 1 | Nothing written: no readable reports, or the workbook could not be written (if it is open in Excel, close it and run again) |
| 2 | Usage error |

## Adding a test alias

A test the catalog does not know appears as its own row, marked in a cell note. To merge it with the same test from the
other laboratory, edit `src/main/resources/lv/sknarovs/analysishistory/catalog.txt` (UTF-8):

```
# <display name> | <unit> | <lab>: <name as printed> [<printed unit> ×<factor>] | …
[Hematoloģija]
Hemoglobīns | g/L | EGL: Hemoglobīns | CLab: Hemoglobīns [g/dL ×10]
```

- `[Section]` lines start a section; the section decides the sample (`Urīna analīze` is urine, `Urīna mikroskopija` is
  urine sediment, everything else is blood).
- Each alias is `EGL: <name>` or `CLab: <name>` exactly as the report prints it (case and spacing around `-` and `,` do
  not matter). Add `[<printed unit> ×<factor>]` (square brackets) when the lab prints a different unit; the value and
  range are multiplied by the factor.
- Units must match the row's unit (or the alias's printed unit), otherwise the result is not merged and a warning is
  shown. Units are never mixed silently.

Then run `./gradlew test`; it fails on duplicate aliases or malformed lines. Rebuild the binary to use the change.

## Building

Needs JDK 25. On the JVM:

```
./gradlew test
./gradlew run --args="path/to/reports"
```

The native binary needs GraalVM CE 25 and a C toolchain; see [AGENTS.md](AGENTS.md) for the exact commands. CI builds all
four targets; pushing a `v*` tag publishes a GitHub release with the executables and checksums.

## Privacy

Lab reports contain your name, personal code and health data. **Never commit, publish or send them.** The repository
ignores `results/`, `*.pdf`, `*.html` and `*.xlsx`; keep reports and the generated workbook outside version control. All
test data in this repository is invented (patient "Jānis Paraugs").

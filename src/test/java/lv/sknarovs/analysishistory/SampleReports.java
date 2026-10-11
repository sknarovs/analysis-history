package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes an EGL-like and a CLab-like sample report, rendered from the {@code egl-sample} and {@code clab-sample}
 * fixtures, into the folder given as the only argument (created when missing). {@code ./gradlew generateSamples}
 * runs this for the smoke test of the native binary.
 */
public final class SampleReports {
    private SampleReports() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: SampleReports DIR");
        }
        Path dir = Files.createDirectories(Path.of(args[0]));
        FixturePdf.write(Fixtures.load("egl-sample"), dir.resolve("testesanas_parskats_20000001.pdf"));
        FixturePdf.write(Fixtures.load("clab-sample"), dir.resolve("CLab_100000001.pdf"));
    }
}

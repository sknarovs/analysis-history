package lv.sknarovs.analysishistory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import lv.sknarovs.analysishistory.AnalysisHistory.RunResult;

/** Turns the laboratory reports in a folder into one Excel workbook of results over time. */
public final class Main {
    private static final String ERROR = "analysis-history: error: ";

    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the command and returns its exit code: 0 workbook written, 1 nothing written, 2 usage error. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (CliOptions.UsageException e) {
            return usageError(err, e.getMessage());
        }
        if (options.help()) {
            out.println(CliOptions.HELP);
            return 0;
        }
        if (!Files.isDirectory(options.dir())) {
            return usageError(err, "not a directory: " + options.dir());
        }
        RunResult result;
        try {
            result = AnalysisHistory.run(options.dir(), TestCatalog.bundled());
        } catch (IOException e) {
            err.println(ERROR + "cannot read " + options.dir() + ": " + reason(e));
            return 1;
        }
        if (result.statuses().isEmpty()) {
            err.println(ERROR + "no PDF files in " + options.dir());
            return 1;
        }
        if (result.statuses().stream().noneMatch(status -> status.status().startsWith("OK"))) {
            err.println(ERROR + "no reports could be read in " + options.dir());
            return 1;
        }
        try {
            WorkbookWriter.write(options.output(), result.history(), result.statuses(), LocalDateTime.now());
        } catch (IOException e) {
            err.println(ERROR + "cannot write " + options.output() + ": " + reason(e)
                    + ". If the workbook is open in another program, close it and run again.");
            return 1;
        }
        out.println("Read " + AnalysisHistory.summary(result.statuses()) + "; " + result.warnings().size()
                + " warnings");
        out.println("Wrote " + options.output());
        for (String warning : result.warnings()) {
            out.println("warning: " + warning);
        }
        AnalysisHistory.unmapped(result.history()).forEach(out::println);
        return 0;
    }

    private static int usageError(PrintStream err, String message) {
        err.println(CliOptions.USAGE);
        err.println(ERROR + message);
        return 2;
    }

    /** Why a file operation failed, without the file names that file-system exceptions put in their message. */
    private static String reason(IOException e) {
        if (e instanceof FileSystemException failure && failure.getReason() != null) {
            return failure.getReason();
        }
        if (e instanceof NoSuchFileException) {
            return "no such file or directory";
        }
        if (e instanceof AccessDeniedException) {
            return "access denied";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}

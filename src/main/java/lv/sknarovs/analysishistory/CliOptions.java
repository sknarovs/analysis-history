package lv.sknarovs.analysishistory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Command-line options. {@code dir} defaults to the current folder and {@code output} to
 * {@code dir/analysis-history.xlsx}; whether {@code dir} exists is checked by {@link Main}.
 */
record CliOptions(Path dir, Path output, boolean help) {
    static final String USAGE = "usage: analysis-history [-h] [-o FILE] [DIR]";
    static final String HELP = USAGE + "\n\n" + """
            Reads the EGL and CLab PDF reports in DIR and writes their results into one Excel workbook.

            positional arguments:
              DIR                   folder with the PDF reports (default: the current folder)

            options:
              -h, --help            show this help message and exit
              -o, --output FILE     workbook to write (default: DIR/analysis-history.xlsx)""";
    private static final String WORKBOOK = "analysis-history.xlsx";

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /** Accepts {@code --output FILE}, {@code --output=FILE}, {@code -o FILE} and {@code -oFILE}, in any order. */
    static CliOptions parse(String... args) throws UsageException {
        Path dir = null;
        Path output = null;
        boolean help = false;
        Deque<String> tokens = tokens(args);
        while (!tokens.isEmpty()) {
            String token = tokens.removeFirst();
            switch (token) {
                case "-o", "--output" -> output = Path.of(value(tokens, "-o/--output"));
                case "-h", "--help" -> help = true;
                default -> {
                    if (token.startsWith("-") || dir != null) {
                        throw new UsageException("unrecognized arguments: " + token);
                    }
                    dir = Path.of(token);
                }
            }
        }
        if (dir == null) {
            dir = Path.of(".");
        }
        return new CliOptions(dir, output != null ? output : dir.resolve(WORKBOOK), help);
    }

    /** Splits {@code --opt=value} and {@code -ovalue} into separate option and value tokens. */
    private static Deque<String> tokens(String... args) {
        Deque<String> tokens = new ArrayDeque<>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                tokens.add(arg.substring(0, equals));
                tokens.add(arg.substring(equals + 1));
            } else if (arg.startsWith("-o") && arg.length() > 2) {
                tokens.add("-o");
                tokens.add(arg.substring(2));
            } else {
                tokens.add(arg);
            }
        }
        return tokens;
    }

    private static String value(Deque<String> tokens, String option) throws UsageException {
        String next = tokens.peekFirst();
        if (next == null || next.startsWith("-")) {
            throw new UsageException("argument " + option + ": expected one argument");
        }
        return tokens.removeFirst();
    }
}

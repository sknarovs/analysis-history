package lv.sknarovs.analysishistory;

import java.nio.file.InvalidPathException;
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
        Deque<Token> tokens = tokens(args);
        while (!tokens.isEmpty()) {
            Token token = tokens.removeFirst();
            switch (token.text()) {
                case "-o", "--output" -> output = path(value(tokens, "-o/--output"), "-o/--output");
                case "-h", "--help" -> help = true;
                default -> {
                    if (token.text().startsWith("-") || dir != null) {
                        throw new UsageException("unrecognized arguments: " + token.text());
                    }
                    dir = path(token.text(), "DIR");
                }
            }
        }
        if (dir == null) {
            dir = Path.of(".");
        }
        return new CliOptions(dir, output != null ? output : dir.resolve(WORKBOOK), help);
    }

    /** An argument, or the value split off an option; {@code attached} when it was written in the option's argument. */
    private record Token(String text, boolean attached) {
    }

    /** Splits {@code --opt=value} and {@code -ovalue} into separate option and attached value tokens. */
    private static Deque<Token> tokens(String... args) {
        Deque<Token> tokens = new ArrayDeque<>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                tokens.add(new Token(arg.substring(0, equals), false));
                tokens.add(new Token(arg.substring(equals + 1), true));
            } else if (arg.startsWith("-o") && arg.length() > 2) {
                tokens.add(new Token("-o", false));
                tokens.add(new Token(arg.substring(2), true));
            } else {
                tokens.add(new Token(arg, false));
            }
        }
        return tokens;
    }

    /** The option's value: the attached one, which may start with {@code -}, or the next argument unless an option. */
    private static String value(Deque<Token> tokens, String option) throws UsageException {
        Token next = tokens.peekFirst();
        if (next == null || !next.attached() && next.text().startsWith("-")) {
            throw new UsageException("argument " + option + ": expected one argument");
        }
        tokens.removeFirst();
        if (next.text().isEmpty()) {
            throw new UsageException("argument " + option + ": expected a non-empty path");
        }
        return next.text();
    }

    /** {@code text} as a path; one the file system cannot name (on Windows {@code "<>|?*}) is a usage error. */
    private static Path path(String text, String argument) throws UsageException {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new UsageException("argument " + argument + ": invalid path: " + e.getReason());
        }
    }
}

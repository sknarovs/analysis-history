package lv.sknarovs.analysishistory;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lv.sknarovs.analysishistory.ResultValue.Text;

/**
 * Compares the earlier result each report prints beside a test with what was read from the earlier report, when that
 * report is among those given; a difference points at a misread value.
 */
final class ConsistencyCheck {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ConsistencyCheck() {
    }

    /**
     * One warning per printed previous result that differs from every matching earlier report's result (see
     * {@link #consistent}).
     */
    static List<String> check(List<Report> reports, TestCatalog catalog) {
        List<Report> ordered = reports.stream().sorted(Report.REGISTRATION_ORDER).toList();
        List<String> warnings = new ArrayList<>();
        for (Report report : ordered) {
            for (Result result : report.results()) {
                Previous previous = result.previous();
                if (previous == null) {
                    continue;
                }
                RowKey key = RowKey.of(report.lab(), result, catalog);
                List<Report> candidates = ordered.stream()
                        .filter(other -> other != report && isPreviousOf(previous, report.lab(), other)).toList();
                String differing = null;
                boolean found = false;
                for (Report candidate : candidates) {
                    Optional<Result> earlier = candidate.results().stream()
                            .filter(r -> RowKey.of(candidate.lab(), r, catalog).equals(key)).findFirst();
                    if (earlier.isEmpty()) {
                        continue;
                    }
                    found = true;
                    if (consistent(earlier.get().value(), previous.value())) {
                        differing = null;
                        break;
                    }
                    if (differing == null) {
                        differing = earlier.get().value().display() + " in " + candidate.file().getFileName();
                    }
                }
                if (found && differing != null) {
                    warnings.add(report.file().getFileName() + ": " + result.name() + " previous result "
                            + previous.value().display() + " (" + DATE.format(previous.date()) + ") differs from "
                            + differing);
                }
            }
        }
        return warnings;
    }

    /**
     * Whether a printed previous result agrees with the earlier report's value: the same result, or for text the
     * earlier value's last words, as CLab prints only {@code pozitīvs} for a value printed {@code Rh(D) pozitīvs}.
     */
    private static boolean consistent(ResultValue earlier, ResultValue previous) {
        if (earlier.sameAs(previous)) {
            return true;
        }
        if (earlier instanceof Text value && previous instanceof Text printed) {
            return value.text().toLowerCase(Locale.ROOT).endsWith(" " + printed.text().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static boolean isPreviousOf(Previous previous, Lab lab, Report other) {
        return other.lab() == lab && other.registered().toLocalDate().equals(previous.date())
                && (previous.time() == null
                        || other.registered().toLocalTime().truncatedTo(ChronoUnit.MINUTES).equals(previous.time()));
    }
}

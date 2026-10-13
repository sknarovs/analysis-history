package lv.sknarovs.analysishistory;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compares the earlier result each report prints beside a test with what was read from the earlier report, when that
 * report is among those given; a difference points at a misread value.
 */
final class ConsistencyCheck {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ConsistencyCheck() {
    }

    /** One warning per printed previous result that differs from every matching earlier report's result. */
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
                    if (earlier.get().value().sameAs(previous.value())) {
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

    private static boolean isPreviousOf(Previous previous, Lab lab, Report other) {
        return other.lab() == lab && other.registered().toLocalDate().equals(previous.date())
                && (previous.time() == null
                        || other.registered().toLocalTime().truncatedTo(ChronoUnit.MINUTES).equals(previous.time()));
    }
}

package lv.sknarovs.analysishistory;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/** A column of the History sheet: reports that {@code lab} registered on {@code date} and that share no test. */
record Visit(Lab lab, LocalDate date, List<Report> reports) {
    /** The reports' numbers in {@link Report#REGISTRATION_ORDER}, separated by {@code ", "}. */
    String numbers() {
        return reports.stream()
                .sorted(Report.REGISTRATION_ORDER)
                .map(Report::number)
                .collect(Collectors.joining(", "));
    }
}

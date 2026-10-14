package lv.sknarovs.analysishistory.history;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import lv.sknarovs.analysishistory.model.Lab;
import lv.sknarovs.analysishistory.model.Report;

/** A column of the History sheet: reports that {@code lab} registered on {@code date} and that share no test. */
public record Visit(Lab lab, LocalDate date, List<Report> reports) {
    /** The reports' numbers in {@link Report#REGISTRATION_ORDER}, separated by {@code ", "}. */
    public String numbers() {
        return reports.stream()
                .sorted(Report.REGISTRATION_ORDER)
                .map(Report::number)
                .collect(Collectors.joining(", "));
    }
}

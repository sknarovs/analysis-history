package lv.sknarovs.analysishistory.model;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

/** What became of one PDF; {@code lab}, {@code number} and {@code registered} are null when unknown. */
public record ReportStatus(Path file, Lab lab, String number, LocalDateTime registered, int results, String status,
        List<String> warnings) {
    /**
     * Counts the statuses: {@code "<n> PDFs: <a> tabulated, <b> not tabulated, <c> unrecognised, <d> duplicates,
     * <e> errors"}.
     */
    public static String summary(List<ReportStatus> statuses) {
        return statuses.size() + " PDFs: "
                + count(statuses, status -> status.startsWith("OK")) + " tabulated, "
                + count(statuses, status -> status.startsWith("Not tabulated")) + " not tabulated, "
                + count(statuses, status -> status.equals("Unrecognised")) + " unrecognised, "
                + count(statuses, status -> status.startsWith("Duplicate of")) + " duplicates, "
                + count(statuses, status -> status.startsWith("Error:")) + " errors";
    }

    private static long count(List<ReportStatus> statuses, Predicate<String> matches) {
        return statuses.stream().map(ReportStatus::status).filter(matches).count();
    }
}

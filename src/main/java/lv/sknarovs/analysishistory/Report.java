package lv.sknarovs.analysishistory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A laboratory report read from {@code file}: its number, when the laboratory registered the sample, the patient's
 * personal code (null when the report prints none; only ever compared between reports, never shown), the section
 * headings and results in the order printed, and the rows that could not be attached to a result.
 */
record Report(Lab lab, String number, LocalDateTime registered, String personalCode, Path file,
        List<String> sections, List<Result> results, List<RowWarning> warnings) {
}

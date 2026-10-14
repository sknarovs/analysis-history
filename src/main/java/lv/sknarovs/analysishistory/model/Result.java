package lv.sknarovs.analysishistory.model;

/**
 * One test's result as a report prints it. {@code section} is the heading it is under ({@code ""} before the first),
 * {@code group} a sub-heading within the section or null, {@code unit} is {@code ""} when none is printed,
 * {@code labFlag} is the laboratory's own arrow ({@link Flag#NONE} without one), and {@code previous} is null when the
 * report prints no earlier result.
 */
public record Result(String section, String group, Specimen specimen, String name, ResultValue value, String unit,
        ReferenceRange range, Flag labFlag, Previous previous) {
}

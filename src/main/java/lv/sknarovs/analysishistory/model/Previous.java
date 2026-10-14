package lv.sknarovs.analysishistory.model;

import java.time.LocalDate;
import java.time.LocalTime;

/** The earlier result a report prints beside a test; {@code time} is null when only the date is printed (EGL). */
public record Previous(ResultValue value, LocalDate date, LocalTime time) {
}

package lv.sknarovs.analysishistory;

import java.time.LocalDate;
import java.time.LocalTime;

/** The earlier result a report prints beside a test; {@code time} is null when only the date is printed (EGL). */
record Previous(ResultValue value, LocalDate date, LocalTime time) {
}

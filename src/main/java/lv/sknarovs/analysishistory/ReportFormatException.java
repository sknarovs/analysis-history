package lv.sknarovs.analysishistory;

/** A report of a known laboratory that lacks something every such report prints, such as its number. */
final class ReportFormatException extends Exception {
    ReportFormatException(String message) {
        super(message);
    }
}

package lv.sknarovs.analysishistory.model;

/** A report of a known laboratory that lacks something every such report prints, such as its number. */
public final class ReportFormatException extends Exception {
    public ReportFormatException(String message) {
        super(message);
    }
}

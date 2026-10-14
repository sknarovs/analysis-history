package lv.sknarovs.analysishistory.pdf;

/** A run of text on a row, separated from its neighbours by a column gap; positions are in points. */
public record Cell(float x, float xEnd, String text, boolean bold) {
}

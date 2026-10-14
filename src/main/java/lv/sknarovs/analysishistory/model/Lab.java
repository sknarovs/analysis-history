package lv.sknarovs.analysishistory.model;

/** The laboratories whose reports the tool reads; {@code label} is how the workbook and console name them. */
public enum Lab {
    EGL("EGL"),
    CLAB("CLab");

    public final String label;

    Lab(String label) {
        this.label = label;
    }
}

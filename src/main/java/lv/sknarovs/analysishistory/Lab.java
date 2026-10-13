package lv.sknarovs.analysishistory;

/** The laboratories whose reports the tool reads; {@code label} is how the workbook and console name them. */
enum Lab {
    EGL("EGL"),
    CLAB("CLab");

    final String label;

    Lab(String label) {
        this.label = label;
    }
}

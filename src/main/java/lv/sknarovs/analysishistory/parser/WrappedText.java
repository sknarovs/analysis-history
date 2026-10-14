package lv.sknarovs.analysishistory.parser;

/**
 * How the parts of a result's field are joined when a report prints it on several lines, or in several cells of one
 * line. Both laboratories wrap names, values and units the same way.
 */
final class WrappedText {
    private WrappedText() {
    }

    /**
     * {@code text} followed by {@code more}, the next part of the same field: after a space, except that a unit goes on
     * straight after a part that ends with {@code /} ({@code mL/min/} and {@code 1.73 m2} make
     * {@code mL/min/1.73 m2}).
     */
    static String join(String text, String more, boolean unit) {
        return text + (unit && text.endsWith("/") ? "" : " ") + more;
    }
}
